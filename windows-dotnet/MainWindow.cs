using Microsoft.Win32;
using SkiaSharp;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;

namespace CubicalCompare.Windows;

public sealed class MainWindow : Window
{
    private readonly RendererStore _rendererStore = new();
    private readonly RendererEngine _engine = new();
    private StudioProject _project = new();
    private RendererSpec _renderer;
    private string? _projectPath;
    private bool _loading;
    private readonly ListBox _cards = new();
    private readonly Image _preview = new() { Stretch = Stretch.Uniform };
    private readonly Slider _timeline = new() { Minimum = 0, IsSnapToTickEnabled = true, TickFrequency = 1 };
    private readonly TextBlock _frameLabel = new();
    private readonly TextBox _title = new();
    private readonly TextBox _value = new();
    private readonly TextBox _badgeHeader = new();
    private readonly TextBox _description = new() { AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
    private readonly TextBox _image = new();
    private readonly TextBox _soundtrack = new();
    private readonly CheckBox _badges = new() { Content = "Show badges" };
    private readonly CheckBox _credits = new() { Content = "Credits" };
    private readonly CheckBox _autoLength = new() { Content = "Automatic length" };
    private readonly TextBox _length = new() { Text = "90" };
    private readonly TextBlock _rendererLabel = new();
    private readonly TextBlock _status = new() { Text = "Ready" };
    private readonly ProgressBar _progress = new() { Minimum = 0, Maximum = 1, Height = 8, Visibility = Visibility.Collapsed };
    private readonly Button _exportButton;
    private readonly Button _cancelExport = new() { Content = "Cancel export", Visibility = Visibility.Collapsed };
    private readonly DispatcherTimer _playTimer = new() { Interval = TimeSpan.FromMilliseconds(16) };
    private readonly DispatcherTimer _previewDebounce = new() { Interval = TimeSpan.FromMilliseconds(70) };
    private CancellationTokenSource? _exportCts;
    private int _selectedIndex;
    private bool _playing;

    public MainWindow()
    {
        _renderer = _rendererStore.Active();
        Title = "Cubical Compare 3.0.300 — Windows (.NET)";
        Width = 1440;
        Height = 900;
        MinWidth = 1050;
        MinHeight = 680;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Background = new SolidColorBrush(Color.FromRgb(24, 24, 24));
        Foreground = Brushes.White;

        var root = new DockPanel();
        Content = root;

        var toolbar = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(10, 8, 10, 6) };
        DockPanel.SetDock(toolbar, Dock.Top);
        root.Children.Add(toolbar);
        toolbar.Children.Add(ActionButton("New", NewProject));
        toolbar.Children.Add(ActionButton("Open", OpenProject));
        toolbar.Children.Add(ActionButton("Save", SaveProject));
        toolbar.Children.Add(ActionButton("Save as", SaveProjectAs));
        toolbar.Children.Add(Separator());
        toolbar.Children.Add(ActionButton("Import renderer", ImportRenderer));
        toolbar.Children.Add(ActionButton("Reset renderer", ResetRenderer));
        toolbar.Children.Add(Separator());
        _exportButton = ActionButton("Export MP4", ExportVideo);
        toolbar.Children.Add(_exportButton);
        _cancelExport.Margin = new Thickness(6, 0, 0, 0);
        _cancelExport.Click += (_, _) => _exportCts?.Cancel();
        toolbar.Children.Add(_cancelExport);

        var footer = new StackPanel { Margin = new Thickness(10, 3, 10, 9) };
        DockPanel.SetDock(footer, Dock.Bottom);
        root.Children.Add(footer);
        footer.Children.Add(_progress);
        var footerRow = new DockPanel { Margin = new Thickness(0, 5, 0, 0) };
        footer.Children.Add(footerRow);
        _rendererLabel.Foreground = new SolidColorBrush(Color.FromRgb(170, 210, 255));
        DockPanel.SetDock(_rendererLabel, Dock.Right);
        footerRow.Children.Add(_rendererLabel);
        _status.Foreground = new SolidColorBrush(Color.FromRgb(190, 190, 190));
        footerRow.Children.Add(_status);

        var main = new Grid { Margin = new Thickness(10, 4, 10, 4) };
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(240) });
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        main.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(330) });
        root.Children.Add(main);

        BuildCardPane(main);
        BuildPreviewPane(main);
        BuildEditorPane(main);

        _timeline.ValueChanged += (_, _) =>
        {
            if (_loading) return;
            RenderPreview((int)Math.Round(_timeline.Value));
        };
        _cards.SelectionChanged += (_, _) =>
        {
            if (_loading || _cards.SelectedIndex < 0) return;
            CommitFields();
            _selectedIndex = _cards.SelectedIndex;
            LoadFields();
        };
        _playTimer.Tick += (_, _) =>
        {
            if (!_playing) return;
            var next = (int)_timeline.Value + 1;
            if (next > _timeline.Maximum) next = 0;
            _timeline.Value = next;
        };
        _previewDebounce.Tick += (_, _) =>
        {
            _previewDebounce.Stop();
            RefreshPreview();
        };
        Closing += (_, _) =>
        {
            _exportCts?.Cancel();
            _engine.Dispose();
        };

        RefreshAll();
    }

    private void BuildCardPane(Grid main)
    {
        var pane = new DockPanel { Margin = new Thickness(0, 0, 10, 0), LastChildFill = true };
        Grid.SetColumn(pane, 0);
        main.Children.Add(pane);
        var header = new TextBlock { Text = "Cards", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 7) };
        DockPanel.SetDock(header, Dock.Top); pane.Children.Add(header);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 7, 0, 0) };
        DockPanel.SetDock(actions, Dock.Bottom); pane.Children.Add(actions);
        actions.Children.Add(ActionButton("Add", () =>
        {
            CommitFields();
            _project.Cards.Add(new StudioCard { Title = $"Card {_project.Cards.Count + 1}", Value = (_project.Cards.Count + 1).ToString() });
            _selectedIndex = _project.Cards.Count - 1;
            RefreshCardList(); RefreshTimeline(); LoadFields(); SchedulePreview();
        }));
        actions.Children.Add(ActionButton("Delete", () =>
        {
            if (_project.Cards.Count <= 1) return;
            _project.Cards.RemoveAt(Math.Clamp(_selectedIndex, 0, _project.Cards.Count - 1));
            _selectedIndex = Math.Clamp(_selectedIndex, 0, _project.Cards.Count - 1);
            RefreshCardList(); RefreshTimeline(); LoadFields(); SchedulePreview();
        }));
        _cards.Background = new SolidColorBrush(Color.FromRgb(35, 35, 35));
        _cards.Foreground = Brushes.White;
        _cards.BorderBrush = new SolidColorBrush(Color.FromRgb(70, 70, 70));
        pane.Children.Add(_cards);
    }

    private void BuildPreviewPane(Grid main)
    {
        var pane = new Grid { Margin = new Thickness(4, 0, 10, 0) };
        pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        pane.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        pane.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Grid.SetColumn(pane, 1);
        main.Children.Add(pane);
        var title = new TextBlock { Text = "Preview", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 7) };
        pane.Children.Add(title);
        var previewBox = new Border { Background = Brushes.Black, BorderBrush = new SolidColorBrush(Color.FromRgb(70,70,70)), BorderThickness = new Thickness(1), Child = _preview };
        Grid.SetRow(previewBox, 1); pane.Children.Add(previewBox);
        var timelinePane = new Grid { Margin = new Thickness(0, 8, 0, 0) };
        timelinePane.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        timelinePane.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        timelinePane.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetRow(timelinePane, 2); pane.Children.Add(timelinePane);
        var play = ActionButton("▶", () => { _playing = !_playing; play.Content = _playing ? "❚❚" : "▶"; if (_playing) _playTimer.Start(); else _playTimer.Stop(); });
        Grid.SetColumn(play, 0); timelinePane.Children.Add(play);
        _timeline.Margin = new Thickness(8, 0, 8, 0); Grid.SetColumn(_timeline, 1); timelinePane.Children.Add(_timeline);
        _frameLabel.VerticalAlignment = VerticalAlignment.Center; Grid.SetColumn(_frameLabel, 2); timelinePane.Children.Add(_frameLabel);
    }

    private void BuildEditorPane(Grid main)
    {
        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto, Margin = new Thickness(5, 0, 0, 0) };
        Grid.SetColumn(scroll, 2); main.Children.Add(scroll);
        var pane = new StackPanel(); scroll.Content = pane;
        pane.Children.Add(new TextBlock { Text = "Card data", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 7) });
        AddField(pane, "Title", _title);
        AddField(pane, "Value", _value);
        AddField(pane, "Badge header", _badgeHeader);
        AddField(pane, "Description", _description, 105);
        var imageRow = AddField(pane, "Artwork", _image);
        imageRow.Children.Add(SmallButton("Browse", () =>
        {
            var dlg = new OpenFileDialog { Filter = "Images|*.png;*.jpg;*.jpeg;*.webp;*.bmp|All files|*.*" };
            if (dlg.ShowDialog(this) == true) _image.Text = dlg.FileName;
        }));
        pane.Children.Add(new Separator { Margin = new Thickness(0, 12, 0, 12) });
        pane.Children.Add(new TextBlock { Text = "Project", FontSize = 18, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 7) });
        var soundRow = AddField(pane, "Soundtrack", _soundtrack);
        soundRow.Children.Add(SmallButton("Browse", () =>
        {
            var dlg = new OpenFileDialog { Filter = "Audio|*.mp3;*.wav;*.m4a;*.aac;*.flac;*.ogg|All files|*.*" };
            if (dlg.ShowDialog(this) == true) _soundtrack.Text = dlg.FileName;
        }));
        _badges.Margin = new Thickness(0, 6, 0, 0); pane.Children.Add(_badges);
        _credits.Margin = new Thickness(0, 6, 0, 0); pane.Children.Add(_credits);
        _autoLength.Margin = new Thickness(0, 6, 0, 0); pane.Children.Add(_autoLength);
        var lengthRow = AddField(pane, "Length (seconds)", _length);

        foreach (var box in new[] { _title, _value, _badgeHeader, _description, _image, _soundtrack, _length }) box.TextChanged += (_, _) => { if (!_loading) { CommitFields(); SchedulePreview(); } };
        foreach (var check in new[] { _badges, _credits, _autoLength })
        {
            check.Checked += (_, _) => { if (!_loading) { CommitFields(); RefreshTimeline(); SchedulePreview(); } };
            check.Unchecked += (_, _) => { if (!_loading) { CommitFields(); RefreshTimeline(); SchedulePreview(); } };
        }
    }

    private static StackPanel AddField(StackPanel parent, string label, TextBox box, double height = double.NaN)
    {
        parent.Children.Add(new TextBlock { Text = label, Foreground = new SolidColorBrush(Color.FromRgb(190,190,190)), Margin = new Thickness(0, 7, 0, 3) });
        var row = new StackPanel { Orientation = Orientation.Horizontal };
        parent.Children.Add(row);
        box.MinWidth = 210;
        box.HorizontalAlignment = HorizontalAlignment.Stretch;
        box.Background = new SolidColorBrush(Color.FromRgb(38, 38, 38));
        box.Foreground = Brushes.White;
        box.BorderBrush = new SolidColorBrush(Color.FromRgb(80, 80, 80));
        box.Padding = new Thickness(7, 5, 7, 5);
        if (!double.IsNaN(height)) box.Height = height;
        row.Children.Add(box);
        return row;
    }

    private static Button ActionButton(string text, Action action)
    {
        var button = new Button { Content = text, Margin = new Thickness(0, 0, 6, 0), Padding = new Thickness(11, 5, 11, 5), MinWidth = 58 };
        button.Click += (_, _) => action();
        return button;
    }
    private static Button SmallButton(string text, Action action)
    {
        var button = new Button { Content = text, Margin = new Thickness(6, 0, 0, 0), Padding = new Thickness(8, 4, 8, 4) };
        button.Click += (_, _) => action();
        return button;
    }
    private static Separator Separator() => new() { Width = 1, Height = 25, Margin = new Thickness(4, 0, 10, 0), Background = new SolidColorBrush(Color.FromRgb(75, 75, 75)) };

    private void NewProject()
    {
        _project = new StudioProject(); _projectPath = null; _selectedIndex = 0; RefreshAll(); _status.Text = "New project";
    }
    private void OpenProject()
    {
        var dlg = new OpenFileDialog { Filter = "Cubical Compare project|*.json;*.ccproject|JSON|*.json|All files|*.*" };
        if (dlg.ShowDialog(this) != true) return;
        try { _project = StudioProject.Load(dlg.FileName); _projectPath = dlg.FileName; _selectedIndex = 0; RefreshAll(); _status.Text = "Opened " + System.IO.Path.GetFileName(dlg.FileName); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Open project", MessageBoxButton.OK, MessageBoxImage.Error); }
    }
    private void SaveProject()
    {
        if (_projectPath == null) { SaveProjectAs(); return; }
        CommitFields();
        try { _project.Save(_projectPath); _status.Text = "Saved " + System.IO.Path.GetFileName(_projectPath); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Save project", MessageBoxButton.OK, MessageBoxImage.Error); }
    }
    private void SaveProjectAs()
    {
        var dlg = new SaveFileDialog { Filter = "Cubical Compare project|*.json", FileName = SanitizeFileName(_project.Name) + ".json" };
        if (dlg.ShowDialog(this) != true) return;
        _projectPath = dlg.FileName; SaveProject();
    }

    private void ImportRenderer()
    {
        var dlg = new OpenFileDialog { Filter = "Cubical Compare renderers|*.renderer;*.renderer3;*.zip|Renderer|*.renderer|Renderer v3|*.renderer3;*.zip|All files|*.*" };
        if (dlg.ShowDialog(this) != true) return;
        try
        {
            var candidate = RendererBundleReader.Inspect(dlg.FileName);
            var dialog = new RendererImportDialog(candidate, dlg.FileName, _rendererStore) { Owner = this };
            if (dialog.ShowDialog() == true && dialog.Activated)
            {
                _renderer = _rendererStore.Active();
                RefreshRendererLabel(); RefreshTimeline(); RefreshPreview();
                _status.Text = "Renderer active: " + _renderer.Name;
            }
        }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Import renderer", MessageBoxButton.OK, MessageBoxImage.Error); }
    }

    private void ResetRenderer()
    {
        if (MessageBox.Show(this, "Restore the built-in Cubical Compare renderer?", "Reset renderer", MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
        _renderer = _rendererStore.Reset(); RefreshRendererLabel(); RefreshTimeline(); RefreshPreview(); _status.Text = "Built-in renderer restored";
    }

    private async void ExportVideo()
    {
        CommitFields();
        var dlg = new SaveFileDialog { Filter = "MP4 video|*.mp4", FileName = SanitizeFileName(_project.Name) + ".mp4" };
        if (dlg.ShowDialog(this) != true) return;
        _exportCts?.Dispose(); _exportCts = new CancellationTokenSource();
        _progress.Value = 0; _progress.Visibility = Visibility.Visible; _cancelExport.Visibility = Visibility.Visible; _exportButton.IsEnabled = false;
        try
        {
            var progress = new Progress<(double Progress, string Status)>(value => { _progress.Value = value.Progress; _status.Text = value.Status; });
            await new VideoExporter().ExportAsync(_project, _renderer, dlg.FileName, progress, _exportCts.Token);
            _status.Text = "Exported " + System.IO.Path.GetFileName(dlg.FileName);
            MessageBox.Show(this, "Video export completed.", "Cubical Compare", MessageBoxButton.OK, MessageBoxImage.Information);
        }
        catch (OperationCanceledException) { _status.Text = "Export cancelled"; }
        catch (Exception ex) { _status.Text = "Export failed"; MessageBox.Show(this, ex.Message, "Export", MessageBoxButton.OK, MessageBoxImage.Error); }
        finally
        {
            _progress.Visibility = Visibility.Collapsed; _cancelExport.Visibility = Visibility.Collapsed; _exportButton.IsEnabled = true;
        }
    }

    private void RefreshAll()
    {
        _loading = true;
        try { RefreshCardList(); LoadFields(); RefreshRendererLabel(); RefreshTimeline(); }
        finally { _loading = false; }
        RefreshPreview(); UpdateTitle();
    }
    private void RefreshCardList()
    {
        _loading = true;
        try
        {
            _cards.ItemsSource = null; _cards.ItemsSource = _project.Cards;
            _selectedIndex = Math.Clamp(_selectedIndex, 0, Math.Max(0, _project.Cards.Count - 1));
            _cards.SelectedIndex = _selectedIndex;
        }
        finally { _loading = false; }
    }
    private void LoadFields()
    {
        _loading = true;
        try
        {
            var card = _project.Cards[Math.Clamp(_selectedIndex, 0, _project.Cards.Count - 1)];
            _title.Text = card.Title; _value.Text = card.Value; _badgeHeader.Text = card.BadgeHeader; _description.Text = card.Description; _image.Text = card.Image;
            _soundtrack.Text = _project.Soundtrack; _badges.IsChecked = _project.ShowBadges; _credits.IsChecked = _project.CreditsEnabled; _autoLength.IsChecked = _project.AutoLength; _length.Text = _project.CustomLengthSeconds.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture);
        }
        finally { _loading = false; }
    }
    private void CommitFields()
    {
        if (_loading || _project.Cards.Count == 0) return;
        var card = _project.Cards[Math.Clamp(_selectedIndex, 0, _project.Cards.Count - 1)];
        card.Title = _title.Text; card.Value = _value.Text; card.BadgeHeader = _badgeHeader.Text; card.Description = _description.Text; card.Image = _image.Text;
        _project.Soundtrack = _soundtrack.Text; _project.ShowBadges = _badges.IsChecked == true; _project.CreditsEnabled = _credits.IsChecked == true; _project.AutoLength = _autoLength.IsChecked == true;
        if (double.TryParse(_length.Text, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var seconds) && seconds > 0) _project.CustomLengthSeconds = seconds;
        RefreshCardNames(); UpdateTitle();
    }
    private void RefreshCardNames()
    {
        if (_cards.Items.Count == _project.Cards.Count) _cards.Items.Refresh();
    }
    private void RefreshRendererLabel() => _rendererLabel.Text = $"{_renderer.Name} • API {_renderer.RendererApi} • {_renderer.ReferenceWidth}×{_renderer.ReferenceHeight}@{_renderer.ReferenceFps}";
    private void RefreshTimeline()
    {
        CommitFields();
        var count = Math.Max(1, _engine.FrameCount(_project, _renderer));
        _timeline.Maximum = count - 1;
        if (_timeline.Value > _timeline.Maximum) _timeline.Value = _timeline.Maximum;
        UpdateFrameLabel();
    }
    private void SchedulePreview()
    {
        _previewDebounce.Stop(); _previewDebounce.Start();
    }
    private void RefreshPreview() => RenderPreview((int)Math.Round(_timeline.Value));
    private void RenderPreview(int frame)
    {
        if (_project.Cards.Count == 0) return;
        try
        {
            using var bitmap = _engine.Render(_project, _renderer, frame, 960, 540);
            _preview.Source = ToBitmapSource(bitmap);
            UpdateFrameLabel();
        }
        catch (Exception ex) { _status.Text = "Preview: " + ex.Message; }
    }
    private void UpdateFrameLabel() => _frameLabel.Text = $"Frame {(int)Math.Round(_timeline.Value):N0} / {(int)_timeline.Maximum:N0}";
    private void UpdateTitle() => Title = $"{_project.Name} — Cubical Compare 3.0.300 Windows (.NET)";

    private static BitmapSource ToBitmapSource(SKBitmap bitmap)
    {
        using var pixmap = bitmap.PeekPixels();
        var size = checked(pixmap.RowBytes * pixmap.Height);
        var pixels = new byte[size];
        Marshal.Copy(pixmap.GetPixels(), pixels, 0, size);
        var source = BitmapSource.Create(bitmap.Width, bitmap.Height, 96, 96, PixelFormats.Bgra32, null, pixels, pixmap.RowBytes);
        source.Freeze();
        return source;
    }
    private static string SanitizeFileName(string name)
    {
        var invalid = System.IO.Path.GetInvalidFileNameChars();
        var safe = new string((string.IsNullOrWhiteSpace(name) ? "Cubical Compare" : name).Select(c => invalid.Contains(c) ? '_' : c).ToArray());
        return safe.Trim();
    }
}

public sealed class RendererImportDialog : Window
{
    private readonly RendererCandidate _candidate;
    private readonly RendererStore _store;
    public bool Activated { get; private set; }

    public RendererImportDialog(RendererCandidate candidate, string sourcePath, RendererStore store)
    {
        _candidate = candidate; _store = store;
        Title = "Import renderer"; Width = 720; Height = 690; MinWidth = 560; MinHeight = 520; WindowStartupLocation = WindowStartupLocation.CenterOwner; Background = new SolidColorBrush(Color.FromRgb(30,30,30)); Foreground = Brushes.White;
        var root = new DockPanel { Margin = new Thickness(18) }; Content = root;
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 14, 0, 0) }; DockPanel.SetDock(buttons, Dock.Bottom); root.Children.Add(buttons);
        buttons.Children.Add(MakeButton("Diagnostics", CopyDiagnostics));
        var installOnly = MakeButton("Install only", InstallOnly); installOnly.IsEnabled = candidate.Report.Compatible; buttons.Children.Add(installOnly);
        buttons.Children.Add(MakeButton("Cancel", () => { DialogResult = false; Close(); }));
        var installUse = MakeButton("Install & use", InstallAndUse); installUse.IsEnabled = candidate.Report.Compatible; buttons.Children.Add(installUse);

        var scroll = new ScrollViewer { VerticalScrollBarVisibility = ScrollBarVisibility.Auto }; root.Children.Add(scroll);
        var pane = new StackPanel(); scroll.Content = pane;
        pane.Children.Add(new TextBlock { Text = System.IO.Path.GetFileName(sourcePath), Foreground = new SolidColorBrush(Color.FromRgb(180,180,180)), TextWrapping = TextWrapping.Wrap });
        pane.Children.Add(new TextBlock { Text = candidate.Spec.Name, FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 7, 0, 2), TextWrapping = TextWrapping.Wrap });
        var versionBlocked = candidate.Report.Errors.Any(x => x.StartsWith("Requires Cubical Compare ", StringComparison.Ordinal));
        var statusText = candidate.Report.Compatible ? $"Ready for Cubical Compare {RendererCapabilities.AppVersion}" : versionBlocked ? $"Requires Cubical Compare {candidate.Spec.MinAppVersion}+ • installed {RendererCapabilities.AppVersion}" : $"Renderer isn't compatible with Cubical Compare {RendererCapabilities.AppVersion}";
        pane.Children.Add(new TextBlock { Text = statusText, FontWeight = FontWeights.SemiBold, Foreground = candidate.Report.Compatible ? new SolidColorBrush(Color.FromRgb(110, 205, 255)) : new SolidColorBrush(Color.FromRgb(255, 115, 115)), Margin = new Thickness(0, 5, 0, 5), TextWrapping = TextWrapping.Wrap });
        pane.Children.Add(new TextBlock { Text = $"By {candidate.Spec.Author} • {candidate.Spec.Engine} • {candidate.Spec.PrecisionMode}\n{candidate.Spec.ReferenceWidth}×{candidate.Spec.ReferenceHeight} @ {candidate.Spec.ReferenceFps} FPS • renderer API {candidate.Spec.RendererApi}", Foreground = new SolidColorBrush(Color.FromRgb(190,190,190)) });
        pane.Children.Add(new Separator { Margin = new Thickness(0, 12, 0, 10) });
        foreach (var error in candidate.Report.Errors) pane.Children.Add(new TextBlock { Text = "Error: " + error, Foreground = new SolidColorBrush(Color.FromRgb(255, 110, 110)), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 3, 0, 3) });
        foreach (var warning in candidate.Report.Warnings) pane.Children.Add(new TextBlock { Text = "Warning: " + warning, Foreground = new SolidColorBrush(Color.FromRgb(255, 195, 90)), TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 3, 0, 3) });
        if (candidate.Report.Compatible)
        {
            pane.Children.Add(new TextBlock { Text = "Preview", FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 12, 0, 5) });
            var image = new Image { Stretch = Stretch.Uniform };
            pane.Children.Add(new Border { Height = 300, Background = Brushes.Black, BorderBrush = new SolidColorBrush(Color.FromRgb(70,70,70)), BorderThickness = new Thickness(1), Child = image });
            Loaded += (_, _) => RenderCandidatePreview(image);
        }
    }

    private void RenderCandidatePreview(Image image)
    {
        try
        {
            var sample = new StudioProject
            {
                Name = "Renderer preview",
                Cards = Enumerable.Range(1, Math.Max(8, Math.Min(20, _candidate.Spec.CanonicalCardCount > 0 ? _candidate.Spec.CanonicalCardCount : 8))).Select(i => new StudioCard { Title = $"Preview {i}", Value = $"{i * 10} People", BadgeHeader = i % 2 == 0 ? "1 IN" : "", Description = i % 3 == 0 ? "Renderer layout and animation preview" : "" }).ToList(),
            };
            var frame = _candidate.Spec.PreviewFrames.FirstOrDefault();
            using var engine = new RendererEngine();
            using var bitmap = engine.Render(sample, _candidate.Spec, frame, 640, 360);
            image.Source = BitmapFrom(bitmap);
        }
        catch (Exception ex)
        {
            image.ToolTip = ex.Message;
        }
    }

    private void CopyDiagnostics()
    {
        var text = $"Cubical Compare Windows renderer preflight\r\nName: {_candidate.Spec.Name}\r\nID: {_candidate.Spec.Id}\r\nAuthor: {_candidate.Spec.Author}\r\nSHA-256: {_candidate.Sha256}\r\nFormat: {_candidate.Spec.FormatVersion}\r\nRenderer API: {_candidate.Spec.RendererApi}\r\nEngine: {_candidate.Spec.Engine}\r\nPrecision: {_candidate.Spec.PrecisionMode}\r\nReference: {_candidate.Spec.ReferenceWidth}x{_candidate.Spec.ReferenceHeight} @ {_candidate.Spec.ReferenceFps} fps\r\nCompatibility: {(_candidate.Report.Compatible ? "Fully compatible" : "Not compatible")}\r\n" + string.Join("\r\n", _candidate.Report.Errors.Select(x => "ERROR: " + x).Concat(_candidate.Report.Warnings.Select(x => "WARNING: " + x)));
        Clipboard.SetText(text);
    }
    private void InstallOnly()
    {
        try { _store.Install(_candidate); MessageBox.Show(this, "Renderer installed.", "Cubical Compare", MessageBoxButton.OK, MessageBoxImage.Information); DialogResult = false; Close(); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Install renderer", MessageBoxButton.OK, MessageBoxImage.Error); }
    }
    private void InstallAndUse()
    {
        try { _store.Activate(_candidate); Activated = true; DialogResult = true; Close(); }
        catch (Exception ex) { MessageBox.Show(this, ex.Message, "Install renderer", MessageBoxButton.OK, MessageBoxImage.Error); }
    }
    private static Button MakeButton(string text, Action action)
    {
        var button = new Button { Content = text, Margin = new Thickness(6,0,0,0), Padding = new Thickness(11,5,11,5) }; button.Click += (_, _) => action(); return button;
    }
    private static BitmapSource BitmapFrom(SKBitmap bitmap)
    {
        using var pixmap = bitmap.PeekPixels(); var size = checked(pixmap.RowBytes * pixmap.Height); var pixels = new byte[size]; Marshal.Copy(pixmap.GetPixels(), pixels, 0, size); var source = BitmapSource.Create(bitmap.Width, bitmap.Height, 96, 96, PixelFormats.Bgra32, null, pixels, pixmap.RowBytes); source.Freeze(); return source;
    }
}
