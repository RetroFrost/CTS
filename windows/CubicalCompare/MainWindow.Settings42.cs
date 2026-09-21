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
            Text = "4.2.1.25 · Installer lock + progress fix",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Setup immediately moves its own current working directory out of the Cubical Compare install tree so it cannot lock the folder Velopack needs to rename\n• closes the full Cubical Compare process tree, install-resident helpers, and stale setup engines before replacement\n• prevents multiple Cubical Compare setup instances from racing each other\n• installer progress is now determinate and visibly advances through extraction, shutdown/repair, installation, and relaunch\n• CI launches Setup.exe with the install folder as its working directory and a background helper running from that folder, then requires setup to release the locks and finish successfully",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.24 · Installer repair",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Setup.exe now closes a running Cubical Compare before the embedded installer replaces files\n• detects and quarantines the malformed current/current + copied .portable/Update.exe layout produced by older ZIP updates before invoking Velopack\n• restores the previous current/ directory automatically if installation fails after quarantine\n• installer errors now include the real engine exit code and Velopack log path instead of only saying the engine returned a failure code\n• CI now tests Setup.exe with Cubical Compare already running and a deliberately damaged old install tree",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.23 · ZIP recovery completion",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• damaged installs now repair from the verified GitHub portable ZIP first instead of depending on the installer fallback\n• keeps the canonical current/ payload and sq.version repair path from 4.2.1.22\n• Setup.exe remains available only when no compatible portable repair ZIP exists",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.22 · Update system recovery",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• fixes GitHub/local ZIP updates selecting the outer Velopack portable bundle instead of its real current/ app payload\n• ZIP updates now replace the canonical current directory and automatically flatten the broken current/current layout created by 4.2.1.20/21\n• sq.version is preserved/restored from the real portable payload so Velopack package updates keep working\n• damaged installed copies automatically prefer the visible Setup.exe recovery path instead of repeatedly choosing a ZIP that cannot repair install metadata\n• Update from ZIP can re-apply the current GitHub release as a repair operation even when no newer version exists",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.21 · Renderer runtime compatibility",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• SmartCard text can render outside the artwork reveal clip without losing title/description fields\n• SmartBadge v2 understands top-to-final entry motion and final-X anchoring, preventing later badges from drifting sideways\n• settledHold is recognized and keeps the final completed badge frame stable\n• per-card SmartBadge placement/sequence overrides are supported without duplicating packs\n• renderer compatibility now recognizes the new SmartCard/SmartBadge feature contracts instead of rejecting valid .renderer3 packages",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.20 · Live playback + GitHub ZIP updates",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Play preview now uses one latest-frame render loop instead of spawning overlapping renderer jobs on every 60 FPS slider tick\n• slow frames are skipped cleanly while the timeline clock stays real-time, so Play no longer freezes the preview\n• Update from ZIP now offers Latest from GitHub or a local ZIP; GitHub mode fetches the portable release asset directly, verifies/stages it, then restarts normally\n• local ZIP updating remains available with the same validation, staging and rollback path",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.19 · Renderer load preview fix",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• loading a valid Renderer v3 no longer always forces the preview to frame 0\n• scene renderers now open on their first authored visible/checkpoint frame, so blank intro frames do not look like a failed renderer load\n• the current timeline position and renderer status now show which initial frame was selected",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.18 · Renderer version compatibility fix",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• renderer minAppVersion checks now use the version of the running Cubical Compare application instead of a stale hardcoded compatibility constant\n• the renderer module reads the entry application's informational/file/assembly version at runtime, so Setup, portable and updated builds stay in sync automatically\n• compatibility errors now report the detected running app version",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.17 · Preview interaction hotfix",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• resize/rotate modes are now chosen from the visible handle geometry instead of routed OriginalSource/template ancestry\n• clicking normal artwork always starts Move, not Scale\n• selecting a card directly in the rendered frame no longer seeks backward to that card's representative timeline frame\n• list selection still keeps the intentional jump-to-card navigation behavior",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.16 · Immersive preview editor",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• normal mouse-up no longer falls through PointerCaptureLost and restores the old image transform\n• drag/resize/rotate use a latest-state render loop, so the real full renderer frame stays visible and catches up continuously while editing\n• SmartCard and SmartBadge fields expose their authored frame geometry to the editor instead of using generic guessed rectangles\n• inline text editing is borderless/transparent, inherits renderer alignment/rotation/size, and temporarily hides only the rendered glyphs underneath\n• SmartCard-local image coordinates are converted correctly, so movement no longer jumps when a renderer scales its live artwork area",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.15 · Audio + universal direct editing",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Audio is now a real primary navigation tab with soundtrack selection, volume, looping and renderer-audio status\n• direct preview hit testing uses renderer-specific geometry for ribbon, Infinite Timeline, Relationships and Renderer v3 cards\n• move, resize and rotate update the live rendered preview while dragging instead of only moving a ghost overlay\n• in-place text editing uses a transparent borderless editor so the preview remains visible underneath",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.14 · Description block fix",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• description direct-edit hit testing now follows the actual wrapped text lines instead of the entire remaining card block\n• the inline description editor only covers the text itself, so empty space no longer steals artwork clicks\n• transformed artwork remains selectable around the description text region",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.13 · Direct preview editing",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• click visible title, description, badge header or badge value text in the rendered preview to edit it in place with a real caret\n• click artwork itself to select and drag it immediately; corner handles resize and the rotate handle transforms it directly\n• Apply to all images is available beside the selected preview artwork and copies position, scale, rotation, crop and layer to every card\n• preview hit-testing now follows the renderer's measured visible card positions instead of requiring a hidden right-click mode",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.12 · Source-exact track scaling",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• removed the old 256-track ceiling that rejected large source-exact renderers\n• renderer tracks now support up to 65,536 independent targets\n• dense tracks can carry up to 65,536 keyframes per target\n• duplicate targets, frame ordering and finite-value validation remain enforced",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.11 · ZIP updates + large Smart Features",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• Update from ZIP is back in Settings and stages local portable releases with rollback + automatic restart\n• portable ZIP extraction is validated and progress-aware\n• Renderer v3 packages and Smart Feature packs now allow up to 65,536 file assets instead of the old 2,048/1,024 limits\n• duplicate paths and expanded-size limits still protect malformed ZIPs",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.10 · SmartCard exact-ribbon proxy",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• proxy renderer scenes can keep SmartCard objects while the wrapped ribbon engine owns exact card positions and scroll timing\n• ribbon cards can now be replaced by SmartCard base + live artwork/text + overlay shine\n• SmartBadge v2 remains layered above SmartCards\n• live content reveal can follow measured card clipping without clipping away the post-content shine",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.9 · SmartCard layered animation",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• SmartCard now renders base shell → live artwork → live title/description → overlay shine\n• shine/glass overlays finally affect the live artwork and text instead of sitting underneath them\n• smart-card-layered-compositing-v1 validates synchronized overlay frames\n• SmartCard authoring uses base/ + overlay/ frame folders with exact contiguous frame validation",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.7 · SmartBadge v2",
            FontSize = 14,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "• one renderer can embed multiple badge-animation ZIPs and choose one per card\n• nested badge packs keep value/unit/header live through jsparse\n• shine/glass overlay frames now composite after live text, so the shine carries across the text too\n• v3 packages can wrap exact ribbon renderers while adding SmartBadge v2 sidecars\n• nested ZIPs and numbered frame continuity are validated before use",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        changelogPanel.Children.Add(new TextBlock
        {
            Text = "4.2.1.6 · Smart Card Animation",
            FontSize = 14,
            Margin = new Thickness(0, 4, 0, 0),
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
