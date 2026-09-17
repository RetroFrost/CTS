using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private bool _interfaceReviewInitialized;
    private TextBlock? _brandSubtitle;
    private ComboBox? _titleResolutionCombo;
    private ComboBox? _titleFpsCombo;
    private Grid? _titleBarGrid;

    internal void InitializeInterfaceReview()
    {
        if (_interfaceReviewInitialized)
            return;
        _interfaceReviewInitialized = true;

        // The compact layout previously closed the navigation pane but also disabled the
        // pane toggle, leaving icon-only navigation with no way to inspect labels.
        RootNavigation.IsPaneToggleButtonVisible = true;
        RootNavigation.IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed;
        RootNavigation.AlwaysShowHeader = false;
        RootNavigation.CompactPaneLength = 52;
        RootNavigation.OpenPaneLength = 226;

        _titleBarGrid = AppTitleBar.Child as Grid;
        _brandSubtitle = EnumerateVisualDescendants(AppTitleBar)
            .OfType<TextBlock>()
            .FirstOrDefault(x => string.Equals(x.Text, "Create stunning comparison videos", StringComparison.Ordinal));

        var titleCombos = EnumerateVisualDescendants(AppTitleBar).OfType<ComboBox>().ToArray();
        if (titleCombos.Length > 0) _titleResolutionCombo = titleCombos[0];
        if (titleCombos.Length > 1) _titleFpsCombo = titleCombos[1];

        foreach (var button in EnumerateVisualDescendants(AppTitleBar).OfType<Button>())
        {
            if (button.Content is not string label)
                continue;
            ToolTipService.SetToolTip(button, label switch
            {
                "New" => "Create a new comparison project",
                "Open" => "Open a .ccproject file",
                "Save" => "Save the current project",
                "Export Video" => "Render and export the current comparison video",
                _ => label,
            });
        }

        if (_titleResolutionCombo is not null)
            ToolTipService.SetToolTip(_titleResolutionCombo, "Export resolution");
        if (_titleFpsCombo is not null)
            ToolTipService.SetToolTip(_titleFpsCombo, "Export frame rate");

        ToolTipService.SetToolTip(PreviewPlayButton, "Play or pause renderer preview");
        ToolTipService.SetToolTip(ProjectFrameSlider, "Scrub renderer frames");

        AppTitleBar.SizeChanged += InterfaceReview_TitleBarSizeChanged;
        Closed += (_, _) => AppTitleBar.SizeChanged -= InterfaceReview_TitleBarSizeChanged;
        ApplyReviewedTitleBarLayout(AppTitleBar.ActualWidth);
    }

    private void InterfaceReview_TitleBarSizeChanged(object sender, SizeChangedEventArgs e)
        => ApplyReviewedTitleBarLayout(e.NewSize.Width);

    private void ApplyReviewedTitleBarLayout(double width)
    {
        if (width <= 0)
            return;

        var constrained = width < 1180;
        var narrow = width < 980;
        var veryNarrow = width < 840;

        if (_brandSubtitle is not null)
            _brandSubtitle.Visibility = constrained ? Visibility.Collapsed : Visibility.Visible;

        ProjectNameText.Visibility = narrow ? Visibility.Collapsed : Visibility.Visible;

        if (_titleResolutionCombo is not null)
        {
            _titleResolutionCombo.Width = constrained ? 142 : 174;
            _titleResolutionCombo.Visibility = veryNarrow ? Visibility.Collapsed : Visibility.Visible;
        }

        if (_titleFpsCombo is not null)
        {
            _titleFpsCombo.Width = constrained ? 82 : 92;
            _titleFpsCombo.Visibility = width < 760 ? Visibility.Collapsed : Visibility.Visible;
        }

        ExportVideoButton.Content = narrow ? "Export" : "Export Video";
        ExportVideoButton.Padding = narrow ? new Thickness(13, 7, 13, 7) : new Thickness(18, 7, 18, 7);

        if (_titleBarGrid is not null)
            _titleBarGrid.Padding = constrained
                ? new Thickness(12, 0, 146, 0)
                : new Thickness(18, 0, 152, 0);
    }
}
