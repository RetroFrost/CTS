using System.Runtime.InteropServices;
using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using SkiaSharp;
using Windows.Foundation;
using Windows.Media.Core;
using Windows.Media.MediaProperties;
using Windows.Media.Transcoding;
using Windows.Security.Cryptography;
using Windows.Storage;
using Windows.Storage.Streams;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private IAsyncActionWithProgress<double>? _videoExportOperation;
    private bool _videoExportInProgress;

    private async void ExportVideo_Click(object sender, RoutedEventArgs e)
    {
        if (_videoExportInProgress)
        {
            TimelineStatusText.Text = "A video export is already running.";
            return;
        }

        if (_legacyRenderer is null)
        {
            await ShowErrorAsync("Load a renderer first", "Video export uses the active Renderer v2/v3 package. Load a renderer, verify the timeline preview, then export.");
            return;
        }

        var file = await PickSaveFileAsync("MP4 video", ".mp4", SafeFileStem(ProjectName));
        if (file is null)
            return;

        _videoExportInProgress = true;
        ExportVideoButton.IsEnabled = false;
        ExportCancelButton.Visibility = Visibility.Visible;
        ExportProgressBar.Visibility = Visibility.Visible;
        ExportProgressBar.Value = 0;
        ExportStatusText.Text = "Preparing video export…";

        try
        {
            // Use an independent evaluator so scrubbing/edit preview cannot share mutable Skia state
            // or image caches with the Media Foundation worker thread during a long export.
            using var renderer = LegacyRendererAdapter.Load(_legacyRenderer.SourcePath);
            var project = BuildProject();
            var width = Math.Clamp(project.Width, 320, 3840);
            var height = Math.Clamp(project.Height, 240, 2160);
            var fps = Math.Clamp(project.Fps, 1, 120);
            var frameCount = Math.Max(1, renderer.FrameCount(project));

            using var output = await file.OpenAsync(FileAccessMode.ReadWrite);
            output.Size = 0;

            var inputProperties = VideoEncodingProperties.CreateUncompressed(MediaEncodingSubtypes.Bgra8, (uint)width, (uint)height);
            inputProperties.FrameRate.Numerator = (uint)fps;
            inputProperties.FrameRate.Denominator = 1;
            inputProperties.PixelAspectRatio.Numerator = 1;
            inputProperties.PixelAspectRatio.Denominator = 1;

            var descriptor = new VideoStreamDescriptor(inputProperties);
            var mediaSource = new MediaStreamSource(descriptor)
            {
                BufferTime = TimeSpan.Zero,
            };

            var nextFrame = 0;
            var frameDurationTicks = Math.Max(1L, TimeSpan.TicksPerSecond / fps);
            Exception? renderFailure = null;

            mediaSource.Starting += (_, args) =>
            {
                if (args.Request.StartPosition is not null)
                    args.Request.SetActualStartPosition(TimeSpan.Zero);
            };

            mediaSource.SampleRequested += (_, args) =>
            {
                var deferral = args.Request.GetDeferral();
                try
                {
                    if (renderFailure is not null || nextFrame >= frameCount)
                    {
                        args.Request.Sample = null;
                        return;
                    }

                    var frameIndex = nextFrame++;
                    using var rendered = renderer.Render(project, frameIndex, width, height);
                    using var bgra = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
                    using (var canvas = new SKCanvas(bgra))
                    {
                        canvas.Clear(SKColors.Black);
                        canvas.DrawBitmap(rendered, new SKRect(0, 0, width, height));
                        canvas.Flush();
                    }

                    if (bgra.RowBytes != width * 4)
                        throw new InvalidOperationException($"Unexpected video frame stride {bgra.RowBytes}; expected {width * 4}.");

                    var byteCount = checked(width * height * 4);
                    var bytes = new byte[byteCount];
                    Marshal.Copy(bgra.GetPixels(), bytes, 0, byteCount);
                    var buffer = CryptographicBuffer.CreateFromByteArray(bytes);
                    var timestamp = TimeSpan.FromTicks(frameIndex * frameDurationTicks);
                    var sample = MediaStreamSample.CreateFromBuffer(buffer, timestamp);
                    sample.Duration = TimeSpan.FromTicks(frameDurationTicks);
                    args.Request.Sample = sample;
                }
                catch (Exception ex)
                {
                    renderFailure = ex;
                    args.Request.Sample = null;
                }
                finally
                {
                    deferral.Complete();
                }
            };

            var profile = MediaEncodingProfile.CreateMp4(VideoEncodingQuality.HD1080p);
            profile.Audio = null;
            profile.Video.Width = (uint)width;
            profile.Video.Height = (uint)height;
            profile.Video.FrameRate.Numerator = (uint)fps;
            profile.Video.FrameRate.Denominator = 1;
            profile.Video.PixelAspectRatio.Numerator = 1;
            profile.Video.PixelAspectRatio.Denominator = 1;
            profile.Video.Bitrate = width >= 1920 ? 12_000_000u : 8_000_000u;

            var transcoder = new MediaTranscoder
            {
                HardwareAccelerationEnabled = true,
            };

            var prepared = await transcoder.PrepareMediaStreamSourceTranscodeAsync(mediaSource, output, profile);
            if (!prepared.CanTranscode)
                throw new InvalidOperationException($"Windows could not prepare the MP4 encoder ({prepared.FailureReason}).");

            ExportStatusText.Text = $"Rendering {frameCount:N0} frames · {width}×{height} · {fps} FPS";
            var operation = prepared.TranscodeAsync();
            _videoExportOperation = operation;
            operation.Progress += (_, progress) =>
            {
                DispatcherQueue.TryEnqueue(() =>
                {
                    ExportProgressBar.Value = Math.Clamp(progress, 0, 100);
                    ExportStatusText.Text = $"Exporting video… {progress:0}%";
                });
            };

            await operation;
            _videoExportOperation = null;

            if (renderFailure is not null)
                throw new InvalidOperationException("A renderer frame failed during export.", renderFailure);

            ExportProgressBar.Value = 100;
            ExportStatusText.Text = $"Exported {Path.GetFileName(file.Path)}";
            TimelineStatusText.Text = "Video export complete";
        }
        catch (TaskCanceledException)
        {
            TryDeleteExport(file.Path);
            ExportStatusText.Text = "Video export cancelled.";
            TimelineStatusText.Text = "Export cancelled";
        }
        catch (OperationCanceledException)
        {
            TryDeleteExport(file.Path);
            ExportStatusText.Text = "Video export cancelled.";
            TimelineStatusText.Text = "Export cancelled";
        }
        catch (Exception ex)
        {
            TryDeleteExport(file.Path);
            ExportStatusText.Text = "Video export failed.";
            TimelineStatusText.Text = $"Export failed: {ex.Message}";
            App.WriteLog("Video export failed", ex);
            await ShowErrorAsync("Could not export video", ex.Message);
        }
        finally
        {
            _videoExportOperation = null;
            _videoExportInProgress = false;
            ExportVideoButton.IsEnabled = true;
            ExportCancelButton.Visibility = Visibility.Collapsed;
        }
    }

    private void CancelVideoExport_Click(object sender, RoutedEventArgs e)
    {
        if (_videoExportOperation is null)
            return;

        ExportStatusText.Text = "Cancelling export…";
        _videoExportOperation.Cancel();
    }

    private static void TryDeleteExport(string path)
    {
        try
        {
            if (File.Exists(path))
                File.Delete(path);
        }
        catch
        {
            // Best-effort cleanup only; never replace the actual export error with cleanup noise.
        }
    }
}
