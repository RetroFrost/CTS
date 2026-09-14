using Windows.Media.Editing;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private async Task AddSoundtrackAsync(StorageFile videoFile, StorageFile outputFile, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(_soundtrackPath) || !File.Exists(_soundtrackPath))
            throw new FileNotFoundException("The selected soundtrack is no longer available.", _soundtrackPath);

        var composition = new MediaComposition();
        var clip = await MediaClip.CreateFromFileAsync(videoFile);
        composition.Clips.Add(clip);

        var audioFile = await StorageFile.GetFileFromPathAsync(_soundtrackPath);
        var videoDuration = clip.OriginalDuration;
        var delay = TimeSpan.Zero;
        var copies = 0;

        while (delay < videoDuration && copies < 512)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var track = await BackgroundAudioTrack.CreateFromFileAsync(audioFile);
            if (track.OriginalDuration <= TimeSpan.Zero)
                throw new InvalidDataException("The soundtrack duration could not be read.");

            track.Volume = Math.Clamp(_soundtrackVolume, 0, 1);
            track.Delay = delay;
            var remaining = videoDuration - delay;
            if (track.OriginalDuration > remaining)
                track.TrimTimeFromEnd = track.OriginalDuration - remaining;
            composition.BackgroundAudioTracks.Add(track);

            copies++;
            if (!_soundtrackLoop) break;
            delay += track.OriginalDuration;
        }

        await RenderSoundtrackCompositionAsync(composition, outputFile, cancellationToken);
    }
}
