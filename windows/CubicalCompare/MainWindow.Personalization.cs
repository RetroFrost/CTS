using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.UI;
using Windows.UI.ViewManagement;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const string DynamicColourPreference = "appearance.dynamicColour";
    private const string EnglishVariantPreference = "language.englishVariant";

    private readonly UISettings _uiSettings = new();
    private bool _personalizationInitialized;
    private bool _dynamicColouring = AppPreferences.GetBool(DynamicColourPreference, true);
    private string _englishVariant = AppPreferences.GetString(EnglishVariantPreference, "en-GB");
    private TextBlock? _appearanceSettingSummary;
    private TextBlock? _languageSettingSummary;

    internal void InitializePersonalization()
    {
        if (_personalizationInitialized) return;
        _personalizationInitialized = true;

        ApplyDynamicColouring();
        _uiSettings.ColorValuesChanged += UiSettings_ColorValuesChanged;
        Closed += (_, _) => _uiSettings.ColorValuesChanged -= UiSettings_ColorValuesChanged;
    }

    private void ConfigurePersonalizationSettings(
        ComboBox colourModeCombo,
        TextBlock appearanceSummary,
        ComboBox englishVariantCombo,
        TextBlock languageSummary)
    {
        _appearanceSettingSummary = appearanceSummary;
        _languageSettingSummary = languageSummary;

        colourModeCombo.Items.Clear();
        colourModeCombo.Items.Add(new ComboBoxItem { Content = EnglishText("Dynamic Windows color", "Dynamic Windows colour"), Tag = "dynamic" });
        colourModeCombo.Items.Add(new ComboBoxItem { Content = EnglishText("Cubical blue", "Cubical blue"), Tag = "blue" });
        colourModeCombo.SelectedIndex = _dynamicColouring ? 0 : 1;
        colourModeCombo.SelectionChanged += (_, _) =>
        {
            _dynamicColouring = colourModeCombo.SelectedIndex == 0;
            AppPreferences.SetBool(DynamicColourPreference, _dynamicColouring);
            ApplyDynamicColouring();
            RefreshPersonalizationSettingText();
        };

        var variants = new[]
        {
            ("English (United Kingdom)", "en-GB"),
            ("English (United States)", "en-US"),
            ("English (Australia)", "en-AU"),
            ("English (Canada)", "en-CA"),
            ("English (New Zealand)", "en-NZ"),
            ("English (Ireland)", "en-IE"),
        };
        foreach (var (label, tag) in variants)
            englishVariantCombo.Items.Add(new ComboBoxItem { Content = label, Tag = tag });

        var selected = Array.FindIndex(variants, pair => pair.Item2.Equals(_englishVariant, StringComparison.OrdinalIgnoreCase));
        englishVariantCombo.SelectedIndex = selected >= 0 ? selected : 0;
        englishVariantCombo.SelectionChanged += (_, _) =>
        {
            if (englishVariantCombo.SelectedItem is not ComboBoxItem item || item.Tag is not string tag) return;
            _englishVariant = tag;
            AppPreferences.SetString(EnglishVariantPreference, tag);
            RefreshPersonalizationSettingText();
            ApplyEnglishVariantToShell();
        };

        RefreshPersonalizationSettingText();
        ApplyEnglishVariantToShell();
    }

    private void UiSettings_ColorValuesChanged(UISettings sender, object args)
    {
        if (!_dynamicColouring) return;
        DispatcherQueue.TryEnqueue(ApplyDynamicColouring);
    }

    private void ApplyDynamicColouring()
    {
        var accent = _dynamicColouring
            ? _uiSettings.GetColorValue(UIColorType.Accent)
            : Color.FromArgb(255, 15, 108, 189);

        SetResourceColor("EditorAccentBrush", accent);
        SetResourceColor("EditorAccentSoftBrush", Mix(accent, Colors.White, 0.82));
        SetResourceColor("EditorBorderStrongBrush", Mix(accent, Colors.White, 0.72));
        SetResourceColor("EditorSurfaceSoftBrush", WithAlpha(Mix(accent, Colors.White, 0.91), 224));
        Application.Current.Resources["SystemAccentColor"] = accent;
    }

    private static void SetResourceColor(string key, Color color)
    {
        if (Application.Current.Resources.TryGetValue(key, out var value) && value is SolidColorBrush brush)
            brush.Color = color;
        else
            Application.Current.Resources[key] = new SolidColorBrush(color);
    }

    private static Color Mix(Color a, Color b, double amountB)
    {
        var p = Math.Clamp(amountB, 0, 1);
        return Color.FromArgb(
            255,
            (byte)Math.Round(a.R + (b.R - a.R) * p),
            (byte)Math.Round(a.G + (b.G - a.G) * p),
            (byte)Math.Round(a.B + (b.B - a.B) * p));
    }

    private static Color WithAlpha(Color color, byte alpha) => Color.FromArgb(alpha, color.R, color.G, color.B);

    private string EnglishText(string american, string commonwealth) =>
        _englishVariant.Equals("en-US", StringComparison.OrdinalIgnoreCase) ? american : commonwealth;

    private void RefreshPersonalizationSettingText()
    {
        if (_appearanceSettingSummary is not null)
        {
            _appearanceSettingSummary.Text = _dynamicColouring
                ? EnglishText(
                    "Follows the Windows accent color live and recolors interactive surfaces without restarting.",
                    "Follows the Windows accent colour live and recolours interactive surfaces without restarting.")
                : EnglishText(
                    "Uses Cubical Compare's fixed blue color.",
                    "Uses Cubical Compare's fixed blue colour.");
        }

        if (_languageSettingSummary is not null)
        {
            _languageSettingSummary.Text =
                $"UI English variant: {_englishVariant}. Spelling and regional wording update immediately.";
        }
    }

    private void ApplyEnglishVariantToShell()
    {
        var subtitle = EnumerateVisualDescendants(AppTitleBar)
            .OfType<TextBlock>()
            .FirstOrDefault(text => text.FontSize == 11 && text.Text.Contains("comparison", StringComparison.OrdinalIgnoreCase));
        if (subtitle is not null)
            subtitle.Text = EnglishText("Create polished comparison videos", "Create polished comparison videos");

        if (_updateStatusText is not null && _availableUpdate is null)
            _updateStatusText.Text = EnglishText(
                "Updates use official GitHub Release assets. Generated source-code ZIPs are never selected.",
                "Updates use official GitHub Release assets. Generated source-code ZIPs are never selected.");
    }
}
