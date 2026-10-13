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

        var smartFeaturesPanel = new StackPanel { Spacing = 9 };
        smartFeaturesPanel.Children.Add(new TextBlock
        {
            Text = "Smart Features",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        smartFeaturesPanel.Children.Add(new TextBlock
        {
            Text = "Smart Badge Animation",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        smartFeaturesPanel.Children.Add(new TextBlock
        {
            Text = "Renderer v3 can play transparent badge-only frame sequences using an Android bootanimation-style desc.txt + part folders. A smart.json sidecar marks every replaceable badge field with the exact token “jsparse”, so Cubical Compare replaces the marker fields with the current card's header, value and unit while preserving the reference badge animation frames.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        smartFeaturesPanel.Children.Add(new TextBlock
        {
            Text = "Safety rule: runtime sequence frames must be clean/empty text plates with transparent backgrounds. Calibration frames may contain jsparse markers, but source text is never composited into exported project data.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });

        var buildIndex = 2;
        pageStack.Children.Insert(buildIndex, CreateSettingsCard(appearancePanel));
        pageStack.Children.Insert(buildIndex + 1, CreateSettingsCard(languagePanel));
        pageStack.Children.Insert(buildIndex + 2, CreateSettingsCard(smartFeaturesPanel));

        ConfigurePersonalizationSettings(colourModeCombo, appearanceSummary, languageCombo, languageSummary);
    }
}
