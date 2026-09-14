using System.Collections.Specialized;
using System.Text.Json;
using CubicalCompare.Core.MegaPack;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Thumbnail;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const string WorkspaceRecoveryFileName = "workspace-recovery.json";
    private static readonly JsonSerializerOptions WorkspaceJsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    private GeneratedThumbnail? _latestThumbnail;
    private long _thumbnailRevision;
    private bool _thumbnailHooksInstalled;
    private long _workspaceRevision;
    private bool _restoringWorkspace;

    private async void RootNavigation_Loaded(object sender, RoutedEventArgs e)
    {
        if (_thumbnailHooksInstalled) return;
        _thumbnailHooksInstalled = true;

        Cards.CollectionChanged += Cards_CollectionChangedForThumbnail;
        foreach (var card in Cards) card.PropertyChanged += ThumbnailCard_PropertyChanged;

        await RestoreWorkspaceAsync();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
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
        ScheduleWorkspaceSave();
    }

    private void ThumbnailCard_PropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
    }

    private void ScheduleThumbnailRefresh()
    {
        var revision = Interlocked.Increment(ref _thumbnailRevision);
        var project = BuildProject();
        _ = RefreshThumbnailAsync(project, revision, delayed: true);
    }

    private async Task RefreshThumbnailAsync(ComparisonProject project, long revision, bool delayed)
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

    private void ScheduleWorkspaceSave()
    {
        if (_restoringWorkspace) return;
        var revision = Interlocked.Increment(ref _workspaceRevision);
        var snapshot = BuildProject();
        _ = SaveWorkspaceAsync(snapshot, revision);
    }

    private async Task SaveWorkspaceAsync(ComparisonProject snapshot, long revision)
    {
        try
        {
            // Debounce rapid typing and slider/property edits. Only the newest snapshot reaches disk.
            await Task.Delay(650);
            if (revision != Interlocked.Read(ref _workspaceRevision) || _restoringWorkspace) return;

            var folder = ApplicationData.Current.LocalFolder.Path;
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, WorkspaceRecoveryFileName);
            var temporaryPath = path + ".tmp";
            var json = JsonSerializer.Serialize(snapshot, WorkspaceJsonOptions);

            await File.WriteAllTextAsync(temporaryPath, json);
            if (revision != Interlocked.Read(ref _workspaceRevision))
            {
                TryDelete(temporaryPath);
                return;
            }

            File.Move(temporaryPath, path, overwrite: true);
        }
        catch (Exception ex)
        {
            // Recovery must never take the editor down. Surface the failure without interrupting work.
            TimelineStatusText.Text = $"Autosave unavailable: {ex.Message}";
        }
    }

    private async Task RestoreWorkspaceAsync()
    {
        var path = Path.Combine(ApplicationData.Current.LocalFolder.Path, WorkspaceRecoveryFileName);
        if (!File.Exists(path)) return;

        try
        {
            var json = await File.ReadAllTextAsync(path);
            var project = JsonSerializer.Deserialize<ComparisonProject>(json, WorkspaceJsonOptions);
            if (project is null || project.Cards is null || project.Cards.Count == 0) return;

            _restoringWorkspace = true;
            ClearProjectCards();
            _projectShowBadges = project.ShowBadges;
            _projectCreditsEnabled = project.CreditsEnabled;
            _projectDurationSeconds = project.AutoLength ? 0 : Math.Max(0, project.CustomLengthSeconds);

            foreach (var card in project.Cards)
            {
                AddProjectCard(new ProjectCardViewModel
                {
                    Id = string.IsNullOrWhiteSpace(card.Id) ? Guid.NewGuid().ToString("N") : card.Id,
                    Title = card.Title ?? "Untitled",
                    Value = card.Value ?? "",
                    BadgeHeader = card.BadgeHeader ?? "",
                    Description = card.Description ?? "",
                    ImagePath = card.ImagePath ?? "",
                    ImageX = card.ImageX,
                    ImageY = card.ImageY,
                    ImageScale = card.ImageScale > 0 ? card.ImageScale : 1,
                    ImageRotation = card.ImageRotation,
                    ImageCropLeft = Math.Max(0, card.ImageCropLeft),
                    ImageCropTop = Math.Max(0, card.ImageCropTop),
                    ImageCropRight = Math.Max(0, card.ImageCropRight),
                    ImageCropBottom = Math.Max(0, card.ImageCropBottom),
                    ImageLayer = string.IsNullOrWhiteSpace(card.ImageLayer) ? "behind" : card.ImageLayer,
                });
            }

            CardsList.SelectedIndex = 0;
            RefreshTimelineRange();
            TimelineStatusText.Text = $"Recovered {Cards.Count} autosaved card{(Cards.Count == 1 ? "" : "s")}";
        }
        catch (Exception ex)
        {
            // Preserve a broken recovery file for diagnosis instead of retrying it every launch.
            var corruptPath = path + $".corrupt-{DateTime.UtcNow:yyyyMMdd-HHmmss}";
            try { File.Move(path, corruptPath, overwrite: true); } catch { }
            TimelineStatusText.Text = $"Recovery file was damaged and quarantined: {ex.Message}";
        }
        finally
        {
            _restoringWorkspace = false;
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

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // Best-effort cleanup only.
        }
    }
}
