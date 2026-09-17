using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Text.Json;
using Velopack;
using Velopack.Exceptions;
using Velopack.Sources;

namespace CubicalCompare.Updates;

public enum CubicalUpdateDelivery
{
    Velopack,
    PortableZip,
}

public sealed record CubicalUpdateCandidate(
    CubicalUpdateDelivery Delivery,
    Version Version,
    string Tag,
    string AssetName,
    Uri? DownloadUri,
    Uri ReleaseUri);

/// <summary>
/// Update service for both installed and portable Cubical Compare builds.
/// Installed copies use Velopack packages. A raw/portable copy falls back to a
/// named GitHub Release ZIP asset. GitHub's generated source-code archives are
/// intentionally impossible to select because they are not release assets.
/// </summary>
public sealed class CubicalUpdateService
{
    public const string RepositoryUrl = "https://github.com/RetroFrost/CTS";
    public const string ReleasesPageUrl = RepositoryUrl + "/releases";
    private const string ReleasesApiUrl = "https://api.github.com/repos/RetroFrost/CTS/releases?per_page=30";

    private static readonly HttpClient Http = CreateHttpClient();

    public async Task<CubicalUpdateCandidate?> CheckForUpdatesAsync(
        Version currentVersion,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(currentVersion);

        // A real Velopack install gets the atomic/delta updater. Development builds
        // and plain extracted ZIPs throw NotInstalledException and use the portable path.
        try
        {
            var manager = new UpdateManager(new GithubSource(RepositoryUrl, null, false));
            var update = await manager.CheckForUpdatesAsync();
            if (update is not null)
            {
                var text = update.TargetFullRelease.Version.ToString();
                var version = ParseVersion(text) ?? IncrementFallback(currentVersion);
                return new CubicalUpdateCandidate(
                    CubicalUpdateDelivery.Velopack,
                    version,
                    text,
                    update.TargetFullRelease.FileName ?? "Velopack update",
                    null,
                    new Uri(ReleasesPageUrl));
            }
        }
        catch (NotInstalledException)
        {
            // Expected for the portable/raw folder build.
        }
        catch
        {
            // If Velopack cannot reach its release index, still try the regular GitHub
            // release API so portable builds and first-generation installs remain usable.
        }

        return await CheckPortableZipAsync(Normalize(currentVersion), cancellationToken);
    }

    public async Task<bool> ApplyUpdateAsync(
        CubicalUpdateCandidate candidate,
        string applicationDirectory,
        string executableName,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(executableName);

        if (candidate.Delivery == CubicalUpdateDelivery.Velopack)
        {
            var manager = new UpdateManager(new GithubSource(RepositoryUrl, null, false));
            var update = await manager.CheckForUpdatesAsync();
            if (update is null)
                return false;

            await manager.DownloadUpdatesAsync(update);
            manager.ApplyUpdatesAndRestart(update);
            return false; // ApplyUpdatesAndRestart normally terminates this process.
        }

        if (candidate.DownloadUri is null)
            throw new InvalidOperationException("The portable update does not have a download URL.");

        var updateRoot = Path.Combine(Path.GetTempPath(), "CubicalCompare", "Updates", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(updateRoot);
        var zipPath = Path.Combine(updateRoot, "CubicalCompare-update.zip");
        var scriptPath = Path.Combine(updateRoot, "Apply-CubicalCompareUpdate.ps1");

        await DownloadAsync(candidate.DownloadUri, zipPath, cancellationToken);
        ValidatePortableArchive(zipPath, executableName);
        await File.WriteAllTextAsync(scriptPath, BuildPortableUpdateScript(), cancellationToken);

        var target = Path.GetFullPath(applicationDirectory);
        var requiresElevation = !CanWriteDirectory(target);
        var process = Process.GetCurrentProcess();
        var args = $"-NoProfile -ExecutionPolicy Bypass -File \"{Escape(scriptPath)}\" " +
                   $"-ProcessId {process.Id} -ZipPath \"{Escape(zipPath)}\" " +
                   $"-TargetDirectory \"{Escape(target)}\" -ExecutableName \"{Escape(executableName)}\"";

        var info = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = args,
            UseShellExecute = true,
            WindowStyle = ProcessWindowStyle.Hidden,
        };
        if (requiresElevation)
        {
            info.Verb = "runas";
            info.WindowStyle = ProcessWindowStyle.Normal;
        }

        _ = Process.Start(info)
            ?? throw new InvalidOperationException("Windows could not start the portable update helper.");

        // Caller should close the running app so the helper can replace locked files.
        return true;
    }

    private static async Task<CubicalUpdateCandidate?> CheckPortableZipAsync(
        Version currentVersion,
        CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(ReleasesApiUrl, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);

        foreach (var release in document.RootElement.EnumerateArray())
        {
            if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean())
                continue;
            if (release.TryGetProperty("prerelease", out var prerelease) && prerelease.GetBoolean())
                continue;

            var tag = release.TryGetProperty("tag_name", out var tagNode)
                ? tagNode.GetString() ?? string.Empty
                : string.Empty;
            var version = ParseVersion(tag);
            if (version is null || Normalize(version) <= currentVersion)
                continue;

            if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                continue;

            var candidates = new List<(string Name, Uri Uri, int Score)>();
            foreach (var asset in assets.EnumerateArray())
            {
                var name = asset.TryGetProperty("name", out var nameNode)
                    ? nameNode.GetString() ?? string.Empty
                    : string.Empty;
                var url = asset.TryGetProperty("browser_download_url", out var urlNode)
                    ? urlNode.GetString()
                    : null;

                if (!IsPortableWindowsZip(name)
                    || string.IsNullOrWhiteSpace(url)
                    || !Uri.TryCreate(url, UriKind.Absolute, out var uri))
                    continue;

                var score = name.Contains("portable", StringComparison.OrdinalIgnoreCase) ? 10 : 0;
                if (name.Contains("x64", StringComparison.OrdinalIgnoreCase)) score += 4;
                candidates.Add((name, uri, score));
            }

            var selected = candidates.OrderByDescending(x => x.Score).ThenBy(x => x.Name, StringComparer.OrdinalIgnoreCase).FirstOrDefault();
            if (selected.Uri is null)
                continue;

            var releaseUrl = release.TryGetProperty("html_url", out var htmlNode)
                && Uri.TryCreate(htmlNode.GetString(), UriKind.Absolute, out var parsedRelease)
                ? parsedRelease
                : new Uri(ReleasesPageUrl);

            return new CubicalUpdateCandidate(
                CubicalUpdateDelivery.PortableZip,
                Normalize(version),
                tag,
                selected.Name,
                selected.Uri,
                releaseUrl);
        }

        return null;
    }

    private static bool IsPortableWindowsZip(string name)
    {
        if (string.IsNullOrWhiteSpace(name)
            || !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase)
            || !name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase)
            || name.Contains("source", StringComparison.OrdinalIgnoreCase))
            return false;

        return name.Contains("windows", StringComparison.OrdinalIgnoreCase)
            || name.Contains("win-x64", StringComparison.OrdinalIgnoreCase)
            || name.Contains("portable", StringComparison.OrdinalIgnoreCase);
    }

    private static async Task DownloadAsync(Uri uri, string destination, CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 256 * 1024, true);
        await input.CopyToAsync(output, 256 * 1024, cancellationToken);
        await output.FlushAsync(cancellationToken);
    }

    private static void ValidatePortableArchive(string path, string executableName)
    {
        const int maxEntries = 50_000;
        const long maxExpandedBytes = 6L * 1024 * 1024 * 1024;

        using var archive = ZipFile.OpenRead(path);
        if (archive.Entries.Count == 0 || archive.Entries.Count > maxEntries)
            throw new InvalidDataException("The update ZIP has an invalid entry count.");

        long expanded = 0;
        var hasExecutable = false;
        foreach (var entry in archive.Entries)
        {
            var normalized = entry.FullName.Replace('\\', '/');
            if (normalized.StartsWith('/', StringComparison.Ordinal)
                || normalized.Split('/', StringSplitOptions.RemoveEmptyEntries).Any(part => part == ".."))
                throw new InvalidDataException($"Unsafe path in update ZIP: {entry.FullName}");

            checked { expanded += entry.Length; }
            if (expanded > maxExpandedBytes)
                throw new InvalidDataException("The update ZIP expands beyond the safety limit.");

            if (normalized.Equals(executableName, StringComparison.OrdinalIgnoreCase)
                || normalized.EndsWith('/' + executableName, StringComparison.OrdinalIgnoreCase))
                hasExecutable = true;
        }

        if (!hasExecutable)
            throw new InvalidDataException($"The release ZIP does not contain {executableName}; refusing to treat it as an app update.");
    }

    private static string BuildPortableUpdateScript() => """
param(
    [Parameter(Mandatory=$true)][int]$ProcessId,
    [Parameter(Mandatory=$true)][string]$ZipPath,
    [Parameter(Mandatory=$true)][string]$TargetDirectory,
    [Parameter(Mandatory=$true)][string]$ExecutableName
)
$ErrorActionPreference = 'Stop'
$stage = Join-Path ([IO.Path]::GetDirectoryName($ZipPath)) 'stage'
try {
    Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
    Expand-Archive -LiteralPath $ZipPath -DestinationPath $stage -Force

    $source = $stage
    if (-not (Test-Path (Join-Path $source $ExecutableName))) {
        $roots = @(Get-ChildItem -LiteralPath $stage -Directory | Where-Object {
            Test-Path (Join-Path $_.FullName $ExecutableName)
        })
        if ($roots.Count -ne 1) { throw 'Could not identify the Cubical Compare application root in the update ZIP.' }
        $source = $roots[0].FullName
    }

    New-Item -ItemType Directory -Path $TargetDirectory -Force | Out-Null
    & robocopy.exe $source $TargetDirectory /E /R:3 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Robocopy failed with exit code $LASTEXITCODE." }

    $exe = Join-Path $TargetDirectory $ExecutableName
    if (-not (Test-Path $exe)) { throw "Updated executable is missing: $exe" }
    Start-Process -FilePath $exe -WorkingDirectory $TargetDirectory
}
catch {
    $errorPath = Join-Path $env:TEMP 'CubicalCompare-update-error.txt'
    ($_ | Out-String) | Set-Content -Path $errorPath -Encoding UTF8
    Start-Process notepad.exe $errorPath
    throw
}
finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $ZipPath -Force -ErrorAction SilentlyContinue
}
""";

    private static bool CanWriteDirectory(string directory)
    {
        try
        {
            Directory.CreateDirectory(directory);
            var probe = Path.Combine(directory, ".cc-write-" + Guid.NewGuid().ToString("N"));
            File.WriteAllText(probe, "ok");
            File.Delete(probe);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static Version? ParseVersion(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return null;
        var value = text.Trim().TrimStart('v', 'V');
        var separator = value.IndexOfAny(['-', '+']);
        if (separator >= 0) value = value[..separator];
        return Version.TryParse(value, out var version) ? Normalize(version) : null;
    }

    private static Version Normalize(Version value) => new(
        Math.Max(0, value.Major),
        Math.Max(0, value.Minor),
        Math.Max(0, value.Build),
        Math.Max(0, value.Revision));

    private static Version IncrementFallback(Version current) => new(
        Math.Max(0, current.Major),
        Math.Max(0, current.Minor),
        Math.Max(0, current.Build) + 1,
        0);

    private static string Escape(string value) => value.Replace("\"", "\\\"");

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("CubicalCompare/4");
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }
}
