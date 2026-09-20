using System.Reflection;
using System.Text.RegularExpressions;

namespace CubicalCompare.Windows;

/// <summary>
/// Resolves the actual Cubical Compare product version seen by the running process.
/// Renderer compatibility must use this value instead of a version constant compiled
/// into the renderer module, because CubicalCompare.Renderer.dll is a separate assembly.
/// </summary>
public static class RendererRuntimeVersion
{
    private static readonly Lazy<string> Resolved = new(Resolve);

    public static string Current => Resolved.Value;

    private static string Resolve()
    {
        // The installed product identity belongs to the entry application assembly.
        // Keep the renderer assembly only as a fallback for tests/tools that host the
        // renderer without launching CubicalCompare.exe.
        var assemblies = new[]
        {
            Assembly.GetEntryAssembly(),
            typeof(RendererRuntimeVersion).Assembly,
        };

        foreach (var assembly in assemblies.Where(value => value is not null).Distinct())
        {
            var informational = assembly!
                .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?
                .InformationalVersion;
            var normalized = Normalize(informational);
            if (normalized is not null)
                return normalized;

            var fileVersion = assembly
                .GetCustomAttribute<AssemblyFileVersionAttribute>()?
                .Version;
            normalized = Normalize(fileVersion);
            if (normalized is not null)
                return normalized;

            normalized = Normalize(assembly.GetName().Version?.ToString());
            if (normalized is not null)
                return normalized;
        }

        // Unknown is deliberately lower than any normal release. That makes a
        // renderer requiring a specific Cubical Compare version fail explicitly
        // rather than being accepted under fabricated/stale version metadata.
        return "0.0.0.0";
    }

    internal static string? Normalize(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        // InformationalVersion can contain Source Link/build metadata such as
        // 4.2.1.18+abcdef. Renderer minAppVersion uses the numeric product part.
        var match = Regex.Match(
            value,
            @"^\s*(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?",
            RegexOptions.CultureInvariant);

        if (!match.Success)
            return null;

        var parts = match.Groups
            .Cast<Group>()
            .Skip(1)
            .Where(group => group.Success)
            .Select(group => group.Value)
            .ToArray();

        return parts.Length == 0 ? null : string.Join('.', parts);
    }
}
