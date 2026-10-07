using System.Diagnostics;
using System.Runtime.InteropServices;

namespace CubicalCompare.Core.Project.Patching;

public sealed record BuildToolEnvironment(string Dotnet, string PowerShell);

public interface IBuildToolSetupHost
{
    Task<string?> FindDotnetSdkAsync(CancellationToken cancellationToken);
    Task<string?> FindPowerShellAsync(CancellationToken cancellationToken);
    bool HasWindowsSdk();
    string? FindPackageManager();
    Task InstallAsync(string packageManager, string packageId, string displayName, IProgress<string>? progress, CancellationToken cancellationToken);
}

/// <summary>Installs missing Microsoft build dependencies with visible installers, then probes again.</summary>
public static class BuildToolBootstrap
{
    public static async Task<BuildToolEnvironment> EnsureAsync(IProgress<string>? progress = null, CancellationToken cancellationToken = default, IBuildToolSetupHost? host = null)
    {
        host ??= new WindowsBuildToolSetupHost();
        progress?.Report("Checking build tools…");
        var dotnet = await host.FindDotnetSdkAsync(cancellationToken);
        var powershell = await host.FindPowerShellAsync(cancellationToken);
        var windowsSdk = host.HasWindowsSdk();
        if (dotnet is not null && powershell is not null && windowsSdk) return new(dotnet, powershell);
        var winget = host.FindPackageManager() ?? throw new InvalidOperationException(
            "Automatic build-tool setup needs Windows App Installer (WinGet). Install or repair App Installer from https://learn.microsoft.com/windows/package-manager/winget/ and retry. Your app code has not been replaced.");
        if (dotnet is null)
        {
            await host.InstallAsync(winget, "Microsoft.DotNet.SDK.10", ".NET 10 SDK", progress, cancellationToken);
            dotnet = await host.FindDotnetSdkAsync(cancellationToken);
            if (dotnet is null) throw new InvalidOperationException("The .NET SDK installer finished, but no .NET 10 SDK was detected. Restart Windows if the installer requested it, then retry.");
        }
        if (powershell is null)
        {
            await host.InstallAsync(winget, "Microsoft.PowerShell", "PowerShell 7", progress, cancellationToken);
            powershell = await host.FindPowerShellAsync(cancellationToken);
            if (powershell is null) throw new InvalidOperationException("PowerShell setup finished, but PowerShell 7 was not detected. Restart Windows if requested, then retry.");
        }
        if (!windowsSdk)
        {
            await host.InstallAsync(winget, "Microsoft.WindowsSDK.10.0.26100", "Windows SDK build tools", progress, cancellationToken);
            if (!host.HasWindowsSdk()) throw new InvalidOperationException("Windows SDK setup finished, but its headers and resource compiler were not detected. Keep the Windows SDK desktop components selected in the installer, then retry.");
        }
        cancellationToken.ThrowIfCancellationRequested();
        progress?.Report("Build tools are ready. Continuing the patch build…");
        return new(dotnet, powershell);
    }

    public static string BuildPath
    {
        get
        {
            var paths = new[]
            {
                Environment.GetEnvironmentVariable("PATH"),
                OperatingSystem.IsWindows() ? Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.Machine) : null,
                OperatingSystem.IsWindows() ? Environment.GetEnvironmentVariable("PATH", EnvironmentVariableTarget.User) : null,
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "dotnet"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "PowerShell", "7"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WindowsApps"),
            };
            return string.Join(Path.PathSeparator, paths.Where(x => !string.IsNullOrWhiteSpace(x)).SelectMany(x => x!.Split(Path.PathSeparator)).Distinct(StringComparer.OrdinalIgnoreCase));
        }
    }

    private sealed class WindowsBuildToolSetupHost : IBuildToolSetupHost
    {
        public async Task<string?> FindDotnetSdkAsync(CancellationToken token)
        {
            foreach (var path in Candidates("dotnet.exe", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "dotnet", "dotnet.exe"), Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".dotnet", "dotnet.exe")))
            {
                var output = await ProbeAsync(path, ["--list-sdks"], token);
                if (output is not null && output.Split('\n').Any(line => int.TryParse(line.Split('.')[0], out var major) && major >= 10)) return path;
            }
            return null;
        }

        public async Task<string?> FindPowerShellAsync(CancellationToken token)
        {
            foreach (var path in Candidates("pwsh.exe", Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "PowerShell", "7", "pwsh.exe")))
            {
                var output = await ProbeAsync(path, ["-NoLogo", "-NoProfile", "-Command", "$PSVersionTable.PSVersion.Major"], token);
                if (int.TryParse(output?.Trim(), out var major) && major >= 7) return path;
            }
            return null;
        }

        public bool HasWindowsSdk()
        {
            foreach (var programFiles in new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles) }.Distinct())
            {
                var kits = Path.Combine(programFiles, "Windows Kits", "10");
                var include = Path.Combine(kits, "Include");
                if (!Directory.Exists(include)) continue;
                foreach (var directory in Directory.EnumerateDirectories(include))
                {
                    var version = Path.GetFileName(directory);
                    if (!Version.TryParse(version, out var number) || number < new Version(10, 0, 26100, 0)) continue;
                    var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
                    if (File.Exists(Path.Combine(directory, "um", "Windows.h")) && File.Exists(Path.Combine(kits, "bin", version, arch, "rc.exe"))) return true;
                }
            }
            return false;
        }

        public string? FindPackageManager() => Candidates("winget.exe").FirstOrDefault();

        private static IEnumerable<string> Candidates(string executable, params string[] explicitPaths) =>
            explicitPaths.Concat(BuildPath.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries).Select(path => Path.Combine(path.Trim('"'), executable)))
                .Distinct(StringComparer.OrdinalIgnoreCase).Where(File.Exists);

        private static async Task<string?> ProbeAsync(string path, IReadOnlyList<string> arguments, CancellationToken token)
        {
            var info = new ProcessStartInfo(path) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
            info.Environment["PATH"] = BuildPath;
            foreach (var argument in arguments) info.ArgumentList.Add(argument);
            using var process = new Process { StartInfo = info };
            try
            {
                process.Start();
                using var registration = token.Register(() => TryStop(process));
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(TimeSpan.FromSeconds(30));
                var output = process.StandardOutput.ReadToEndAsync(timeout.Token);
                var error = process.StandardError.ReadToEndAsync(timeout.Token);
                await process.WaitForExitAsync(timeout.Token);
                await error;
                var text = await output;
                return process.ExitCode == 0 ? text : null;
            }
            catch (OperationCanceledException) { TryStop(process); token.ThrowIfCancellationRequested(); return null; }
            catch (System.ComponentModel.Win32Exception) { return null; }
        }

        public async Task InstallAsync(string packageManager, string packageId, string displayName, IProgress<string>? progress, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            progress?.Report($"Installing {displayName}. Its setup window is visible; approve Windows elevation if asked…");
            var info = new ProcessStartInfo(packageManager) { UseShellExecute = true, WindowStyle = ProcessWindowStyle.Normal };
            foreach (var argument in InstallArguments(packageId)) info.ArgumentList.Add(argument);
            using var process = Process.Start(info) ?? throw new InvalidOperationException("Could not start build-tool setup.");
            using var registration = token.Register(() => TryStop(process));
            await process.WaitForExitAsync();
            token.ThrowIfCancellationRequested();
            if (process.ExitCode != 0 && process.ExitCode != 3010)
                throw new InvalidOperationException($"{displayName} setup stopped with code 0x{unchecked((uint)process.ExitCode):X8}. If you cancelled its installer or declined elevation, retry when ready. App code was not replaced.");
        }

        private static void TryStop(Process process)
        {
            try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
            catch (InvalidOperationException) { } catch (System.ComponentModel.Win32Exception) { }
        }
    }

    public static IReadOnlyList<string> InstallArguments(string packageId) =>
        ["install", "--id", packageId, "--exact", "--source", "winget", "--interactive", "--accept-package-agreements", "--accept-source-agreements"];
}
