using System.Windows;
using System.Windows.Controls;
using Button = System.Windows.Controls.Button;
using System.Windows.Ink;
using System.Windows.Media;

namespace CubicalCompareV5;

public partial class MainWindow
{
    private Button? _selectedIsland;

    private void HighlightIsland(Button button)
    {
        foreach (var item in new[] { NavProjectButton, NavCardsButton, NavThumbnailsButton, NavAudioButton, NavExportButton, NavDrawButton, NavSettingsButton })
            item.Background = ReferenceEquals(item, button) ? BrushOf("#34507899") : Brushes.Transparent;
        _selectedIsland = button;
    }

    private void ShowCardInspector()
    {
        AudioInspectorPanel.Visibility = Visibility.Collapsed;
        CardInspectorPanel.Visibility = Visibility.Visible;
    }

    private void NavProject_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = !ProjectPopup.IsOpen;
        HighlightIsland(NavProjectButton);
    }

    private void NavCards_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        ShowCardInspector();
        SetMode(false);
        HighlightIsland(NavCardsButton);
        CardList.Focus();
    }

    private void NavThumbnails_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        ShowCardInspector();
        SetMode(true);
        HighlightIsland(NavThumbnailsButton);
    }

    private void NavAudio_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        CardInspectorPanel.Visibility = Visibility.Collapsed;
        AudioInspectorPanel.Visibility = Visibility.Visible;
        HighlightIsland(NavAudioButton);
        RefreshAudioSummary();
    }

    private void NavExport_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        HighlightIsland(NavExportButton);
        Export_Click(sender, e);
    }

    private void NavDraw_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        ShowCardInspector();
        SetInkMode(InkCanvasEditingMode.Ink);
        HighlightIsland(NavDrawButton);
    }

    private void NavSettings_Click(object sender, RoutedEventArgs e)
    {
        ProjectPopup.IsOpen = false;
        ShowCardInspector();
        HighlightIsland(NavSettingsButton);
        EditorScrollViewer.ScrollToEnd();
        Dispatcher.BeginInvoke(new Action(() => EditorScrollViewer.ScrollToEnd()),
            System.Windows.Threading.DispatcherPriority.Loaded);
    }
    internal void RunFloatingIslandRegressionTest()
    {
        NavAudio_Click(this, new RoutedEventArgs());
        if (AudioInspectorPanel.Visibility != Visibility.Visible ||
            CardInspectorPanel.Visibility != Visibility.Collapsed)
            throw new InvalidOperationException("Audio island does not open its panel.");

        NavThumbnails_Click(this, new RoutedEventArgs());
        if (ThumbnailStage.Visibility != Visibility.Visible ||
            CardInspectorPanel.Visibility != Visibility.Visible ||
            AudioInspectorPanel.Visibility != Visibility.Collapsed)
            throw new InvalidOperationException("Thumbnail navigation does not activate the editor.");

        NavCards_Click(this, new RoutedEventArgs());
        if (Stage.Visibility != Visibility.Visible || ThumbnailStage.Visibility != Visibility.Collapsed)
            throw new InvalidOperationException("Cards navigation does not restore the main preview.");

        NavSettings_Click(this, new RoutedEventArgs());
        if (CardInspectorPanel.Visibility != Visibility.Visible)
            throw new InvalidOperationException("Settings navigation hid the settings panel.");

        M3uPlaylist.RunRegressionChecks();
    }

}
