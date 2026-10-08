using CubicalCompare.Core.Project;
using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private bool _soundtrackFadeOut = true;
    private Microsoft.UI.Dispatching.DispatcherQueueTimer? _audioAuditionFadeTimer;
    private Microsoft.UI.Xaml.Controls.CheckBox? _audioFadeOutCheckBox;

    private void UpdateAudioAuditionVolume()
    {
        var player = _audioAuditionPlayer;
        if (player is null) return;
        var session = player.PlaybackSession;
        var duration = session.NaturalDuration;
        var remaining = (duration - session.Position).TotalSeconds;
        var gain = _soundtrackFadeOut && duration > TimeSpan.Zero
            ? Math.Clamp(remaining / Math.Min(2, duration.TotalSeconds), 0, 1) : 1;
        player.Volume = Math.Clamp(_soundtrackVolume * gain, 0, 1);
    }

    private void StartAudioAuditionFade()
    {
        if (_audioAuditionFadeTimer is null)
        {
            _audioAuditionFadeTimer = DispatcherQueue.CreateTimer();
            _audioAuditionFadeTimer.Interval = TimeSpan.FromMilliseconds(20);
            _audioAuditionFadeTimer.Tick += (_, _) => UpdateAudioAuditionVolume();
        }
        _audioAuditionFadeTimer.Start();
    }

    private void StopAudioAudition()
    {
        _audioAuditionFadeTimer?.Stop();
        _audioAuditionPlayer?.Dispose();
        _audioAuditionPlayer = null;
    }

    internal static async Task<IReadOnlyList<BackgroundAudioTrack>> FadePlaylistEndingAsync(
        IReadOnlyList<BackgroundAudioTrack> tracks, IReadOnlyList<string> paths,
        List<string> temporaryFiles, CancellationToken cancellationToken)
    {
        if (tracks.Count == 0) return tracks;
        var end = tracks.Max(t => t.Delay + t.TrimmedDuration);
        var fadeDuration = end < TimeSpan.FromSeconds(2) ? end : TimeSpan.FromSeconds(2);
        if (fadeDuration <= TimeSpan.Zero) return tracks;
        var start = end - fadeDuration;
        var result = new List<BackgroundAudioTrack>();
        for (var index = 0; index < tracks.Count; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var track = tracks[index];
            var trackEnd = track.Delay + track.TrimmedDuration;
            if (trackEnd <= start) { result.Add(track); continue; }
            var overlapStart = track.Delay > start ? track.Delay : start;
            var duration = trackEnd - overlapStart;
            var sourceStart = track.TrimTimeFromStart + overlapStart - track.Delay;
            var source = await StorageFile.GetFileFromPathAsync(paths[index % paths.Count]);
            var tail = await ApplicationData.Current.TemporaryFolder.CreateFileAsync("cc-audio-fade-" + Guid.NewGuid().ToString("N") + ".wav", CreationCollisionOption.ReplaceExisting);
            temporaryFiles.Add(tail.Path);
            var profile = MediaEncodingProfile.CreateWav(AudioEncodingQuality.High);
            profile.Audio = AudioEncodingProperties.CreatePcm(48000, 2, 16);
            var transcoder = new MediaTranscoder { TrimStartTime = sourceStart, TrimStopTime = sourceStart + duration, AlwaysReencode = true };
            var prepare = transcoder.PrepareFileTranscodeAsync(source, tail, profile);
            using var prepareCancellation = cancellationToken.Register(() => prepare.Cancel());
            var prepared = await prepare;
            cancellationToken.ThrowIfCancellationRequested();
            if (!prepared.CanTranscode) throw new InvalidOperationException($"Could not prepare audio fade ({prepared.FailureReason}).");
            var transcode = prepared.TranscodeAsync();
            using var transcodeCancellation = cancellationToken.Register(() => transcode.Cancel());
            await transcode;
            var startGain = Math.Clamp((end - overlapStart).TotalSeconds / fadeDuration.TotalSeconds, 0, 1);
            var endGain = Math.Clamp((end - trackEnd).TotalSeconds / fadeDuration.TotalSeconds, 0, 1);
            await Task.Run(() =>
            {
                var bytes = File.ReadAllBytes(tail.Path);
                if (bytes.Length > 4 * 1024 * 1024) throw new InvalidDataException("Decoded fade tail is unexpectedly large.");
                PcmAudioFade.Apply(bytes, startGain, endGain, cancellationToken);
                File.WriteAllBytes(tail.Path, bytes);
            }, cancellationToken);
            var faded = await ReadSoundtrackAsync(tail, cancellationToken);
            if (Math.Abs((faded.OriginalDuration - duration).TotalSeconds) > .05)
                throw new InvalidDataException("Decoded audio fade duration differs from the selected tail.");
            if (faded.OriginalDuration > duration) faded.TrimTimeFromEnd = faded.OriginalDuration - duration;
            faded.Delay = overlapStart;
            faded.Volume = track.Volume;
            if (overlapStart > track.Delay)
            {
                var head = track.Clone();
                head.TrimTimeFromEnd += trackEnd - overlapStart;
                result.Add(head);
            }
            result.Add(faded);
        }
        return result;
    }
}
