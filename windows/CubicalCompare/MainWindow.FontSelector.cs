using System.Collections.ObjectModel;
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
    private readonly ObservableCollection<string> _renderFontLabels = [];
    private ComboBox? _renderFontComboBox;
    private TextBlock? _renderFontStatusText;
    private bool _renderFontUiUpdating;
    private bool _renderFontSelectorInitialized;
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
            PlaceholderText = "Select a font installed on Windows",
            ItemsSource = _renderFontLabels,
            MaxDropDownHeight = 520,
        };
        _renderFontComboBox.SelectionChanged += RenderFontComboBox_SelectionChanged;

        _renderFontStatusText = new TextBlock
        {
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
            Content = "Use default Nexa / system fallback",
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
            Text = "The renderer and automatic thumbnail use the exact selected font file. Nexa ExtraBold is picked automatically when it is installed.",
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
        inspectorStack.Children.Add(expander);

        RenderFontSelection.Changed += RenderFontSelection_Changed;
        Closed += (_, _) => RenderFontSelection.Changed -= RenderFontSelection_Changed;

        RefreshRenderFontUi();
        _ = LoadSystemFontsAsync(force: false);
    }

    private async Task LoadSystemFontsAsync(bool force)
    {
        if (!_renderFontSelectorInitialized)
            return;

        var revision = Interlocked.Increment(ref _renderFontScanRevision);
        if (_renderFontStatusText is not null)
            _renderFontStatusText.Text = force ? "Refreshing installed fonts…" : "Scanning installed fonts…";

        List<RenderFontChoice> choices;
        try
        {
            choices = await Task.Run(EnumerateSystemFonts);
        }
        catch (Exception ex)
        {
            App.WriteLog("System font scan failed", ex);
            if (_renderFontStatusText is not null)
                _renderFontStatusText.Text = $"Could not scan Windows fonts: {ex.Message}";
            return;
        }

        if (revision != Interlocked.Read(ref _renderFontScanRevision))
            return;

        _renderFontUiUpdating = true;
        try
        {
            _renderFontChoices.Clear();
            _renderFontLabels.Clear();
            foreach (var choice in choices)
            {
                _renderFontChoices.Add(choice);
                _renderFontLabels.Add(choice.DisplayName);
            }
        }
        finally
        {
            _renderFontUiUpdating = false;
        }

        // Prefer the exact ExtraBold face when Nexa is installed, matching Cubical Compare's
        // established typography without requiring any font payload to be shipped with the app.
        if (string.IsNullOrWhiteSpace(RenderFontSelection.CurrentFile))
        {
            var preferred = _renderFontChoices
                .Where(x => x.FamilyName.Equals("Nexa", StringComparison.OrdinalIgnoreCase) && x.Weight >= 750)
                .OrderByDescending(x => x.Weight)
                .FirstOrDefault();
            if (preferred is not null)
                RenderFontSelection.Apply(preferred.FamilyName, preferred.FilePath);
        }

        RefreshRenderFontUi();
        if (_renderFontStatusText is not null
            && string.IsNullOrWhiteSpace(RenderFontSelection.CurrentFile))
        {
            _renderFontStatusText.Text = "Nexa ExtraBold is not installed. Select any Windows font above, or choose a .ttf/.otf file.";
        }
    }

    private static List<RenderFontChoice> EnumerateSystemFonts()
    {
        var directories = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var systemFonts = Environment.GetFolderPath(Environment.SpecialFolder.Fonts);
        if (!string.IsNullOrWhiteSpace(systemFonts) && Directory.Exists(systemFonts))
            directories.Add(systemFonts);

        var userFonts = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "Microsoft",
            "Windows",
            "Fonts");
        if (Directory.Exists(userFonts))
            directories.Add(userFonts);

        var choices = new Dictionary<string, RenderFontChoice>(StringComparer.OrdinalIgnoreCase);
        foreach (var directory in directories)
        {
            IEnumerable<string> files;
            try
            {
                files = Directory.EnumerateFiles(directory, "*.*", SearchOption.TopDirectoryOnly).ToArray();
            }
            catch
            {
                continue;
            }

            foreach (var path in files)
            {
                var extension = Path.GetExtension(path).ToLowerInvariant();
                if (extension is not (".ttf" or ".otf" or ".ttc"))
                    continue;

                var choice = CreateFontChoice(path);
                if (choice is null)
                    continue;

                var key = $"{choice.FamilyName}\u001f{choice.Weight}\u001f{choice.Slant}";
                choices.TryAdd(key, choice);
            }
        }

        return choices.Values
            .OrderBy(x => x.FamilyName, StringComparer.CurrentCultureIgnoreCase)
            .ThenBy(x => x.Weight)
            .ThenBy(x => x.Slant)
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
            var selectedIndex = string.IsNullOrWhiteSpace(currentFile)
                ? -1
                : _renderFontChoices.FindIndex(x => x.FilePath.Equals(currentFile, StringComparison.OrdinalIgnoreCase));
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

    private void EnsureFontChoiceVisible(RenderFontChoice choice)
    {
        var existing = _renderFontChoices.FindIndex(x =>
            x.FilePath.Equals(choice.FilePath, StringComparison.OrdinalIgnoreCase));
        if (existing >= 0)
            return;

        _renderFontChoices.Insert(0, choice);
        _renderFontLabels.Insert(0, choice.DisplayName);
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
        public string DisplayName => StyleName.Equals("Regular", StringComparison.OrdinalIgnoreCase)
            ? FamilyName
            : $"{FamilyName} — {StyleName}";
    }
}
