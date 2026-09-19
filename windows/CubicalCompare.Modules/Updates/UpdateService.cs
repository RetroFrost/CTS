using System.Diagnostics;
using System.IO.Compression;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using Velopack;
using Velopack.Exceptions;
using Velopack.Sources;

namespace CubicalCompare.Updates;

public enum CubicalUpdateDelivery
{
    Velopack,
    SetupExe,
    PortableZip,
}

public sealed record CubicalUpdateCandidate(
    CubicalUpdateDelivery Delivery,
    Version Version,
    string Tag,
    string AssetName,
    Uri? DownloadUri,
    Uri ReleaseUri,
    string SelectionReason,
    string? ExpectedSha256 = null,
    CubicalUpdateDelivery? FallbackDelivery = null,
    string? FallbackAssetName = null,
    Uri? FallbackDownloadUri = null,
    string? FallbackExpectedSha256 = null)
{
    public bool HasFallback => FallbackDelivery is not null && FallbackDownloadUri is not null;
}

public sealed record CubicalUpdateProgress(
    string Phase,
    int Percent,
    long BytesReceived,
    long? TotalBytes);

/// <summary>
/// Reliability-first Windows update service.
///
/// Selection is automatic:
/// 1. A real Velopack-managed copy prefers the published full .nupkg + releases.win.json feed.
/// 2. If Velopack cannot safely service an installed copy, a visible Setup.exe is preferred.
/// 3. Raw/portable copies use the versioned portable ZIP.
///
/// A Velopack candidate also carries a Setup.exe/ZIP fallback so transient package-feed
/// failures never strand the user on a broken portable replacement path.
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
        var current = Normalize(currentVersion);

        var release = await FindLatestReleaseAsync(current, cancellationToken);
        if (release is null)
            return null;

        if (release.FullNupkg is not null && release.ReleaseIndex is not null)
        {
            try
            {
                var manager = CreateVelopackManager();
                var update = await manager.CheckForUpdatesAsync();
                var targetName = update?.TargetFullRelease.FileName;

                if (update is not null &&
                    !string.IsNullOrWhiteSpace(targetName) &&
                    release.MatchesPackage(targetName))
                {
                    var fallback = BestInstalledFallback(release);
                    return new CubicalUpdateCandidate(
                        CubicalUpdateDelivery.Velopack,
                        release.Version,
                        release.Tag,
                        targetName,
                        null,
                        release.ReleaseUri,
                        "Automatic choice: verified Velopack package feed is available for this installed copy.",
                        null,
                        fallback?.Delivery,
                        fallback?.Asset.Name,
                        fallback?.Asset.Uri,
                        fallback?.Asset.Sha256);
                }

                var installedFallback = BestInstalledFallback(release);
                if (installedFallback is not null)
                    return BuildDirectCandidate(
                        release,
                        installedFallback.Value,
                        "Automatic choice: package feed did not resolve cleanly, so Cubical Compare selected the safer installed-app fallback.");
            }
            catch (NotInstalledException)
            {
                // Expected for raw folders and non-Velopack portable copies.
            }
            catch
            {
                var recovery = BestInstalledFallback(release) ?? BestPortableFallback(release);
                if (recovery is not null)
                    return BuildDirectCandidate(
                        release,
                        recovery.Value,
                        "Automatic choice: the package updater was temporarily unavailable, so Cubical Compare selected the safest available fallback.");
            }
        }

        var portable = BestPortableFallback(release) ?? BestInstalledFallback(release);
        return portable is null
            ? null
            : BuildDirectCandidate(
                release,
                portable.Value,
                portable.Value.Delivery == CubicalUpdateDelivery.PortableZip
                    ? "Automatic choice: this copy is not managed by Velopack, so the portable ZIP is the safest update path."
                    : "Automatic choice: no portable ZIP is available, so Cubical Compare selected the visible installer.");
    }

    public async Task<bool> ApplyUpdateAsync(
        CubicalUpdateCandidate candidate,
        string applicationDirectory,
        string executableName,
        IProgress<CubicalUpdateProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(candidate);
        ArgumentException.ThrowIfNullOrWhiteSpace(applicationDirectory);
        ArgumentException.ThrowIfNullOrWhiteSpace(executableName);

        if (candidate.Delivery == CubicalUpdateDelivery.Velopack)
        {
            try
            {
                var manager = CreateVelopackManager();
                var update = await manager.CheckForUpdatesAsync();
                if (update is null)
                    throw new InvalidOperationException("The Velopack update feed no longer contains the selected update.");

                progress?.Report(new CubicalUpdateProgress("Using verified package update", 0, 0, null));
                await manager.DownloadUpdatesAsync(
                    update,
                    value => progress?.Report(new CubicalUpdateProgress(
                        "Downloading verified package",
                        Math.Clamp(value, 0, 100),
                        0,
                        null)),
                    cancellationToken);

                progress?.Report(new CubicalUpdateProgress("Applying verified package", 100, 0, null));
                manager.ApplyUpdatesAndRestart(update);
                return false;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch when (candidate.HasFallback)
            {
                progress?.Report(new CubicalUpdateProgress(
                    "Package update unavailable - switching automatically",
                    0,
                    0,
                    null));
                return await ApplyFallbackAsync(
                    candidate,
                    applicationDirectory,
                    executableName,
                    progress,
                    cancellationToken);
            }
        }

        return candidate.Delivery switch
        {
            CubicalUpdateDelivery.SetupExe => await ApplySetupAsync(
                candidate.DownloadUri ?? throw new InvalidOperationException("The setup update does not have a download URL."),
                candidate.ExpectedSha256,
                progress,
                cancellationToken),

            CubicalUpdateDelivery.PortableZip => await ApplyPortableZipAsync(
                candidate.DownloadUri ?? throw new InvalidOperationException("The portable update does not have a download URL."),
                candidate.ExpectedSha256,
                applicationDirectory,
                executableName,
                progress,
                cancellationToken),

            _ => throw new InvalidOperationException("Unknown update delivery method."),
        };
    }

    private static async Task<bool> ApplyFallbackAsync(
        CubicalUpdateCandidate candidate,
        string applicationDirectory,
        string executableName,
        IProgress<CubicalUpdateProgress>? progress,
        CancellationToken cancellationToken)
    {
        var delivery = candidate.FallbackDelivery
            ?? throw new InvalidOperationException("The selected update has no fallback delivery method.");
        var uri = candidate.FallbackDownloadUri
            ?? throw new InvalidOperationException("The selected update has no fallback download URL.");

        return delivery switch
        {
            CubicalUpdateDelivery.SetupExe => await ApplySetupAsync(
                uri,
                candidate.FallbackExpectedSha256,
                progress,
                cancellationToken),

            CubicalUpdateDelivery.PortableZip => await ApplyPortableZipAsync(
                uri,
                candidate.FallbackExpectedSha256,
                applicationDirectory,
                executableName,
                progress,
                cancellationToken),

            _ => throw new InvalidOperationException("Unsupported fallback update delivery method."),
        };
    }

    private static async Task<bool> ApplySetupAsync(
        Uri uri,
        string? expectedSha256,
        IProgress<CubicalUpdateProgress>? progress,
        CancellationToken cancellationToken)
    {
        var updateRoot = Path.Combine(Path.GetTempPath(), "CubicalCompare", "Updates", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(updateRoot);
        var setupPath = Path.Combine(updateRoot, "CubicalCompare-Update-Setup.exe");
        var started = false;

        try
        {
            await DownloadWithRetriesAsync(uri, setupPath, progress, "Downloading installer", cancellationToken);
            VerifySha256(setupPath, expectedSha256);
            var length = new FileInfo(setupPath).Length;
            progress?.Report(new CubicalUpdateProgress("Launching visible installer", 100, length, length));

            _ = Process.Start(new ProcessStartInfo
            {
                FileName = setupPath,
                UseShellExecute = true,
                WindowStyle = ProcessWindowStyle.Normal,
            }) ?? throw new InvalidOperationException("Windows could not start the Cubical Compare installer.");

            started = true;
            return true;
        }
        finally
        {
            if (!started)
            {
                try { Directory.Delete(updateRoot, recursive: true); }
                catch { }
            }
        }
    }

    private static async Task<bool> ApplyPortableZipAsync(
        Uri uri,
        string? expectedSha256,
        string applicationDirectory,
        string executableName,
        IProgress<CubicalUpdateProgress>? progress,
        CancellationToken cancellationToken)
    {
        var updateRoot = Path.Combine(Path.GetTempPath(), "CubicalCompare", "Updates", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(updateRoot);
        var zipPath = Path.Combine(updateRoot, "CubicalCompare-update.zip");
        var stagePath = Path.Combine(updateRoot, "stage");
        var scriptPath = Path.Combine(updateRoot, "Apply-CubicalCompareUpdate.ps1");
        var helperStarted = false;

        try
        {
            await DownloadWithRetriesAsync(uri, zipPath, progress, "Downloading portable update", cancellationToken);
            VerifySha256(zipPath, expectedSha256);

            var downloadedBytes = new FileInfo(zipPath).Length;
            progress?.Report(new CubicalUpdateProgress("Validating portable update", 100, downloadedBytes, downloadedBytes));
            ValidatePortableArchive(zipPath, executableName);

            if (Directory.Exists(stagePath))
                Directory.Delete(stagePath, recursive: true);
            ZipFile.ExtractToDirectory(zipPath, stagePath);
            var stagedRoot = ResolveApplicationRoot(stagePath, executableName);
            if (!File.Exists(Path.Combine(stagedRoot, executableName)))
                throw new InvalidDataException($"The staged update does not contain {executableName}.");

            await File.WriteAllTextAsync(scriptPath, BuildPortableUpdateScript(), cancellationToken);

            var target = Path.GetFullPath(applicationDirectory);
            var requiresElevation = !CanWriteDirectory(target);
            var process = Process.GetCurrentProcess();
            var args = $"-NoProfile -ExecutionPolicy Bypass -File \"{Escape(scriptPath)}\" " +
                       $"-ProcessId {process.Id} -StageDirectory \"{Escape(stagePath)}\" " +
                       $"-TargetDirectory \"{Escape(target)}\" -ExecutableName \"{Escape(executableName)}\" " +
                       $"-WorkRoot \"{Escape(updateRoot)}\"";

            var info = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = args,
                UseShellExecute = true,
                WindowStyle = requiresElevation ? ProcessWindowStyle.Normal : ProcessWindowStyle.Hidden,
            };
            if (requiresElevation)
                info.Verb = "runas";

            _ = Process.Start(info)
                ?? throw new InvalidOperationException("Windows could not start the portable update helper.");
            helperStarted = true;
            return true;
        }
        finally
        {
            if (!helperStarted)
            {
                try { Directory.Delete(updateRoot, recursive: true); }
                catch { }
            }
        }
    }

    private static async Task<ReleaseSnapshot?> FindLatestReleaseAsync(
        Version currentVersion,
        CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(ReleasesApiUrl, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);

        var releases = new List<ReleaseSnapshot>();
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

            var releaseUri = release.TryGetProperty("html_url", out var htmlNode) &&
                             Uri.TryCreate(htmlNode.GetString(), UriKind.Absolute, out var parsedRelease)
                ? parsedRelease
                : new Uri(ReleasesPageUrl);

            var assets = new List<ReleaseAsset>();
            if (release.TryGetProperty("assets", out var assetArray) && assetArray.ValueKind == JsonValueKind.Array)
            {
                foreach (var asset in assetArray.EnumerateArray())
                {
                    var name = asset.TryGetProperty("name", out var nameNode)
                        ? nameNode.GetString() ?? string.Empty
                        : string.Empty;
                    var url = asset.TryGetProperty("browser_download_url", out var urlNode)
                        ? urlNode.GetString()
                        : null;
                    if (name.Length == 0 || string.IsNullOrWhiteSpace(url) ||
                        !Uri.TryCreate(url, UriKind.Absolute, out var uri))
                        continue;

                    var digest = asset.TryGetProperty("digest", out var digestNode)
                        ? ParseSha256Digest(digestNode.GetString())
                        : null;
                    assets.Add(new ReleaseAsset(name, uri, digest));
                }
            }

            releases.Add(new ReleaseSnapshot(Normalize(version), tag, releaseUri, assets));
        }

        return releases
            .OrderByDescending(release => release.Version)
            .ThenByDescending(release => release.Tag, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();
    }

    private static CubicalUpdateCandidate BuildDirectCandidate(
        ReleaseSnapshot release,
        DeliveryAsset choice,
        string reason) =>
        new(
            choice.Delivery,
            release.Version,
            release.Tag,
            choice.Asset.Name,
            choice.Asset.Uri,
            release.ReleaseUri,
            reason,
            choice.Asset.Sha256);

    private static DeliveryAsset? BestInstalledFallback(ReleaseSnapshot release)
    {
        if (release.Setup is not null)
            return new DeliveryAsset(CubicalUpdateDelivery.SetupExe, release.Setup);
        if (release.PortableZip is not null)
            return new DeliveryAsset(CubicalUpdateDelivery.PortableZip, release.PortableZip);
        return null;
    }

    private static DeliveryAsset? BestPortableFallback(ReleaseSnapshot release)
    {
        if (release.PortableZip is not null)
            return new DeliveryAsset(CubicalUpdateDelivery.PortableZip, release.PortableZip);
        if (release.Setup is not null)
            return new DeliveryAsset(CubicalUpdateDelivery.SetupExe, release.Setup);
        return null;
    }

    private static UpdateManager CreateVelopackManager() =>
        new(new GithubSource(RepositoryUrl, null, false));

    private static bool IsPortableWindowsZip(string name)
    {
        if (string.IsNullOrWhiteSpace(name) ||
            !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase) ||
            !name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase) ||
            name.Contains("source", StringComparison.OrdinalIgnoreCase))
            return false;

        return name.Contains("windows", StringComparison.OrdinalIgnoreCase) ||
               name.Contains("win-x64", StringComparison.OrdinalIgnoreCase) ||
               name.Contains("portable", StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsSetupWindowsExe(string name) =>
        !string.IsNullOrWhiteSpace(name) &&
        name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) &&
        name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase) &&
        name.Contains("Setup", StringComparison.OrdinalIgnoreCase);

    private static bool IsFullNupkg(string name) =>
        !string.IsNullOrWhiteSpace(name) &&
        name.EndsWith("-full.nupkg", StringComparison.OrdinalIgnoreCase) &&
        name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase);

    private static bool IsReleaseIndex(string name) =>
        name.Equals("releases.win.json", StringComparison.OrdinalIgnoreCase);

    private static async Task DownloadWithRetriesAsync(
        Uri uri,
        string destination,
        IProgress<CubicalUpdateProgress>? progress,
        string phase,
        CancellationToken cancellationToken)
    {
        Exception? last = null;
        var delays = new[] { TimeSpan.Zero, TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(3) };

        for (var attempt = 0; attempt < delays.Length; attempt++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (delays[attempt] > TimeSpan.Zero)
                await Task.Delay(delays[attempt], cancellationToken);

            try
            {
                if (File.Exists(destination))
                    File.Delete(destination);
                await DownloadAsync(uri, destination, progress, phase, cancellationToken);
                return;
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (Exception ex)
            {
                last = ex;
            }
        }

        throw new IOException("The update download failed after three attempts.", last);
    }

    private static async Task DownloadAsync(
        Uri uri,
        string destination,
        IProgress<CubicalUpdateProgress>? progress,
        string phase,
        CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        var total = response.Content.Headers.ContentLength;
        await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
        await using var output = new FileStream(destination, FileMode.Create, FileAccess.Write, FileShare.Read, 256 * 1024, true);

        var buffer = new byte[256 * 1024];
        long received = 0;
        while (true)
        {
            var read = await input.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken);
            if (read <= 0) break;
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            received += read;
            var percent = total is > 0
                ? (int)Math.Clamp(Math.Round(received * 100d / total.Value), 0, 100)
                : 0;
            progress?.Report(new CubicalUpdateProgress(phase, percent, received, total));
        }

        await output.FlushAsync(cancellationToken);
    }

    private static void VerifySha256(string path, string? expectedSha256)
    {
        if (string.IsNullOrWhiteSpace(expectedSha256))
            return;

        using var stream = File.OpenRead(path);
        var actual = Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
        if (!actual.Equals(expectedSha256, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("The downloaded update failed its GitHub SHA-256 verification.");
    }

    private static string? ParseSha256Digest(string? digest)
    {
        if (string.IsNullOrWhiteSpace(digest))
            return null;
        const string prefix = "sha256:";
        var value = digest.Trim();
        if (!value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            return null;
        var hash = value[prefix.Length..].Trim();
        return hash.Length == 64 && hash.All(Uri.IsHexDigit) ? hash.ToLowerInvariant() : null;
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
            if (normalized.StartsWith('/', StringComparison.Ordinal) ||
                normalized.Split('/', StringSplitOptions.RemoveEmptyEntries).Any(part => part == ".."))
                throw new InvalidDataException($"Unsafe path in update ZIP: {entry.FullName}");

            checked { expanded += entry.Length; }
            if (expanded > maxExpandedBytes)
                throw new InvalidDataException("The update ZIP expands beyond the safety limit.");

            if (normalized.Equals(executableName, StringComparison.OrdinalIgnoreCase) ||
                normalized.EndsWith('/' + executableName, StringComparison.OrdinalIgnoreCase))
                hasExecutable = true;
        }

        if (!hasExecutable)
            throw new InvalidDataException($"The release ZIP does not contain {executableName}; refusing to treat it as an app update.");
    }

    private static string ResolveApplicationRoot(string stagePath, string executableName)
    {
        if (File.Exists(Path.Combine(stagePath, executableName)))
            return stagePath;

        var matches = Directory
            .EnumerateFiles(stagePath, executableName, SearchOption.AllDirectories)
            .Select(Path.GetDirectoryName)
            .Where(path => !string.IsNullOrWhiteSpace(path))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToArray();

        return matches.Length == 1
            ? matches[0]!
            : throw new InvalidDataException("Could not identify exactly one Cubical Compare application root in the portable update.");
    }

    private static string BuildPortableUpdateScript() => """
param(
    [Parameter(Mandatory=$true)][int]$ProcessId,
    [Parameter(Mandatory=$true)][string]$StageDirectory,
    [Parameter(Mandatory=$true)][string]$TargetDirectory,
    [Parameter(Mandatory=$true)][string]$ExecutableName,
    [Parameter(Mandatory=$true)][string]$WorkRoot
)
$ErrorActionPreference = 'Stop'
$backup = Join-Path $WorkRoot 'backup'

function Invoke-RobocopyChecked([string]$Source, [string]$Destination, [switch]$Mirror) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    $arguments = @($Source, $Destination)
    if ($Mirror) { $arguments += '/MIR' } else { $arguments += '/E' }
    $arguments += @('/R:3','/W:1','/COPY:DAT','/DCOPY:DAT','/NFL','/NDL','/NJH','/NJS','/NP')
    & robocopy.exe @arguments | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "Robocopy failed with exit code $LASTEXITCODE." }
}

function Resolve-AppRoot([string]$Root, [string]$ExeName) {
    $direct = Join-Path $Root $ExeName
    if (Test-Path $direct) { return $Root }
    $matches = @(Get-ChildItem -LiteralPath $Root -Filter $ExeName -File -Recurse -ErrorAction Stop)
    if ($matches.Count -ne 1) { throw 'Could not identify exactly one application root in the staged update.' }
    return $matches[0].DirectoryName
}

try {
    Wait-Process -Id $ProcessId -ErrorAction SilentlyContinue
    $source = Resolve-AppRoot $StageDirectory $ExecutableName

    if (Test-Path $backup) { Remove-Item $backup -Recurse -Force }
    if (Test-Path $TargetDirectory) {
        Invoke-RobocopyChecked $TargetDirectory $backup
    }

    Invoke-RobocopyChecked $source $TargetDirectory -Mirror

    $exe = Join-Path $TargetDirectory $ExecutableName
    if (-not (Test-Path $exe)) { throw "Updated executable is missing: $exe" }
    Start-Process -FilePath $exe -WorkingDirectory $TargetDirectory
}
catch {
    $originalError = $_ | Out-String
    try {
        if (Test-Path $backup) {
            Invoke-RobocopyChecked $backup $TargetDirectory -Mirror
            $oldExe = Join-Path $TargetDirectory $ExecutableName
            if (Test-Path $oldExe) {
                Start-Process -FilePath $oldExe -WorkingDirectory $TargetDirectory
            }
        }
    }
    catch {
        $originalError += [Environment]::NewLine + "Rollback also failed:" + [Environment]::NewLine + ($_ | Out-String)
    }

    $errorPath = Join-Path $env:TEMP 'CubicalCompare-update-error.txt'
    $originalError | Set-Content -Path $errorPath -Encoding UTF8
    Start-Process notepad.exe $errorPath
    throw
}
finally {
    Remove-Item -LiteralPath $StageDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
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

    private static string Escape(string value) => value.Replace("\"", "\\\"");

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("CubicalCompare/4");
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }

    private readonly record struct DeliveryAsset(CubicalUpdateDelivery Delivery, ReleaseAsset Asset);

    private sealed record ReleaseAsset(string Name, Uri Uri, string? Sha256);

    private sealed record ReleaseSnapshot(
        Version Version,
        string Tag,
        Uri ReleaseUri,
        IReadOnlyList<ReleaseAsset> Assets)
    {
        public ReleaseAsset? PortableZip => Assets
            .Where(asset => IsPortableWindowsZip(asset.Name))
            .OrderByDescending(asset => asset.Name.Contains("portable", StringComparison.OrdinalIgnoreCase))
            .ThenByDescending(asset => asset.Name.Contains("x64", StringComparison.OrdinalIgnoreCase))
            .ThenBy(asset => asset.Name, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();

        public ReleaseAsset? Setup => Assets
            .Where(asset => IsSetupWindowsExe(asset.Name))
            .OrderByDescending(asset => asset.Name.Contains("x64", StringComparison.OrdinalIgnoreCase))
            .ThenBy(asset => asset.Name, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();

        public ReleaseAsset? FullNupkg => Assets
            .Where(asset => IsFullNupkg(asset.Name))
            .OrderBy(asset => asset.Name, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();

        public ReleaseAsset? ReleaseIndex => Assets
            .Where(asset => IsReleaseIndex(asset.Name))
            .FirstOrDefault();

        public bool MatchesPackage(string fileName) =>
            Assets.Any(asset =>
                IsFullNupkg(asset.Name) &&
                asset.Name.Equals(fileName, StringComparison.OrdinalIgnoreCase));
    }
}
