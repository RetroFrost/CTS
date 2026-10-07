using System.Diagnostics;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;

namespace CubicalCompare.Core.Project.Patching;

/// <summary>Applies full-app patches only in an isolated source/build workspace.</summary>
public sealed class AppPatchBuilder : IDisposable
{
    public string JobDirectory { get; }
    public string SourceDirectory => Path.Combine(JobDirectory, "source");
    public string BuildLogPath => Path.Combine(JobDirectory, "build.log");
    public IReadOnlyList<string> ChangedFiles { get; private set; } = [];
    private AppPatchBuilder(string jobDirectory) => JobDirectory = jobDirectory;

    public static async Task<AppPatchBuilder> PrepareAsync(string sourceZip, IEnumerable<string> patchPaths, string jobRoot, CancellationToken cancellationToken = default)
    {
        if (!File.Exists(sourceZip)) throw new FileNotFoundException("This app build has no bundled source snapshot. Install a release with full-app patch support first.", sourceZip);
        var job = new AppPatchBuilder(Path.Combine(jobRoot, Guid.NewGuid().ToString("N")));
        Directory.CreateDirectory(job.SourceDirectory);
        try
        {
            await Task.Run(() =>
            {
                ExtractSource(sourceZip, job.SourceDirectory, cancellationToken);
                var changed = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var patchPath in patchPaths)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    if (!patchPath.EndsWith(".patch", StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Select .patch files.");
                    if (new FileInfo(patchPath).Length > 32 * 1024 * 1024) throw new InvalidDataException("Patch exceeds 32 MB.");
                    var patch = File.ReadAllText(patchPath, new UTF8Encoding(false, true));
                    var changes = UnifiedPatch.Prepare(job.SourceDirectory, patch);
                    UnifiedPatch.Apply(job.SourceDirectory, changes);
                    foreach (var change in changes)
                        foreach (var path in new[] { change.OldPath, change.NewPath }) if (path is not null) changed.Add(path);
                }
                if (changed.Count == 0) throw new InvalidDataException("No patches selected.");
                if (!File.Exists(Path.Combine(job.SourceDirectory, "windows", "CubicalCompare", "CubicalCompare.csproj")))
                    throw new InvalidDataException("Patch removed the app project; it cannot build a replacement.");
                job.ChangedFiles = changed.OrderBy(x => x, StringComparer.Ordinal).ToArray();
            }, cancellationToken);
            return job;
        }
        catch { job.Dispose(); throw; }
    }

    private static void ExtractSource(string sourceZip, string root, CancellationToken cancellationToken)
    {
        using var archive = ZipFile.OpenRead(sourceZip);
        if (archive.Entries.Count is 0 or > 50000) throw new InvalidDataException("Source snapshot entry count is invalid.");
        long size = 0;
        var paths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in archive.Entries)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (entry.FullName.EndsWith('/')) continue;
            checked { size += entry.Length; }
            if (size > 512L * 1024 * 1024) throw new InvalidDataException("Source snapshot exceeds 512 MB.");
            var path = UnifiedPatch.Resolve(root, entry.FullName);
            if (!paths.Add(path)) throw new InvalidDataException("Source snapshot contains duplicate paths.");
            if (((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000) throw new InvalidDataException("Source snapshot contains a link.");
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            entry.ExtractToFile(path);
        }
    }

    public async Task<string> BuildAsync(IProgress<string>? progress = null, CancellationToken cancellationToken = default)
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("Full WinUI app patch builds require Windows.");
        progress?.Report("Checking .NET SDK and PowerShell build tools…");
        var sdks = await RunAsync("dotnet", ["--list-sdks"], cancellationToken: cancellationToken);
        if (!sdks.Split('\n').Any(line => int.TryParse(line.Split('.')[0], out var major) && major >= 10))
            throw new InvalidOperationException("Install the .NET 10 SDK, PowerShell 7 and Windows desktop build tools, then retry. The app's bundled .NET runtime cannot compile source.");
        await RunAsync("pwsh", ["-NoLogo", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.ToString()"], cancellationToken: cancellationToken);
        var architecture = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "ARM64" : "x64";
        var runtime = architecture == "ARM64" ? "win-arm64" : "win-x64";
        var publish = Path.Combine(JobDirectory, "publish");
        progress?.Report("Restoring dependencies and compiling the full app…");
        await RunAsync("dotnet", ["publish", Path.Combine("windows", "CubicalCompare", "CubicalCompare.csproj"), "-c", "Release", "-r", runtime,
            "-p:Platform=" + architecture, "--self-contained", "true", "-o", publish], progress, cancellationToken);
        foreach (var required in new[] { "CubicalCompare.exe", "CubicalCompare.dll", "CubicalCompare.pri", "CubicalCompare.runtimeconfig.json" })
            if (!File.Exists(Path.Combine(publish, required))) throw new InvalidDataException($"Build is incomplete: {required} is missing. See {BuildLogPath}.");
        if (!File.Exists(Path.Combine(publish, "Assets", "AppSource.zip")))
            throw new InvalidDataException("Patched build is missing its source snapshot; refusing to break future patch imports.");
        // Retain Velopack install identity for its existing updater, not an invented package version.
        var manifest = Path.Combine(AppContext.BaseDirectory, "sq.version");
        if (File.Exists(manifest)) File.Copy(manifest, Path.Combine(publish, "sq.version"), overwrite: true);
        progress?.Report("Packaging the compiled app…");
        var zip = Path.Combine(JobDirectory, "patched-app.zip");
        await Task.Run(() => ZipFile.CreateFromDirectory(publish, zip, CompressionLevel.Fastest, false), cancellationToken);
        return zip;
    }

    private async Task<string> RunAsync(string executable, IReadOnlyList<string> arguments, IProgress<string>? progress = null, CancellationToken cancellationToken = default)
    {
        var info = new ProcessStartInfo(executable) { WorkingDirectory = SourceDirectory, UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var argument in arguments) info.ArgumentList.Add(argument);
        using var process = new Process { StartInfo = info };
        try { process.Start(); }
        catch (System.ComponentModel.Win32Exception ex) { throw new InvalidOperationException($"Build tool '{executable}' was not found. Install .NET 10 SDK, PowerShell 7 and Windows desktop build tools. No app code was replaced.", ex); }
        using var registration = cancellationToken.Register(() => { try { if (!process.HasExited) process.Kill(entireProcessTree: true); } catch (InvalidOperationException) { } catch (System.ComponentModel.Win32Exception) { } });
        var output = new Queue<string>();
        var lastProgress = DateTime.MinValue;
        var gate = new object();
        using var log = new StreamWriter(BuildLogPath, append: true);
        async Task Drain(StreamReader reader)
        {
            while (await reader.ReadLineAsync() is { } line)
            {
                lock (gate)
                {
                    output.Enqueue(line.Length > 10000 ? line[..10000] : line);
                    while (output.Count > 2000) output.Dequeue();
                    log.WriteLine(line);
                    log.Flush();
                    if (DateTime.UtcNow - lastProgress > TimeSpan.FromMilliseconds(200))
                    {
                        progress?.Report(line.Length > 220 ? line[..220] : line);
                        lastProgress = DateTime.UtcNow;
                    }
                }
            }
        }
        await Task.WhenAll(Drain(process.StandardOutput), Drain(process.StandardError), process.WaitForExitAsync());
        cancellationToken.ThrowIfCancellationRequested();
        if (process.ExitCode != 0) throw new InvalidOperationException($"{executable} failed with code {process.ExitCode}. Full log: {BuildLogPath}\n" + string.Join('\n', output.TakeLast(12)));
        return string.Join("\n", output);
    }

    public void RetainLogOnly()
    {
        foreach (var directory in new[] { SourceDirectory, Path.Combine(JobDirectory, "publish") })
        {
            try { if (Directory.Exists(directory)) Directory.Delete(directory, recursive: true); }
            catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
        try { File.Delete(Path.Combine(JobDirectory, "patched-app.zip")); }
        catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    // Build failures keep their logs for diagnosis; cancellation/review rejection can remove the job.
    public void Dispose() { try { Directory.Delete(JobDirectory, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { } }
}
