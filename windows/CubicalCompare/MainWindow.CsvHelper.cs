using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Globalization;
using System.Runtime.CompilerServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    public ObservableCollection<CsvHelperRowViewModel> CsvHelperRows { get; } = [];
    private int _csvHelperSequence;

    private void InitializeCsvHelper()
    {
        CsvHelperRows.Clear();
        _csvHelperSequence = 0;
        AddCsvHelperRow();
        AddCsvHelperRow();
        AddCsvHelperRow();
        CsvHelperRowsList.ItemsSource = CsvHelperRows;
        UpdateCsvHelperStatus();
    }

    private void AddCsvHelperRow()
    {
        CsvHelperRows.Add(new CsvHelperRowViewModel(++_csvHelperSequence));
        UpdateCsvHelperStatus();
    }

    private void CsvHelperAddRow_Click(object sender, RoutedEventArgs e) => AddCsvHelperRow();

    private void CsvHelperRemoveRow_Click(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: CsvHelperRowViewModel row })
            CsvHelperRows.Remove(row);

        RenumberCsvHelperRows();
        UpdateCsvHelperStatus();
    }

    private void RenumberCsvHelperRows()
    {
        for (var i = 0; i < CsvHelperRows.Count; i++)
            CsvHelperRows[i].Number = i + 1;
    }

    private void UpdateCsvHelperStatus()
    {
        if (CsvHelperStatusText is null) return;
        var filled = CsvHelperRows.Count(row =>
            !string.IsNullOrWhiteSpace(row.Title) ||
            !string.IsNullOrWhiteSpace(row.Value) ||
            !string.IsNullOrWhiteSpace(row.Image));

        var invalid = CsvHelperRows.Count(row => !string.IsNullOrWhiteSpace(row.Value) && !IsValidCsvValue(row.Value));
        CsvHelperStatusText.Text = invalid > 0
            ? $"{CsvHelperRows.Count} rows · {invalid} value(s) need attention"
            : $"{filled}/{CsvHelperRows.Count} rows filled · Ready";
    }

    private static bool IsValidCsvValue(string value)
    {
        var trimmed = value.Trim().TrimEnd('%');
        return double.TryParse(trimmed, NumberStyles.Float, CultureInfo.InvariantCulture, out var number)
               && double.IsFinite(number)
               && number >= 0;
    }

    private async void CsvHelperExport_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var rows = CsvHelperRows
                .Where(row => !string.IsNullOrWhiteSpace(row.Title) ||
                              !string.IsNullOrWhiteSpace(row.Value) ||
                              !string.IsNullOrWhiteSpace(row.Image))
                .ToArray();

            if (rows.Length == 0)
            {
                await ShowErrorAsync("Nothing to export", "Add at least one comparison row first.");
                return;
            }

            var invalid = rows.Where(row => !string.IsNullOrWhiteSpace(row.Value) && !IsValidCsvValue(row.Value)).ToArray();
            if (invalid.Length > 0)
            {
                await ShowErrorAsync("Check the values", $"These rows have invalid numeric values: {string.Join(", ", invalid.Select(row => row.Number))}.");
                return;
            }

            var file = await PickSaveCsvAsync();
            if (file is null) return;

            var lines = new List<string> { "Title,Value,Badge Header,Description,Image,Duration" };
            var duration = CsvHelperDurationToCsv();
            var header = string.IsNullOrWhiteSpace(CsvHelperDefaultHeaderBox.Text) ? "Probability" : CsvHelperDefaultHeaderBox.Text.Trim();
            foreach (var row in rows)
            {
                lines.Add(string.Join(",",
                    CsvField(row.Title),
                    CsvField(row.Value),
                    CsvField(header),
                    CsvField(string.Empty),
                    CsvField(row.Image),
                    CsvField(duration)));
            }

            await File.WriteAllTextAsync(file.Path, string.Join(Environment.NewLine, lines) + Environment.NewLine);
            CsvHelperStatusText.Text = $"Exported {rows.Length} rows · {Path.GetFileName(file.Path)}";
        }
        catch (Exception ex)
        {
            App.WriteLog("CSV Helper export failed", ex);
            await ShowErrorAsync("Could not export CSV", ex.Message);
        }
    }

    private async void CsvHelperImport_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".csv"]);
        if (file is null) return;

        try
        {
            var result = await Core.Project.CsvImportService.ImportAsync(file.Path);
            CsvHelperRows.Clear();
            _csvHelperSequence = 0;

            CsvHelperDurationBox.Text = result.DurationSeconds is > 0
                ? TimeSpan.FromSeconds(result.DurationSeconds.Value).ToString(@"hh\:mm\:ss")
                : string.Empty;

            foreach (var card in result.Cards)
            {
                var row = new CsvHelperRowViewModel(++_csvHelperSequence)
                {
                    Title = card.Title,
                    Value = card.Value,
                    Image = card.ImagePath,
                };
                CsvHelperRows.Add(row);
            }

            RenumberCsvHelperRows();
            UpdateCsvHelperStatus();
            CsvHelperStatusText.Text = $"Loaded {result.Cards.Count} rows · {Path.GetFileName(file.Path)}";

            if (result.Warnings.Count > 0)
                await ShowErrorAsync("CSV loaded with warnings", string.Join(Environment.NewLine, result.Warnings));
        }
        catch (Exception ex)
        {
            App.WriteLog("CSV Helper import failed", ex);
            await ShowErrorAsync("Could not load CSV", ex.Message);
        }
    }

    private async void CsvHelperLoadIntoProject_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            var rows = CsvHelperRows
                .Where(row => !string.IsNullOrWhiteSpace(row.Title) ||
                              !string.IsNullOrWhiteSpace(row.Value) ||
                              !string.IsNullOrWhiteSpace(row.Image))
                .ToArray();

            if (rows.Length == 0)
            {
                await ShowErrorAsync("Nothing to load", "Add at least one comparison row first.");
                return;
            }

            ClearProjectCards();

            var duration = ParseHelperDuration();
            if (duration > 0)
                _projectDurationSeconds = duration;

            foreach (var row in rows)
            {
                AddProjectCard(new ProjectCardViewModel
                {
                    Title = string.IsNullOrWhiteSpace(row.Title) ? $"Card {Cards.Count + 1}" : row.Title.Trim(),
                    Value = row.Value.Trim(),
                    BadgeHeader = string.IsNullOrWhiteSpace(CsvHelperDefaultHeaderBox.Text) ? "Probability" : CsvHelperDefaultHeaderBox.Text.Trim(),
                    ImagePath = row.Image.Trim(),
                });
            }

            CardsList.SelectedIndex = 0;
            RefreshTimelineRange();
            await RenderCurrentFrameAsync();
            RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
            TimelineStatusText.Text = $"Loaded {rows.Length} cards from CSV Helper";
        }
        catch (Exception ex)
        {
            App.WriteLog("CSV Helper load into project failed", ex);
            await ShowErrorAsync("Could not load CSV Helper data", ex.Message);
        }
    }

    private string CsvHelperDurationToCsv()
    {
        var text = CsvHelperDurationBox.Text?.Trim() ?? string.Empty;
        if (string.IsNullOrWhiteSpace(text)) return string.Empty;
        return text;
    }

    private double ParseHelperDuration()
    {
        var text = CsvHelperDurationBox.Text?.Trim() ?? string.Empty;
        if (string.IsNullOrWhiteSpace(text)) return 0;

        if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds) &&
            double.IsFinite(seconds) && seconds > 0)
            return seconds;

        var parts = text.Split(':');
        if (parts.Length is 2 or 3 &&
            parts.All(part => int.TryParse(part, NumberStyles.Integer, CultureInfo.InvariantCulture, out _)))
        {
            var nums = parts.Select(part => int.Parse(part, CultureInfo.InvariantCulture)).ToArray();
            var hours = parts.Length == 3 ? nums[0] : 0;
            var minutes = parts.Length == 3 ? nums[1] : nums[0];
            var secs = nums[^1];
            if (hours >= 0 && minutes is >= 0 and <= 59 && secs is >= 0 and <= 59)
                return hours * 3600 + minutes * 60 + secs;
        }

        return 0;
    }

    private static string CsvField(string value)
    {
        if (string.IsNullOrEmpty(value)) return string.Empty;
        return value.Contains(',') || value.Contains('"') || value.Contains('\n') || value.Contains('\r')
            ? $""{value.Replace(""", """")}""
            : value;
    }

    private async Task<StorageFileCompat?> PickSaveCsvAsync()
    {
        var picker = new FileSavePicker
        {
            SuggestedFileName = string.IsNullOrWhiteSpace(CsvHelperProjectNameBox.Text) ? "comparison.csv" : CsvHelperProjectNameBox.Text.Trim() + ".csv",
            FileTypeChoices = { { "CSV file", [".csv"] } },
        };
        picker.SuggestedStartLocation = PickerLocationId.DocumentsLibrary;
        InitializeWithWindow.Initialize(picker, WindowNative.GetWindowHandle(this));

        var file = await picker.PickSaveFileAsync();
        return file is null ? null : new StorageFileCompat(file);
    }

    private sealed class StorageFileCompat
    {
        public StorageFileCompat(Windows.Storage.StorageFile file) => Path = file.Path;
        public string Path { get; }
    }
}

public sealed class CsvHelperRowViewModel : INotifyPropertyChanged
{
    private string _title = string.Empty;
    private string _value = string.Empty;
    private string _image = string.Empty;
    private int _number;

    public CsvHelperRowViewModel(int number) => _number = number;

    public int Number
    {
        get => _number;
        set
        {
            if (_number == value) return;
            _number = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Number)));
        }
    }

    public string Title
    {
        get => _title;
        set
        {
            if (_title == value) return;
            _title = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Title)));
        }
    }

    public string Value
    {
        get => _value;
        set
        {
            if (_value == value) return;
            _value = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Value)));
        }
    }

    public string Image
    {
        get => _image;
        set
        {
            if (_image == value) return;
            _image = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Image)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;
}
