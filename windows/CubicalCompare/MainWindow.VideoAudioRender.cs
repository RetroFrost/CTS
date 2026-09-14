using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private async Task RenderSoundtrackCompositionAsync(MediaComposition composition, StorageFile outputFile, CancellationToken cancellationToken)
    {
        var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD1080p);
        profile.Video.Width = 1920;
        profile.Video.Height = 1080;
        profile.Video.FrameRate.Numerator = 60;
        profile.Video.FrameRate.Denominator = 1;
        profile.Video.PixelAspectRatio.Numerator = 1;
        profile.Video.PixelAspectRatio.Denominator = 1;
        profile.Video.Bitrate = 12_000_000;
        if (profile.Audio is not null) profile.Audio.Bitrate = 192_000;

        ExportStatusText.Text = "Adding soundtrack…";
        var operation = composition.RenderToFileAsync(outputFile, MediaTrimmingPreference.Precise, profile);
        using var registration = cancellationToken.Register(() => operation.Cancel());
        operation.Progress += (_, progress) =>
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                var p = Math.Clamp(progress, 0, 100);
                ExportProgressBar.Value = p;
                ExportStatusText.Text = $"Adding soundtrack… {p:0}%";
            });
        };

        var result = await operation;
        if (result != TranscodeFailureReason.None)
            throw new InvalidOperationException($"Windows could not combine the soundtrack ({result}).");
    }
}
