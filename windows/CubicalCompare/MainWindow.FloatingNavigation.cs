using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;

namespace CubicalCompare;

/// <summary>
/// Real, movable floating dock over the whole WinUI editor. The existing NavigationView
/// remains the routing/state source but its permanent side panel is collapsed.
/// </summary>
public sealed partial class MainWindow
{
    private bool _floatingDockInitialized;
    private bool _floatingDockDragging;
    private global::Windows.Foundation.Point _floatingDockDragStart;
    private double _floatingDockSavedX;
    private double _floatingDockSavedY;

    internal void InitializeFloatingNavigation()
    {
        if (_floatingDockInitialized) return;
        _floatingDockInitialized = true;

        // The dock is the actual primary navigation; do not leave a fixed side menu.
        RootNavigation.PaneDisplayMode = NavigationViewPaneDisplayMode.LeftMinimal;
        RootNavigation.IsPaneOpen = false;
        RootNavigation.IsPaneToggleButtonVisible = false;
        // WinUI may reopen a NavigationView pane during its first responsive layout.
        // Hide it again after Loaded, during resizes, and before each dock navigation.
        RootNavigation.Loaded += (_, _) => RootNavigation.IsPaneOpen = false;
        RootNavigation.SizeChanged += (_, _) =>
        {
            if (RootNavigation.IsPaneOpen) RootNavigation.IsPaneOpen = false;
        };

        FloatingDockOffset.X = AppPreferences.GetInt("v5_floating_dock_x", 0);
        FloatingDockOffset.Y = AppPreferences.GetInt("v5_floating_dock_y", 0);
        EditorRootGrid.SizeChanged += (_, _) => ClampFloatingDock();
        RootNavigation.SelectionChanged += (_, e) =>
        {
            var tag = e.SelectedItemContainer?.Tag as string
                ?? (e.SelectedItem as NavigationViewItem)?.Tag as string;
            if (!string.IsNullOrWhiteSpace(tag))
                UpdateFloatingDockSelection(tag);
        };
        ClampFloatingDock();
        UpdateFloatingDockSelection((RootNavigation.SelectedItem as NavigationViewItem)?.Tag as string ?? "project");
    }

    private void FloatingDockNavigate_Click(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement element && element.Tag is string tag)
            NavigateFromFloatingDock(tag);
    }

    private void NavigateFromFloatingDock(string tag)
    {
        var destinations = RootNavigation.MenuItems.OfType<NavigationViewItem>()
            .Concat(RootNavigation.FooterMenuItems.OfType<NavigationViewItem>());
        var destination = destinations.FirstOrDefault(item => string.Equals(item.Tag as string, tag, StringComparison.Ordinal));
        if (destination is null)
            throw new InvalidOperationException($"Floating dock destination is missing: {tag}");

        RootNavigation.SelectedItem = destination;
        RootNavigation.IsPaneOpen = false;
        // Explicit page routing is necessary while the normal pane is not visually realized.
        ShowPage(tag);
        if (tag == "audio") RefreshSoundtrackUi();
    }

    private void UpdateFloatingDockSelection(string tag)
    {
        if (FloatingDockButtons is null) return;
        foreach (var button in FloatingDockButtons.Children.OfType<Button>())
        {
            var selected = string.Equals(button.Tag as string, tag, StringComparison.Ordinal)
                || ((tag == "settings" || tag == "developer" || tag == "updates")
                    && string.Equals(button.Tag as string, "more", StringComparison.Ordinal));
            button.Background = selected
                ? (Brush)Application.Current.Resources["EditorAccentBrush"]
                : new SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
    }

    private void FloatingDockDragHandle_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (!FloatingDockDragHandle.CapturePointer(e.Pointer)) return;
        _floatingDockDragging = true;
        _floatingDockDragStart = e.GetCurrentPoint(EditorRootGrid).Position;
        _floatingDockSavedX = FloatingDockOffset.X;
        _floatingDockSavedY = FloatingDockOffset.Y;
        e.Handled = true;
    }

    private void FloatingDockDragHandle_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!_floatingDockDragging) return;
        var at = e.GetCurrentPoint(EditorRootGrid).Position;
        FloatingDockOffset.X = _floatingDockSavedX + at.X - _floatingDockDragStart.X;
        FloatingDockOffset.Y = _floatingDockSavedY + at.Y - _floatingDockDragStart.Y;
        ClampFloatingDock();
        e.Handled = true;
    }

    private void FloatingDockDragHandle_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!_floatingDockDragging) return;
        _floatingDockDragging = false;
        FloatingDockDragHandle.ReleasePointerCapture(e.Pointer);
        SaveFloatingDockPosition();
        e.Handled = true;
    }

    private void FloatingDockDragHandle_PointerCaptureLost(object sender, PointerRoutedEventArgs e)
    {
        if (!_floatingDockDragging) return;
        _floatingDockDragging = false;
        SaveFloatingDockPosition();
    }

    private void ClampFloatingDock()
    {
        // Keep the dock on-screen even after the user resizes the window.
        var maxX = Math.Max(0, (EditorRootGrid.ActualWidth - FloatingNavigationDock.ActualWidth) / 2 - 12);
        var maxUp = Math.Max(0, EditorRootGrid.ActualHeight - FloatingNavigationDock.ActualHeight - 90);
        FloatingNavigationDock.MaxWidth = Math.Max(160, EditorRootGrid.ActualWidth - 24);
        FloatingDockOffset.X = Math.Clamp(FloatingDockOffset.X, -maxX, maxX);
        FloatingDockOffset.Y = Math.Clamp(FloatingDockOffset.Y, -maxUp, 0);
    }

    private void SaveFloatingDockPosition()
    {
        AppPreferences.SetInts(
            ("v5_floating_dock_x", (int)Math.Round(FloatingDockOffset.X)),
            ("v5_floating_dock_y", (int)Math.Round(FloatingDockOffset.Y)));
    }

    private void FloatingDockReset_Click(object sender, RoutedEventArgs e)
    {
        FloatingDockOffset.X = 0;
        FloatingDockOffset.Y = 0;
        SaveFloatingDockPosition();
    }

    private void RunFloatingDockSmoke()
    {
        // WinUI's responsive NavigationView can reopen itself on first layout.
        RootNavigation.IsPaneOpen = false;
        if (!EditorRootGrid.Children.Contains(FloatingNavigationDock)
            || FloatingNavigationDock.Visibility != Visibility.Visible
            || RootNavigation.IsPaneOpen || RootNavigation.IsPaneToggleButtonVisible)
            throw new InvalidOperationException(
                $"V5 floating dock check: child={EditorRootGrid.Children.Contains(FloatingNavigationDock)}, " +
                $"dockVisibility={FloatingNavigationDock.Visibility}, paneOpen={RootNavigation.IsPaneOpen}, " +
                $"toggleVisible={RootNavigation.IsPaneToggleButtonVisible}, paneMode={RootNavigation.PaneDisplayMode}.");

        var destinations = new[] { "project", "assets", "thumbnail", "audio", "renderer", "settings", "developer" };
        foreach (var tag in destinations)
        {
            NavigateFromFloatingDock(tag);
            var selection = (RootNavigation.SelectedItem as NavigationViewItem)?.Tag as string;
            if (selection != tag)
                throw new InvalidOperationException($"Floating navigation did not select {tag}.");
            var expected = tag switch
            {
                "project" => ProjectPage,
                "assets" => AssetsPage,
                "thumbnail" => ThumbnailPage,
                "renderer" => RendererPage,
                "developer" => DeveloperPage,
                "settings" => _settingsPage,
                "audio" => _finalAudioPage,
                _ => null
            };
            if (expected is null || expected.Visibility != Visibility.Visible)
                throw new InvalidOperationException($"Floating dock did not open the real {tag} editor.");
        }
        NavigateFromFloatingDock("project");
        if (FloatingWorkspaceButton.Background is not Brush)
            throw new InvalidOperationException("Floating dock selected state is not applied.");
        App.WriteLog("Floating, draggable V5 dock navigation smoke passed.");
    }
}
