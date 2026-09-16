using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.System;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private Uri? _latestWindowsPackageUri;
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
            Panel.SetZIndex(_settingsPage, 100);

        // The release channel currently ships the verified installer bundle as a ZIP.
        // The old updater only exposed its action button for raw MSIX/AppInstaller assets,
        // so users could see "Update available" with no update button. Replace that click
        // path with one that supports both direct installers and verified release ZIPs.
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
        foreach (var child in inspectorRoot.Children.Where(child => Grid.GetRow(child) == 0))
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
                Panel.SetZIndex(_settingsPage, 100);
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

        // The built-in handler disables the button synchronously before its first await.
        // Wait for it to finish so our ZIP-aware action is applied last and cannot be
        // hidden again by the legacy updater path.
        var deadline = DateTimeOffset.UtcNow.AddSeconds(20);
        while (!_checkUpdatesButton.IsEnabled && DateTimeOffset.UtcNow < deadline)
            await Task.Delay(80);
    }

    private async Task RefreshUpdateActionAsync()
    {
        if (_installUpdateButton is null)
            return;

        _latestWindowsPackageUri = null;
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

                Uri? fallbackZip = null;
                Uri? directInstaller = null;

                foreach (var asset in assets.EnumerateArray())
                {
                    var name = asset.TryGetProperty("name", out var nameNode)
                        ? nameNode.GetString() ?? string.Empty
                        : string.Empty;
                    var url = asset.TryGetProperty("browser_download_url", out var urlNode)
                        ? urlNode.GetString()
                        : null;

                    if (string.IsNullOrWhiteSpace(url) || !IsWindowsReleaseAsset(name) || !Uri.TryCreate(url, UriKind.Absolute, out var assetUri))
                        continue;

                    var extension = Path.GetExtension(name);
                    if (extension.Equals(".msix", StringComparison.OrdinalIgnoreCase)
                        || extension.Equals(".msixbundle", StringComparison.OrdinalIgnoreCase)
                        || extension.Equals(".appinstaller", StringComparison.OrdinalIgnoreCase))
                    {
                        directInstaller = assetUri;
                        break;
                    }

                    if (extension.Equals(".zip", StringComparison.OrdinalIgnoreCase))
                        fallbackZip ??= assetUri;
                }

                _latestWindowsPackageUri = directInstaller ?? fallbackZip;
                _latestWindowsPackageIsDirectInstaller = directInstaller is not null;
                if (_latestWindowsPackageUri is null)
                    continue;

                if (release.TryGetProperty("html_url", out var htmlNode)
                    && Uri.TryCreate(htmlNode.GetString(), UriKind.Absolute, out var releaseUri))
                    _latestReleaseUri = releaseUri;

                _installUpdateButton.Content = _latestWindowsPackageIsDirectInstaller
                    ? "Install update"
                    : "Download update";
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
        }

        await OpenLatestReleaseAsync();
    }
}
