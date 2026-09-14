using Windows.Media.Editing;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private async Task AddSoundtrackAsync(StorageFile videoFile, StorageFile outputFile, CancellationToken cancellationToken)
    {
        var composition = new MediaComposition();
        var clip = await MediaClip.CreateFromFileAsync(videoFile);
        composition.Clips.Add(clip);
    }
}
