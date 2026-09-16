using System.Reflection;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.ApplicationModel;
using Windows.System;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const string ReleasesApiUrl = "https://api.github.com/repos/RetroFrost/CTS/releases?per_page=30";
    private const string ReleasesPageUrl = "https://github.com/RetroFrost/CTS/releases";

    private static readonly HttpClient UpdateHttpClient = CreateUpdateHttpClient();

    private Grid? _settingsPage;
    private TextBlock? _currentVersionText;
    private TextBlock? _latestVersionText;
    private TextBlock? _updateStatusText;
    private Button? _checkUpdatesButton;
    private Button? _installUpdateButton;
    private Button? _openReleaseButton;
    private Uri? _latestReleaseUri;
    private Uri? _latestInstallablePackageUri;
    private bool _settingsAutoChecked;

    internal void InitializeFinalReleaseUi()
    {
        if (_settingsPage is not null)
            return;

        BuildFinalNavigation();
        RemovePrototypeInspectorTabs();
        BuildSettingsPage();
        RootNavigation.SelectionChanged += FinalNavigation_SelectionChanged;

        if (RootNavigation.MenuItems.Count > 0)
            RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
    }

    private void BuildFinalNavigation()
    {
        RootNavigation.MenuItems.Clear();
        RootNavigation.MenuItems.Add(CreateNavigationItem("Workspace", "project", Symbol.Home));
        RootNavigation.MenuItems.Add(CreateNavigationItem("MegaPacks", "assets", Symbol.Library));
        RootNavigation.MenuItems.Add(CreateNavigationItem("Style & Model", "renderer", Symbol.Setting));

        RootNavigation.FooterMenuItems.Clear();
        RootNavigation.FooterMenuItems.Add(CreateNavigationItem("Settings", "settings", Symbol.Setting));
    }

    private static NavigationViewItem CreateNavigationItem(string content, string tag, Symbol symbol) => new()
    {
        Content = content,
        Tag = tag,
        Icon = new SymbolIcon(symbol),
    };

    private void RemovePrototypeInspectorTabs()
    {
        foreach (var descendant in EnumerateVisualDescendants(ProjectPage))
        {
            if (descendant is not Grid candidate)
                continue;

            var labels = candidate.Children
                .OfType<Button>()
                .Select(button => button.Content as string)
                .Where(text => text is not null)
                .Cast<string>()
                .ToHashSet(StringComparer.OrdinalIgnoreCase);

            if (!labels.SetEquals(["Card", "Text", "Style", "Project"]))
                continue;

            if (VisualTreeHelper.GetParent(candidate) is Border tabBorder)
            {
                tabBorder.Visibility = Visibility.Collapsed;
                if (VisualTreeHelper.GetParent(tabBorder) is Grid inspectorRoot && inspectorRoot.RowDefinitions.Count > 0)
                    inspectorRoot.RowDefinitions[0].Height = new GridLength(0);
            }
            break;
        }
    }

    private static IEnumerable<DependencyObject> EnumerateVisualDescendants(DependencyObject root)
    {
        var childCount = VisualTreeHelper.GetChildrenCount(root);
        for (var index = 0; index < childCount; index++)
        {
            var child = VisualTreeHelper.GetChild(root, index);
            yield return child;
            foreach (var nested in EnumerateVisualDescendants(child))
                yield return nested;
        }
    }

    private void BuildSettingsPage()
    {
        if (RootNavigation.Content is not Grid contentHost)
            throw new InvalidOperationException("The NavigationView content host is unavailable.");

        _currentVersionText = new TextBlock
        {
            Text = $"Cubical Compare {FormatVersion(GetCurrentAppVersion())}",
            FontSize = 16,
            FontWeight = Windows.UI.Text.FontWeights.SemiBold,
        };

        _latestVersionText = new TextBlock
        {
            Text = "Latest Windows release —",
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        _updateStatusText = new TextBlock
        {
            Text = "Updates are delivered from the official RetroFrost/CTS GitHub Releases feed.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        _checkUpdatesButton = new Button
        {
            Content = "Check for updates",
            Padding = new Thickness(16, 7, 16, 7),
            Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorAccentBrush"],
            Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.White),
        };
        _checkUpdatesButton.Click += async (_, _) => await CheckForUpdatesAsync(userInitiated: true);

        _installUpdateButton = new Button
        {
            Content = "Install update",
            Padding = new Thickness(16, 7, 16, 7),
            Visibility = Visibility.Collapsed,
        };
        _installUpdateButton.Click += InstallUpdate_Click;

        _openReleaseButton = new Button
        {
            Content = "Open GitHub Releases",
            Padding = new Thickness(16, 7, 16, 7),
        };
        _openReleaseButton.Click += OpenRelease_Click;

        var updateButtons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
        };
        updateButtons.Children.Add(_checkUpdatesButton);
        updateButtons.Children.Add(_installUpdateButton);
        updateButtons.Children.Add(_openReleaseButton);

        var updatesPanel = new StackPanel { Spacing = 10 };
        updatesPanel.Children.Add(new TextBlock
        {
            Text = "Updates",
            FontSize = 18,
            FontWeight = Windows.UI.Text.FontWeights.SemiBold,
        });
        updatesPanel.Children.Add(new TextBlock
        {
            Text = "Update server · GitHub Releases · stable Windows channel",
            FontSize = 12,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        updatesPanel.Children.Add(_currentVersionText);
        updatesPanel.Children.Add(_latestVersionText);
        updatesPanel.Children.Add(_updateStatusText);
        updatesPanel.Children.Add(updateButtons);

        var aboutPanel = new StackPanel { Spacing = 8 };
        aboutPanel.Children.Add(new TextBlock
        {
            Text = "Cubical Compare 4",
            FontSize = 18,
            FontWeight = Windows.UI.Text.FontWeights.SemiBold,
        });
        aboutPanel.Children.Add(new TextBlock
        {
            Text = "Windows comparison-video editor · Renderer v2/v3 compatibility · MegaPack Zipack2 · soundtrack muxing · direct artwork transform",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        aboutPanel.Children.Add(new TextBlock
        {
            Text = "Release channel: Stable",
            FontSize = 12,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });

        var pageStack = new StackPanel
        {
            Spacing = 14,
            MaxWidth = 900,
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        pageStack.Children.Add(new TextBlock
        {
            Text = "Settings",
            FontSize = 30,
            FontWeight = Windows.UI.Text.FontWeights.SemiBold,
        });
        pageStack.Children.Add(new TextBlock
        {
            Text = "Final release settings and update delivery.",
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        pageStack.Children.Add(CreateSettingsCard(updatesPanel));
        pageStack.Children.Add(CreateSettingsCard(aboutPanel));

        _settingsPage = new Grid
        {
            Visibility = Visibility.Collapsed,
        };
        _settingsPage.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = pageStack,
        });

        contentHost.Children.Add(_settingsPage);
    }

    private static Border CreateSettingsCard(UIElement content) => new()
    {
        Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorSurfaceRaisedBrush"],
        BorderBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorBorderBrush"],
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(14),
        Padding = new Thickness(18),
        Child = content,
    };

    private async void FinalNavigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_settingsPage is null)
            return;

        var isSettings = args.SelectedItemContainer?.Tag as string == "settings";
        _settingsPage.Visibility = isSettings ? Visibility.Visible : Visibility.Collapsed;

        if (isSettings && !_settingsAutoChecked)
        {
            _settingsAutoChecked = true;
            await CheckForUpdatesAsync(userInitiated: false);
        }
    }

    private async Task CheckForUpdatesAsync(bool userInitiated)
    {
        if (_updateStatusText is null || _latestVersionText is null || _checkUpdatesButton is null || _installUpdateButton is null)
            return;

        _checkUpdatesButton.IsEnabled = false;
        _installUpdateButton.Visibility = Visibility.Collapsed;
        _latestReleaseUri = null;
        _latestInstallablePackageUri = null;
        _updateStatusText.Text = userInitiated ? "Checking GitHub Releases…" : "Checking for a Windows update…";

        try
        {
            using var response = await UpdateHttpClient.GetAsync(ReleasesApiUrl);
            response.EnsureSuccessStatusCode();
            await using var stream = await response.Content.ReadAsStreamAsync();
            using var document = await JsonDocument.ParseAsync(stream);

            JsonElement? windowsRelease = null;
            string? installableUrl = null;
            string? windowsAssetName = null;

            foreach (var release in document.RootElement.EnumerateArray())
            {
                if (release.TryGetProperty("draft", out var draft) && draft.GetBoolean())
                    continue;
                if (release.TryGetProperty("prerelease", out var prerelease) && prerelease.GetBoolean())
                    continue;
                if (!release.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
                    continue;

                string? fallbackWindowsAsset = null;
                foreach (var asset in assets.EnumerateArray())
                {
                    var name = asset.TryGetProperty("name", out var nameNode) ? nameNode.GetString() ?? string.Empty : string.Empty;
                    var url = asset.TryGetProperty("browser_download_url", out var urlNode) ? urlNode.GetString() : null;
                    if (string.IsNullOrWhiteSpace(url) || !IsWindowsReleaseAsset(name))
                        continue;

                    fallbackWindowsAsset ??= name;
                    var extension = Path.GetExtension(name);
                    if (extension.Equals(".msix", StringComparison.OrdinalIgnoreCase)
                        || extension.Equals(".msixbundle", StringComparison.OrdinalIgnoreCase)
                        || extension.Equals(".appinstaller", StringComparison.OrdinalIgnoreCase))
                    {
                        installableUrl = url;
                        windowsAssetName = name;
                        break;
                    }
                }

                if (installableUrl is not null || fallbackWindowsAsset is not null)
                {
                    windowsRelease = release.Clone();
                    windowsAssetName ??= fallbackWindowsAsset;
                    break;
                }
            }

            if (windowsRelease is null)
            {
                _latestVersionText.Text = "Latest Windows release — not published yet";
                _updateStatusText.Text = "No stable Windows package is currently published on GitHub Releases. This build will detect it automatically when one is released.";
                return;
            }

            var releaseElement = windowsRelease.Value;
            var tag = releaseElement.TryGetProperty("tag_name", out var tagNode) ? tagNode.GetString() ?? "unknown" : "unknown";
            var releaseUrl = releaseElement.TryGetProperty("html_url", out var htmlNode) ? htmlNode.GetString() : null;
            if (Uri.TryCreate(releaseUrl, UriKind.Absolute, out var releaseUri))
                _latestReleaseUri = releaseUri;
            if (Uri.TryCreate(installableUrl, UriKind.Absolute, out var packageUri))
                _latestInstallablePackageUri = packageUri;

            var currentVersion = NormalizeVersion(GetCurrentAppVersion());
            var releaseVersion = TryParseVersion(tag);
            _latestVersionText.Text = windowsAssetName is null
                ? $"Latest Windows release · {tag}"
                : $"Latest Windows release · {tag} · {windowsAssetName}";

            if (releaseVersion is not null && NormalizeVersion(releaseVersion) <= currentVersion)
            {
                _updateStatusText.Text = $"You're up to date. Current version: {FormatVersion(currentVersion)}.";
                return;
            }

            _updateStatusText.Text = releaseVersion is null
                ? $"A Windows release is available on GitHub Releases ({tag})."
                : $"Update available: {tag}. Current version: {FormatVersion(currentVersion)}.";

            if (_latestInstallablePackageUri is not null)
                _installUpdateButton.Visibility = Visibility.Visible;
        }
        catch (Exception ex)
        {
            App.WriteLog("GitHub Releases update check failed", ex);
            _updateStatusText.Text = $"Could not check GitHub Releases: {ex.Message}";
        }
        finally
        {
            _checkUpdatesButton.IsEnabled = true;
        }
    }

    private async void InstallUpdate_Click(object sender, RoutedEventArgs e)
    {
        if (_latestInstallablePackageUri is null)
        {
            await OpenLatestReleaseAsync();
            return;
        }

        try
        {
            var appInstallerUri = new Uri($"ms-appinstaller:?source={Uri.EscapeDataString(_latestInstallablePackageUri.AbsoluteUri)}");
            var launched = await Launcher.LaunchUriAsync(appInstallerUri);
            if (!launched)
                await OpenLatestReleaseAsync();
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not launch Windows update installer", ex);
            if (_updateStatusText is not null)
                _updateStatusText.Text = "Windows App Installer could not be opened. Opening the release page instead.";
            await OpenLatestReleaseAsync();
        }
    }

    private async void OpenRelease_Click(object sender, RoutedEventArgs e) => await OpenLatestReleaseAsync();

    private async Task OpenLatestReleaseAsync()
    {
        var uri = _latestReleaseUri ?? new Uri(ReleasesPageUrl);
        await Launcher.LaunchUriAsync(uri);
    }

    private static bool IsWindowsReleaseAsset(string name)
    {
        if (string.IsNullOrWhiteSpace(name))
            return false;

        var extension = Path.GetExtension(name);
        if (extension.Equals(".msix", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".msixbundle", StringComparison.OrdinalIgnoreCase)
            || extension.Equals(".appinstaller", StringComparison.OrdinalIgnoreCase))
            return true;

        return extension.Equals(".zip", StringComparison.OrdinalIgnoreCase)
            && name.Contains("CubicalCompare", StringComparison.OrdinalIgnoreCase)
            && (name.Contains("win", StringComparison.OrdinalIgnoreCase) || name.Contains("windows", StringComparison.OrdinalIgnoreCase));
    }

    private static HttpClient CreateUpdateHttpClient()
    {
        var client = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(15),
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("CubicalCompare/4.0");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        client.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28");
        return client;
    }

    private static Version GetCurrentAppVersion()
    {
        try
        {
            var version = Package.Current.Id.Version;
            return new Version(version.Major, version.Minor, version.Build, version.Revision);
        }
        catch
        {
            return Assembly.GetExecutingAssembly().GetName().Version ?? new Version(4, 0, 0, 0);
        }
    }

    private static Version NormalizeVersion(Version version) => new(
        Math.Max(0, version.Major),
        Math.Max(0, version.Minor),
        Math.Max(0, version.Build),
        Math.Max(0, version.Revision));

    private static Version? TryParseVersion(string tag)
    {
        if (string.IsNullOrWhiteSpace(tag))
            return null;

        var value = tag.Trim().TrimStart('v', 'V');
        var separator = value.IndexOfAny(['-', '+']);
        if (separator >= 0)
            value = value[..separator];
        return Version.TryParse(value, out var version) ? version : null;
    }

    private static string FormatVersion(Version version)
    {
        var normalized = NormalizeVersion(version);
        return normalized.Revision == 0
            ? $"{normalized.Major}.{normalized.Minor}.{normalized.Build}"
            : normalized.ToString(4);
    }
}
