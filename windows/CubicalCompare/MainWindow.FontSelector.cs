using System.Security.Cryptography;
using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using SkiaSharp;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private readonly List<RenderFontChoice> _renderFontChoices = [];
    private ComboBox? _renderFontComboBox;
    private TextBlock? _renderFontStatusText;
    private bool _renderFontUiUpdating;
    private bool _renderFontSelectorInitialized;
    private bool _renderFontSystemLoaded;
    private bool _renderFontRetryHooked;
    private long _renderFontScanRevision;
    private Flyout? _floatingFontFlyout;
    private ComboBox? _floatingFontComboBox;
    private TextBlock? _floatingFontStatusText;
    private bool _floatingFontPickerInitialized;

    internal void InitializeFontSelector()
    {
        InitializeFloatingFontPicker();
        if (_renderFontSelectorInitialized)
            return;

        var transformExpander = FindFontAncestor<Expander>(ImageScaleBox);
        if (transformExpander?.Parent is not StackPanel inspectorStack)
        {
            // Never requeue ourselves immediately. Before the visual tree is realised that creates
            // an endless dispatcher loop which can starve WinUI before the first frame is painted.
            // Wait for the NavigationView to finish loading, then try exactly once more.
            if (!_renderFontRetryHooked)
            {
                _renderFontRetryHooked = true;
                RootNavigation.Loaded += FontSelector_RootNavigationLoaded;
            }
            return;
        }

        _renderFontRetryHooked = false;
        RootNavigation.Loaded -= FontSelector_RootNavigationLoaded;
        _renderFontSelectorInitialized = true;

        _renderFontComboBox = new ComboBox
        {
            HorizontalAlignment = HorizontalAlignment.Stretch,
            PlaceholderText = "Select an installed Windows font",
            MaxDropDownHeight = 520,
        };
        _renderFontComboBox.SelectionChanged += RenderFontComboBox_SelectionChanged;

        _renderFontStatusText = new TextBlock
        {
            Text = "Open this section to load installed fonts.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.62,
            FontSize = 11,
        };

        var chooseFile = new Button
        {
            Content = "Choose font file…",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        chooseFile.Click += ChooseRenderFontFile_Click;

        var refresh = new Button
        {
            Content = "Refresh system fonts",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        refresh.Click += async (_, _) => await LoadSystemFontsAsync(force: true);

        var buttons = new Grid { ColumnSpacing = 8 };
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttons.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttons.Children.Add(chooseFile);
        Grid.SetColumn(refresh, 1);
        buttons.Children.Add(refresh);

        var useDefault = new Button
        {
            Content = "Use Nexa / system fallback",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        useDefault.Click += (_, _) => RenderFontSelection.Apply("Nexa", string.Empty);

        var panel = new StackPanel
        {
            Spacing = 8,
            Margin = new Thickness(0, 8, 0, 0),
        };
        panel.Children.Add(new TextBlock
        {
            Text = "Installed font families load only when this panel is opened, so app startup stays fast. Choose a font file when you need an exact face such as a specific ExtraBold weight.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 11,
            Opacity = 0.58,
        });
        panel.Children.Add(_renderFontComboBox);
        panel.Children.Add(_renderFontStatusText);
        panel.Children.Add(buttons);
        panel.Children.Add(useDefault);

        var expander = new Expander
        {
            Header = "Render font",
            IsExpanded = false,
            Content = panel,
            Margin = new Thickness(0, 4, 0, 0),
        };
        expander.Tapped += (_, _) =>
        {
            if (!_renderFontSystemLoaded)
                _ = LoadSystemFontsAsync(force: false);
        };
        inspectorStack.Children.Add(expander);

        RefreshRenderFontUi();
    }

    private void InitializeFloatingFontPicker()
    {
        if (_floatingFontPickerInitialized) return;
        _floatingFontPickerInitialized = true;

        _floatingFontComboBox = new ComboBox
        {
            PlaceholderText = "Choose Windows render font",
            HorizontalAlignment = HorizontalAlignment.Stretch,
            MaxDropDownHeight = 440,
        };
        _floatingFontComboBox.SelectionChanged += FloatingFontSelectionChanged;

        _floatingFontStatusText = new TextBlock
        {
            Text = "Current render font · loading on first open",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.75,
            FontSize = 12
        };

        var chooseFile = new Button
        {
            Content = "Import .ttf / .otf / .ttc",
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        chooseFile.Click += ChooseRenderFontFile_Click;

        var rescan = new Button
        {
            Content = "Refresh installed fonts",
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        rescan.Click += async (_, _) => await LoadSystemFontsAsync(force: true);

        var reset = new Button
        {
            Content = "Use Nexa / system fallback",
            HorizontalAlignment = HorizontalAlignment.Stretch
        };
        reset.Click += (_, _) => RenderFontSelection.Apply("Nexa", string.Empty);

        var choices = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8 };
        choices.Children.Add(chooseFile);
        choices.Children.Add(rescan);

        var panel = new StackPanel
        {
            Width = 425,
            MaxWidth = 425,
            Spacing = 11,
            Padding = new Thickness(12)
        };
        panel.Children.Add(new TextBlock
        {
            Text = "Render font",
            FontSize = 19,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold
        });
        panel.Children.Add(new TextBlock
        {
            Text = "Changes apply to renderer output and thumbnails. All installed fonts and imported files are supported.",
            TextWrapping = TextWrapping.Wrap,
            FontSize = 12,
            Opacity = 0.7
        });
        panel.Children.Add(_floatingFontComboBox);
        panel.Children.Add(_floatingFontStatusText);
        panel.Children.Add(choices);
        panel.Children.Add(reset);

        _floatingFontFlyout = new Flyout { Content = panel };
        RenderFontSelection.Changed += RenderFontSelection_Changed;
        Closed += (_, _) => RenderFontSelection.Changed -= RenderFontSelection_Changed;
        RefreshFloatingFontUi();
    }

    private void OpenFloatingFontPicker_Click(object sender, RoutedEventArgs e)
    {
        InitializeFloatingFontPicker();
        RefreshFloatingFontUi();
        if (_floatingFontFlyout is not null)
            _floatingFontFlyout.ShowAt(FloatingFontsButton);

        if (!_renderFontSystemLoaded)
            _ = LoadSystemFontsAsync(force: false);
    }

    private void FloatingFontSelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_renderFontUiUpdating || _floatingFontComboBox is null) return;
        var index = _floatingFontComboBox.SelectedIndex;
        if (index < 0 || index >= _renderFontChoices.Count) return;
        var choice = _renderFontChoices[index];
        RenderFontSelection.Apply(choice.FamilyName, choice.FilePath);
        TimelineStatusText.Text = $"Render font · {choice.DisplayName}";
    }

    private void RefreshFloatingFontUi()
    {
        if (_floatingFontComboBox is null || _floatingFontStatusText is null) return;

        var currentFamily = RenderFontSelection.CurrentFamily;
        var currentFile = RenderFontSelection.CurrentFile;
        var selectionIndex = string.IsNullOrWhiteSpace(currentFile)
            ? _renderFontChoices.FindIndex(x => string.IsNullOrWhiteSpace(x.FilePath)
                && x.FamilyName.Equals(currentFamily, StringComparison.CurrentCultureIgnoreCase))
            : _renderFontChoices.FindIndex(x => !string.IsNullOrWhiteSpace(x.FilePath)
                && x.FilePath.Equals(currentFile, StringComparison.OrdinalIgnoreCase));
        _renderFontUiUpdating = true;
        try
        {
            _floatingFontComboBox.SelectedIndex = selectionIndex;
        }
        finally { _renderFontUiUpdating = false; }

        _floatingFontStatusText.Text = _renderFontSystemLoaded
            ? $"{_renderFontChoices.Count} font choices · current: {currentFamily}"
            : $"Current: {currentFamily} · fonts load when opened";
    }

    private void FontSelector_RootNavigationLoaded(object sender, RoutedEventArgs e)
    {
        RootNavigation.Loaded -= FontSelector_RootNavigationLoaded;
        _renderFontRetryHooked = false;
        InitializeFontSelector();
    }

    private async Task LoadSystemFontsAsync(bool force)
    {
        if (!_renderFontSelectorInitialized && !_floatingFontPickerInitialized)
            return;
        if (_renderFontSystemLoaded && !force)
            return;

        var revision = Interlocked.Increment(ref _renderFontScanRevision);
        if (_renderFontStatusText is not null)
            _renderFontStatusText.Text = force ? "Refreshing installed fonts…" : "Loading installed fonts…";

        List<RenderFontChoice> choices;
        try
        {
            choices = await Task.Run(EnumerateSystemFontsFast);
        }
        catch (Exception ex)
        {
            App.WriteLog("System font scan failed", ex);
            if (_renderFontStatusText is not null)
                _renderFontStatusText.Text = $"Could not read Windows fonts: {ex.Message}";
            if (_floatingFontStatusText is not null)
                _floatingFontStatusText.Text = $"Could not load Windows fonts: {ex.Message}";
            return;
        }

        if (revision != Interlocked.Read(ref _renderFontScanRevision))
            return;

        _renderFontChoices.Clear();
        _renderFontChoices.AddRange(choices);
        _renderFontSystemLoaded = true;

        var currentFile = RenderFontSelection.CurrentFile;
        if (!string.IsNullOrWhiteSpace(currentFile) && File.Exists(currentFile))
        {
            var custom = CreateFontChoice(currentFile);
            if (custom is not null)
                EnsureFontChoiceVisible(custom, rebind: false);
        }

        RebindFontChoices();
        RefreshRenderFontUi();

        if (_renderFontStatusText is not null && string.IsNullOrWhiteSpace(RenderFontSelection.CurrentFile))
        {
            _renderFontStatusText.Text = _renderFontChoices.Count == 0
                ? "Windows did not report any installed font families. You can still choose a .ttf/.otf/.ttc file."
                : $"{_renderFontChoices.Count} installed font families ready · current: {RenderFontSelection.CurrentFamily}";
        }
    }

    private static List<RenderFontChoice> EnumerateSystemFontsFast()
    {
        var families = SKFontManager.Default.FontFamilies ?? Array.Empty<string>();
        return families
            .Where(x => !string.IsNullOrWhiteSpace(x))
            .Select(x => x.Trim())
            .Distinct(StringComparer.CurrentCultureIgnoreCase)
            .OrderBy(x => x, StringComparer.CurrentCultureIgnoreCase)
            .Select(x => new RenderFontChoice(x, string.Empty, 400, SKFontStyleSlant.Upright, "System"))
            .ToList();
    }

    private async void ChooseRenderFontFile_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".ttf", ".otf", ".ttc"]);
        if (file is null)
            return;

        try
        {
            var sourceChoice = CreateFontChoice(file.Path)
                ?? throw new InvalidDataException("The selected file is not a readable OpenType/TrueType font.");

            var extension = Path.GetExtension(file.Path).ToLowerInvariant();
            var bytes = await File.ReadAllBytesAsync(file.Path);
            var digest = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
            var folder = global::CubicalCompare.Core.Project.AppDataPaths.FontsDirectory; // Works for unpackaged WinUI, and keeps V5 fonts separate from V4.
            Directory.CreateDirectory(folder);
            var localPath = Path.Combine(folder, $"font-{digest[..20]}{extension}");
            if (!File.Exists(localPath))
                await File.WriteAllBytesAsync(localPath, bytes);

            var localChoice = CreateFontChoice(localPath) ?? sourceChoice with { FilePath = localPath };
            EnsureFontChoiceVisible(localChoice);
            RenderFontSelection.Apply(localChoice.FamilyName, localChoice.FilePath);
            TimelineStatusText.Text = $"Render font · {localChoice.DisplayName}";
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not use render font", ex);
            await ShowErrorAsync("Could not use font", ex.Message);
        }
    }

    private void RenderFontComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_renderFontUiUpdating || _renderFontComboBox is null)
            return;

        var index = _renderFontComboBox.SelectedIndex;
        if (index < 0 || index >= _renderFontChoices.Count)
            return;

        var choice = _renderFontChoices[index];
        RenderFontSelection.Apply(choice.FamilyName, choice.FilePath);
        TimelineStatusText.Text = $"Render font · {choice.DisplayName}";
    }

    private void RenderFontSelection_Changed(object? sender, EventArgs e)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            RefreshRenderFontUi();
            if (!_restoringWorkspace)
            {
                ScheduleThumbnailRefresh();
                ScheduleWorkspaceSave();
            }

            if (_legacyRenderer is not null)
            {
                RefreshTimelineRange();
                await RenderCurrentFrameAsync();
            }
        });
    }

    private void RefreshRenderFontUi()
    {
        RefreshFloatingFontUi();
        if (_renderFontComboBox is null || _renderFontStatusText is null)
            return;

        var currentFile = RenderFontSelection.CurrentFile;
        var currentFamily = RenderFontSelection.CurrentFamily;

        if (!string.IsNullOrWhiteSpace(currentFile) && File.Exists(currentFile))
        {
            var currentChoice = CreateFontChoice(currentFile);
            if (currentChoice is not null)
                EnsureFontChoiceVisible(currentChoice);
        }

        _renderFontUiUpdating = true;
        try
        {
            var selectedIndex = !string.IsNullOrWhiteSpace(currentFile)
                ? _renderFontChoices.FindIndex(x =>
                    !string.IsNullOrWhiteSpace(x.FilePath)
                    && x.FilePath.Equals(currentFile, StringComparison.OrdinalIgnoreCase))
                : _renderFontChoices.FindIndex(x =>
                    string.IsNullOrWhiteSpace(x.FilePath)
                    && x.FamilyName.Equals(currentFamily, StringComparison.CurrentCultureIgnoreCase));

            _renderFontComboBox.SelectedIndex = selectedIndex;

            if (selectedIndex >= 0)
            {
                var choice = _renderFontChoices[selectedIndex];
                _renderFontStatusText.Text = $"Current: {choice.DisplayName}";
            }
            else if (!string.IsNullOrWhiteSpace(currentFile))
            {
                _renderFontStatusText.Text = File.Exists(currentFile)
                    ? $"Current font file: {currentFile}"
                    : $"Selected font is missing: {currentFile}";
            }
            else if (!_renderFontSystemLoaded)
            {
                _renderFontStatusText.Text = $"Current: {currentFamily} · open this section to load installed fonts.";
            }
            else
            {
                _renderFontStatusText.Text = $"Current: {currentFamily} family fallback";
            }
        }
        finally
        {
            _renderFontUiUpdating = false;
        }
    }

    private void RebindFontChoices()
    {
        _renderFontUiUpdating = true;
        try
        {
            var names = _renderFontChoices.Select(x => x.DisplayName).ToArray();
            if (_renderFontComboBox is not null)
            {
                _renderFontComboBox.ItemsSource = null;
                _renderFontComboBox.ItemsSource = names;
            }
            if (_floatingFontComboBox is not null)
            {
                _floatingFontComboBox.ItemsSource = null;
                _floatingFontComboBox.ItemsSource = names;
            }
        }
        finally { _renderFontUiUpdating = false; }
        RefreshRenderFontUi();
    }

    private void EnsureFontChoiceVisible(RenderFontChoice choice, bool rebind = true)
    {
        var existing = _renderFontChoices.FindIndex(x =>
            !string.IsNullOrWhiteSpace(choice.FilePath)
                ? x.FilePath.Equals(choice.FilePath, StringComparison.OrdinalIgnoreCase)
                : string.IsNullOrWhiteSpace(x.FilePath)
                  && x.FamilyName.Equals(choice.FamilyName, StringComparison.CurrentCultureIgnoreCase));
        if (existing >= 0)
            return;

        _renderFontChoices.Insert(0, choice);
        if (rebind)
            RebindFontChoices();
    }

    private static RenderFontChoice? CreateFontChoice(string path)
    {
        try
        {
            using var typeface = SKTypeface.FromFile(path);
            if (typeface is null || string.IsNullOrWhiteSpace(typeface.FamilyName))
                return null;

            var style = typeface.FontStyle;
            var weight = style.Weight;
            var styleName = WeightName(weight);
            if (style.Slant == SKFontStyleSlant.Italic)
                styleName += " Italic";
            else if (style.Slant == SKFontStyleSlant.Oblique)
                styleName += " Oblique";

            return new RenderFontChoice(
                typeface.FamilyName.Trim(),
                path,
                weight,
                style.Slant,
                styleName);
        }
        catch
        {
            return null;
        }
    }

    private static string WeightName(int weight) => weight switch
    {
        <= 150 => "Thin",
        <= 250 => "ExtraLight",
        <= 350 => "Light",
        <= 450 => "Regular",
        <= 550 => "Medium",
        <= 650 => "SemiBold",
        <= 750 => "Bold",
        <= 850 => "ExtraBold",
        _ => "Black",
    };

    private static T? FindFontAncestor<T>(DependencyObject? child) where T : DependencyObject
    {
        var current = child;
        while (current is not null)
        {
            if (current is T match)
                return match;
            current = VisualTreeHelper.GetParent(current);
        }
        return null;
    }

    private sealed record RenderFontChoice(
        string FamilyName,
        string FilePath,
        int Weight,
        SKFontStyleSlant Slant,
        string StyleName)
    {
        public string DisplayName
        {
            get
            {
                if (string.IsNullOrWhiteSpace(FilePath))
                    return FamilyName;
                return StyleName.Equals("Regular", StringComparison.OrdinalIgnoreCase)
                    ? $"{FamilyName} — custom file"
                    : $"{FamilyName} — {StyleName} · custom file";
            }
        }
    }
}
