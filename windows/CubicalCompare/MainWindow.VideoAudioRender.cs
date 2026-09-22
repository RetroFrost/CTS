using Windows.Media.Editing;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Storage;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private async Task RenderSoundtrackCompositionAsync(MediaComposition composition, StorageFile outputFile, CancellationToken cancellationToken)
    {
        var width = Math.Clamp(SelectedExportWidth, 320, 3840);
        var height = Math.Clamp(SelectedExportHeight, 240, 2160);
        var fps = Math.Clamp(SelectedExportFps, 1, 120);

        var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD1080p);
        profile.Video.Width = (uint)width;
        profile.Video.Height = (uint)height;
        profile.Video.FrameRate.Numerator = (uint)fps;
        profile.Video.FrameRate.Denominator = 1;
        profile.Video.PixelAspectRatio.Numerator = 1;
        profile.Video.PixelAspectRatio.Denominator = 1;
        profile.Video.Bitrate = CalculateVideoBitrate(width, height, fps);
        if (profile.Audio is not null) profile.Audio.Bitrate = 192_000;

        ExportStatusText.Text = "Adding soundtrack…";
        var operation = composition.RenderToFileAsync(outputFile, MediaTrimmingPreference.Fast, profile);
        using var registration = cancellationToken.Register(() => operation.Cancel());
        operation.Progress += (_, progress) =>
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                var p = Math.Clamp(progress, 0, 100);
                ExportProgressBar.Value = p;
                var writtenBytes = File.Exists(outputFile.Path) ? new FileInfo(outputFile.Path).Length : 0;
                var detail = $"Adding soundtrack · {p:0}% · {FormatByteCount(writtenBytes)} written";
                ExportStatusText.Text = detail;
                UpdateActivityWatcher("Video export", detail, p);
            });
        };

        var result = await operation;
        if (result != TranscodeFailureReason.None)
            throw new InvalidOperationException($"Windows could not combine the soundtrack ({result}).");
    }
}
