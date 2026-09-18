using System.Reflection;
using System.Runtime.ExceptionServices;
using System.Runtime.Loader;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using SkiaSharp;
using Legacy = CubicalCompare.Windows;

namespace CubicalCompare.Core.Renderer;

public sealed record InternalCodeOverrideInstallResult(
    string Engine,
    string TargetType,
    string FriendlyName,
    string StoredSourcePath,
    IReadOnlyList<string> Warnings);

public sealed record InternalCodeOverrideStatus(
    string Engine,
    string FriendlyName,
    bool Installed,
    bool Active,
    string StoredSourcePath,
    string? Error);

/// <summary>
/// Compiles selected Cubical Compare renderer source files with Roslyn and hot-swaps them
/// behind the existing renderer engine dispatch. Overrides are source-first: Cubical Compare
/// stores the .cs file and recompiles it against the current app build when needed.
/// </summary>
public static class InternalCodeOverrideManager
{
    private const int MaxSourceBytes = 4 * 1024 * 1024;
    private const string GlobalUsingsSource = """
global using System;
global using System.Collections.Generic;
global using System.IO;
global using System.Linq;
global using System.Threading;
global using System.Threading.Tasks;
""";

    private static readonly OverrideTarget[] Targets =
    [
        new(
            "relationships-exact",
            "CubicalCompare.Windows.RelationshipsRenderer",
            "RelationshipsRenderer.cs"),
        new(
            "infinite-timeline-exact",
            "CubicalCompare.Windows.InfiniteTimelineRenderer",
            "InfiniteTimelineRenderer.cs"),
    ];

    private static readonly object Gate = new();
    private static readonly Dictionary<string, LoadedOverride> Active = new(StringComparer.Ordinal);
    private static readonly HashSet<string> LoadAttempted = new(StringComparer.Ordinal);
    private static readonly Dictionary<string, string> Errors = new(StringComparer.Ordinal);

    public static IReadOnlyList<string> SupportedFiles => Targets.Select(target => target.FriendlyName).ToArray();

    public static InternalCodeOverrideInstallResult Install(string sourcePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(sourcePath);
        var info = new FileInfo(sourcePath);
        if (!info.Exists) throw new FileNotFoundException("The selected C# source file does not exist.", sourcePath);
        if (info.Length <= 0) throw new InvalidDataException("The selected C# source file is empty.");
        if (info.Length > MaxSourceBytes) throw new InvalidDataException("Internal code overrides are limited to 4 MiB of C# source.");

        var source = File.ReadAllText(info.FullName);
        var compiled = CompileAndLoad(source, info.Name);
        var storedSourcePath = SourcePath(compiled.Target);
        AtomicWriteText(storedSourcePath, source);

        lock (Gate)
        {
            ReplaceActiveLocked(compiled.Target.Engine, compiled);
            LoadAttempted.Add(compiled.Target.Engine);
            Errors.Remove(compiled.Target.Engine);
        }

        return new InternalCodeOverrideInstallResult(
            compiled.Target.Engine,
            compiled.Target.TypeName,
            compiled.Target.FriendlyName,
            storedSourcePath,
            compiled.Warnings);
    }

    public static IReadOnlyList<InternalCodeOverrideStatus> Status()
    {
        lock (Gate)
        {
            return Targets.Select(target =>
            {
                var path = SourcePath(target);
                return new InternalCodeOverrideStatus(
                    target.Engine,
                    target.FriendlyName,
                    File.Exists(path),
                    Active.ContainsKey(target.Engine),
                    path,
                    Errors.TryGetValue(target.Engine, out var error) ? error : null);
            }).ToArray();
        }
    }

    public static bool RemoveAll()
    {
        var removed = false;
        lock (Gate)
        {
            foreach (var target in Targets)
            {
                if (Active.Remove(target.Engine, out var loaded))
                {
                    loaded.Dispose();
                    removed = true;
                }

                LoadAttempted.Remove(target.Engine);
                Errors.Remove(target.Engine);

                var path = SourcePath(target);
                if (File.Exists(path))
                {
                    File.Delete(path);
                    removed = true;
                }
            }
        }

        return removed;
    }

    public static bool TryRender(
        string engine,
        Legacy.StudioProject project,
        Legacy.RendererSpec spec,
        int frame,
        int width,
        int height,
        out SKBitmap bitmap)
    {
        lock (Gate)
        {
            var loaded = GetOrLoadLocked(engine);
            if (loaded is null)
            {
                bitmap = null!;
                return false;
            }

            bitmap = loaded.Render(project, spec, frame, width, height);
            return true;
        }
    }

    public static bool TryFrameCount(
        string engine,
        Legacy.StudioProject project,
        Legacy.RendererSpec spec,
        out int frameCount)
    {
        lock (Gate)
        {
            var loaded = GetOrLoadLocked(engine);
            if (loaded is null)
            {
                frameCount = 0;
                return false;
            }

            frameCount = loaded.FrameCount(project, spec);
            return true;
        }
    }

    /// <summary>
    /// CI-only runtime verification. It exercises Roslyn compilation, collectible loading,
    /// host-assembly type sharing and reflective invocation without installing an override.
    /// </summary>
    public static void RunCompilerSelfTest()
    {
        const string source = """
using SkiaSharp;

namespace CubicalCompare.Windows;

public sealed class RelationshipsRenderer : IDisposable
{
    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)
        => new(Math.Max(2, width), Math.Max(2, height));

    public int FrameCount(StudioProject project, RendererSpec spec) => 7;

    public void Dispose() { }
}
""";

        using var loaded = CompileAndLoad(source, "RelationshipsRenderer.selftest.cs");
        var project = new Legacy.StudioProject();
        var spec = Legacy.RendererSpec.BuiltIn();
        using var bitmap = loaded.Render(project, spec, 0, 8, 6);
        if (bitmap.Width != 8 || bitmap.Height != 6 || loaded.FrameCount(project, spec) != 7)
            throw new InvalidOperationException("Internal code override compiler self-test returned unexpected results.");
    }

    private static LoadedOverride? GetOrLoadLocked(string engine)
    {
        if (Active.TryGetValue(engine, out var active)) return active;
        if (LoadAttempted.Contains(engine)) return null;

        LoadAttempted.Add(engine);
        var target = Targets.FirstOrDefault(candidate => candidate.Engine == engine);
        if (target is null) return null;

        var path = SourcePath(target);
        if (!File.Exists(path)) return null;

        try
        {
            var source = File.ReadAllText(path);
            var loaded = CompileAndLoad(source, target.FriendlyName);
            if (loaded.Target.Engine != engine)
                throw new InvalidDataException(
                    $"Stored override declares {loaded.Target.FriendlyName}, not {target.FriendlyName}.");

            ReplaceActiveLocked(engine, loaded);
            Errors.Remove(engine);
            return loaded;
        }
        catch (Exception ex)
        {
            Errors[engine] = FirstUsefulLine(ex.Message);
            return null;
        }
    }

    private static void ReplaceActiveLocked(string engine, LoadedOverride replacement)
    {
        if (Active.Remove(engine, out var previous)) previous.Dispose();
        Active[engine] = replacement;
    }

    private static LoadedOverride CompileAndLoad(string source, string displayName)
    {
        var parseOptions = CSharpParseOptions.Default.WithLanguageVersion(LanguageVersion.CSharp14);
        var sourceTree = CSharpSyntaxTree.ParseText(source, parseOptions, displayName);
        var globalsTree = CSharpSyntaxTree.ParseText(
            GlobalUsingsSource,
            parseOptions,
            "CubicalCompare.Override.GlobalUsings.g.cs");

        var compilation = CSharpCompilation.Create(
            $"CubicalCompare.InternalOverride.{Guid.NewGuid():N}",
            [globalsTree, sourceTree],
            BuildReferences(),
            new CSharpCompilationOptions(
                OutputKind.DynamicallyLinkedLibrary,
                optimizationLevel: OptimizationLevel.Release,
                allowUnsafe: false,
                nullableContextOptions: NullableContextOptions.Enable));

        using var pe = new MemoryStream();
        var emit = compilation.Emit(pe);
        var errors = emit.Diagnostics
            .Where(diagnostic => diagnostic.Severity == DiagnosticSeverity.Error)
            .Select(FormatDiagnostic)
            .ToArray();

        if (!emit.Success || errors.Length > 0)
            throw new InvalidDataException(
                "The C# override did not compile:" + Environment.NewLine +
                string.Join(Environment.NewLine, errors.Take(40)));

        var warnings = emit.Diagnostics
            .Where(diagnostic => diagnostic.Severity == DiagnosticSeverity.Warning)
            .Select(FormatDiagnostic)
            .Take(40)
            .ToArray();

        pe.Position = 0;
        var context = new OverrideLoadContext();
        Assembly assembly;
        try
        {
            assembly = context.LoadFromStream(pe);
        }
        catch
        {
            context.Unload();
            throw;
        }

        var matches = Targets
            .Select(target => (target, type: assembly.GetType(target.TypeName, throwOnError: false, ignoreCase: false)))
            .Where(pair => pair.type is not null)
            .ToArray();

        if (matches.Length == 0)
        {
            context.Unload();
            throw new InvalidDataException(
                "The source compiled, but it does not replace a supported internal class. " +
                "Supported files: " + string.Join(", ", SupportedFiles) + ".");
        }

        if (matches.Length > 1)
        {
            context.Unload();
            throw new InvalidDataException(
                "One override file must replace exactly one supported internal class.");
        }

        var selected = matches[0];
        try
        {
            return LoadedOverride.Create(context, selected.target, selected.type!, warnings);
        }
        catch
        {
            context.Unload();
            throw;
        }
    }

    private static IEnumerable<MetadataReference> BuildReferences()
    {
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        if (AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES") is string trusted &&
            !string.IsNullOrWhiteSpace(trusted))
        {
            foreach (var path in trusted.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
                AddReferencePath(paths, path);
        }

        foreach (var assembly in AppDomain.CurrentDomain.GetAssemblies())
        {
            if (assembly.IsDynamic) continue;
            try { AddReferencePath(paths, assembly.Location); }
            catch (NotSupportedException) { }
        }

        AddReferencePath(paths, typeof(Legacy.RendererEngine).Assembly.Location);
        AddReferencePath(paths, typeof(SKBitmap).Assembly.Location);

        return paths.Select(path => MetadataReference.CreateFromFile(path)).ToArray();
    }

    private static void AddReferencePath(HashSet<string> paths, string? path)
    {
        if (!string.IsNullOrWhiteSpace(path) && File.Exists(path)) paths.Add(path);
    }

    private static string FormatDiagnostic(Diagnostic diagnostic)
    {
        var span = diagnostic.Location.GetLineSpan();
        if (!span.IsValid || string.IsNullOrWhiteSpace(span.Path))
            return $"{diagnostic.Id}: {diagnostic.GetMessage()}";

        var line = span.StartLinePosition.Line + 1;
        var column = span.StartLinePosition.Character + 1;
        return $"{Path.GetFileName(span.Path)}({line},{column}): {diagnostic.Id}: {diagnostic.GetMessage()}";
    }

    private static string FirstUsefulLine(string message) =>
        message.Replace("\r", "").Split('\n').FirstOrDefault(line => !string.IsNullOrWhiteSpace(line))?.Trim()
        ?? "Unknown override error.";

    private static string SourcePath(OverrideTarget target)
    {
        var root = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CubicalCompare",
            "code-overrides",
            target.Engine);
        return Path.Combine(root, target.FriendlyName);
    }

    private static void AtomicWriteText(string path, string content)
    {
        var directory = Path.GetDirectoryName(path)
            ?? throw new InvalidOperationException("Could not determine the internal-code override directory.");
        Directory.CreateDirectory(directory);
        var temp = path + ".tmp";
        File.WriteAllText(temp, content);
        File.Move(temp, path, overwrite: true);
    }

    private sealed record OverrideTarget(string Engine, string TypeName, string FriendlyName);

    private sealed class OverrideLoadContext : AssemblyLoadContext
    {
        public OverrideLoadContext() : base($"CubicalCompare.InternalOverride.{Guid.NewGuid():N}", isCollectible: true) { }

        protected override Assembly? Load(AssemblyName assemblyName)
        {
            return Default.Assemblies.FirstOrDefault(
                assembly => AssemblyName.ReferenceMatchesDefinition(assembly.GetName(), assemblyName));
        }
    }

    private sealed class LoadedOverride : IDisposable
    {
        private readonly OverrideLoadContext _context;
        private readonly object _instance;
        private readonly MethodInfo _render;
        private readonly MethodInfo _frameCount;
        private readonly MethodInfo? _dispose;

        private LoadedOverride(
            OverrideLoadContext context,
            OverrideTarget target,
            object instance,
            MethodInfo render,
            MethodInfo frameCount,
            MethodInfo? dispose,
            IReadOnlyList<string> warnings)
        {
            _context = context;
            Target = target;
            _instance = instance;
            _render = render;
            _frameCount = frameCount;
            _dispose = dispose;
            Warnings = warnings;
        }

        public OverrideTarget Target { get; }
        public IReadOnlyList<string> Warnings { get; }

        public static LoadedOverride Create(
            OverrideLoadContext context,
            OverrideTarget target,
            Type type,
            IReadOnlyList<string> warnings)
        {
            var constructor = type.GetConstructor(Type.EmptyTypes)
                ?? throw new InvalidDataException(
                    $"{target.FriendlyName} must have a public parameterless constructor.");

            var render = type.GetMethod(
                "Render",
                BindingFlags.Instance | BindingFlags.Public,
                binder: null,
                types:
                [
                    typeof(Legacy.StudioProject),
                    typeof(Legacy.RendererSpec),
                    typeof(int),
                    typeof(int),
                    typeof(int),
                ],
                modifiers: null)
                ?? throw new InvalidDataException(
                    $"{target.FriendlyName} must expose Render(StudioProject, RendererSpec, int, int, int).");

            if (!typeof(SKBitmap).IsAssignableFrom(render.ReturnType))
                throw new InvalidDataException($"{target.FriendlyName}.Render must return SKBitmap.");

            var frameCount = type.GetMethod(
                "FrameCount",
                BindingFlags.Instance | BindingFlags.Public,
                binder: null,
                types: [typeof(Legacy.StudioProject), typeof(Legacy.RendererSpec)],
                modifiers: null)
                ?? throw new InvalidDataException(
                    $"{target.FriendlyName} must expose FrameCount(StudioProject, RendererSpec).");

            if (frameCount.ReturnType != typeof(int))
                throw new InvalidDataException($"{target.FriendlyName}.FrameCount must return int.");

            var dispose = type.GetMethod(
                "Dispose",
                BindingFlags.Instance | BindingFlags.Public,
                binder: null,
                types: Type.EmptyTypes,
                modifiers: null);

            var instance = constructor.Invoke(null);
            return new LoadedOverride(context, target, instance, render, frameCount, dispose, warnings);
        }

        public SKBitmap Render(
            Legacy.StudioProject project,
            Legacy.RendererSpec spec,
            int frame,
            int width,
            int height)
        {
            var result = Invoke(_render, [project, spec, frame, width, height]);
            return result as SKBitmap
                ?? throw new InvalidOperationException($"{Target.FriendlyName}.Render returned null or a non-SKBitmap result.");
        }

        public int FrameCount(Legacy.StudioProject project, Legacy.RendererSpec spec)
        {
            var result = Invoke(_frameCount, [project, spec]);
            return result is int count
                ? count
                : throw new InvalidOperationException($"{Target.FriendlyName}.FrameCount returned a non-integer result.");
        }

        public void Dispose()
        {
            try
            {
                if (_dispose is not null) Invoke(_dispose, []);
            }
            catch
            {
                // Removing/replacing an override must remain possible even if its cleanup is broken.
            }
            finally
            {
                _context.Unload();
            }
        }

        private object? Invoke(MethodInfo method, object?[] arguments)
        {
            try
            {
                return method.Invoke(_instance, arguments);
            }
            catch (TargetInvocationException ex) when (ex.InnerException is not null)
            {
                ExceptionDispatchInfo.Capture(ex.InnerException).Throw();
                throw;
            }
        }
    }
}
