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
    private long _renderFontScanRevision;

    internal void InitializeFontSelector()
    {
        if (_renderFontSelectorInitialized)
            return;

        var transformExpander = FindFontAncestor<Expander>(ImageScaleBox);
        if (transformExpander?.Parent is not StackPanel inspectorStack)
        {
            DispatcherQueue.TryEnqueue(InitializeFontSelector);
            return;
        }

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
        expander.Expanded += async (_, _) =>
        {
            if (!_renderFontSystemLoaded)
                await LoadSystemFontsAsync(force: false);
        };
        inspectorStack.Children.Add(expander);

        RenderFontSelection.Changed += RenderFontSelection_Changed;
        Closed += (_, _) => RenderFontSelection.Changed -= RenderFontSelection_Changed;

        RefreshRenderFontUi();
    }

    private async Task LoadSystemFontsAsync(bool force)
    {
        if (!_renderFontSelectorInitialized)
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
        // SKFontManager asks the platform font manager for family names directly. The previous
        // implementation opened every TTF/OTF/TTC file on disk one by one, which could make launch
        // and the first ComboBox open look frozen on machines with large font collections.
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
            var folder = Path.Combine(ApplicationData.Current.LocalFolder.Path, "Fonts");
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
        if (_renderFontComboBox is null)
            return;

        _renderFontUiUpdating = true;
        try
        {
            _renderFontComboBox.ItemsSource = null;
            _renderFontComboBox.ItemsSource = _renderFontChoices.Select(x => x.DisplayName).ToArray();
        }
        finally
        {
            _renderFontUiUpdating = false;
        }
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
