using SkiaSharp;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace CubicalCompare.Windows;

public sealed class RendererImportDialog : Window
{
    private readonly RendererCandidate _candidate;
    private readonly RendererStore _store;
    private readonly Image _preview = new() { Stretch = Stretch.Uniform };
    private readonly TextBlock _checkpoint = new();
    private List<int> _frames = [];
    private int _previewIndex;
    public bool RendererActivated { get; private set; }

    public RendererImportDialog(RendererCandidate candidate, string sourcePath, RendererStore store)
    {
        _candidate = candidate; _store = store;
        Title = "Import renderer"; Width = 760; Height = 760; MinWidth = 520; MinHeight = 480; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        Background = new SolidColorBrush(Color.FromRgb(30,30,32)); Foreground = Brushes.White;
        var root = new DockPanel { Margin = new Thickness(18) }; Content = root;
        var buttons = new WrapPanel { HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) }; DockPanel.SetDock(buttons, Dock.Bottom); root.Children.Add(buttons);
        buttons.Children.Add(MakeButton("Copy diagnostics", CopyDiagnostics));
        var installOnly = MakeButton("Install only", InstallOnly); installOnly.IsEnabled = candidate.Report.Compatible; buttons.Children.Add(installOnly);
        buttons.Children.Add(MakeButton("Cancel", () => { DialogResult = false; Close(); }));
        var installUse = MakeButton("Install & use", InstallAndUse); installUse.IsEnabled = candidate.Report.Compatible; buttons.Children.Add(installUse);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; root.Children.Add(scroll);
        var pane = new StackPanel(); scroll.Content = pane;
        pane.Children.Add(new TextBlock { Text = Path.GetFileName(sourcePath), Foreground = new SolidColorBrush(Color.FromRgb(180,180,180)), TextWrapping = TextWrapping.Wrap });
        pane.Children.Add(new TextBlock { Text = candidate.Spec.Name, FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 7, 0, 2), TextWrapping = TextWrapping.Wrap });
        var versionBlocked = candidate.Report.Errors.Any(x => x.StartsWith("Requires Cubical Compare ", StringComparison.Ordinal));
        var statusText = candidate.Report.Compatible ? $"Ready for Cubical Compare {RendererCapabilities.AppVersion}" : versionBlocked ? $"Requires Cubical Compare {candidate.Spec.MinAppVersion}+ • installed {RendererCapabilities.AppVersion}" : $"Renderer isn't compatible with Cubical Compare {RendererCapabilities.AppVersion}";
        pane.Children.Add(new TextBlock { Text = statusText, FontWeight = FontWeights.SemiBold, Foreground = candidate.Report.Compatible ? new SolidColorBrush(Color.FromRgb(110,205,255)) : new SolidColorBrush(Color.FromRgb(255,115,115)), Margin = new Thickness(0,5,0,5), TextWrapping = TextWrapping.Wrap });
        pane.Children.Add(new TextBlock { Text = $"By {candidate.Spec.Author} • {candidate.Spec.Engine} • {candidate.Spec.PrecisionMode}\nSchema {candidate.Spec.FormatVersion} • API {candidate.Spec.RendererApi} • {candidate.Spec.ReferenceWidth}×{candidate.Spec.ReferenceHeight} @ {candidate.Spec.ReferenceFps} FPS\nCanonical cards {candidate.Spec.CanonicalCardCount} • frames {candidate.Spec.CanonicalFrameCount} • tracks {candidate.Spec.Tracks.Count}", Foreground = new SolidColorBrush(Color.FromRgb(190,190,190)), TextWrapping = TextWrapping.Wrap });
        pane.Children.Add(new TextBlock { Text = "SHA-256: " + candidate.Sha256, FontSize = 11, Foreground = new SolidColorBrush(Color.FromRgb(160,160,160)), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0,4,0,0) });
        pane.Children.Add(new Separator { Margin = new Thickness(0,12,0,10) });
        foreach (var error in candidate.Report.Errors) pane.Children.Add(new TextBlock { Text = "Error: " + error, Foreground = new SolidColorBrush(Color.FromRgb(255,110,110)), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0,3,0,3) });
        foreach (var warning in candidate.Report.Warnings) pane.Children.Add(new TextBlock { Text = "Warning: " + warning, Foreground = new SolidColorBrush(Color.FromRgb(255,195,90)), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0,3,0,3) });
        if (candidate.Report.Compatible)
        {
            pane.Children.Add(new TextBlock { Text = "Pre-activation preview", FontWeight = FontWeights.SemiBold, Margin = new Thickness(0,12,0,5) });
            pane.Children.Add(new Border { Height = 300, Background = Brushes.Black, BorderBrush = new SolidColorBrush(Color.FromRgb(70,70,70)), BorderThickness = new Thickness(1), Child = _preview });
            var navigation = new DockPanel { Margin = new Thickness(0,7,0,0) }; pane.Children.Add(navigation);
            var next = MakeButton("Next", () => { if (_previewIndex < _frames.Count - 1) { _previewIndex++; RenderCandidatePreview(); } }); DockPanel.SetDock(next, Dock.Right); navigation.Children.Add(next);
            var previous = MakeButton("Previous", () => { if (_previewIndex > 0) { _previewIndex--; RenderCandidatePreview(); } }); DockPanel.SetDock(previous, Dock.Right); navigation.Children.Add(previous);
            _checkpoint.VerticalAlignment = VerticalAlignment.Center; navigation.Children.Add(_checkpoint);
            Loaded += (_, _) => { _frames = PreviewFrames(candidate.Spec); RenderCandidatePreview(); };
        }
    }

    private static List<int> PreviewFrames(RendererSpec spec)
    {
        var maximum = spec.CanonicalFrameCount > 0 ? spec.CanonicalFrameCount - 1 : int.MaxValue;
        var explicitFrames = spec.PreviewFrames.Where(x => x >= 0 && x <= maximum).Distinct().Order().ToList();
        if (explicitFrames.Count > 0) return explicitFrames;
        return new[] { 0, spec.OpeningStarts.FirstOrDefault(), spec.ContinuousStartFrame, Math.Max(spec.ContinuousStartFrame, Math.Max(0, spec.CanonicalFrameCount - 1)) }.Where(x => x >= 0 && x <= maximum).Distinct().ToList();
    }

    private StudioProject PreviewProject()
    {
        var count = Math.Min(60, Math.Max(4, _candidate.Spec.CanonicalCardCount > 0 ? _candidate.Spec.CanonicalCardCount : 8));
        return new StudioProject
        {
            Name = "Renderer preflight",
            Width = _candidate.Spec.ReferenceWidth,
            Height = _candidate.Spec.ReferenceHeight,
            Fps = _candidate.Spec.ReferenceFps,
            Cards = Enumerable.Range(1, count).Select(i => new StudioCard { Title = $"Preview {i}", Value = $"{i * 10} People", BadgeHeader = "1 IN", Description = i % 3 == 0 ? "Renderer layout and animation preview" : "" }).ToList(),
        };
    }

    private void RenderCandidatePreview()
    {
        try
        {
            if (_frames.Count == 0) return;
            _previewIndex = Math.Clamp(_previewIndex, 0, _frames.Count - 1);
            var frame = _frames[_previewIndex];
            using var engine = new RendererEngine(); using var bitmap = engine.Render(PreviewProject(), _candidate.Spec, frame, 640, 360);
            _preview.Source = BitmapFrom(bitmap); _checkpoint.Text = $"Checkpoint {_previewIndex + 1}/{_frames.Count} • frame {frame}";
        }
        catch (Exception ex) { _preview.ToolTip = ex.Message; _checkpoint.Text = "Preview failed: " + ex.Message; }
    }

    private void CopyDiagnostics()
    {
        var text = $"Cubical Compare Windows renderer preflight\r\nName: {_candidate.Spec.Name}\r\nID: {_candidate.Spec.Id}\r\nAuthor: {_candidate.Spec.Author}\r\nSHA-256: {_candidate.Sha256}\r\nFormat: {_candidate.Spec.FormatVersion}\r\nRenderer API: {_candidate.Spec.RendererApi}\r\nEngine: {_candidate.Spec.Engine}\r\nPrecision: {_candidate.Spec.PrecisionMode}\r\nTimeline: {_candidate.Spec.TimelineUnit}\r\nReference: {_candidate.Spec.ReferenceWidth}x{_candidate.Spec.ReferenceHeight} @ {_candidate.Spec.ReferenceFps} fps\r\nCanonical cards: {_candidate.Spec.CanonicalCardCount}\r\nCanonical frames: {_candidate.Spec.CanonicalFrameCount}\r\nTracks: {_candidate.Spec.Tracks.Count}\r\nRequired features: {string.Join(", ", _candidate.Spec.RequiredFeatures)}\r\nCompatibility: {(_candidate.Report.Compatible ? "Fully compatible" : "Not compatible")}\r\n" + string.Join("\r\n", _candidate.Report.Errors.Select(x => "ERROR: " + x).Concat(_candidate.Report.Warnings.Select(x => "WARNING: " + x)));
        Clipboard.SetText(text);
    }
    private void InstallOnly() { try { _store.Install(_candidate); MessageBox.Show(this, "Renderer installed.", "Cubical Compare", MessageBoxButton.OK, MessageBoxImage.Information); DialogResult = false; Close(); } catch (Exception ex) { MessageBox.Show(this, ex.Message, "Install renderer", MessageBoxButton.OK, MessageBoxImage.Error); } }
    private void InstallAndUse() { try { _store.Activate(_candidate); RendererActivated = true; DialogResult = true; Close(); } catch (Exception ex) { MessageBox.Show(this, ex.Message, "Install renderer", MessageBoxButton.OK, MessageBoxImage.Error); } }
    private static Button MakeButton(string text, Action action) { var button = new Button { Content = text, Margin = new Thickness(6,0,0,0), Padding = new Thickness(11,5,11,5) }; button.Click += (_, _) => action(); return button; }
    private static BitmapSource BitmapFrom(SKBitmap bitmap) { using var pixmap = bitmap.PeekPixels(); var size = checked(pixmap.RowBytes * pixmap.Height); var pixels = new byte[size]; Marshal.Copy(pixmap.GetPixels(), pixels, 0, size); var source = BitmapSource.Create(bitmap.Width, bitmap.Height, 96, 96, PixelFormats.Bgra32, null, pixels, pixmap.RowBytes); source.Freeze(); return source; }
}