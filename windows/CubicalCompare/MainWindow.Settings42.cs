using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private void Initialize42SettingsEnhancements()
    {
        if (_settingsPage is null || _currentVersionText is null)
            return;

        var scroll = _settingsPage.Children.OfType<ScrollViewer>().FirstOrDefault();
        if (scroll?.Content is not StackPanel pageStack)
            return;

        var appearanceSummary = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };
        var colourModeCombo = new ComboBox
        {
            MinWidth = 280,
            HorizontalAlignment = HorizontalAlignment.Left,
        };

        var appearancePanel = new StackPanel { Spacing = 9 };
        appearancePanel.Children.Add(new TextBlock
        {
            Text = EnglishText("Appearance & color", "Appearance & colour"),
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        appearancePanel.Children.Add(appearanceSummary);
        appearancePanel.Children.Add(colourModeCombo);

        var languageSummary = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };
        var languageCombo = new ComboBox
        {
            MinWidth = 280,
            HorizontalAlignment = HorizontalAlignment.Left,
        };

        var languagePanel = new StackPanel { Spacing = 9 };
        languagePanel.Children.Add(new TextBlock
        {
            Text = "English variation",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        languagePanel.Children.Add(languageSummary);
        languagePanel.Children.Add(languageCombo);

        var developerStatus = new TextBlock
        {
            Text = "Developer Options are enabled.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };
        var developerPanel = new StackPanel { Spacing = 9 };
        developerPanel.Children.Add(new TextBlock
        {
            Text = "Developer Options",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        developerPanel.Children.Add(developerStatus);
        developerPanel.Children.Add(new TextBlock
        {
            Text = "Style & Model can compile one or many .cs files into a live runtime bundle. Imported code runs with Cubical Compare's process permissions.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });

        var developerCard = CreateSettingsCard(developerPanel);
        developerCard.Visibility = Visibility.Collapsed;

        // Put personalization before build diagnostics so Settings reads as user-first,
        // while the hidden developer card remains close to the About/version area.
        var buildIndex = Math.Max(2, pageStack.Children.Count - 1);
        pageStack.Children.Insert(buildIndex, CreateSettingsCard(appearancePanel));
        pageStack.Children.Insert(buildIndex + 1, CreateSettingsCard(languagePanel));
        pageStack.Children.Insert(buildIndex + 2, developerCard);

        ConfigurePersonalizationSettings(colourModeCombo, appearanceSummary, languageCombo, languageSummary);
        ConfigureDeveloperUnlock(_currentVersionText, developerCard, developerStatus);
    }
}
