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

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private IAsyncActionWithProgress<double>? _videoExportOperation;
    private CancellationTokenSource? _videoExportCancellation;
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

        _videoExportCancellation?.Dispose();
        _videoExportCancellation = new CancellationTokenSource();
        var cancellationToken = _videoExportCancellation.Token;
        StorageFile? temporaryVideo = null;

        try
        {
            using var renderer = LegacyRendererAdapter.Load(_legacyRenderer.SourcePath);
            var project = BuildProject();
            var width = Math.Clamp(project.Width, 320, 3840);
            var height = Math.Clamp(project.Height, 240, 2160);
            var fps = Math.Clamp(project.Fps, 1, 120);
            var frameCount = Math.Max(1, renderer.FrameCount(project));
            var hasSoundtrack = !string.IsNullOrWhiteSpace(_soundtrackPath) && File.Exists(_soundtrackPath);

            if (hasSoundtrack)
            {
                temporaryVideo = await ApplicationData.Current.TemporaryFolder.CreateFileAsync(
                    $"cc-silent-{Guid.NewGuid():N}.mp4",
                    CreationCollisionOption.ReplaceExisting);
            }

            var renderTarget = temporaryVideo ?? file;
            using var output = await renderTarget.OpenAsync(FileAccessMode.ReadWrite);
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

            var nextFrame = -1;
            var frameDurationTicks = Math.Max(1L, TimeSpan.TicksPerSecond / fps);
            Exception? renderFailure = null;
            var sampleGate = new SemaphoreSlim(1, 1);
            var lastUiProgressTicks = 0L;

            mediaSource.Starting += (_, args) =>
            {
                if (args.Request.StartPosition is not null)
                    args.Request.SetActualStartPosition(TimeSpan.Zero);
            };

            mediaSource.SampleRequested += (_, args) =>
            {
                var request = args.Request;
                var deferral = request.GetDeferral();

                _ = Task.Run(async () =>
                {
                    var gateHeld = false;
                    try
                    {
                        await sampleGate.WaitAsync(cancellationToken).ConfigureAwait(false);
                        gateHeld = true;
                        cancellationToken.ThrowIfCancellationRequested();

                        if (Volatile.Read(ref renderFailure) is not null)
                        {
                            request.Sample = null;
                            return;
                        }

                        var frameIndex = Interlocked.Increment(ref nextFrame);
                        if (frameIndex >= frameCount)
                        {
                            request.Sample = null;
                            return;
                        }

                        using var rendered = renderer.Render(project, frameIndex, width, height);
                        cancellationToken.ThrowIfCancellationRequested();

                        if (rendered.Width != width || rendered.Height != height)
                            throw new InvalidOperationException($"Renderer returned {rendered.Width}x{rendered.Height}; expected {width}x{height}.");

                        var byteCount = checked(width * height * 4);
                        var bytes = GC.AllocateUninitializedArray<byte>(byteCount);
                        var pixels = rendered.GetPixels();
                        if (pixels == IntPtr.Zero)
                            throw new InvalidOperationException("Renderer returned a frame with no pixel buffer.");

                        if (rendered.ColorType == SKColorType.Bgra8888)
                        {
                            var packedStride = width * 4;
                            if (rendered.RowBytes == packedStride)
                            {
                                Marshal.Copy(pixels, bytes, 0, byteCount);
                            }
                            else
                            {
                                for (var y = 0; y < height; y++)
                                    Marshal.Copy(IntPtr.Add(pixels, y * rendered.RowBytes), bytes, y * packedStride, packedStride);
                            }
                        }
                        else
                        {
                            using var bgra = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
                            using (var canvas = new SKCanvas(bgra))
                            {
                                canvas.Clear(SKColors.Black);
                                canvas.DrawBitmap(rendered, new SKRect(0, 0, width, height));
                                canvas.Flush();
                            }
                            Marshal.Copy(bgra.GetPixels(), bytes, 0, byteCount);
                        }

                        var buffer = CryptographicBuffer.CreateFromByteArray(bytes);
                        var timestamp = TimeSpan.FromTicks(frameIndex * frameDurationTicks);
                        var sample = MediaStreamSample.CreateFromBuffer(buffer, timestamp);
                        sample.Duration = TimeSpan.FromTicks(frameDurationTicks);
                        request.Sample = sample;

                        var now = Environment.TickCount64;
                        if (frameIndex == frameCount - 1 || now - Interlocked.Read(ref lastUiProgressTicks) >= 125)
                        {
                            Interlocked.Exchange(ref lastUiProgressTicks, now);
                            var renderedCount = frameIndex + 1;
                            var progress = renderedCount * 100.0 / frameCount;
                            DispatcherQueue.TryEnqueue(() =>
                            {
                                ExportProgressBar.Value = Math.Max(ExportProgressBar.Value, Math.Clamp(progress, 0, 99.5));
                                ExportStatusText.Text = $"Rendering video… {renderedCount:N0} / {frameCount:N0} frames · {progress:0.0}%";
                            });
                        }
                    }
                    catch (OperationCanceledException)
                    {
                        request.Sample = null;
                    }
                    catch (Exception ex)
                    {
                        Interlocked.CompareExchange(ref renderFailure, ex, null);
                        request.Sample = null;
                    }
                    finally
                    {
                        if (gateHeld)
                            sampleGate.Release();
                        deferral.Complete();
                    }
                }, CancellationToken.None);
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

            ExportStatusText.Text = $"Starting {frameCount:N0}-frame export · {width}×{height} · {fps} FPS";
            var operation = prepared.TranscodeAsync();
            _videoExportOperation = operation;
            operation.Progress += (_, progress) =>
            {
                DispatcherQueue.TryEnqueue(() =>
                {
                    ExportProgressBar.Value = Math.Max(ExportProgressBar.Value, Math.Clamp(progress, 0, 99.5));
                });
            };

            await operation;
            _videoExportOperation = null;
            cancellationToken.ThrowIfCancellationRequested();

            if (renderFailure is not null)
                throw new InvalidOperationException("A renderer frame failed during export.", renderFailure);

            output.Dispose();

            if (temporaryVideo is not null)
            {
                ExportProgressBar.Value = 0;
                await AddSoundtrackAsync(temporaryVideo, file, cancellationToken);
            }

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
            if (temporaryVideo is not null)
                TryDeleteExport(temporaryVideo.Path);
            _videoExportOperation = null;
            _videoExportCancellation?.Dispose();
            _videoExportCancellation = null;
            _videoExportInProgress = false;
            ExportVideoButton.IsEnabled = true;
            ExportCancelButton.Visibility = Visibility.Collapsed;
        }
    }

    private void CancelVideoExport_Click(object sender, RoutedEventArgs e)
    {
        if (!_videoExportInProgress)
            return;

        ExportStatusText.Text = "Cancelling export…";
        _videoExportCancellation?.Cancel();
        _videoExportOperation?.Cancel();
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
        }
    }
}
