using System.Diagnostics;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private Uri? _latestWindowsPackageUri;
    private Uri? _latestWindowsCertificateUri;
    private bool _latestWindowsPackageIsDirectInstaller;
    private bool _releaseUxFixesInitialized;

    internal void InitializeFinalReleaseFixes()
    {
        if (_releaseUxFixesInitialized)
            return;
        _releaseUxFixesInitialized = true;

        CollapsePrototypeInspectorTabsReliably();

        // Make navigation deterministic. The original XAML handler and the final-release
        // handler both remain in place for compatibility, but this handler explicitly
        // routes every final sidebar item to its real page so a dynamically rebuilt
        // NavigationView can never leave the content host on the wrong page.
        RootNavigation.SelectionChanged += FinalReleaseNavigationFix_SelectionChanged;

        if (_settingsPage is not null)
            Canvas.SetZIndex(_settingsPage, 100);

        if (_installUpdateButton is not null)
        {
            _installUpdateButton.Click -= InstallUpdate_Click;
            _installUpdateButton.Click += FixedUpdateAction_Click;
        }

        if (_checkUpdatesButton is not null)
        {
            _checkUpdatesButton.Click += async (_, _) =>
            {
                await WaitForBuiltInUpdateCheckAsync();
                await RefreshUpdateActionAsync();
            };
        }
    }

    private void CollapsePrototypeInspectorTabsReliably()
    {
        var inspector = ProjectPage.Children
            .OfType<Border>()
            .FirstOrDefault(border => Grid.GetColumn(border) == 2);

        if (inspector?.Child is not Grid inspectorRoot || inspectorRoot.RowDefinitions.Count < 2)
            return;

        inspectorRoot.RowDefinitions[0].Height = new GridLength(0);
        foreach (var child in inspectorRoot.Children.OfType<FrameworkElement>().Where(child => Grid.GetRow(child) == 0))
            child.Visibility = Visibility.Collapsed;
    }

    private async void FinalReleaseNavigationFix_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        var tag = args.SelectedItemContainer?.Tag as string
            ?? (args.SelectedItem as NavigationViewItem)?.Tag as string;

        if (string.IsNullOrWhiteSpace(tag))
            return;

        if (string.Equals(tag, "settings", StringComparison.Ordinal))
        {
            ShowPage("__settings__");
            if (_settingsPage is not null)
            {
                _settingsPage.Visibility = Visibility.Visible;
                Canvas.SetZIndex(_settingsPage, 100);
            }

            await WaitForBuiltInUpdateCheckAsync();
            await RefreshUpdateActionAsync();
            return;
        }

        if (_settingsPage is not null)
            _settingsPage.Visibility = Visibility.Collapsed;

        ShowPage(tag);
    }

    private async Task WaitForBuiltInUpdateCheckAsync()
    {
        if (_checkUpdatesButton is null)
            return;

        var deadline = DateTimeOffset.UtcNow.AddSeconds(20);
        while (!_checkUpdatesButton.IsEnabled && DateTimeOffset.UtcNow < deadline)
            await Task.Delay(80);
    }

    private async Task RefreshUpdateActionAsync()
    {
        if (_installUpdateButton is null)
            return;

        _latestWindowsPackageUri = null;
        _latestWindowsCertificateUri = null;
        _latestWindowsPackageIsDirectInstaller = false;
        _installUpdateButton.Visibility = Visibility.Collapsed;

        try
        {
            using var response = await UpdateHttpClient.GetAsync(ReleasesApiUrl);
            response.EnsureSuccessStatusCode();
            await using var stream = await response.Content.ReadAsStreamAsync();
            using var document = await JsonDocument.ParseAsync(stream);

            foreach (var release in document.RootElement.EnumerateArray())
            {
                if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean())
                    continue;
                if (release.TryGetProperty("prerelease", out var prerelease) && prerelease.GetBoolean())
                    continue;
                if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                    continue;

                var tag = release.TryGetProperty("tag_name", out var tagNode)
                    ? tagNode.GetString() ?? string.Empty
                    : string.Empty;
                var version = TryParseVersion(tag);
                if (version is not null && NormalizeVersion(version) <= NormalizeVersion(GetCurrentAppVersion()))
                    continue;

                Uri? appInstaller = null;
                Uri? msixBundle = null;
                Uri? msix = null;
                Uri? releaseCertificate = null;
                Uri? fallbackZip = null;

                foreach (var asset in assets.EnumerateArray())
                {
                    var name = asset.TryGetProperty("name", out var nameNode)
                        ? nameNode.GetString() ?? string.Empty
                        : string.Empty;
                    var url = asset.TryGetProperty("browser_download_url", out var urlNode)
                        ? urlNode.GetString()
                        : null;

                    if (string.IsNullOrWhiteSpace(url) || !Uri.TryCreate(url, UriKind.Absolute, out var assetUri))
                        continue;

                    var extension = Path.GetExtension(name);
                    if (extension.Equals(".cer", StringComparison.OrdinalIgnoreCase)
                        && name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase))
                    {
                        releaseCertificate ??= assetUri;
                        continue;
                    }

                    if (!IsWindowsReleaseAsset(name))
                        continue;

                    if (extension.Equals(".appinstaller", StringComparison.OrdinalIgnoreCase))
                        appInstaller ??= assetUri;
                    else if (extension.Equals(".msixbundle", StringComparison.OrdinalIgnoreCase))
                        msixBundle ??= assetUri;
                    else if (extension.Equals(".msix", StringComparison.OrdinalIgnoreCase))
                        msix ??= assetUri;
                    else if (extension.Equals(".zip", StringComparison.OrdinalIgnoreCase))
                        fallbackZip ??= assetUri;
                }

                // Prefer an App Installer-compatible package. CI attaches the matching
                // development certificate beside the MSIXBundle. The update helper first
                // downloads both, verifies the bundle signer matches that exact certificate,
                // then elevates once to enable the App Installer protocol and trust that
                // exact certificate before launching App Installer.
                _latestWindowsPackageUri = appInstaller ?? msixBundle ?? msix ?? fallbackZip;
                _latestWindowsCertificateUri = releaseCertificate;
                _latestWindowsPackageIsDirectInstaller = _latestWindowsPackageUri == appInstaller
                    || _latestWindowsPackageUri == msixBundle
                    || _latestWindowsPackageUri == msix;

                if (_latestWindowsPackageUri is null)
                    continue;

                if (release.TryGetProperty("html_url", out var htmlNode)
                    && Uri.TryCreate(htmlNode.GetString(), UriKind.Absolute, out var releaseUri))
                    _latestReleaseUri = releaseUri;

                var canPrepareAppInstaller = _latestWindowsPackageIsDirectInstaller
                    && (_latestWindowsPackageUri == appInstaller || _latestWindowsCertificateUri is not null);

                _installUpdateButton.Content = canPrepareAppInstaller ? "Install update" : "Download update";
                _installUpdateButton.Visibility = Visibility.Visible;
                return;
            }
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not refresh release update action", ex);
        }
    }

    private async void FixedUpdateAction_Click(object sender, RoutedEventArgs e)
    {
        if (_latestWindowsPackageUri is null)
        {
            await OpenLatestReleaseAsync();
            return;
        }

        try
        {
            var extension = Path.GetExtension(_latestWindowsPackageUri.AbsolutePath);
            if (_latestWindowsPackageIsDirectInstaller
                && (extension.Equals(".msixbundle", StringComparison.OrdinalIgnoreCase)
                    || extension.Equals(".msix", StringComparison.OrdinalIgnoreCase))
                && _latestWindowsCertificateUri is not null)
            {
                await PrepareAndLaunchAppInstallerAsync(_latestWindowsPackageUri, _latestWindowsCertificateUri);
                return;
            }

            if (_latestWindowsPackageIsDirectInstaller)
            {
                var appInstallerUri = new Uri($"ms-appinstaller:?source={Uri.EscapeDataString(_latestWindowsPackageUri.AbsoluteUri)}");
                if (await Launcher.LaunchUriAsync(appInstallerUri))
                    return;
            }
            else
            {
                if (_updateStatusText is not null)
                    _updateStatusText.Text = "Opening the verified Windows update download…";
                if (await Launcher.LaunchUriAsync(_latestWindowsPackageUri))
                    return;
            }
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not launch release update action", ex);
            if (_updateStatusText is not null)
                _updateStatusText.Text = $"Could not prepare the update: {ex.Message}";
        }

        await OpenLatestReleaseAsync();
    }

    private async Task PrepareAndLaunchAppInstallerAsync(Uri packageUri, Uri certificateUri)
    {
        if (_installUpdateButton is not null)
            _installUpdateButton.IsEnabled = false;
        if (_updateStatusText is not null)
            _updateStatusText.Text = "Preparing App Installer update… Windows will ask for administrator approval.";

        var helperDirectory = Path.Combine(Path.GetTempPath(), "CubicalCompare", "Updater");
        Directory.CreateDirectory(helperDirectory);
        var helperPath = Path.Combine(helperDirectory, "Prepare-CubicalCompareUpdate.ps1");

        var script = $$"""
        param(
            [Parameter(Mandatory=$true)][string]$PackageUrl,
            [Parameter(Mandatory=$true)][string]$CertificateUrl
        )
        $ErrorActionPreference = 'Stop'
        $work = Join-Path $env:TEMP ('CubicalCompare-Update-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $work -Force | Out-Null
        $package = Join-Path $work 'CubicalCompare.msixbundle'
        $certificate = Join-Path $work 'CubicalCompare-Development.cer'

        try {
            Invoke-WebRequest -Uri $PackageUrl -OutFile $package -UseBasicParsing
            Invoke-WebRequest -Uri $CertificateUrl -OutFile $certificate -UseBasicParsing

            $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($certificate)
            if ($cert.Subject -ne 'CN=RetroFrost Development') {
                throw "Unexpected update certificate subject: $($cert.Subject)"
            }
            if ((Get-Date) -gt $cert.NotAfter) {
                throw "The release signing certificate expired on $($cert.NotAfter)."
            }

            $signature = Get-AuthenticodeSignature -FilePath $package
            if (-not $signature.SignerCertificate) {
                throw 'The MSIXBundle has no readable signing certificate.'
            }
            if ($signature.SignerCertificate.Thumbprint -ne $cert.Thumbprint) {
                throw 'The MSIXBundle signer does not match the certificate attached to this release.'
            }

            & reg.exe add 'HKLM\SOFTWARE\Policies\Microsoft\Windows\AppInstaller' /v EnableMSAppInstallerProtocol /t REG_DWORD /d 1 /f | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'Could not enable the App Installer protocol policy.' }

            # Trust only the exact release certificate after the bundle/signer match check.
            Import-Certificate -FilePath $certificate -CertStoreLocation 'Cert:\LocalMachine\TrustedPeople' | Out-Null
            Import-Certificate -FilePath $certificate -CertStoreLocation 'Cert:\LocalMachine\Root' | Out-Null

            $signature = Get-AuthenticodeSignature -FilePath $package
            if ($signature.Status -ne 'Valid') {
                throw "MSIXBundle signature is not trusted after certificate installation: $($signature.Status)"
            }

            $encoded = [Uri]::EscapeDataString($PackageUrl)
            Start-Process "ms-appinstaller:?source=$encoded"
        }
        finally {
            Remove-Item -Path $work -Recurse -Force -ErrorAction SilentlyContinue
        }
        """;

        await File.WriteAllTextAsync(helperPath, script);

        try
        {
            var arguments = $"-NoProfile -ExecutionPolicy Bypass -File \"{helperPath}\" -PackageUrl \"{packageUri.AbsoluteUri}\" -CertificateUrl \"{certificateUri.AbsoluteUri}\"";
            using var process = Process.Start(new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = arguments,
                UseShellExecute = true,
                Verb = "runas",
                WindowStyle = ProcessWindowStyle.Normal,
            });

            if (process is null)
                throw new InvalidOperationException("Windows could not start the elevated update helper.");

            await process.WaitForExitAsync();
            if (process.ExitCode != 0)
                throw new InvalidOperationException($"Update preparation failed with exit code {process.ExitCode}.");

            if (_updateStatusText is not null)
                _updateStatusText.Text = "App Installer opened with the verified update package.";
        }
        finally
        {
            if (_installUpdateButton is not null)
                _installUpdateButton.IsEnabled = true;
            try { File.Delete(helperPath); } catch { }
        }
    }
}
