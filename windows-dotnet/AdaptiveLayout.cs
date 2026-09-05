using System.Windows;
using System.Windows.Controls;

namespace CubicalCompare.Windows;

/// <summary>
/// Responsive layout controller for the Windows editor. WPF uses device-independent
/// pixels, so SystemParameters.WorkArea already reflects both monitor resolution and
/// Windows display scaling. The controller keeps the window inside the usable monitor
/// area and reflows the editor instead of clipping fixed-width panes.
/// </summary>
public static class AdaptiveLayout
{
    private const double WideBreakpoint = 1180.0;
    private const double CompactBreakpoint = 820.0;

    public static void Attach(MainWindow window)
    {
        FitWindowToWorkArea(window);

        window.Loaded += (_, _) =>
        {
            FitWindowToWorkArea(window);
            ConfigureRoot(window);
            Apply(window);
        };
        window.SizeChanged += (_, _) => Apply(window);
        window.StateChanged += (_, _) =>
        {
            if (window.WindowState == WindowState.Normal) FitWindowToWorkArea(window, resizeOnlyIfOffscreen: true);
            Apply(window);
        };
    }

    private static void FitWindowToWorkArea(Window window, bool resizeOnlyIfOffscreen = false)
    {
        var work = SystemParameters.WorkArea;
        var targetWidth = Math.Min(1440.0, Math.Max(480.0, work.Width * 0.96));
        var targetHeight = Math.Min(900.0, Math.Max(360.0, work.Height * 0.96));

        // Never force a minimum size that is larger than the current monitor.
        window.MinWidth = Math.Min(760.0, Math.Max(480.0, work.Width * 0.62));
        window.MinHeight = Math.Min(500.0, Math.Max(360.0, work.Height * 0.62));

        if (!resizeOnlyIfOffscreen || window.Width > work.Width || window.Height > work.Height)
        {
            window.Width = targetWidth;
            window.Height = targetHeight;
        }
    }

    private static void ConfigureRoot(MainWindow window)
    {
        if (window.Content is not DockPanel root) return;

        // The old horizontal StackPanel could run off-screen at lower widths or high
        // Windows scaling. Replace it with a WrapPanel while preserving the controls.
        var toolbar = root.Children.OfType<StackPanel>()
            .FirstOrDefault(panel => DockPanel.GetDock(panel) == Dock.Top && panel.Orientation == Orientation.Horizontal);
        if (toolbar != null)
        {
            var index = root.Children.IndexOf(toolbar);
            var children = toolbar.Children.Cast<UIElement>().ToArray();
            foreach (var child in children) toolbar.Children.Remove(child);

            var wrapped = new WrapPanel
            {
                Orientation = Orientation.Horizontal,
                Margin = toolbar.Margin,
                HorizontalAlignment = HorizontalAlignment.Stretch,
            };
            foreach (var child in children) wrapped.Children.Add(child);
            DockPanel.SetDock(wrapped, Dock.Top);
            root.Children.Remove(toolbar);
            root.Children.Insert(index, wrapped);
        }

        var footer = root.Children.OfType<StackPanel>()
            .FirstOrDefault(panel => DockPanel.GetDock(panel) == Dock.Bottom);
        if (footer != null)
        {
            foreach (var text in FindVisualChildren<TextBlock>(footer))
            {
                text.TextTrimming = TextTrimming.CharacterEllipsis;
            }
        }
    }

    private static void Apply(MainWindow window)
    {
        if (window.Content is not DockPanel root) return;
        var main = root.Children.OfType<Grid>()
            .FirstOrDefault(grid => grid.Children.Count >= 3 && (grid.ColumnDefinitions.Count == 3 || grid.RowDefinitions.Count > 0));
        if (main == null || main.Children.Count < 3) return;

        var cards = main.Children[0];
        var preview = main.Children[1];
        var editor = main.Children[2];
        var width = window.ActualWidth > 0 ? window.ActualWidth : window.Width;
        var height = window.ActualHeight > 0 ? window.ActualHeight : window.Height;

        main.ColumnDefinitions.Clear();
        main.RowDefinitions.Clear();

        if (width >= WideBreakpoint)
        {
            // Desktop/laptop: preserve the familiar 3-pane editor but let the side
            // panes shrink proportionally instead of demanding a 1440px window.
            var cardWidth = Math.Clamp(width * 0.165, 185.0, 240.0);
            var editorWidth = Math.Clamp(width * 0.225, 260.0, 330.0);
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(cardWidth) });
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star), MinWidth = 320 });
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(editorWidth) });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
            Place(cards, 0, 0);
            Place(preview, 0, 1);
            Place(editor, 0, 2);
        }
        else if (width >= CompactBreakpoint)
        {
            // Compact window: preview gets the full top row; cards and editor share
            // the lower row. This keeps the 16:9 preview useful on ~800-1179 DIP widths.
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.38, GridUnitType.Star), MinWidth = 230 });
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(0.62, GridUnitType.Star), MinWidth = 300 });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(height < 650 ? 0.48 : 0.58, GridUnitType.Star), MinHeight = 210 });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(height < 650 ? 0.52 : 0.42, GridUnitType.Star), MinHeight = 190 });
            Place(preview, 0, 0, columnSpan: 2);
            Place(cards, 1, 0);
            Place(editor, 1, 1);
        }
        else
        {
            // Very narrow windows/tablet portrait: one column, no horizontal clipping.
            main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(0.46, GridUnitType.Star), MinHeight = 190 });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(0.24, GridUnitType.Star), MinHeight = 125 });
            main.RowDefinitions.Add(new RowDefinition { Height = new GridLength(0.30, GridUnitType.Star), MinHeight = 165 });
            Place(preview, 0, 0);
            Place(cards, 1, 0);
            Place(editor, 2, 0);
        }
    }

    private static void Place(UIElement element, int row, int column, int rowSpan = 1, int columnSpan = 1)
    {
        Grid.SetRow(element, row);
        Grid.SetColumn(element, column);
        Grid.SetRowSpan(element, rowSpan);
        Grid.SetColumnSpan(element, columnSpan);
    }

    private static IEnumerable<T> FindVisualChildren<T>(DependencyObject parent) where T : DependencyObject
    {
        if (parent is Panel panel)
        {
            foreach (UIElement child in panel.Children)
            {
                if (child is T match) yield return match;
                foreach (var nested in FindVisualChildren<T>(child)) yield return nested;
            }
        }
        else if (parent is ContentControl content && content.Content is DependencyObject child)
        {
            if (child is T match) yield return match;
            foreach (var nested in FindVisualChildren<T>(child)) yield return nested;
        }
    }
}
