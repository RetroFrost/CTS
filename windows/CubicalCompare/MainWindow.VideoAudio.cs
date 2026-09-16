using Windows.Media.Editing;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private Task AddSoundtrackAsync(StorageFile videoFile, StorageFile outputFile, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(_soundtrackPath) || !File.Exists(_soundtrackPath))
            throw new FileNotFoundException("The selected soundtrack is no longer available.", _soundtrackPath);

        return AddSoundtrackAsync(
            videoFile,
            outputFile,
            _soundtrackPath,
            _soundtrackVolume,
            _soundtrackLoop,
            cancellationToken);
    }

    private async Task AddSoundtrackAsync(
        StorageFile videoFile,
        StorageFile outputFile,
        string soundtrackPath,
        double soundtrackVolume,
        bool soundtrackLoop,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(soundtrackPath) || !File.Exists(soundtrackPath))
            throw new FileNotFoundException("The soundtrack is no longer available.", soundtrackPath);

        var composition = new MediaComposition();
        var clip = await MediaClip.CreateFromFileAsync(videoFile);
        composition.Clips.Add(clip);

        var audioFile = await StorageFile.GetFileFromPathAsync(soundtrackPath);
        var videoDuration = clip.OriginalDuration;
        var delay = TimeSpan.Zero;
        var copies = 0;

        while (delay < videoDuration && copies < 512)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var track = await BackgroundAudioTrack.CreateFromFileAsync(audioFile);
            if (track.OriginalDuration <= TimeSpan.Zero)
                throw new InvalidDataException("The soundtrack duration could not be read.");

            track.Volume = Math.Clamp(soundtrackVolume, 0, 1);
            track.Delay = delay;
            var remaining = videoDuration - delay;
            if (track.OriginalDuration > remaining)
                track.TrimTimeFromEnd = track.OriginalDuration - remaining;
            composition.BackgroundAudioTracks.Add(track);

            copies++;
            if (!soundtrackLoop) break;
            delay += track.OriginalDuration;
        }

        await RenderSoundtrackCompositionAsync(composition, outputFile, cancellationToken);
    }
}
