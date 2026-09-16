using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private sealed record ExportResolutionOption(string Label, int Width, int Height);

    private static readonly ExportResolutionOption[] ExportResolutionOptions =
    [
        new("640 × 360 (360p)", 640, 360),
        new("854 × 480 (480p)", 854, 480),
        new("1024 × 576 (576p)", 1024, 576),
        new("1280 × 720 (HD)", 1280, 720),
        new("1600 × 900 (900p)", 1600, 900),
        new("1920 × 1080 (Full HD)", 1920, 1080),
        new("2560 × 1440 (QHD)", 2560, 1440),
        new("3200 × 1800 (QHD+)", 3200, 1800),
        new("3840 × 2160 (4K UHD)", 3840, 2160),
    ];

    private static readonly int[] ExportFpsOptions = [24, 25, 30, 48, 50, 60, 90, 120];

    private ComboBox? _exportResolutionCombo;
    private ComboBox? _exportFpsCombo;
    private TextBlock? _previewResolutionText;
    private TextBlock? _previewFpsText;
    private Grid? _workspaceEditorGrid;
    private Border? _workspacePreviewBorder;
    private UIElement? _workspaceSoundtrackLane;
    private bool _adaptiveExportInitialized;
    private bool _exportUiUpdating;
    private int _exportWidth = 1920;
    private int _exportHeight = 1080;
    private int _exportFps = 60;

    internal int SelectedExportWidth => _exportWidth;
    internal int SelectedExportHeight => _exportHeight;
    internal int SelectedExportFps => _exportFps;

    internal void InitializeAdaptiveExportUi()
    {
        if (_adaptiveExportInitialized)
            return;
        _adaptiveExportInitialized = true;

        LoadExportPreferences();
        ConfigureExportProfileSelectors();
        CaptureAdaptiveWorkspaceElements();
        RemoveNonFunctionalPrototypeControls();

        RootNavigation.SizeChanged += AdaptiveWorkspace_SizeChanged;
        Closed += (_, _) => RootNavigation.SizeChanged -= AdaptiveWorkspace_SizeChanged;
        ApplyAdaptiveWorkspaceLayout();
        RefreshExportProfileUi();
    }

    private void ConfigureExportProfileSelectors()
    {
        var comboBoxes = EnumerateVisualDescendants(AppTitleBar).OfType<ComboBox>().ToArray();
        if (comboBoxes.Length < 2)
            return;

        _exportResolutionCombo = comboBoxes[0];
        _exportFpsCombo = comboBoxes[1];

        _exportUiUpdating = true;
        try
        {
            _exportResolutionCombo.Items.Clear();
            foreach (var option in ExportResolutionOptions)
            {
                _exportResolutionCombo.Items.Add(new ComboBoxItem
                {
                    Content = option.Label,
                    Tag = option,
                });
            }

            _exportFpsCombo.Items.Clear();
            foreach (var fps in ExportFpsOptions)
            {
                _exportFpsCombo.Items.Add(new ComboBoxItem
                {
                    Content = $"{fps} FPS",
                    Tag = fps,
                });
            }

            SelectMatchingExportItems();
        }
        finally
        {
            _exportUiUpdating = false;
        }

        _exportResolutionCombo.SelectionChanged += ExportResolutionCombo_SelectionChanged;
        _exportFpsCombo.SelectionChanged += ExportFpsCombo_SelectionChanged;
        ToolTipService.SetToolTip(_exportResolutionCombo, "Video export resolution");
        ToolTipService.SetToolTip(_exportFpsCombo, "Video export frame rate");
    }

    private void CaptureAdaptiveWorkspaceElements()
    {
        _workspaceEditorGrid = ProjectPage.Children
            .OfType<Grid>()
            .FirstOrDefault(grid => Grid.GetColumn(grid) == 0 && grid.RowDefinitions.Count >= 5);

        if (_workspaceEditorGrid is null)
            return;

        _workspacePreviewBorder = _workspaceEditorGrid.Children
            .OfType<Border>()
            .FirstOrDefault(border => Grid.GetRow(border) == 0);
        if (_workspacePreviewBorder is not null)
        {
            // The prototype forced a 360 px minimum preview height. On 1366×768 and
            // smaller displays that minimum made the complete workspace taller than the
            // available window and Windows clipped the preview. Let the star row scale it.
            _workspacePreviewBorder.MinHeight = 0;

            var previewText = EnumerateVisualDescendants(_workspacePreviewBorder)
                .OfType<TextBlock>()
                .ToArray();
            _previewResolutionText = previewText.FirstOrDefault(text => text.Text.Contains("1920 × 1080", StringComparison.Ordinal));
            _previewFpsText = previewText.FirstOrDefault(text => string.Equals(text.Text, "60 FPS", StringComparison.Ordinal));
        }

        _workspaceSoundtrackLane = _workspaceEditorGrid.Children
            .FirstOrDefault(element => Grid.GetRow(element) == 3);
    }

    private void RemoveNonFunctionalPrototypeControls()
    {
        // The small Fit button in the mock-up transport never had an action. Keep the
        // working play/scrub controls and remove the dead prototype control instead of
        // shipping a button that does nothing.
        var deadFit = PreviewTimelineGrid.Children
            .OfType<Button>()
            .FirstOrDefault(button => string.Equals(button.Content as string, "Fit", StringComparison.Ordinal));
        if (deadFit is not null)
            deadFit.Visibility = Visibility.Collapsed;

        if (_workspacePreviewBorder is null)
            return;

        // The large centre play glyph and the fake 42% progress strip were visual-only
        // mock-up decorations. The real transport directly below the preview is wired to
        // renderer playback, so remove the misleading duplicates in the final build.
        foreach (var border in EnumerateVisualDescendants(_workspacePreviewBorder).OfType<Border>())
        {
            if (Math.Abs(border.Width - 76) < 0.5 && Math.Abs(border.Height - 76) < 0.5)
                border.Visibility = Visibility.Collapsed;
            else if (Math.Abs(border.Height - 34) < 0.5)
                border.Visibility = Visibility.Collapsed;
        }
    }

    private void ExportResolutionCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_exportUiUpdating || _exportResolutionCombo?.SelectedItem is not ComboBoxItem item || item.Tag is not ExportResolutionOption option)
            return;

        _exportWidth = option.Width;
        _exportHeight = option.Height;
        SaveExportPreferences();
        RefreshExportProfileUi();
        TimelineStatusText.Text = $"Export resolution · {option.Width}×{option.Height}";
    }

    private void ExportFpsCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_exportUiUpdating || _exportFpsCombo?.SelectedItem is not ComboBoxItem item || item.Tag is not int fps)
            return;

        _exportFps = Math.Clamp(fps, 1, 120);
        SaveExportPreferences();
        RefreshExportProfileUi();
        TimelineStatusText.Text = $"Export frame rate · {_exportFps} FPS";
    }

    private void SelectMatchingExportItems()
    {
        if (_exportResolutionCombo is not null)
        {
            var resolutionIndex = ExportResolutionOptions
                .Select((option, index) => (option, index))
                .FirstOrDefault(x => x.option.Width == _exportWidth && x.option.Height == _exportHeight)
                .index;
            if (!ExportResolutionOptions.Any(option => option.Width == _exportWidth && option.Height == _exportHeight))
                resolutionIndex = Array.FindIndex(ExportResolutionOptions, option => option.Width == 1920 && option.Height == 1080);
            _exportResolutionCombo.SelectedIndex = Math.Max(0, resolutionIndex);
        }

        if (_exportFpsCombo is not null)
        {
            var fpsIndex = Array.IndexOf(ExportFpsOptions, _exportFps);
            if (fpsIndex < 0)
                fpsIndex = Array.IndexOf(ExportFpsOptions, 60);
            _exportFpsCombo.SelectedIndex = Math.Max(0, fpsIndex);
        }
    }

    private void RefreshExportProfileUi()
    {
        if (_previewResolutionText is not null)
            _previewResolutionText.Text = $"{_exportWidth} × {_exportHeight}";
        if (_previewFpsText is not null)
            _previewFpsText.Text = $"{_exportFps} FPS";
    }

    private void LoadExportPreferences()
    {
        try
        {
            var values = ApplicationData.Current.LocalSettings.Values;
            if (values.TryGetValue("ExportWidth", out var widthValue) && widthValue is int width)
                _exportWidth = width;
            if (values.TryGetValue("ExportHeight", out var heightValue) && heightValue is int height)
                _exportHeight = height;
            if (values.TryGetValue("ExportFps", out var fpsValue) && fpsValue is int fps)
                _exportFps = fps;

            if (!ExportResolutionOptions.Any(option => option.Width == _exportWidth && option.Height == _exportHeight))
            {
                _exportWidth = 1920;
                _exportHeight = 1080;
            }
            if (!ExportFpsOptions.Contains(_exportFps))
                _exportFps = 60;
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not load export preferences", ex);
            _exportWidth = 1920;
            _exportHeight = 1080;
            _exportFps = 60;
        }
    }

    private void SaveExportPreferences()
    {
        try
        {
            var values = ApplicationData.Current.LocalSettings.Values;
            values["ExportWidth"] = _exportWidth;
            values["ExportHeight"] = _exportHeight;
            values["ExportFps"] = _exportFps;
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not save export preferences", ex);
        }
    }

    private void AdaptiveWorkspace_SizeChanged(object sender, SizeChangedEventArgs e) => ApplyAdaptiveWorkspaceLayout();

    private void ApplyAdaptiveWorkspaceLayout()
    {
        var width = RootNavigation.ActualWidth;
        var height = RootNavigation.ActualHeight;
        if (width <= 0 || height <= 0)
            return;

        var compactNavigation = width < 1220;
        RootNavigation.PaneDisplayMode = compactNavigation
            ? NavigationViewPaneDisplayMode.LeftCompact
            : NavigationViewPaneDisplayMode.Left;
        RootNavigation.IsPaneOpen = !compactNavigation;
        RootNavigation.CompactPaneLength = 52;
        RootNavigation.OpenPaneLength = 220;

        if (ProjectPage.ColumnDefinitions.Count >= 3)
        {
            var inspectorWidth = width switch
            {
                < 850 => 240,
                < 1050 => 270,
                < 1300 => 310,
                _ => 350,
            };
            ProjectPage.ColumnDefinitions[2].Width = new GridLength(inspectorWidth);
        }

        if (_workspaceEditorGrid is null || _workspaceEditorGrid.RowDefinitions.Count < 5)
            return;

        var shortScreen = height < 720;
        var veryShortScreen = height < 580;

        _workspaceEditorGrid.RowDefinitions[0].MinHeight = 0;
        _workspaceEditorGrid.RowDefinitions[2].Height = new GridLength(veryShortScreen ? 150 : 174);
        _workspaceEditorGrid.RowDefinitions[3].Height = new GridLength(shortScreen ? 0 : 54);

        if (_workspaceSoundtrackLane is not null)
            _workspaceSoundtrackLane.Visibility = shortScreen ? Visibility.Collapsed : Visibility.Visible;

        if (_workspacePreviewBorder is not null)
            _workspacePreviewBorder.MinHeight = 0;
    }
}
