using System.IO.Compression;
using System.Text.Json;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Project.Patching;
using CubicalCompare.Updates;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private CancellationTokenSource? _patchBuildCancellation;
    private static string PatchStorage => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RetroFrost", "CubicalCompare", "code-patches");
    private static string PreviousPatchedAppZip => Path.Combine(PatchStorage, "previous-app.zip");

    private async void ImportAppPatch_Click(object sender, RoutedEventArgs e)
    {
        if (_patchBuildCancellation is not null) return;
        if (_videoExportCancellation is not null) { await ShowErrorAsync("Export in progress", "Finish or cancel the export before rebuilding app code."); return; }
        var files = await PickFilesAsync([".patch"]);
        if (files.Count == 0) return;
        _patchBuildCancellation = new CancellationTokenSource();
        var token = _patchBuildCancellation.Token;
        SetPatchControls(true);
        AppPatchBuilder? builder = null;
        var preserveLog = false;
        try
        {
            ShowActivityWatcher("App patch", "Checking patch context against this app's source…", null, true);
            builder = await AppPatchBuilder.PrepareAsync(Path.Combine(AppContext.BaseDirectory, "Assets", "AppSource.zip"), files.Select(x => x.Path), Path.Combine(PatchStorage, "jobs"), token);
            var review = new ContentDialog
            {
                XamlRoot = RootNavigation.XamlRoot,
                Title = $"Rebuild app with {builder.ChangedFiles.Count} changed files?",
                Content = new ScrollViewer { MaxHeight = 420, Content = new TextBlock { TextWrapping = TextWrapping.Wrap, Text =
                    "This patch can change the entire Windows app: UI, renderer, imports and updates. Building runs the selected source and project scripts with your permissions. Import patches from a source you trust.\n\n" +
                    "Requires .NET 10 SDK, PowerShell 7 and Windows desktop build tools. The current app stays active during compilation. A successful build saves your workspace, backs up the app, then replaces it and restarts.\n\n" +
                    "Patch order: " + string.Join(", ", files.Select(x => x.Name)) + "\n\n" + string.Join("\n", builder.ChangedFiles) } },
                PrimaryButtonText = "Build & restart",
                CloseButtonText = "Cancel",
                DefaultButton = ContentDialogButton.Close,
            };
            if (await review.ShowAsync() != ContentDialogResult.Primary) { CompleteActivityWatcher("App patch", "No app code changed."); return; }
            var progress = new Progress<string>(text => { AppPatchStatusText.Text = text; ShowActivityWatcher("App patch", text, null, true); });
            preserveLog = true;
            var zip = await builder.BuildAsync(progress, token);
            token.ThrowIfCancellationRequested();
            if (_videoExportCancellation is not null) throw new InvalidOperationException("An export started during the build. Finish it before applying the patch; the current app was not replaced.");
            await FlushWorkspaceBeforePatchAsync();
            AppPatchStatusText.Text = "Saving previous app for restoration…";
            await SavePatchRollbackAsync(token);
            // The updater takes ownership of its own copy; cancel is disabled after this handoff.
            CancelAppPatchButton.IsEnabled = false;
            var updateProgress = new Progress<CubicalUpdateProgress>(state => { AppPatchStatusText.Text = state.Phase; UpdateActivityWatcher("App patch", state.Phase, state.Percent); });
            if (await _updateService.ApplyPortableZipFileAsync(zip, AppContext.BaseDirectory, "CubicalCompare.exe", updateProgress, token))
            {
                CompleteActivityWatcher("App patch", "Patched app staged. Restarting…");
                Application.Current.Exit();
            }
            else throw new InvalidOperationException("The updater did not stage the patched app.");
        }
        catch (OperationCanceledException) { AppPatchStatusText.Text = "Build cancelled. Current app unchanged."; CompleteActivityWatcher("App patch", AppPatchStatusText.Text); }
        catch (Exception ex)
        {
            App.WriteLog("Full-app patch import failed", ex);
            AppPatchStatusText.Text = builder is null ? ex.Message : $"Patch failed. Build log: {builder.BuildLogPath}";
            FailActivityWatcher("App patch failed", ex.Message);
            await ShowErrorAsync("Could not apply app patch", ex.Message);
        }
        finally
        {
            if (!preserveLog) builder?.Dispose();
            else builder?.RetainLogOnly();
            _patchBuildCancellation?.Dispose();
            _patchBuildCancellation = null;
            SetPatchControls(false);
        }
    }

    private void CancelAppPatch_Click(object sender, RoutedEventArgs e) => _patchBuildCancellation?.Cancel();

    private void SetPatchControls(bool building, bool updateControls = true)
    {
        ImportAppPatchButton.IsEnabled = !building;
        RestoreAppPatchButton.IsEnabled = !building && File.Exists(PreviousPatchedAppZip);
        CancelAppPatchButton.IsEnabled = building;
        ReplaceInternalCodeButton.IsEnabled = !building;
        RemoveInternalCodeOverrideButton.IsEnabled = !building;
        if (updateControls && _updateFromZipButton is not null) _updateFromZipButton.IsEnabled = !building;
        if (updateControls && _installUpdateButton is not null) _installUpdateButton.IsEnabled = !building;
    }

    private async Task FlushWorkspaceBeforePatchAsync()
    {
        Interlocked.Increment(ref _workspaceRevision);
        var snapshot = BuildProjectSnapshot();
        ProjectFileService.ValidateAndNormalize(snapshot);
        await _workspaceIoGate.WaitAsync();
        var path = Path.Combine(ApplicationData.Current.LocalFolder.Path, WorkspaceRecoveryFileName);
        var temporary = path + ".patch-save-" + Guid.NewGuid().ToString("N");
        try
        {
            await File.WriteAllTextAsync(temporary, JsonSerializer.Serialize(snapshot, WorkspaceJsonOptions));
            File.Move(temporary, path, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); _workspaceIoGate.Release(); }
    }

    private static async Task SavePatchRollbackAsync(CancellationToken token)
    {
        Directory.CreateDirectory(PatchStorage);
        var temporary = PreviousPatchedAppZip + ".new-" + Guid.NewGuid().ToString("N");
        try
        {
            await Task.Run(() =>
            {
                using var archive = ZipFile.Open(temporary, ZipArchiveMode.Create);
                var root = Path.GetFullPath(AppContext.BaseDirectory);
                foreach (var file in Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories))
                {
                    token.ThrowIfCancellationRequested();
                    archive.CreateEntryFromFile(file, Path.GetRelativePath(root, file).Replace('\\', '/'), CompressionLevel.Fastest);
                }
            }, token);
            File.Move(temporary, PreviousPatchedAppZip, overwrite: true);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }

    private async void RestoreAppPatch_Click(object sender, RoutedEventArgs e)
    {
        if (_patchBuildCancellation is not null || !File.Exists(PreviousPatchedAppZip)) return;
        if (_videoExportCancellation is not null) { await ShowErrorAsync("Export in progress", "Finish or cancel the export before restoring app code."); return; }
        var review = new ContentDialog { XamlRoot = RootNavigation.XamlRoot, Title = "Restore app before the last patch?", Content = "Your workspace will be saved. The app will restore its previous compiled code and restart.", PrimaryButtonText = "Restore & restart", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close };
        if (await review.ShowAsync() != ContentDialogResult.Primary) return;
        _patchBuildCancellation = new CancellationTokenSource();
        SetPatchControls(true);
        CancelAppPatchButton.IsEnabled = false;
        try
        {
            await FlushWorkspaceBeforePatchAsync();
            ShowActivityWatcher("Restore app", "Staging the previous app…", null, true);
            if (await _updateService.ApplyPortableZipFileAsync(PreviousPatchedAppZip, AppContext.BaseDirectory, "CubicalCompare.exe")) Application.Current.Exit();
            else throw new InvalidOperationException("The updater did not stage the restored app.");
        }
        catch (Exception ex) { FailActivityWatcher("Restore app failed", ex.Message); await ShowErrorAsync("Could not restore app", ex.Message); }
        finally { _patchBuildCancellation.Dispose(); _patchBuildCancellation = null; SetPatchControls(false); }
    }
}
