using OpenFileDialog = Microsoft.Win32.OpenFileDialog;
using SaveFileDialog = Microsoft.Win32.SaveFileDialog;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows;
using System.Windows.Controls;

namespace CubicalCompareV5;

public sealed class AudioTrack
{
    public AudioTrack(string path) { FullPath = Path.GetFullPath(path); }
    public string FullPath { get; }
    public string Label => (File.Exists(FullPath) ? "♫  " : "⚠  ") + Path.GetFileName(FullPath);
    public override string ToString() => Label;
}

public partial class MainWindow
{
    private readonly ObservableCollection<AudioTrack> _audioTracks = new();
    private bool _audioControlsUpdating = true;
    private double _audioVolume = 1.0;
    private bool _audioRepeat = true;
    private bool _audioFadeOut = true;

    private void InitializeAudioEditor()
    {
        AudioTrackList.ItemsSource = _audioTracks;
        _audioControlsUpdating = true;
        AudioVolumeSlider.Value = _audioVolume * 100;
        AudioRepeatCheck.IsChecked = _audioRepeat;
        AudioFadeCheck.IsChecked = _audioFadeOut;
        _audioControlsUpdating = false;
        RefreshAudioSummary();
    }

    private void RefreshAudioSummary()
    {
        if (AudioStatus is null) return;
        int missing = _audioTracks.Count(t => !File.Exists(t.FullPath));
        AudioStatus.Text = _audioTracks.Count == 0
            ? "No audio selected. Exports will contain video only."
            : missing > 0
                ? $"{_audioTracks.Count} tracks in order. Warning: {missing} missing file(s); relink before exporting."
                : $"{_audioTracks.Count} tracks, played consecutively in the displayed order.";
    }

    private void AudioAdd_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog
        {
            Title = "Add tracks to audio pattern",
            Multiselect = true,
            Filter = "Audio files|*.mp3;*.wav;*.flac;*.m4a;*.ogg;*.aac;*.wma;*.opus|All files|*.*"
        };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            if (_audioTracks.Count + dialog.FileNames.Length > M3uPlaylist.MaxTracks)
                throw new InvalidDataException("A pattern can contain at most 128 tracks.");
            foreach (var path in dialog.FileNames) _audioTracks.Add(new AudioTrack(path));
            AudioTrackList.SelectedIndex = _audioTracks.Count - 1;
            RefreshAudioSummary();
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void AudioRemove_Click(object sender, RoutedEventArgs e)
    {
        int index = AudioTrackList.SelectedIndex;
        if (index < 0) return;
        _audioTracks.RemoveAt(index);
        AudioTrackList.SelectedIndex = Math.Min(index, _audioTracks.Count - 1);
        RefreshAudioSummary();
    }

    private void AudioUp_Click(object sender, RoutedEventArgs e) => MoveAudioTrack(-1);
    private void AudioDown_Click(object sender, RoutedEventArgs e) => MoveAudioTrack(1);
    private void MoveAudioTrack(int offset)
    {
        int from = AudioTrackList.SelectedIndex;
        int to = from + offset;
        if (from < 0 || to < 0 || to >= _audioTracks.Count) return;
        _audioTracks.Move(from, to);
        AudioTrackList.SelectedIndex = to;
    }

    private void AudioVolume_Changed(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (_audioControlsUpdating) return;
        _audioVolume = Math.Clamp(AudioVolumeSlider.Value / 100d, 0d, 1d);
    }

    private void AudioRepeat_Changed(object sender, RoutedEventArgs e)
    {
        if (_audioControlsUpdating) return;
        _audioRepeat = AudioRepeatCheck.IsChecked == true;
    }

    private void AudioFade_Changed(object sender, RoutedEventArgs e)
    {
        if (_audioControlsUpdating) return;
        _audioFadeOut = AudioFadeCheck.IsChecked == true;
    }

    private void AudioSavePattern_Click(object sender, RoutedEventArgs e)
    {
        if (_audioTracks.Count == 0)
        {
            System.Windows.MessageBox.Show(this, "Add at least one soundtrack first.", "Audio pattern");
            return;
        }
        var dialog = new SaveFileDialog { Title = "Save soundtrack pattern", Filter = "M3U playlist|*.m3u", DefaultExt = ".m3u", AddExtension = true, FileName = "My-Comparison-Audio.m3u" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            M3uPlaylist.Save(dialog.FileName, _audioTracks.Select(t => t.FullPath));
            AudioStatus.Text = "Saved ordered pattern: " + Path.GetFileName(dialog.FileName);
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void AudioImportPattern_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new OpenFileDialog { Title = "Import soundtrack pattern", Filter = "M3U playlist|*.m3u;*.m3u8|All files|*.*" };
        if (dialog.ShowDialog(this) != true) return;
        try
        {
            var paths = M3uPlaylist.Load(dialog.FileName);
            if (paths.Count == 0) throw new InvalidDataException("The playlist has no audio entries.");
            if (_audioTracks.Count > 0 &&
                System.Windows.MessageBox.Show(this, "Replace the current audio track list with this pattern?", "Import audio pattern",
                    MessageBoxButton.YesNo, MessageBoxImage.Question) != MessageBoxResult.Yes) return;
            SetAudioTracks(paths);
            int missing = _audioTracks.Count(t => !File.Exists(t.FullPath));
            AudioStatus.Text = $"Imported {_audioTracks.Count} tracks in order." +
                (missing == 0 ? "" : $" {missing} files were not found; check their paths before exporting.");
        }
        catch (Exception ex) { ShowError(ex); }
    }

    private void SetAudioTracks(IEnumerable<string> paths)
    {
        var incoming = paths.Select(path => new AudioTrack(path)).ToList();
        if (incoming.Count > M3uPlaylist.MaxTracks) throw new InvalidDataException("The playlist is limited to 128 tracks.");
        _audioTracks.Clear();
        foreach (var track in incoming) _audioTracks.Add(track);
        AudioTrackList.SelectedIndex = _audioTracks.Count == 0 ? -1 : 0;
        RefreshAudioSummary();
    }

    private void LoadAudioFromProject(ComparisonProject project)
    {
        SetAudioTracks(project.AudioTracks ?? new List<string>());
        _audioVolume = double.IsFinite(project.AudioVolume) ? Math.Clamp(project.AudioVolume, 0, 1) : 1;
        _audioRepeat = project.AudioRepeat;
        _audioFadeOut = project.AudioFadeOut;
        _audioControlsUpdating = true;
        try
        {
            AudioVolumeSlider.Value = _audioVolume * 100;
            AudioRepeatCheck.IsChecked = _audioRepeat;
            AudioFadeCheck.IsChecked = _audioFadeOut;
        }
        finally { _audioControlsUpdating = false; }
    }

    private ComparisonProject BuildProjectSnapshot() => new()
    {
        Cards = _cards,
        AudioTracks = _audioTracks.Select(t => t.FullPath).ToList(),
        AudioVolume = _audioVolume,
        AudioRepeat = _audioRepeat,
        AudioFadeOut = _audioFadeOut
    };

    // Mixed codecs are normal for creator playlists; normalise to stereo/48k
    // and concatenate before muxing. The resulting temporary AAC file can
    // be looped as a *whole pattern*, not as separate individual songs.
    private async Task<string?> BuildAudioPatternAsync(string ffmpeg, string workFolder)
    {
        if (_audioTracks.Count == 0) return null;
        var paths = _audioTracks.Select(t => t.FullPath).ToList();
        var missing = paths.Where(p => !File.Exists(p)).ToList();
        if (missing.Count > 0)
            throw new FileNotFoundException("Audio pattern contains missing files: " +
                string.Join(", ", missing.Select(Path.GetFileName).Take(5)));
        Directory.CreateDirectory(workFolder);
        string destination = Path.Combine(workFolder, "pattern.m4a");
        var args = new List<string> { "-hide_banner", "-loglevel", "error", "-y" };
        foreach (var path in paths) { args.Add("-i"); args.Add(path); }
        if (paths.Count == 1) { args.Add("-map"); args.Add("0:a:0"); }
        else
        {
            var parts = new List<string>();
            for (int i = 0; i < paths.Count; i++)
                parts.Add($"[{i}:a:0]aresample=48000,aformat=sample_fmts=fltp:channel_layouts=stereo,asetpts=PTS-STARTPTS[a{i}]");
            parts.Add(string.Concat(Enumerable.Range(0, paths.Count).Select(i => $"[a{i}]")) +
                $"concat=n={paths.Count}:v=0:a=1[pattern]");
            args.Add("-filter_complex"); args.Add(string.Join(";", parts));
            args.Add("-map"); args.Add("[pattern]");
        }
        args.AddRange(["-vn", "-ar", "48000", "-ac", "2", "-c:a", "aac", "-b:a", "192k", destination]);
        await RunFfmpegAsync(ffmpeg, args);
        if (!File.Exists(destination) || new FileInfo(destination).Length == 0)
            throw new IOException("The audio pattern could not be encoded.");
        return destination;
    }

    private static async Task RunFfmpegAsync(string executable, IEnumerable<string> arguments)
    {
        using var proc = new Process();
        var info = new ProcessStartInfo
        {
            FileName = executable,
            CreateNoWindow = true,
            UseShellExecute = false,
            RedirectStandardError = true
        };
        foreach (var arg in arguments) info.ArgumentList.Add(arg);
        proc.StartInfo = info;
        if (!proc.Start()) throw new IOException("FFmpeg failed to start.");
        var errorTask = proc.StandardError.ReadToEndAsync();
        await proc.WaitForExitAsync();
        var error = await errorTask;
        if (proc.ExitCode != 0) throw new IOException("FFmpeg audio conversion failed: " + error);
    }
}
