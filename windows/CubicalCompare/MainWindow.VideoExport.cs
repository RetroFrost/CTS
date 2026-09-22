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
        ShowActivityWatcher("Video export", $"Preparing {Path.GetFileName(file.Path)}…", 0);

        _videoExportCancellation?.Dispose();
        _videoExportCancellation = new CancellationTokenSource();
        var cancellationToken = _videoExportCancellation.Token;
        StorageFile? stagedRenderVideo = null;
        StorageFile? stagedFinalVideo = null;
        StorageFile? temporaryRendererAudio = null;
        List<LegacyRendererAdapter>? exportRenderers = null;
        Dictionary<int, Task<byte[]>>? pendingFrameRenders = null;

        try
        {
            using var renderer = LegacyRendererAdapter.Load(_legacyRenderer.SourcePath);
            var project = BuildProject();

            // Output profile is intentionally applied to the renderer project before
            // FrameCount is calculated. This keeps animation duration/timing correct at
            // every selectable frame rate rather than merely changing MP4 timestamps.
            project.Width = Math.Clamp(SelectedExportWidth, 320, 3840);
            project.Height = Math.Clamp(SelectedExportHeight, 240, 2160);
            project.Fps = Math.Clamp(SelectedExportFps, 1, 120);

            var width = project.Width;
            var height = project.Height;
            var fps = project.Fps;
            var frameCount = Math.Max(1, renderer.FrameCount(project));

            var soundtrackPath = _soundtrackPath;
            var soundtrackVolume = _soundtrackVolume;
            var soundtrackLoop = _soundtrackLoop;
            if (string.IsNullOrWhiteSpace(soundtrackPath) || !File.Exists(soundtrackPath))
            {
                var embeddedAudio = renderer.EmbeddedAudio;
                if (embeddedAudio is not null)
                {
                    var extension = Path.GetExtension(embeddedAudio.FileName);
                    if (string.IsNullOrWhiteSpace(extension) || extension.Length > 12)
                        extension = ".m4a";
                    temporaryRendererAudio = await ApplicationData.Current.TemporaryFolder.CreateFileAsync(
                        $"cc-renderer-audio-{Guid.NewGuid():N}{extension}",
                        CreationCollisionOption.ReplaceExisting);
                    await FileIO.WriteBytesAsync(temporaryRendererAudio, embeddedAudio.Data);
                    soundtrackPath = temporaryRendererAudio.Path;
                    soundtrackVolume = embeddedAudio.Volume;
                    soundtrackLoop = embeddedAudio.Loop;
                }
            }

            var hasSoundtrack = !string.IsNullOrWhiteSpace(soundtrackPath) && File.Exists(soundtrackPath);

            // Render next to the selected destination and only replace the user's file
            // once every render/mux stage has succeeded. This prevents a cancelled or
            // failed export from truncating an existing MP4 selected in FileSavePicker.
            var destinationDirectory = Path.GetDirectoryName(file.Path)
                ?? throw new InvalidOperationException("Could not determine the export destination folder.");
            var destinationFolder = await StorageFolder.GetFolderFromPathAsync(destinationDirectory);
            stagedFinalVideo = await destinationFolder.CreateFileAsync(
                $".cc-export-{Guid.NewGuid():N}.mp4",
                CreationCollisionOption.GenerateUniqueName);
            if (hasSoundtrack)
            {
                stagedRenderVideo = await destinationFolder.CreateFileAsync(
                    $".cc-silent-{Guid.NewGuid():N}.mp4",
                    CreationCollisionOption.GenerateUniqueName);
            }

            var renderTarget = stagedRenderVideo ?? stagedFinalVideo;
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
            using var sampleGate = new SemaphoreSlim(1, 1);
            var lastUiProgressTicks = 0L;

            // Rendering, not H.264 encoding, is normally the expensive part of a
            // comparison export. MediaTranscoder already has hardware acceleration
            // enabled below, so feed it from a bounded multi-core renderer pool.
            // Each worker owns its own renderer engine/caches to avoid sharing Skia
            // dictionaries across threads.
            var frameBytes = Math.Max(1L, (long)width * height * 4);
            const long renderQueueBudgetBytes = 384L * 1024 * 1024;
            var maxBufferedFrames = (int)Math.Clamp(
                renderQueueBudgetBytes / frameBytes,
                2L,
                32L);

            var rendererPackageBytes = Math.Max(
                1L,
                File.Exists(_legacyRenderer.SourcePath)
                    ? new FileInfo(_legacyRenderer.SourcePath).Length
                    : 32L * 1024 * 1024);
            var availableMemory = Math.Max(
                512L * 1024 * 1024,
                GC.GetGCMemoryInfo().TotalAvailableMemoryBytes);
            var rendererPoolBudget = Math.Clamp(
                availableMemory / 5,
                256L * 1024 * 1024,
                1536L * 1024 * 1024);
            var estimatedRendererBytes = Math.Max(
                32L * 1024 * 1024,
                rendererPackageBytes * 2);
            var maxWorkersByMemory = (int)Math.Clamp(
                rendererPoolBudget / estimatedRendererBytes,
                1L,
                16L);
            var exportWorkerCount = Math.Max(
                1,
                Math.Min(
                    Environment.ProcessorCount,
                    Math.Min(maxBufferedFrames, maxWorkersByMemory)));
            var prefetchDepth = Math.Clamp(
                exportWorkerCount * 2,
                exportWorkerCount,
                maxBufferedFrames);

            exportRenderers = new List<LegacyRendererAdapter>(exportWorkerCount);
            var exportSessions = new List<LegacyRendererAdapter.RenderSession>(exportWorkerCount);
            for (var workerIndex = 0; workerIndex < exportWorkerCount; workerIndex++)
            {
                var workerRenderer = LegacyRendererAdapter.Load(_legacyRenderer.SourcePath);
                exportRenderers.Add(workerRenderer);
                exportSessions.Add(workerRenderer.CreateRenderSession(project));
            }

            pendingFrameRenders = new Dictionary<int, Task<byte[]>>();

            var reusableWorkers = new Stack<LegacyRendererAdapter.RenderSession>(exportSessions);
            var reusableWorkersGate = new SemaphoreSlim(exportWorkerCount, exportWorkerCount);
            var reusableWorkersLock = new object();

            async Task<byte[]> RenderFrameWithReusableWorkerAsync(int frameIndex)
            {
                await reusableWorkersGate.WaitAsync(cancellationToken).ConfigureAwait(false);
                LegacyRendererAdapter.RenderSession workerRenderer;
                lock (reusableWorkersLock)
                    workerRenderer = reusableWorkers.Pop();

                try
                {
                    return await Task.Run(
                        () => RenderExportFrameBytes(
                            workerRenderer,
                            frameIndex,
                            width,
                            height),
                        cancellationToken).ConfigureAwait(false);
                }
                finally
                {
                    lock (reusableWorkersLock)
                        reusableWorkers.Push(workerRenderer);
                    reusableWorkersGate.Release();
                }
            }

            void ScheduleFrame(int frameIndex)
            {
                if (frameIndex < 0 || frameIndex >= frameCount ||
                    pendingFrameRenders.ContainsKey(frameIndex))
                    return;
                pendingFrameRenders[frameIndex] = RenderFrameWithReusableWorkerAsync(frameIndex);
            }

            for (var frameIndex = 0; frameIndex < Math.Min(frameCount, prefetchDepth); frameIndex++)
                ScheduleFrame(frameIndex);

            ExportStatusText.Text =
                $"Max-power render · {exportWorkerCount} workers · {prefetchDepth}-frame queue";

            mediaSource.Starting += (_, args) =>
            {
                if (args.Request.StartPosition is not null)
                    args.Request.SetActualStartPosition(TimeSpan.Zero);
            };

            mediaSource.SampleRequested += (source, args) =>
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

                        if (!pendingFrameRenders.TryGetValue(frameIndex, out var frameTask))
                        {
                            ScheduleFrame(frameIndex);
                            frameTask = pendingFrameRenders[frameIndex];
                        }

                        var bytes = await frameTask.ConfigureAwait(false);
                        pendingFrameRenders.Remove(frameIndex);
                        ScheduleFrame(frameIndex + prefetchDepth);
                        cancellationToken.ThrowIfCancellationRequested();

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
                                var writtenBytes = File.Exists(renderTarget.Path) ? new FileInfo(renderTarget.Path).Length : 0;
                                var detail = $"Max-power render · {exportWorkerCount} workers · {renderedCount:N0}/{frameCount:N0} frames · {progress:0.0}% · {FormatByteCount(writtenBytes)}";
                                ExportStatusText.Text = detail;
                                UpdateActivityWatcher("Video export", detail, Math.Clamp(progress, 0, 99.5));
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
            profile.Video.Bitrate = CalculateVideoBitrate(width, height, fps);

            var transcoder = new MediaTranscoder
            {
                HardwareAccelerationEnabled = true,
                AlwaysReencode = false,
            };

            var prepared = await transcoder.PrepareMediaStreamSourceTranscodeAsync(mediaSource, output, profile);
            if (!prepared.CanTranscode)
                throw new InvalidOperationException($"Windows could not prepare the MP4 encoder ({prepared.FailureReason}) for {width}×{height} at {fps} FPS.");

            ExportStatusText.Text = $"Starting {frameCount:N0}-frame export · {width}×{height} · {fps} FPS";
            var operation = prepared.TranscodeAsync();
            _videoExportOperation = operation;
            operation.Progress += (_, progress) =>
            {
                DispatcherQueue.TryEnqueue(() =>
                {
                    ExportProgressBar.Value = Math.Max(ExportProgressBar.Value, Math.Clamp(progress, 0, 99.5));
                    var writtenBytes = File.Exists(renderTarget.Path) ? new FileInfo(renderTarget.Path).Length : 0;
                    var detail = $"Encoding MP4 · {progress:0.0}% · {FormatByteCount(writtenBytes)} written";
                    ExportStatusText.Text = detail;
                    UpdateActivityWatcher("Video export", detail, Math.Clamp(progress, 0, 99.5));
                });
            };

            await operation;
            _videoExportOperation = null;
            cancellationToken.ThrowIfCancellationRequested();

            if (renderFailure is not null)
                throw new InvalidOperationException("A renderer frame failed during export.", renderFailure);

            output.Dispose();

            if (stagedRenderVideo is not null && hasSoundtrack)
            {
                ExportProgressBar.Value = 0;
                ShowActivityWatcher("Video export", "Adding soundtrack…", null, true);
                await AddSoundtrackAsync(
                    stagedRenderVideo,
                    stagedFinalVideo,
                    soundtrackPath!,
                    soundtrackVolume,
                    soundtrackLoop,
                    cancellationToken);
            }

            cancellationToken.ThrowIfCancellationRequested();
            CommitStagedExport(stagedFinalVideo.Path, file.Path);
            stagedFinalVideo = null;

            ExportProgressBar.Value = 100;
            ExportStatusText.Text = $"Exported {Path.GetFileName(file.Path)} · {width}×{height} · {fps} FPS";
            TimelineStatusText.Text = "Video export complete";
            var finalBytes = File.Exists(file.Path) ? new FileInfo(file.Path).Length : 0;
            CompleteActivityWatcher("Video export complete", $"{Path.GetFileName(file.Path)} · {FormatByteCount(finalBytes)}");
        }
        catch (TaskCanceledException)
        {
            ExportStatusText.Text = "Video export cancelled.";
            TimelineStatusText.Text = "Export cancelled";
            FailActivityWatcher("Video export cancelled", Path.GetFileName(file.Path));
        }
        catch (OperationCanceledException)
        {
            ExportStatusText.Text = "Video export cancelled.";
            TimelineStatusText.Text = "Export cancelled";
            FailActivityWatcher("Video export cancelled", Path.GetFileName(file.Path));
        }
        catch (Exception ex)
        {
            ExportStatusText.Text = "Video export failed.";
            TimelineStatusText.Text = $"Export failed: {ex.Message}";
            App.WriteLog("Video export failed", ex);
            FailActivityWatcher("Video export failed", ex.Message);
            await ShowErrorAsync("Could not export video", ex.Message);
        }
        finally
        {
            _videoExportCancellation?.Cancel();
            if (pendingFrameRenders is not null && pendingFrameRenders.Count > 0)
            {
                try { await Task.WhenAll(pendingFrameRenders.Values); }
                catch { }
                pendingFrameRenders.Clear();
            }
            if (exportRenderers is not null)
            {
                foreach (var workerRenderer in exportRenderers)
                    workerRenderer.Dispose();
                exportRenderers.Clear();
            }

            if (stagedRenderVideo is not null)
                TryDeleteExport(stagedRenderVideo.Path);
            if (stagedFinalVideo is not null)
                TryDeleteExport(stagedFinalVideo.Path);
            if (temporaryRendererAudio is not null)
                TryDeleteExport(temporaryRendererAudio.Path);
            _videoExportOperation = null;
            _videoExportCancellation?.Dispose();
            _videoExportCancellation = null;
            _videoExportInProgress = false;
            ExportVideoButton.IsEnabled = true;
            ExportCancelButton.Visibility = Visibility.Collapsed;
        }
    }

    private static uint CalculateVideoBitrate(int width, int height, int fps)
    {
        var pixelsPerSecond = (long)width * height * fps;
        return pixelsPerSecond switch
        {
            >= 700_000_000 => 80_000_000u,
            >= 400_000_000 => 48_000_000u,
            >= 220_000_000 => 28_000_000u,
            >= 110_000_000 => 16_000_000u,
            >= 55_000_000 => 10_000_000u,
            >= 25_000_000 => 7_000_000u,
            _ => 4_000_000u,
        };
    }

    private static byte[] RenderExportFrameBytes(
        LegacyRendererAdapter.RenderSession renderer,
        int frameIndex,
        int width,
        int height)
    {
        using var rendered = renderer.Render(frameIndex, width, height);
        if (rendered.Width != width || rendered.Height != height)
            throw new InvalidOperationException(
                $"Renderer returned {rendered.Width}x{rendered.Height}; expected {width}x{height}.");

        var byteCount = checked(width * height * 4);
        var bytes = GC.AllocateUninitializedArray<byte>(byteCount);
        var pixels = rendered.GetPixels();
        if (pixels == IntPtr.Zero)
            throw new InvalidOperationException("Renderer returned a frame with no pixel buffer.");

        // Media Foundation consumes positive-stride BGRA as bottom-up DIB data.
        if (rendered.ColorType == SKColorType.Bgra8888)
        {
            CopyBgraRowsBottomUp(pixels, rendered.RowBytes, bytes, width, height);
            return bytes;
        }

        using var bgra = new SKBitmap(
            new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using (var canvas = new SKCanvas(bgra))
        {
            canvas.Clear(SKColors.Black);
            canvas.DrawBitmap(rendered, new SKRect(0, 0, width, height));
            canvas.Flush();
        }

        var bgraPixels = bgra.GetPixels();
        if (bgraPixels == IntPtr.Zero)
            throw new InvalidOperationException("Could not access the converted BGRA frame buffer.");
        CopyBgraRowsBottomUp(bgraPixels, bgra.RowBytes, bytes, width, height);
        return bytes;
    }

    private static void CopyBgraRowsBottomUp(IntPtr sourcePixels, int sourceRowBytes, byte[] destination, int width, int height)
    {
        var packedStride = checked(width * 4);
        if (sourceRowBytes < packedStride)
            throw new InvalidOperationException($"Frame stride {sourceRowBytes} is smaller than the packed BGRA stride {packedStride}.");

        for (var destinationRow = 0; destinationRow < height; destinationRow++)
        {
            var sourceRow = height - 1 - destinationRow;
            Marshal.Copy(
                IntPtr.Add(sourcePixels, sourceRow * sourceRowBytes),
                destination,
                destinationRow * packedStride,
                packedStride);
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

    private static void CommitStagedExport(string stagedPath, string destinationPath)
    {
        if (string.IsNullOrWhiteSpace(stagedPath) || !File.Exists(stagedPath))
            throw new FileNotFoundException("The completed staged export is missing.", stagedPath);
        if (string.IsNullOrWhiteSpace(destinationPath))
            throw new ArgumentException("The export destination path is empty.", nameof(destinationPath));

        var backupPath = destinationPath + ".cc-backup-" + Guid.NewGuid().ToString("N");
        try
        {
            if (File.Exists(destinationPath))
            {
                // The staged file lives beside the destination, so File.Replace stays on
                // one volume and can swap the finished MP4 in only after export succeeds.
                File.Replace(stagedPath, destinationPath, backupPath, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(stagedPath, destinationPath);
            }
        }
        finally
        {
            TryDeleteExport(backupPath);
        }
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
