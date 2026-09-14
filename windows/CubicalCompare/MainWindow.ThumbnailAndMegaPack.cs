using System.Collections.Specialized;
using System.Text.Json;
using CubicalCompare.Core.MegaPack;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Thumbnail;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const string WorkspaceRecoveryFileName = "workspace-recovery.json";
    private const long WorkspaceRecoveryMaxBytes = 16L * 1024 * 1024;
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
    private readonly SemaphoreSlim _workspaceIoGate = new(1, 1);

    private async void RootNavigation_Loaded(object sender, RoutedEventArgs e)
    {
        if (_thumbnailHooksInstalled) return;
        _thumbnailHooksInstalled = true;

        Cards.CollectionChanged += Cards_CollectionChangedForThumbnail;
        CardsList.SelectionChanged += CardsList_SelectionChangedForRendererPreview;
        foreach (var card in Cards) card.PropertyChanged += ThumbnailCard_PropertyChanged;

        await RestoreWorkspaceAsync();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
    }

    private async void CardsList_SelectionChangedForRendererPreview(object sender, SelectionChangedEventArgs e)
    {
        var renderer = _legacyRenderer;
        if (renderer is null || CardsList.SelectedItem is not ProjectCardViewModel selected) return;

        var cardIndex = Cards.IndexOf(selected);
        if (cardIndex < 0) return;

        try
        {
            var project = BuildProject();
            var frame = renderer.PreviewFrameForCard(project, cardIndex);
            frame = Math.Clamp(frame, 0, (int)Math.Round(ProjectFrameSlider.Maximum));

            TimelineStatusText.Text = $"Previewing card {cardIndex + 1} · {selected.Title}";
            if (Math.Abs(ProjectFrameSlider.Value - frame) > 0.5)
            {
                // ValueChanged owns the render when the timeline actually moves.
                ProjectFrameSlider.Value = frame;
            }
            else
            {
                // The selected card can map to the current frame (especially one-card projects).
                // Render explicitly so selection still refreshes the preview.
                await RenderCurrentFrameAsync();
            }
        }
        catch (Exception ex)
        {
            TimelineStatusText.Text = $"Could not preview selected card: {ex.Message}";
            App.WriteLog("Renderer card-selection preview failed", ex);
        }
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
            App.WriteLog("Automatic thumbnail generation failed", ex);
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
        try
        {
            if (_latestThumbnail is null)
            {
                var revision = Interlocked.Increment(ref _thumbnailRevision);
                await RefreshThumbnailAsync(BuildProject(), revision, delayed: false);
            }
            if (_latestThumbnail is null)
            {
                ThumbnailStatusText.Text = "Thumbnail is not available to save.";
                return;
            }

            var file = await PickSaveFileAsync(
                "PNG image",
                ".png",
                SafeFileStem(ProjectName) + "-thumbnail");
            if (file is null) return;

            await File.WriteAllBytesAsync(file.Path, _latestThumbnail.Png);
            ThumbnailStatusText.Text = $"Saved {Path.GetFileName(file.Path)}";
        }
        catch (Exception ex)
        {
            ThumbnailStatusText.Text = "Could not save thumbnail.";
            App.WriteLog("Thumbnail save failed", ex);
            await ShowErrorAsync("Could not save thumbnail", ex.Message);
        }
    }

    private async void ExportMegaPack_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickSaveFileAsync(
            "MegaPack Zipack2",
            ".zipack2",
            SafeFileStem(ProjectName));
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
            App.WriteLog("MegaPack export failed", ex);
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
        string? temporaryPath = null;
        try
        {
            // Debounce rapid typing and slider/property edits. Only the newest snapshot reaches disk.
            await Task.Delay(650);
            if (revision != Interlocked.Read(ref _workspaceRevision) || _restoringWorkspace) return;

            await _workspaceIoGate.WaitAsync();
            try
            {
                // Re-check after acquiring the gate: another edit may have happened while this save
                // was waiting behind an older write.
                if (revision != Interlocked.Read(ref _workspaceRevision) || _restoringWorkspace) return;

                ProjectFileService.ValidateAndNormalize(snapshot);
                var folder = ApplicationData.Current.LocalFolder.Path;
                Directory.CreateDirectory(folder);
                var path = Path.Combine(folder, WorkspaceRecoveryFileName);
                temporaryPath = path + ".tmp-" + Guid.NewGuid().ToString("N");
                var json = JsonSerializer.Serialize(snapshot, WorkspaceJsonOptions);

                await File.WriteAllTextAsync(temporaryPath, json);
                if (revision != Interlocked.Read(ref _workspaceRevision)) return;

                File.Move(temporaryPath, path, overwrite: true);
                temporaryPath = null;
            }
            finally
            {
                _workspaceIoGate.Release();
            }
        }
        catch (Exception ex)
        {
            // Recovery must never take the editor down. Surface the failure without interrupting work.
            TimelineStatusText.Text = $"Autosave unavailable: {ex.Message}";
            App.WriteLog("Workspace autosave failed", ex);
        }
        finally
        {
            if (temporaryPath is not null) TryDelete(temporaryPath);
        }
    }

    private void FlushWorkspaceOnClose()
    {
        if (_restoringWorkspace) return;

        string? temporaryPath = null;
        var gateHeld = false;
        try
        {
            // Closing can happen while an async autosave is already writing. Bump the revision first,
            // then wait for that write to leave the critical section before persisting the final state.
            // This prevents an older snapshot from winning a shutdown race and overwriting last edits.
            Interlocked.Increment(ref _workspaceRevision);
            _workspaceIoGate.Wait();
            gateHeld = true;

            var snapshot = BuildProject();
            ProjectFileService.ValidateAndNormalize(snapshot);
            var folder = ApplicationData.Current.LocalFolder.Path;
            Directory.CreateDirectory(folder);
            var path = Path.Combine(folder, WorkspaceRecoveryFileName);
            temporaryPath = path + ".closing-" + Guid.NewGuid().ToString("N") + ".tmp";
            var json = JsonSerializer.Serialize(snapshot, WorkspaceJsonOptions);
            File.WriteAllText(temporaryPath, json);
            File.Move(temporaryPath, path, overwrite: true);
            temporaryPath = null;
        }
        catch (Exception ex)
        {
            // Window shutdown should never be blocked by recovery persistence.
            App.WriteLog("Final workspace flush failed", ex);
        }
        finally
        {
            if (temporaryPath is not null) TryDelete(temporaryPath);
            if (gateHeld) _workspaceIoGate.Release();
        }
    }

    private async Task RestoreWorkspaceAsync()
    {
        var path = Path.Combine(ApplicationData.Current.LocalFolder.Path, WorkspaceRecoveryFileName);
        if (!File.Exists(path)) return;

        try
        {
            var info = new FileInfo(path);
            if (info.Length <= 0)
                throw new InvalidDataException("The recovery file is empty.");
            if (info.Length > WorkspaceRecoveryMaxBytes)
                throw new InvalidDataException($"The recovery file is unexpectedly large ({info.Length:N0} bytes).");

            var json = await File.ReadAllTextAsync(path);
            var project = JsonSerializer.Deserialize<ComparisonProject>(json, WorkspaceJsonOptions);
            if (project is null)
                throw new InvalidDataException("The recovery file does not contain a project.");

            // Apply the same bounds and normalization used for explicit project files so malformed
            // recovery data cannot inject NaN transforms, duplicate IDs, absurd dimensions or card counts.
            ProjectFileService.ValidateAndNormalize(project);

            _restoringWorkspace = true;
            ClearProjectCards();
            _projectShowBadges = project.ShowBadges;
            _projectCreditsEnabled = project.CreditsEnabled;
            _projectDurationSeconds = project.AutoLength ? 0 : Math.Max(0, project.CustomLengthSeconds);

            foreach (var card in project.Cards)
            {
                AddProjectCard(new ProjectCardViewModel
                {
                    Id = card.Id,
                    Title = card.Title,
                    Value = card.Value,
                    BadgeHeader = card.BadgeHeader,
                    Description = card.Description,
                    ImagePath = card.ImagePath,
                    ImageX = card.ImageX,
                    ImageY = card.ImageY,
                    ImageScale = card.ImageScale,
                    ImageRotation = card.ImageRotation,
                    ImageCropLeft = card.ImageCropLeft,
                    ImageCropTop = card.ImageCropTop,
                    ImageCropRight = card.ImageCropRight,
                    ImageCropBottom = card.ImageCropBottom,
                    ImageLayer = card.ImageLayer,
                });
            }

            CardsList.SelectedIndex = 0;
            RefreshTimelineRange();
            TimelineStatusText.Text = $"Recovered {Cards.Count} autosaved card{(Cards.Count == 1 ? "" : "s")}";
        }
        catch (Exception ex)
        {
            // Preserve a broken recovery file for diagnosis instead of retrying it every launch or
            // silently replacing it with the starter card.
            var corruptPath = path + $".corrupt-{DateTime.UtcNow:yyyyMMdd-HHmmss}";
            try { File.Move(path, corruptPath, overwrite: true); } catch { }
            TimelineStatusText.Text = $"Recovery file was damaged and quarantined: {ex.Message}";
            App.WriteLog("Workspace recovery file quarantined", ex);
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
        stem = stem.Trim().TrimEnd('.');
        return string.IsNullOrWhiteSpace(stem) ? "Cubical-Compare" : stem;
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
