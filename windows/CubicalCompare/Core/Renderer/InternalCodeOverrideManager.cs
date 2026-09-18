using System.Reflection;
using System.Runtime.ExceptionServices;
using System.Runtime.Loader;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using SkiaSharp;
using Legacy = CubicalCompare.Windows;

namespace CubicalCompare.Core.Renderer;

public sealed record InternalCodeOverrideInstallResult(
    IReadOnlyList<string> Files,
    IReadOnlyList<string> ActivatedTargets,
    string StoredSourceDirectory,
    IReadOnlyList<string> Warnings);

public sealed record InternalCodeOverrideStatus(
    bool Installed,
    bool Loaded,
    string StoredSourceDirectory,
    IReadOnlyList<string> Files,
    IReadOnlyList<string> ActivatedTargets,
    string? Error);

/// <summary>
/// Compiles a developer-selected C# source bundle with Roslyn and hot-swaps compatible
/// renderer classes behind the legacy renderer dispatch. File names are intentionally
/// irrelevant: every selected .cs file is compiled as part of one bundle so helper types,
/// partial classes and shared utilities can be imported together.
/// </summary>
public static class InternalCodeOverrideManager
{
    private const int MaxFileBytes = 4 * 1024 * 1024;
    private const int MaxBundleBytes = 16 * 1024 * 1024;
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
        new("relationships-exact", "CubicalCompare.Windows.RelationshipsRenderer", "Relationships renderer"),
        new("infinite-timeline-exact", "CubicalCompare.Windows.InfiniteTimelineRenderer", "Infinite Timeline renderer"),
    ];

    private static readonly object Gate = new();
    private static LoadedBundle? _activeBundle;
    private static bool _loadAttempted;
    private static string? _loadError;

    public static IReadOnlyList<string> HotSwapTargets => Targets.Select(target => target.DisplayName).ToArray();

    public static InternalCodeOverrideInstallResult InstallMany(IEnumerable<string> sourcePaths)
    {
        ArgumentNullException.ThrowIfNull(sourcePaths);
        var files = sourcePaths
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Select(Path.GetFullPath)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        if (files.Length == 0)
            throw new InvalidDataException("Select at least one .cs source file.");

        long bundleBytes = 0;
        var sources = new List<SourceUnit>(files.Length);
        foreach (var path in files)
        {
            var info = new FileInfo(path);
            if (!info.Exists) throw new FileNotFoundException("A selected C# source file does not exist.", path);
            if (!info.Extension.Equals(".cs", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException($"Only .cs source files can be imported: {info.Name}");
            if (info.Length <= 0) throw new InvalidDataException($"The selected source file is empty: {info.Name}");
            if (info.Length > MaxFileBytes) throw new InvalidDataException($"{info.Name} exceeds the 4 MiB per-file limit.");
            checked { bundleBytes += info.Length; }
            if (bundleBytes > MaxBundleBytes)
                throw new InvalidDataException("The combined C# source bundle exceeds 16 MiB.");

            sources.Add(new SourceUnit(info.Name, File.ReadAllText(info.FullName)));
        }

        var compiled = CompileAndLoad(sources);
        try
        {
            PersistSources(sources);
        }
        catch
        {
            compiled.Dispose();
            throw;
        }

        lock (Gate)
        {
            _activeBundle?.Dispose();
            _activeBundle = compiled;
            _loadAttempted = true;
            _loadError = null;
        }

        return new InternalCodeOverrideInstallResult(
            sources.Select(source => source.FileName).ToArray(),
            compiled.ActivatedTargets,
            SourceDirectory,
            compiled.Warnings);
    }

    public static InternalCodeOverrideStatus Status()
    {
        lock (Gate)
        {
            var files = ReadPersistedSourcePaths()
                .Select(Path.GetFileName)
                .Where(name => !string.IsNullOrWhiteSpace(name))
                .Cast<string>()
                .ToArray();

            return new InternalCodeOverrideStatus(
                files.Length > 0,
                _activeBundle is not null,
                SourceDirectory,
                files,
                _activeBundle?.ActivatedTargets ?? Array.Empty<string>(),
                _loadError);
        }
    }

    public static bool RemoveAll()
    {
        lock (Gate)
        {
            var removed = _activeBundle is not null || Directory.Exists(SourceDirectory);
            _activeBundle?.Dispose();
            _activeBundle = null;
            _loadAttempted = false;
            _loadError = null;

            if (Directory.Exists(SourceDirectory))
                Directory.Delete(SourceDirectory, recursive: true);

            return removed;
        }
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
            EnsureBundleLoadedLocked();
            if (_activeBundle is null || !_activeBundle.TryGet(engine, out var renderer))
            {
                bitmap = null!;
                return false;
            }

            bitmap = renderer.Render(project, spec, frame, width, height);
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
            EnsureBundleLoadedLocked();
            if (_activeBundle is null || !_activeBundle.TryGet(engine, out var renderer))
            {
                frameCount = 0;
                return false;
            }

            frameCount = renderer.FrameCount(project, spec);
            return true;
        }
    }

    /// <summary>
    /// CI-only verification for multi-file compilation and renderer contract binding.
    /// </summary>
    public static void RunCompilerSelfTest()
    {
        var sources = new[]
        {
            new SourceUnit("Helper.cs", """
namespace CubicalCompare.Windows;
internal static class OverrideHelper
{
    public static int Frames => 7;
}
"""),
            new SourceUnit("AnythingYouWant.cs", """
using SkiaSharp;

namespace CubicalCompare.Windows;

public sealed class RelationshipsRenderer : IDisposable
{
    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)
        => new(Math.Max(2, width), Math.Max(2, height));

    public int FrameCount(StudioProject project, RendererSpec spec) => OverrideHelper.Frames;
    public void Dispose() { }
}
"""),
        };

        using var loaded = CompileAndLoad(sources);
        if (!loaded.TryGet("relationships-exact", out var renderer))
            throw new InvalidOperationException("Internal code override self-test did not activate the relationships target.");

        var project = new Legacy.StudioProject();
        var spec = Legacy.RendererSpec.BuiltIn();
        using var bitmap = renderer.Render(project, spec, 0, 8, 6);
        if (bitmap.Width != 8 || bitmap.Height != 6 || renderer.FrameCount(project, spec) != 7)
            throw new InvalidOperationException("Internal code override compiler self-test returned unexpected results.");
    }

    private static void EnsureBundleLoadedLocked()
    {
        if (_activeBundle is not null || _loadAttempted) return;
        _loadAttempted = true;

        var paths = ReadPersistedSourcePaths();
        if (paths.Length == 0) return;

        try
        {
            var sources = paths.Select(path => new SourceUnit(Path.GetFileName(path), File.ReadAllText(path))).ToArray();
            _activeBundle = CompileAndLoad(sources);
            _loadError = null;
        }
        catch (Exception ex)
        {
            _loadError = FirstUsefulLine(ex.Message);
            _activeBundle?.Dispose();
            _activeBundle = null;
        }
    }

    private static LoadedBundle CompileAndLoad(IReadOnlyList<SourceUnit> sources)
    {
        if (sources.Count == 0) throw new InvalidDataException("The source bundle is empty.");

        var parseOptions = CSharpParseOptions.Default.WithLanguageVersion(LanguageVersion.CSharp14);
        var trees = new List<SyntaxTree>(sources.Count + 1)
        {
            CSharpSyntaxTree.ParseText(GlobalUsingsSource, parseOptions, "CubicalCompare.Override.GlobalUsings.g.cs"),
        };
        trees.AddRange(sources.Select(source =>
            CSharpSyntaxTree.ParseText(source.Text, parseOptions, source.FileName)));

        var compilation = CSharpCompilation.Create(
            $"CubicalCompare.InternalOverride.{Guid.NewGuid():N}",
            trees,
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
                "The C# source bundle did not compile:" + Environment.NewLine +
                string.Join(Environment.NewLine, errors.Take(60)));

        var warnings = emit.Diagnostics
            .Where(diagnostic => diagnostic.Severity == DiagnosticSeverity.Warning)
            .Select(FormatDiagnostic)
            .Take(60)
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

        try
        {
            var renderers = new Dictionary<string, LoadedRenderer>(StringComparer.Ordinal);
            foreach (var target in Targets)
            {
                var type = assembly.GetType(target.TypeName, throwOnError: false, ignoreCase: false);
                if (type is null) continue;
                renderers[target.Engine] = LoadedRenderer.Create(target, type);
            }

            return new LoadedBundle(context, renderers, warnings);
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

    private static void PersistSources(IReadOnlyList<SourceUnit> sources)
    {
        var parent = Path.GetDirectoryName(SourceDirectory)
            ?? throw new InvalidOperationException("Could not determine the developer override directory.");
        Directory.CreateDirectory(parent);

        var token = Guid.NewGuid().ToString("N");
        var staging = SourceDirectory + ".new-" + token;
        var backup = SourceDirectory + ".old-" + token;
        Directory.CreateDirectory(staging);
        var oldMoved = false;
        var newInstalled = false;

        try
        {
            for (var index = 0; index < sources.Count; index++)
            {
                var safeName = SanitizeFileName(sources[index].FileName);
                var destination = Path.Combine(staging, $"{index + 1:D3}-{safeName}");
                File.WriteAllText(destination, sources[index].Text);
            }

            // Never delete the last working bundle before the replacement directory is
            // ready. Directory.Move is a same-volume rename here, so the swap leaves us
            // with either the old bundle or the new one if an I/O error interrupts it.
            if (Directory.Exists(SourceDirectory))
            {
                Directory.Move(SourceDirectory, backup);
                oldMoved = true;
            }

            Directory.Move(staging, SourceDirectory);
            newInstalled = true;

            if (oldMoved && Directory.Exists(backup))
            {
                try { Directory.Delete(backup, recursive: true); }
                catch { }
            }
        }
        catch
        {
            if (!newInstalled && oldMoved && Directory.Exists(backup) && !Directory.Exists(SourceDirectory))
            {
                try { Directory.Move(backup, SourceDirectory); }
                catch { }
            }
            throw;
        }
        finally
        {
            if (Directory.Exists(staging))
            {
                try { Directory.Delete(staging, recursive: true); }
                catch { }
            }
            if (newInstalled && Directory.Exists(backup))
            {
                try { Directory.Delete(backup, recursive: true); }
                catch { }
            }
        }
    }

    private static string[] ReadPersistedSourcePaths()
    {
        if (!Directory.Exists(SourceDirectory)) return [];
        return Directory.GetFiles(SourceDirectory, "*.cs", SearchOption.TopDirectoryOnly)
            .OrderBy(path => path, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private static string SanitizeFileName(string fileName)
    {
        var name = Path.GetFileName(fileName);
        foreach (var invalid in Path.GetInvalidFileNameChars())
            name = name.Replace(invalid, '_');
        return string.IsNullOrWhiteSpace(name) ? "source.cs" : name;
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

    private static string SourceDirectory => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RetroFrost",
        "CubicalCompare",
        "code-overrides",
        "bundle");

    private sealed record SourceUnit(string FileName, string Text);
    private sealed record OverrideTarget(string Engine, string TypeName, string DisplayName);

    private sealed class OverrideLoadContext : AssemblyLoadContext
    {
        public OverrideLoadContext() : base($"CubicalCompare.InternalOverride.{Guid.NewGuid():N}", isCollectible: true) { }

        protected override Assembly? Load(AssemblyName assemblyName)
        {
            return Default.Assemblies.FirstOrDefault(
                assembly => AssemblyName.ReferenceMatchesDefinition(assembly.GetName(), assemblyName));
        }
    }

    private sealed class LoadedBundle : IDisposable
    {
        private readonly OverrideLoadContext _context;
        private readonly IReadOnlyDictionary<string, LoadedRenderer> _renderers;

        public LoadedBundle(
            OverrideLoadContext context,
            IReadOnlyDictionary<string, LoadedRenderer> renderers,
            IReadOnlyList<string> warnings)
        {
            _context = context;
            _renderers = renderers;
            Warnings = warnings;
        }

        public IReadOnlyList<string> Warnings { get; }
        public IReadOnlyList<string> ActivatedTargets =>
            _renderers.Values.Select(renderer => renderer.Target.DisplayName).OrderBy(name => name, StringComparer.Ordinal).ToArray();

        public bool TryGet(string engine, out LoadedRenderer renderer) => _renderers.TryGetValue(engine, out renderer!);

        public void Dispose()
        {
            foreach (var renderer in _renderers.Values)
                renderer.Dispose();
            _context.Unload();
        }
    }

    private sealed class LoadedRenderer : IDisposable
    {
        private readonly object _instance;
        private readonly MethodInfo _render;
        private readonly MethodInfo _frameCount;
        private readonly MethodInfo? _dispose;

        private LoadedRenderer(
            OverrideTarget target,
            object instance,
            MethodInfo render,
            MethodInfo frameCount,
            MethodInfo? dispose)
        {
            Target = target;
            _instance = instance;
            _render = render;
            _frameCount = frameCount;
            _dispose = dispose;
        }

        public OverrideTarget Target { get; }

        public static LoadedRenderer Create(OverrideTarget target, Type type)
        {
            var constructor = type.GetConstructor(Type.EmptyTypes)
                ?? throw new InvalidDataException($"{target.TypeName} must have a public parameterless constructor.");

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
                    $"{target.TypeName} must expose Render(StudioProject, RendererSpec, int, int, int).");

            if (!typeof(SKBitmap).IsAssignableFrom(render.ReturnType))
                throw new InvalidDataException($"{target.TypeName}.Render must return SKBitmap.");

            var frameCount = type.GetMethod(
                "FrameCount",
                BindingFlags.Instance | BindingFlags.Public,
                binder: null,
                types: [typeof(Legacy.StudioProject), typeof(Legacy.RendererSpec)],
                modifiers: null)
                ?? throw new InvalidDataException(
                    $"{target.TypeName} must expose FrameCount(StudioProject, RendererSpec).");

            if (frameCount.ReturnType != typeof(int))
                throw new InvalidDataException($"{target.TypeName}.FrameCount must return int.");

            var dispose = type.GetMethod(
                "Dispose",
                BindingFlags.Instance | BindingFlags.Public,
                binder: null,
                types: Type.EmptyTypes,
                modifiers: null);

            return new LoadedRenderer(target, constructor.Invoke(null), render, frameCount, dispose);
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
                ?? throw new InvalidOperationException($"{Target.TypeName}.Render returned null or a non-SKBitmap result.");
        }

        public int FrameCount(Legacy.StudioProject project, Legacy.RendererSpec spec)
        {
            var result = Invoke(_frameCount, [project, spec]);
            return result is int count
                ? count
                : throw new InvalidOperationException($"{Target.TypeName}.FrameCount returned a non-integer result.");
        }

        public void Dispose()
        {
            if (_dispose is null) return;
            try { Invoke(_dispose, []); }
            catch { }
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
