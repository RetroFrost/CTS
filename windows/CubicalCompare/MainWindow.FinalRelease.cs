using System.Reflection;
using CubicalCompare.Updates;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;
using Windows.System;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private readonly CubicalUpdateService _updateService = new();
    private Grid? _settingsPage;
    private Grid? _finalAudioPage;
    private TextBlock? _currentVersionText;
    private TextBlock? _latestVersionText;
    private TextBlock? _updateStatusText;
    private Button? _checkUpdatesButton;
    private Button? _installUpdateButton;
    private Button? _updateFromZipButton;
    private Button? _openReleaseButton;
    private CubicalUpdateCandidate? _availableUpdate;
    private bool _settingsAutoChecked;

    internal void InitializeFinalReleaseUi()
    {
        if (_settingsPage is not null)
            return;

        BuildFinalNavigation();
        RemovePrototypeInspectorTabs();
        BuildSettingsPage();
        BuildFinalAudioPage();
        Initialize42SettingsEnhancements();
        RootNavigation.SelectionChanged += FinalNavigation_SelectionChanged;

        if (RootNavigation.MenuItems.Count > 0)
            RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
    }

    private void BuildFinalNavigation()
    {
        RootNavigation.MenuItems.Clear();
        RootNavigation.MenuItems.Add(CreateNavigationItem("Workspace", "project", Symbol.Home));
        RootNavigation.MenuItems.Add(CreateNavigationItem("MegaPacks", "assets", Symbol.Library));
        RootNavigation.MenuItems.Add(CreateNavigationItem("Thumbnail", "thumbnail", Symbol.Pictures));
        RootNavigation.MenuItems.Add(CreateNavigationItem("Audio", "audio", Symbol.Audio));
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
        var inspector = ProjectPage.Children
            .OfType<Border>()
            .FirstOrDefault(border => Grid.GetColumn(border) == 2);

        if (inspector?.Child is not Grid inspectorRoot || inspectorRoot.RowDefinitions.Count < 2)
            return;

        inspectorRoot.RowDefinitions[0].Height = new GridLength(0);
        foreach (var child in inspectorRoot.Children.OfType<FrameworkElement>().Where(child => Grid.GetRow(child) == 0))
            child.Visibility = Visibility.Collapsed;
    }

    internal static IEnumerable<DependencyObject> EnumerateVisualDescendants(DependencyObject root)
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
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        };

        _latestVersionText = new TextBlock
        {
            Text = "Latest Windows release —",
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        _updateStatusText = new TextBlock
        {
            Text = "Updates automatically choose the safest available path: verified Velopack package, visible Setup.exe, or portable ZIP fallback.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        _checkUpdatesButton = new Button
        {
            Content = "Check for updates",
            Padding = new Thickness(16, 7, 16, 7),
            Background = (Brush)Application.Current.Resources["EditorAccentBrush"],
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
        };
        _checkUpdatesButton.Click += async (_, _) => await CheckForUpdatesAsync(userInitiated: true);

        _installUpdateButton = new Button
        {
            Content = "Install update",
            Padding = new Thickness(16, 7, 16, 7),
            Visibility = Visibility.Collapsed,
        };
        _installUpdateButton.Click += InstallUpdate_Click;

        _updateFromZipButton = new Button
        {
            Content = "Update from ZIP",
            Padding = new Thickness(16, 7, 16, 7),
        };
        _updateFromZipButton.Click += UpdateFromZip_Click;

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
        updateButtons.Children.Add(_updateFromZipButton);
        updateButtons.Children.Add(_openReleaseButton);

        var updatesPanel = new StackPanel { Spacing = 10 };
        updatesPanel.Children.Add(new TextBlock
        {
            Text = "Updates",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        updatesPanel.Children.Add(new TextBlock
        {
            Text = "GitHub Releases · Velopack install/update · portable ZIP fallback · no certificate dependency",
            FontSize = 12,
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        updatesPanel.Children.Add(_currentVersionText);
        updatesPanel.Children.Add(_latestVersionText);
        updatesPanel.Children.Add(_updateStatusText);
        updatesPanel.Children.Add(updateButtons);

        var buildPanel = new StackPanel { Spacing = 8 };
        buildPanel.Children.Add(new TextBlock
        {
            Text = "Windows build",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        buildPanel.Children.Add(new TextBlock
        {
            Text = ".NET 10 · WinUI 3 · Windows App SDK 2.4 · self-contained unpackaged deployment",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        buildPanel.Children.Add(new TextBlock
        {
            Text = "Subsystems are split into Core, Renderer, MegaPack, Thumbnail and Updates assemblies. Settings and logs live outside the application folder so updates can replace app files safely.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
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
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        pageStack.Children.Add(new TextBlock
        {
            Text = "Application, update and release settings.",
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        pageStack.Children.Add(CreateSettingsCard(updatesPanel));
        pageStack.Children.Add(CreateSettingsCard(buildPanel));

        _settingsPage = new Grid { Visibility = Visibility.Collapsed };
        _settingsPage.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = pageStack,
        });
        Canvas.SetZIndex(_settingsPage, 100);
        contentHost.Children.Add(_settingsPage);
    }

    private void BuildFinalAudioPage()
    {
        if (_finalAudioPage is not null)
            return;
        if (RootNavigation.Content is not Grid contentHost)
            throw new InvalidOperationException("The NavigationView content host is unavailable.");

        _finalAudioPage = BuildAudioPage();
        Canvas.SetZIndex(_finalAudioPage, 100);
        contentHost.Children.Add(_finalAudioPage);
    }

    private static Border CreateSettingsCard(UIElement content) => new()
    {
        Background = (Brush)Application.Current.Resources["EditorSurfaceRaisedBrush"],
        BorderBrush = (Brush)Application.Current.Resources["EditorBorderBrush"],
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(14),
        Padding = new Thickness(18),
        Child = content,
    };

    private async void FinalNavigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_settingsPage is null)
            return;

        var tag = args.SelectedItemContainer?.Tag as string
            ?? (args.SelectedItem as NavigationViewItem)?.Tag as string;
        var isSettings = string.Equals(tag, "settings", StringComparison.Ordinal);
        var isAudio = string.Equals(tag, "audio", StringComparison.Ordinal);
        _settingsPage.Visibility = isSettings ? Visibility.Visible : Visibility.Collapsed;
        if (_finalAudioPage is not null)
            _finalAudioPage.Visibility = isAudio ? Visibility.Visible : Visibility.Collapsed;
        if (isAudio)
            RefreshSoundtrackUi();

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
        _availableUpdate = null;
        _updateStatusText.Text = userInitiated ? "Checking GitHub Releases…" : "Checking for a Windows update…";

        try
        {
            var current = NormalizeVersion(GetCurrentAppVersion());
            var candidate = await _updateService.CheckForUpdatesAsync(current);
            _availableUpdate = candidate;

            if (candidate is null)
            {
                _latestVersionText.Text = "Latest Windows release · up to date";
                _updateStatusText.Text = $"You're up to date. Current version: {FormatVersion(current)}.";
                return;
            }

            _latestVersionText.Text = $"Latest Windows release · {candidate.Tag} · {candidate.AssetName}";
            var method = candidate.Delivery switch
            {
                CubicalUpdateDelivery.Velopack => "verified package update",
                CubicalUpdateDelivery.SetupExe => "visible installer",
                CubicalUpdateDelivery.PortableZip => "portable ZIP with rollback",
                _ => "automatic updater",
            };
            var fallback = candidate.HasFallback && !string.IsNullOrWhiteSpace(candidate.FallbackAssetName)
                ? $" If that path fails before applying, Cubical Compare will automatically fall back to {candidate.FallbackAssetName}."
                : string.Empty;
            _updateStatusText.Text = $"Update available: {FormatVersion(candidate.Version)}. Selected automatically: {method}. {candidate.SelectionReason}{fallback}";
            _installUpdateButton.Content = "Install update";
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
        if (_availableUpdate is null)
        {
            await OpenLatestReleaseAsync();
            return;
        }

        if (_installUpdateButton is not null)
            _installUpdateButton.IsEnabled = false;
        if (_updateStatusText is not null)
            _updateStatusText.Text = _availableUpdate.Delivery switch
            {
                CubicalUpdateDelivery.Velopack => "Starting verified package update…",
                CubicalUpdateDelivery.SetupExe => "Downloading the visible update installer…",
                CubicalUpdateDelivery.PortableZip => "Preparing portable update with rollback protection…",
                _ => "Starting automatic update…",
            };

        try
        {
            ShowActivityWatcher("Windows update", "Starting download…", 0);
            var progress = new Progress<CubicalUpdateProgress>(state =>
            {
                var detail = state.TotalBytes is > 0
                    ? $"{state.Phase} · {FormatByteCount(state.BytesReceived)} / {FormatByteCount(state.TotalBytes.Value)} · {state.Percent}%"
                    : $"{state.Phase} · {state.Percent}%";
                if (_updateStatusText is not null) _updateStatusText.Text = detail;
                UpdateActivityWatcher("Windows update", detail, state.Percent);
            });

            var exitRequired = await _updateService.ApplyUpdateAsync(
                _availableUpdate,
                AppContext.BaseDirectory,
                "CubicalCompare.exe",
                progress);

            if (exitRequired)
            {
                if (_updateStatusText is not null)
                    _updateStatusText.Text = "Update ready. Restarting Cubical Compare automatically…";
                CompleteActivityWatcher("Windows update", "Update ready. Restarting Cubical Compare automatically…");
                Application.Current.Exit();
                return;
            }

            // The release can disappear between the check and the click (for example,
            // when a release is replaced). Do not leave the Install button permanently
            // disabled if the updater no longer has anything to apply.
            _availableUpdate = null;
            if (_updateStatusText is not null)
                _updateStatusText.Text = "That update is no longer available. Check again for the latest Windows release.";
            if (_installUpdateButton is not null)
            {
                _installUpdateButton.Visibility = Visibility.Collapsed;
                _installUpdateButton.IsEnabled = true;
            }
            FailActivityWatcher("Windows update changed", "The selected release is no longer available.");
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not apply Windows update", ex);
            if (_updateStatusText is not null)
                _updateStatusText.Text = $"Update failed: {ex.Message}";
            if (_installUpdateButton is not null)
                _installUpdateButton.IsEnabled = true;
            FailActivityWatcher("Windows update failed", ex.Message);
        }
    }

    private async void UpdateFromZip_Click(object sender, RoutedEventArgs e)
    {
        var sourceDialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "Update from ZIP",
            Content = "Get the newest Windows portable ZIP directly from GitHub Releases, or choose a ZIP already on this PC.",
            PrimaryButtonText = "Get latest from GitHub",
            SecondaryButtonText = "Choose local ZIP",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        var sourceChoice = await sourceDialog.ShowAsync();
        if (sourceChoice == ContentDialogResult.None)
            return;

        CubicalUpdateCandidate? githubZip = null;
        Windows.Storage.StorageFile? localZip = null;

        if (sourceChoice == ContentDialogResult.Primary)
        {
            try
            {
                if (_updateStatusText is not null)
                    _updateStatusText.Text = "Checking GitHub Releases for the newest Windows ZIP…";

                githubZip = await _updateService.CheckForPortableZipUpdateAsync(GetCurrentAppVersion());
                if (githubZip is null)
                {
                    if (_updateStatusText is not null)
                        _updateStatusText.Text = "No newer GitHub Windows ZIP is available.";
                    await ShowErrorAsync(
                        "No newer ZIP update",
                        "GitHub Releases does not currently contain a newer Cubical Compare Windows portable ZIP.");
                    return;
                }
            }
            catch (Exception ex)
            {
                App.WriteLog("Could not resolve GitHub ZIP update", ex);
                if (_updateStatusText is not null)
                    _updateStatusText.Text = $"Could not fetch GitHub ZIP update: {ex.Message}";
                await ShowErrorAsync("Could not fetch GitHub ZIP update", ex.Message);
                return;
            }
        }
        else
        {
            var picker = new FileOpenPicker
            {
                SuggestedStartLocation = PickerLocationId.Downloads,
                ViewMode = PickerViewMode.List,
            };
            picker.FileTypeFilter.Add(".zip");

            var hwnd = WindowNative.GetWindowHandle(this);
            InitializeWithWindow.Initialize(picker, hwnd);
            localZip = await picker.PickSingleFileAsync();
            if (localZip is null)
                return;
        }

        if (_updateFromZipButton is not null)
            _updateFromZipButton.IsEnabled = false;
        if (_installUpdateButton is not null)
            _installUpdateButton.IsEnabled = false;
        if (_checkUpdatesButton is not null)
            _checkUpdatesButton.IsEnabled = false;

        var sourceLabel = githubZip is not null
            ? $"GitHub · {githubZip.AssetName}"
            : $"Local · {localZip!.Name}";

        try
        {
            _updateStatusText!.Text = githubZip is not null
                ? $"Downloading ZIP from GitHub · {githubZip.AssetName}"
                : $"Preparing local ZIP · {localZip!.Name}";
            ShowActivityWatcher("Windows ZIP update", $"Preparing {sourceLabel}…", 0);

            var progress = new Progress<CubicalUpdateProgress>(state =>
            {
                var detail = state.TotalBytes is > 0
                    ? $"{state.Phase} · {FormatByteCount(state.BytesReceived)} / {FormatByteCount(state.TotalBytes.Value)} · {state.Percent}%"
                    : $"{state.Phase} · {state.Percent}%";
                if (_updateStatusText is not null) _updateStatusText.Text = detail;
                UpdateActivityWatcher("Windows ZIP update", detail, state.Percent);
            });

            var exitRequired = githubZip is not null
                ? await _updateService.ApplyUpdateAsync(
                    githubZip,
                    AppContext.BaseDirectory,
                    "CubicalCompare.exe",
                    progress)
                : await _updateService.ApplyPortableZipFileAsync(
                    localZip!.Path,
                    AppContext.BaseDirectory,
                    "CubicalCompare.exe",
                    progress);

            if (exitRequired)
            {
                if (_updateStatusText is not null)
                    _updateStatusText.Text = "ZIP update staged. Restarting Cubical Compare automatically…";
                CompleteActivityWatcher("Windows ZIP update", "Update staged. Restarting Cubical Compare automatically…");
                Application.Current.Exit();
                return;
            }
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not apply ZIP update", ex);
            if (_updateStatusText is not null)
                _updateStatusText.Text = $"ZIP update failed: {ex.Message}";
            FailActivityWatcher("Windows ZIP update failed", ex.Message);
            await ShowErrorAsync("Could not update from ZIP", ex.Message);
        }
        finally
        {
            if (_updateFromZipButton is not null)
                _updateFromZipButton.IsEnabled = true;
            if (_installUpdateButton is not null)
                _installUpdateButton.IsEnabled = true;
            if (_checkUpdatesButton is not null)
                _checkUpdatesButton.IsEnabled = true;
        }
    }

    private async void OpenRelease_Click(object sender, RoutedEventArgs e) => await OpenLatestReleaseAsync();

    private async Task OpenLatestReleaseAsync()
    {
        var uri = _availableUpdate?.ReleaseUri ?? new Uri(CubicalUpdateService.ReleasesPageUrl);
        await Launcher.LaunchUriAsync(uri);
    }

    private static Version GetCurrentAppVersion()
        => Assembly.GetExecutingAssembly().GetName().Version ?? new Version(4, 2, 1, 19);

    private static Version NormalizeVersion(Version version) => new(
        Math.Max(0, version.Major),
        Math.Max(0, version.Minor),
        Math.Max(0, version.Build),
        Math.Max(0, version.Revision));

    private static string FormatVersion(Version version)
    {
        var normalized = NormalizeVersion(version);
        return normalized.Revision == 0
            ? $"{normalized.Major}.{normalized.Minor}.{normalized.Build}"
            : normalized.ToString(4);
    }
}
