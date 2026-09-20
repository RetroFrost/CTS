using CubicalCompare.Core.MegaPack;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private string _soundtrackPath = string.Empty;
    private double _soundtrackVolume = 1.0;
    private bool _soundtrackLoop = true;

    private TextBlock? _soundtrackPathText;
    private Slider? _soundtrackVolumeSlider;
    private CheckBox? _soundtrackLoopCheckBox;
    private bool _soundtrackUiUpdating;
    private Zipack2ImportResult? _appliedMegaPackSoundtrack;
    private Grid? _audioPage;
    private TextBlock? _audioPagePathText;
    private TextBlock? _audioRendererAudioText;
    private Slider? _audioPageVolumeSlider;
    private CheckBox? _audioPageLoopCheckBox;

    internal void InitializeSoundtrackEditor()
    {
        if (_soundtrackPathText is not null)
            return;

        var transformExpander = FindAncestor<Expander>(ImageScaleBox);
        if (transformExpander?.Parent is not StackPanel inspectorStack)
            return;

        _soundtrackPathText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            MaxHeight = 42,
            Opacity = 0.6,
        };

        var chooseButton = new Button
        {
            Content = "Choose audio…",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        chooseButton.Click += ChooseSoundtrack_Click;

        var clearButton = new Button { Content = "Clear" };
        clearButton.Click += ClearSoundtrack_Click;

        var buttonGrid = new Grid { ColumnSpacing = 8 };
        buttonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        buttonGrid.Children.Add(chooseButton);
        Grid.SetColumn(clearButton, 1);
        buttonGrid.Children.Add(clearButton);

        _soundtrackVolumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            StepFrequency = 1,
            Value = 100,
        };
        _soundtrackVolumeSlider.ValueChanged += (_, args) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackVolume = Math.Clamp(args.NewValue / 100.0, 0, 1);
            ScheduleWorkspaceSave();
        };

        _soundtrackLoopCheckBox = new CheckBox
        {
            Content = "Loop soundtrack to video length",
            IsChecked = true,
        };
        _soundtrackLoopCheckBox.Checked += (_, _) => SetSoundtrackLoopFromUi(true);
        _soundtrackLoopCheckBox.Unchecked += (_, _) => SetSoundtrackLoopFromUi(false);

        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        panel.Children.Add(_soundtrackPathText);
        panel.Children.Add(buttonGrid);
        panel.Children.Add(new TextBlock { Text = "Volume", FontSize = 11, Opacity = 0.65 });
        panel.Children.Add(_soundtrackVolumeSlider);
        panel.Children.Add(_soundtrackLoopCheckBox);

        var expander = new Expander
        {
            Header = "Soundtrack",
            IsExpanded = false,
            Content = panel,
            Margin = new Thickness(0, 4, 0, 0),
        };

        inspectorStack.Children.Add(expander);
        Cards.CollectionChanged += (_, _) => ApplyPendingMegaPackSoundtrackIfProjectMatches();
        ApplyPendingMegaPackSoundtrackIfProjectMatches();
        RefreshSoundtrackUi();
    }

    internal Grid BuildAudioPage()
    {
        if (_audioPage is not null)
            return _audioPage;

        _audioPagePathText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };
        _audioRendererAudioText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        var chooseButton = new Button
        {
            Content = "Choose audio…",
            Padding = new Thickness(16, 7, 16, 7),
        };
        chooseButton.Click += ChooseSoundtrack_Click;

        var clearButton = new Button
        {
            Content = "Clear soundtrack",
            Padding = new Thickness(16, 7, 16, 7),
        };
        clearButton.Click += ClearSoundtrack_Click;

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
        };
        buttons.Children.Add(chooseButton);
        buttons.Children.Add(clearButton);

        _audioPageVolumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            StepFrequency = 1,
            Width = 420,
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        _audioPageVolumeSlider.ValueChanged += (_, args) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackVolume = Math.Clamp(args.NewValue / 100.0, 0, 1);
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };

        _audioPageLoopCheckBox = new CheckBox
        {
            Content = "Loop soundtrack to video length",
        };
        _audioPageLoopCheckBox.Checked += (_, _) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackLoop = true;
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };
        _audioPageLoopCheckBox.Unchecked += (_, _) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackLoop = false;
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };

        var soundtrackPanel = new StackPanel { Spacing = 10 };
        soundtrackPanel.Children.Add(new TextBlock
        {
            Text = "Soundtrack",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        soundtrackPanel.Children.Add(_audioPagePathText);
        soundtrackPanel.Children.Add(buttons);
        soundtrackPanel.Children.Add(new TextBlock
        {
            Text = "Volume",
            FontSize = 12,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        soundtrackPanel.Children.Add(_audioPageVolumeSlider);
        soundtrackPanel.Children.Add(_audioPageLoopCheckBox);

        var rendererAudioPanel = new StackPanel { Spacing = 8 };
        rendererAudioPanel.Children.Add(new TextBlock
        {
            Text = "Renderer audio",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        rendererAudioPanel.Children.Add(_audioRendererAudioText);

        var stack = new StackPanel
        {
            Spacing = 14,
            MaxWidth = 900,
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        stack.Children.Add(new TextBlock
        {
            Text = "Audio",
            FontSize = 30,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        stack.Children.Add(new TextBlock
        {
            Text = "Choose the soundtrack used for preview/export. Renderer-embedded audio is shown separately so it is never hidden inside the inspector.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        stack.Children.Add(CreateAudioCard(soundtrackPanel));
        stack.Children.Add(CreateAudioCard(rendererAudioPanel));

        _audioPage = new Grid { Visibility = Visibility.Collapsed };
        _audioPage.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = stack,
        });

        RefreshSoundtrackUi();
        return _audioPage;
    }

    private static Border CreateAudioCard(UIElement content) => new()
    {
        Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorSurfaceRaisedBrush"],
        BorderBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorBorderBrush"],
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(14),
        Padding = new Thickness(18),
        Child = content,
    };

    private async void ChooseSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".mp3", ".wav", ".m4a", ".aac", ".wma"]);
        if (file is null)
            return;

        _soundtrackPath = file.Path;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();
        TimelineStatusText.Text = $"Soundtrack set · {file.Name}";
    }

    private void ClearSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        _soundtrackPath = string.Empty;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();
        TimelineStatusText.Text = "Soundtrack cleared";
    }

    private void SetSoundtrackLoopFromUi(bool value)
    {
        if (_soundtrackUiUpdating) return;
        _soundtrackLoop = value;
        ScheduleWorkspaceSave();
    }

    private void ApplyPendingMegaPackSoundtrackIfProjectMatches()
    {
        var pack = _pendingZipack2;
        if (pack is null || ReferenceEquals(pack, _appliedMegaPackSoundtrack) || Cards.Count == 0)
            return;

        if (!Cards.Any(card => IsInsideDirectory(card.ImagePath, pack.ExtractionDirectory)))
            return;

        _appliedMegaPackSoundtrack = pack;
        _soundtrackPath = pack.SoundtrackPath;
        _soundtrackLoop = pack.SoundtrackLoop;
        _soundtrackVolume = double.IsFinite(pack.SoundtrackVolume) ? Math.Clamp(pack.SoundtrackVolume, 0, 1) : 1.0;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();

        if (!string.IsNullOrWhiteSpace(_soundtrackPath))
            TimelineStatusText.Text = $"MegaPack soundtrack loaded · {Path.GetFileName(_soundtrackPath)}";
    }

    private static bool IsInsideDirectory(string filePath, string directoryPath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || string.IsNullOrWhiteSpace(directoryPath)) return false;
        try
        {
            var directory = Path.GetFullPath(directoryPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                + Path.DirectorySeparatorChar;
            var file = Path.GetFullPath(filePath);
            return file.StartsWith(directory, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private void RefreshSoundtrackUi()
    {
        if (_soundtrackPathText is null)
            return;

        _soundtrackUiUpdating = true;
        try
        {
            _soundtrackPathText.Text = string.IsNullOrWhiteSpace(_soundtrackPath)
                ? "No soundtrack selected. Export will contain video only."
                : File.Exists(_soundtrackPath)
                    ? _soundtrackPath
                    : $"Missing audio file: {_soundtrackPath}";

            if (_soundtrackVolumeSlider is not null)
                _soundtrackVolumeSlider.Value = Math.Clamp(_soundtrackVolume * 100.0, 0, 100);
            if (_soundtrackLoopCheckBox is not null)
                _soundtrackLoopCheckBox.IsChecked = _soundtrackLoop;

            if (_audioPagePathText is not null)
                _audioPagePathText.Text = string.IsNullOrWhiteSpace(_soundtrackPath)
                    ? "No soundtrack selected. Export will contain renderer audio only when the loaded renderer provides it."
                    : File.Exists(_soundtrackPath)
                        ? _soundtrackPath
                        : $"Missing audio file: {_soundtrackPath}";
            if (_audioPageVolumeSlider is not null)
                _audioPageVolumeSlider.Value = Math.Clamp(_soundtrackVolume * 100.0, 0, 100);
            if (_audioPageLoopCheckBox is not null)
                _audioPageLoopCheckBox.IsChecked = _soundtrackLoop;

            if (_audioRendererAudioText is not null)
            {
                var embedded = _legacyRenderer?.EmbeddedAudio;
                _audioRendererAudioText.Text = embedded is null
                    ? "The loaded renderer does not provide embedded audio."
                    : $"Embedded renderer audio: {embedded.AssetName} · {embedded.MimeType}. A selected project soundtrack remains independently controllable here.";
            }
        }
        finally
        {
            _soundtrackUiUpdating = false;
        }
    }
}
