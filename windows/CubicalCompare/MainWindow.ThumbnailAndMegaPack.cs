using System.Collections.Specialized;
using CubicalCompare.Core.MegaPack;
using CubicalCompare.Core.Thumbnail;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private GeneratedThumbnail? _latestThumbnail;
    private long _thumbnailRevision;
    private bool _thumbnailHooksInstalled;

    private void RootNavigation_Loaded(object sender, RoutedEventArgs e)
    {
        if (_thumbnailHooksInstalled) return;
        _thumbnailHooksInstalled = true;

        Cards.CollectionChanged += Cards_CollectionChangedForThumbnail;
        foreach (var card in Cards) card.PropertyChanged += ThumbnailCard_PropertyChanged;
        ScheduleThumbnailRefresh();
    }

    private void Cards_CollectionChangedForThumbnail(object? sender, NotifyCollectionChangedEventArgs e)
    {
        if (e.OldItems is not null)
            foreach (ProjectCardViewModel card in e.OldItems)
                card.PropertyChanged -= ThumbnailCard_PropertyChanged;

        if (e.NewItems is not null)
            foreach (ProjectCardViewModel card in e.NewItems)
                card.PropertyChanged += ThumbnailCard_PropertyChanged;

        ScheduleThumbnailRefresh();
    }

    private void ThumbnailCard_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
        => ScheduleThumbnailRefresh();

    private void ScheduleThumbnailRefresh()
    {
        var revision = Interlocked.Increment(ref _thumbnailRevision);
        var project = BuildProject();
        _ = RefreshThumbnailAsync(project, revision, delayed: true);
    }

    private async Task RefreshThumbnailAsync(Core.Project.ComparisonProject project, long revision, bool delayed)
    {
        try
        {
            if (delayed) await Task.Delay(180);
            if (revision != Interlocked.Read(ref _thumbnailRevision)) return;

            var generated = await Task.Run(() => AutoThumbnailGenerator.Generate(project));
            if (revision != Interlocked.Read(ref _thumbnailRevision)) return;

            _latestThumbnail = generated;
            ThumbnailPreviewImage.Source = await BitmapFromPngAsync(generated.Png);
            ThumbnailStatusText.Text = $"Auto-generated 1280×720 · cards {string.Join(", ", generated.CardIndices.Select(x => x + 1))}";
        }
        catch (Exception ex)
        {
            ThumbnailStatusText.Text = $"Thumbnail generation failed: {ex.Message}";
        }
    }

    private async void RegenerateThumbnail_Click(object sender, RoutedEventArgs e)
    {
        var revision = Interlocked.Increment(ref _thumbnailRevision);
        ThumbnailStatusText.Text = "Generating thumbnail…";
        await RefreshThumbnailAsync(BuildProject(), revision, delayed: false);
    }

    private async void SaveThumbnail_Click(object sender, RoutedEventArgs e)
    {
        if (_latestThumbnail is null)
        {
            var revision = Interlocked.Increment(ref _thumbnailRevision);
            await RefreshThumbnailAsync(BuildProject(), revision, delayed: false);
        }
        if (_latestThumbnail is null) return;

        var file = await PickSaveFileAsync(
            "PNG image",
            ".png",
            SafeFileStem(ProjectName) + "-thumbnail");
        if (file is null) return;

        await File.WriteAllBytesAsync(file.Path, _latestThumbnail.Png);
        ThumbnailStatusText.Text = $"Saved {Path.GetFileName(file.Path)}";
    }

    private async void ExportMegaPack_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickSaveFileAsync(
            "MegaPack Zipack2",
            ".zipack2",
            SafeFileStem(ProjectName) + ".megapack");
        if (file is null) return;

        try
        {
            MegaPackExportStatusText.Text = "Building full-resolution contact sheets…";
            var result = await Zipack2Exporter.ExportAsync(BuildProject(), file.Path);
            MegaPackExportStatusText.Text = $"Exported {result.Cards} cards across {result.ContactSheets} contact sheet(s) · {Path.GetFileName(result.Path)}";
        }
        catch (Exception ex)
        {
            MegaPackExportStatusText.Text = "MegaPack export failed.";
            await ShowErrorAsync("Could not export MegaPack", ex.Message);
        }
    }

    private async Task<StorageFile?> PickSaveFileAsync(string label, string extension, string suggestedFileName)
    {
        var picker = new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.Downloads,
            SuggestedFileName = suggestedFileName,
        };
        picker.FileTypeChoices.Add(label, [extension]);

        var hwnd = WindowNative.GetWindowHandle(this);
        InitializeWithWindow.Initialize(picker, hwnd);
        return await picker.PickSaveFileAsync();
    }

    private static string SafeFileStem(string value)
    {
        var stem = string.IsNullOrWhiteSpace(value) ? "Cubical-Compare" : value.Trim();
        foreach (var invalid in Path.GetInvalidFileNameChars()) stem = stem.Replace(invalid, '-');
        return stem.Trim().TrimEnd('.');
    }
}
