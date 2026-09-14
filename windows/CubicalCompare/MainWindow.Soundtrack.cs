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
        RefreshSoundtrackUi();
    }

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
        }
        finally
        {
            _soundtrackUiUpdating = false;
        }
    }
}
