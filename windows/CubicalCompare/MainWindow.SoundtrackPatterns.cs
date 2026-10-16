using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private async void SaveSoundtrackPattern_Click(object sender, RoutedEventArgs e)
    {
        if (_audioImportBusy) return;
        var tracks = CurrentSoundtrackPaths();
        if (tracks.Count == 0)
        {
            await ShowErrorAsync("No audio tracks", "Add soundtracks in the Audio tab before saving a pattern.");
            return;
        }

        var file = await PickSaveFileAsync("M3U audio playlist", ".m3u", SafeFileStem(_projectDisplayName) + "-audio-pattern");
        if (file is null) return;

        try
        {
            await M3uPlaylistService.SaveAsync(file.Path, tracks);
            var status = $"Saved M3U pattern · {tracks.Count} ordered track(s) · {file.Name}";
            TimelineStatusText.Text = status;
            if (_audioImportStatus is not null) _audioImportStatus.Text = status;
        }
        catch (Exception ex)
        {
            App.WriteLog("M3U playlist export failed", ex);
            await ShowErrorAsync("Could not save audio pattern", ex.Message);
        }
    }

    private async void ImportSoundtrackPattern_Click(object sender, RoutedEventArgs e)
    {
        if (_audioImportBusy) return;
        var file = await PickFileAsync([".m3u", ".m3u8"]);
        if (file is null) return;

        _audioImportBusy = true;
        try
        {
            var ordered = await M3uPlaylistService.LoadAsync(file.Path);
            var missing = ordered.Where(path => !File.Exists(path))
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .Take(12).ToArray();
            if (missing.Length > 0)
                throw new FileNotFoundException("M3U references missing audio files. Move the playlist next to its original music or repair these paths:\n" +
                    string.Join("\n", missing.Select(Path.GetFileName)) +
                    (missing.Length < ordered.Count && ordered.Count > 12 ? "\n(Additional files may also be missing.)" : string.Empty));

            // Verify the new playlist before touching the current, working audio order.
            var durations = new Dictionary<string, TimeSpan>(StringComparer.OrdinalIgnoreCase);
            foreach (var path in ordered.Distinct(StringComparer.OrdinalIgnoreCase))
            {
                var trackFile = await global::Windows.Storage.StorageFile.GetFileFromPathAsync(path);
                var audio = await ReadSoundtrackAsync(trackFile);
                if (audio.OriginalDuration <= TimeSpan.Zero)
                    throw new InvalidDataException($"Not playable audio: {Path.GetFileName(path)}");
                durations[path] = audio.OriginalDuration;
            }

            StopAudioAudition();
            _soundtrackPaths.Clear();
            _soundtrackPaths.AddRange(ordered);
            _soundtrackPath = _soundtrackPaths[0];
            foreach (var pair in durations)
                _soundtrackDurations[pair.Key] = pair.Value;
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
            var message = $"Imported M3U pattern · {ordered.Count} ordered tracks · {file.Name}";
            TimelineStatusText.Text = message;
            if (_audioImportStatus is not null) _audioImportStatus.Text = message;
        }
        catch (Exception ex)
        {
            App.WriteLog("M3U playlist import failed", ex);
            TimelineStatusText.Text = "M3U import failed · existing playlist retained";
            await ShowErrorAsync("Could not import audio pattern", ex.Message);
        }
        finally
        {
            _audioImportBusy = false;
        }
    }
}
