using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using CubicalCompare.Core.MegaPack;
using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow : Window
{
    public ObservableCollection<ProjectCardViewModel> Cards { get; } = [];
    public ObservableCollection<DetectedCardViewModel> DetectedCards { get; } = [];

    public string ProjectName => "Untitled comparison";

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        SystemBackdrop = new MicaBackdrop();
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1440, 900));

        Cards.Add(new ProjectCardViewModel
        {
            Title = "Card 1",
            Value = "1",
            Description = "Start editing here, or import a MegaPack Zipack2 contact sheet.",
        });

        Loaded += (_, _) =>
        {
            RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
            CardsList.SelectedIndex = 0;
        };
    }

    private void RootNavigation_SelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItemContainer?.Tag is not string tag) return;
        ShowPage(tag);
    }

    private void ShowPage(string tag)
    {
        ProjectPage.Visibility = tag == "project" ? Visibility.Visible : Visibility.Collapsed;
        AssetsPage.Visibility = tag == "assets" ? Visibility.Visible : Visibility.Collapsed;
        RendererPage.Visibility = tag == "renderer" ? Visibility.Visible : Visibility.Collapsed;
    }

    private void NewProject_Click(object sender, RoutedEventArgs e)
    {
        Cards.Clear();
        Cards.Add(new ProjectCardViewModel { Title = "Card 1", Value = "1" });
        CardsList.SelectedIndex = 0;
        RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
    }

    private void AddCard_Click(object sender, RoutedEventArgs e)
    {
        var card = new ProjectCardViewModel
        {
            Title = $"Card {Cards.Count + 1}",
            Value = (Cards.Count + 1).ToString(),
        };
        Cards.Add(card);
        CardsList.SelectedItem = card;
        CardsList.ScrollIntoView(card);
    }

    private void RemoveCard_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel card) return;
        var index = Cards.IndexOf(card);
        Cards.Remove(card);
        if (Cards.Count == 0) Cards.Add(new ProjectCardViewModel { Title = "Card 1", Value = "1" });
        CardsList.SelectedIndex = Math.Clamp(index, 0, Cards.Count - 1);
    }

    private async void ImportMegaPack_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".zipack2"]);
        if (file is null) return;

        try
        {
            DetectedSummaryText.Text = "Scanning contact sheets and yellow outlines…";
            RootNavigation.SelectedItem = RootNavigation.MenuItems[1];

            var result = await Zipack2Importer.ImportAsync(file.Path);
            DetectedCards.Clear();
            var sequence = 1;
            foreach (var card in result.Cards)
                DetectedCards.Add(new DetectedCardViewModel(card, sequence++));

            RefreshDetectedOrder();
            DetectedSummaryText.Text = $"{result.Name} · {result.Sheets.Count} contact sheet(s) · {DetectedCards.Count} detected card(s)";
            if (DetectedCards.Count > 0) DetectedCardsList.SelectedIndex = 0;
        }
        catch (Exception ex)
        {
            DetectedSummaryText.Text = "Import failed.";
            await ShowErrorAsync("Could not import Zipack2", ex.Message);
        }
    }

    private void MoveDetectedEarlier_Click(object sender, RoutedEventArgs e) => MoveDetectedCard(-1);
    private void MoveDetectedLater_Click(object sender, RoutedEventArgs e) => MoveDetectedCard(1);

    private void MoveDetectedCard(int offset)
    {
        if (DetectedCardsList.SelectedItem is not DetectedCardViewModel selected) return;
        var index = DetectedCards.IndexOf(selected);
        var destination = index + offset;
        if (destination < 0 || destination >= DetectedCards.Count) return;
        DetectedCards.Move(index, destination);
        RefreshDetectedOrder();
        DetectedCardsList.SelectedItem = selected;
        DetectedCardsList.ScrollIntoView(selected);
    }

    private void RefreshDetectedOrder()
    {
        for (var index = 0; index < DetectedCards.Count; index++)
            DetectedCards[index].SetSequence(index + 1);
    }

    private async void ApproveDetectedCards_Click(object sender, RoutedEventArgs e)
    {
        var approved = DetectedCards.Where(x => x.Included).ToArray();
        if (approved.Length == 0)
        {
            await ShowErrorAsync("No cards selected", "Select at least one detected card before importing into the project.");
            return;
        }

        Cards.Clear();
        for (var index = 0; index < approved.Length; index++)
        {
            var detected = approved[index];
            Cards.Add(new ProjectCardViewModel
            {
                Title = $"Card {index + 1}",
                Value = (index + 1).ToString(),
                ImagePath = detected.ExtractedPath,
            });
        }

        CardsList.SelectedIndex = 0;
        RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
    }

    private async void InspectRenderer_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".renderer", ".renderer2", ".renderer3", ".renderer4", ".zip"]);
        if (file is null) return;

        try
        {
            var renderer = await RendererPackageProbe.InspectAsync(file.Path);
            RendererNameText.Text = renderer.Name;
            RendererGenerationText.Text = $"Renderer v{(int)renderer.Generation} · API {renderer.Api}";
            RendererEngineText.Text = $"Engine {renderer.Engine}";
            RendererCanvasText.Text = $"Reference {renderer.ReferenceWidth}×{renderer.ReferenceHeight} · {renderer.ReferenceFps} FPS";
            RendererSourceText.Text = renderer.SourcePath;
        }
        catch (Exception ex)
        {
            await ShowErrorAsync("Could not inspect renderer", ex.Message);
        }
    }

    private async Task<StorageFile?> PickFileAsync(IReadOnlyList<string> extensions)
    {
        var picker = new FileOpenPicker
        {
            SuggestedStartLocation = PickerLocationId.Downloads,
            ViewMode = PickerViewMode.List,
        };
        foreach (var extension in extensions) picker.FileTypeFilter.Add(extension);

        var hwnd = WindowNative.GetWindowHandle(this);
        InitializeWithWindow.Initialize(picker, hwnd);
        return await picker.PickSingleFileAsync();
    }

    private async Task ShowErrorAsync(string title, string message)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = title,
            Content = message,
            CloseButtonText = "Close",
            DefaultButton = ContentDialogButton.Close,
        };
        await dialog.ShowAsync();
    }
}

public sealed class ProjectCardViewModel : INotifyPropertyChanged
{
    private string _title = "Untitled";
    private string _value = "";
    private string _description = "";
    private string _imagePath = "";
    private BitmapImage? _preview;

    public string Title
    {
        get => _title;
        set => Set(ref _title, value);
    }

    public string Value
    {
        get => _value;
        set => Set(ref _value, value);
    }

    public string Description
    {
        get => _description;
        set => Set(ref _description, value);
    }

    public string ImagePath
    {
        get => _imagePath;
        set
        {
            if (!Set(ref _imagePath, value)) return;
            Preview = CreatePreview(value);
        }
    }

    public BitmapImage? Preview
    {
        get => _preview;
        private set => Set(ref _preview, value);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    private bool Set<T>(ref T field, T value, [CallerMemberName] string? propertyName = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value)) return false;
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(propertyName));
        return true;
    }

    private static BitmapImage? CreatePreview(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return null;
        return new BitmapImage(new Uri(path)) { DecodePixelWidth = 960 };
    }
}

public sealed class DetectedCardViewModel : INotifyPropertyChanged
{
    private int _sequence;
    private bool _included = true;

    public DetectedCardViewModel(DetectedZipack2Card card, int sequence)
    {
        Card = card;
        _sequence = sequence;
        Preview = new BitmapImage(new Uri(card.ExtractedPath)) { DecodePixelWidth = 220 };
    }

    public DetectedZipack2Card Card { get; }
    public string ExtractedPath => Card.ExtractedPath;
    public BitmapImage Preview { get; }
    public string Label => $"Card {_sequence}";
    public string Details => $"Sheet {Card.SheetOrder + 1} · {Card.Bounds.Width}×{Card.Bounds.Height} · {Card.Confidence:P0} confidence";

    public bool Included
    {
        get => _included;
        set
        {
            if (_included == value) return;
            _included = value;
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Included)));
        }
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public void SetSequence(int sequence)
    {
        if (_sequence == sequence) return;
        _sequence = sequence;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Label)));
    }
}
