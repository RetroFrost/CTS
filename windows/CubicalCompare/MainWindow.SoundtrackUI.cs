using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private TextBlock? _soundtrackFileText;
    private bool _soundtrackUiInitialized;

    internal void InitializeSoundtrackControls()
    {
        if (_soundtrackUiInitialized) return;
        var transform = FindAncestor<Expander>(ImageScaleBox);
        if (transform?.Parent is not StackPanel stack)
        {
            DispatcherQueue.TryEnqueue(InitializeSoundtrackControls);
            return;
        }

        _soundtrackUiInitialized = true;
        _soundtrackFileText = new TextBlock { Text = "No soundtrack selected", FontSize = 11, Opacity = 0.62, TextWrapping = TextWrapping.Wrap };
        var choose = new Button { Content = "Choose audio…" };
        choose.Click += ChooseSoundtrack_Click;
        var clear = new Button { Content = "Clear" };
        clear.Click += ClearSoundtrack_Click;
        var volume = new Slider { Minimum = 0, Maximum = 100, Value = 100, StepFrequency = 1 };
        volume.ValueChanged += (_, e) => _soundtrackVolume = Math.Clamp(e.NewValue / 100.0, 0, 1);
        var loop = new CheckBox { Content = "Loop to video length", IsChecked = true };
        loop.Checked += (_, _) => _soundtrackLoop = true;
        loop.Unchecked += (_, _) => _soundtrackLoop = false;

        var panel = new StackPanel { Spacing = 7, Margin = new Thickness(0, 8, 0, 0) };
        panel.Children.Add(_soundtrackFileText);
        panel.Children.Add(choose);
        panel.Children.Add(clear);
        panel.Children.Add(new TextBlock { Text = "Volume", FontSize = 11, Opacity = 0.65 });
        panel.Children.Add(volume);
        panel.Children.Add(loop);

        var expander = new Expander { Header = "Soundtrack", Content = panel, IsExpanded = false };
        var i = stack.Children.IndexOf(transform);
        stack.Children.Insert(Math.Max(0, i + 1), expander);
    }

    private async void ChooseSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".mp3", ".wav", ".m4a", ".aac", ".wma", ".flac"]);
        if (file is null) return;
        _soundtrackPath = file.Path;
        if (_soundtrackFileText is not null) _soundtrackFileText.Text = file.Name;
        ExportStatusText.Text = $"Soundtrack · {file.Name}";
    }

    private void ClearSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        _soundtrackPath = string.Empty;
        if (_soundtrackFileText is not null) _soundtrackFileText.Text = "No soundtrack selected";
        ExportStatusText.Text = "Soundtrack cleared";
    }
}
