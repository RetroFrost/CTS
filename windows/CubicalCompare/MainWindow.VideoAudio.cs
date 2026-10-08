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
            CurrentSoundtrackPaths(),
            _soundtrackVolume,
            _soundtrackLoop,
            cancellationToken);
    }

    private async Task AddSoundtrackAsync(
        StorageFile videoFile,
        StorageFile outputFile,
        IReadOnlyList<string> soundtrackPaths,
        double soundtrackVolume,
        bool soundtrackLoop,
        CancellationToken cancellationToken)
    {
        if (soundtrackPaths.Count == 0) throw new InvalidDataException("No soundtrack selected.");
        var composition = new MediaComposition();
        var clip = await MediaClip.CreateFromFileAsync(videoFile);
        composition.Clips.Add(clip);
        foreach (var track in await BuildPlaylistTracksAsync(soundtrackPaths, clip.OriginalDuration, soundtrackVolume, soundtrackLoop, cancellationToken))
            composition.BackgroundAudioTracks.Add(track);
        await RenderSoundtrackCompositionAsync(composition, outputFile, cancellationToken);
    }
    internal static async Task<IReadOnlyList<BackgroundAudioTrack>> BuildPlaylistTracksAsync(
        IReadOnlyList<string> soundtrackPaths, TimeSpan videoDuration, double soundtrackVolume, bool soundtrackLoop, CancellationToken cancellationToken)
    {
        if (soundtrackPaths.Count == 0) throw new InvalidDataException("No soundtrack selected.");
        var tracks = new List<BackgroundAudioTrack>();
        var templates = new List<BackgroundAudioTrack>();
        foreach (var path in soundtrackPaths)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var file = await StorageFile.GetFileFromPathAsync(path);
            var track = await BackgroundAudioTrack.CreateFromFileAsync(file);
            if (track.OriginalDuration <= TimeSpan.Zero) throw new InvalidDataException($"No playable audio: {Path.GetFileName(path)}");
            templates.Add(track);
        }
        var delay = TimeSpan.Zero;
        do
        {
            foreach (var template in templates)
            {
                cancellationToken.ThrowIfCancellationRequested();
                if (delay >= videoDuration) break;
                if (tracks.Count >= 10000)
                    throw new InvalidDataException("The soundtrack requires more than 10,000 segments. Use longer audio tracks or disable repeat.");
                var track = template.Clone();
                track.Volume = Math.Clamp(soundtrackVolume, 0, 1);
                track.Delay = delay;
                var remaining = videoDuration - delay;
                if (track.OriginalDuration > remaining) track.TrimTimeFromEnd = track.OriginalDuration - remaining;
                tracks.Add(track);
                delay += track.OriginalDuration;
            }
        } while (soundtrackLoop && delay < videoDuration);
        return tracks;
    }

}
