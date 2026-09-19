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

        var changelogPanel = new StackPanel { Spacing = 8 };
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "Built-in changelog",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.6 · Smart Card Animation",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• frame-addressed transparent card plates now animate the card shell itself\n• project artwork stays live through per-frame artwork destination + reveal clip geometry\n• title and description remain live jsparse fields\n• opening cards can play one source frame per renderer frame while steady cards hold the exact final plate\n• missing or duplicate numbered card frames are rejected instead of silently skipped",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.5 · Renderer frame accuracy",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Smart Badge sequences are frame-locked one-to-one when sequence FPS matches the renderer FPS\n• missing or duplicate numbered animation frames are rejected instead of silently skipped\n• Relationships cards now have source-style rounded geometry\n• artwork reveal gets the full-width measured reveal-edge shine rather than treating the effect as badge-only",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.4 · Fast native setup",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• removed the huge self-contained .NET wrapper around Setup.exe\n• native Velopack setup now opens directly and is smoke-tested for fast visible startup\n• setup fallbacks use direct process launch instead of Windows shell resolution, preventing Microsoft Store fallbacks\n• fast delta updates and no-OK automatic restart remain enabled",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.3 · No-block update restart",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• removed the blocking Velopack OK button from in-app package updates\n• Cubical Compare owns the visible download/staging progress\n• package apply runs without an acknowledgement dialog and restarts the app automatically\n• routine installed updates use BestSpeed delta packages when available, avoiding a full package download/rebuild\n• internal package version is no longer shown as the app version during the update flow",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.2 · Reliable automatic updater",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• automatically prefers verified Velopack .nupkg updates when the current install can use them\n• automatically falls back to the visible Setup.exe for installed copies\n• portable copies use ZIP only when it is the safer path\n• ZIP downloads retry, verify GitHub SHA-256 digests, fully stage before exit and roll back on copy failure\n• Updates now has one Install update action instead of a fixed Update from ZIP button",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.1 · Smart Features",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Smart Badge Animation frame sequences\n• bootanimation-style desc.txt / part folders inside Renderer v3 packages\n• jsparse replacement markers for header, value and unit fields\n• per-frame field rectangles, opacity and rotation for exact text placement\n• transparent clean badge plates only — project data stays live\n• built-in Settings changelog",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1 · Renderer Accuracy Pack",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "20 renderer-accuracy capabilities including source-region crops, native raster sizing, deterministic transforms, clip controls, dense-track timing and extended compositing.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });

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
        developerCard.Visibility = Visibility.Visible;

        // Put personalization before build diagnostics so Settings reads as user-first,
        // with Developer Options visible near the About/version area.
        var buildIndex = Math.Max(2, pageStack.Children.Count - 1);
        pageStack.Children.Insert(buildIndex, CreateSettingsCard(appearancePanel));
        pageStack.Children.Insert(buildIndex + 1, CreateSettingsCard(languagePanel));
        pageStack.Children.Insert(buildIndex + 2, CreateSettingsCard(smartFeaturesPanel));
        pageStack.Children.Insert(buildIndex + 3, CreateSettingsCard(changelogPanel));
        pageStack.Children.Insert(buildIndex + 4, developerCard);

        ConfigurePersonalizationSettings(colourModeCombo, appearanceSummary, languageCombo, languageSummary);
        ConfigureDeveloperUnlock(_currentVersionText, developerCard, developerStatus);
    }
}
