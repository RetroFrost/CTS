using System.Diagnostics;
using System.Runtime.InteropServices;
using SkiaSharp;

namespace CubicalCompare.Windows;

public sealed class VideoExporter
{
    public async Task ExportAsync(
        StudioProject project,
        RendererSpec renderer,
        string destination,
        IProgress<(double Progress, string Status)>? progress,
        CancellationToken cancellationToken)
    {
        var ffmpeg = FfmpegLocator.Find();
        var width = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceWidth : project.Width;
        var height = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceHeight : project.Height;
        var fps = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceFps : project.Fps;
        if (width < 2 || height < 2 || fps < 1) throw new InvalidOperationException("The output resolution or frame rate is invalid.");
        // H.26x 4:2:0 requires even dimensions.
        width -= width & 1; height -= height & 1;
        var encoder = HardwareEncoderSelector.Resolve(project.EncoderPreference);
        using var engine = new RendererEngine();
        var baseFrames = Math.Max(1, engine.FrameCount(project, renderer));
        var rendererIntro = RendererIntroFrames(renderer);
        var skipRendererIntro = project.IntroMode != IntroMode.Renderer;
        var contentFrames = Math.Max(1, baseFrames - (skipRendererIntro ? Math.Min(rendererIntro, baseFrames - 1) : 0));
        var engineOffset = skipRendererIntro ? rendererIntro : 0;
        if (project.IntroMode == IntroMode.Custom && !File.Exists(project.IntroVideo)) throw new FileNotFoundException("Choose a custom MP4 intro or switch the intro mode.", project.IntroVideo);
        if (!string.IsNullOrWhiteSpace(project.Soundtrack) && !File.Exists(project.Soundtrack)) throw new FileNotFoundException("The selected soundtrack no longer exists.", project.Soundtrack);

        var tempRoot = Path.Combine(Path.GetTempPath(), "CubicalCompare", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        var rendered = Path.Combine(tempRoot, "content.mp4");
        var withIntro = Path.Combine(tempRoot, "with-intro.mp4");
        var withAudio = Path.Combine(tempRoot, "with-audio.mp4");
        var finalVideo = rendered;
        try
        {
            progress?.Report((0, $"Rendering with {encoder.DisplayName}"));
            try
            {
                await RenderContentAsync(ffmpeg, engine, project, renderer, rendered, width, height, fps, contentFrames, engineOffset, encoder, progress, cancellationToken);
            }
            catch (Exception) when (encoder.Hardware && !cancellationToken.IsCancellationRequested)
            {
                // Drivers can disappear or fail after the smoke test. Never lose an export because of it.
                encoder = project.EncoderPreference == EncoderPreference.H265 ? EncoderSelection.SoftwareH265 : EncoderSelection.SoftwareH264;
                TryDelete(rendered);
                progress?.Report((0, $"Hardware encoder unavailable; retrying with {encoder.DisplayName}"));
                await RenderContentAsync(ffmpeg, engine, project, renderer, rendered, width, height, fps, contentFrames, engineOffset, encoder, progress, cancellationToken);
            }

            if (project.IntroMode == IntroMode.Custom)
            {
                progress?.Report((0.91, "Joining custom intro"));
                await AddIntroAsync(ffmpeg, project.IntroVideo, rendered, withIntro, width, height, fps, encoder, cancellationToken);
                finalVideo = withIntro;
            }

            if (!string.IsNullOrWhiteSpace(project.Soundtrack))
            {
                progress?.Report((0.96, "Adding soundtrack"));
                await AddSoundtrackAsync(ffmpeg, finalVideo, project.Soundtrack, withAudio, project.SoundtrackVolume, project.SoundtrackLoop, cancellationToken);
                finalVideo = withAudio;
            }

            cancellationToken.ThrowIfCancellationRequested();
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(destination))!);
            var partial = destination + ".partial-" + Guid.NewGuid().ToString("N");
            try
            {
                File.Copy(finalVideo, partial, true);
                using (var stream = new FileStream(partial, FileMode.Open, FileAccess.ReadWrite, FileShare.None)) stream.Flush(true);
                File.Move(partial, destination, true);
            }
            finally { TryDelete(partial); }
            progress?.Report((1, $"Export complete • {encoder.DisplayName}"));
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    private static int RendererIntroFrames(RendererSpec spec) => spec.RendererApi >= 3 || spec.Engine == "scene-v3" ? 0 : Math.Max(0, spec.OpeningStarts.FirstOrDefault());

    private static async Task RenderContentAsync(
        string ffmpeg, RendererEngine engine, StudioProject project, RendererSpec renderer, string output,
        int width, int height, int fps, int frames, int engineOffset, EncoderSelection encoder,
        IProgress<(double Progress, string Status)>? progress, CancellationToken cancellationToken)
    {
        var psi = new ProcessStartInfo(ffmpeg) { UseShellExecute = false, RedirectStandardInput = true, RedirectStandardError = true, CreateNoWindow = true };
        Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s:v", $"{width}x{height}", "-r", fps.ToString(), "-i", "pipe:0", "-an", "-c:v", encoder.Codec);
        AddEncoderQuality(psi, encoder, width, height, fps);
        Add(psi, "-pix_fmt", "yuv420p", "-movflags", "+faststart", output);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start bundled FFmpeg.");
        var stderrTask = process.StandardError.ReadToEndAsync();
        using var registration = cancellationToken.Register(() => TryKill(process));
        try
        {
            for (var frame = 0; frame < frames; frame++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                using var bitmap = engine.Render(project, renderer, frame + engineOffset, width, height);
                using var pixmap = bitmap.PeekPixels();
                var byteCount = checked(pixmap.RowBytes * pixmap.Height);
                var bytes = new byte[byteCount]; Marshal.Copy(pixmap.GetPixels(), bytes, 0, byteCount);
                await process.StandardInput.BaseStream.WriteAsync(bytes, cancellationToken);
                if ((frame & 7) == 0 || frame + 1 == frames)
                    progress?.Report(((frame + 1) / (double)frames * .90, $"Rendering frame {frame + 1:N0} / {frames:N0} • {encoder.DisplayName}"));
            }
            await process.StandardInput.BaseStream.FlushAsync(cancellationToken); process.StandardInput.Close();
            await process.WaitForExitAsync(cancellationToken); var error = await stderrTask;
            if (process.ExitCode != 0) throw new InvalidOperationException("FFmpeg video encoder failed: " + error.Trim());
        }
        catch
        {
            TryKill(process);
            string error = ""; try { error = await stderrTask; } catch { }
            if (error.Length > 0 && !cancellationToken.IsCancellationRequested) throw new InvalidOperationException($"{encoder.DisplayName} failed: {error.Trim()}");
            throw;
        }
    }

    private static void AddEncoderQuality(ProcessStartInfo psi, EncoderSelection encoder, int width, int height, int fps)
    {
        if (!encoder.Hardware)
        {
            Add(psi, "-preset", "faster", "-crf", encoder.Codec == "libx265" ? "20" : "18");
            return;
        }
        // A bitrate target is accepted by NVENC/QSV/AMF and avoids backend-specific quality flags.
        var pixelsPerSecond = (long)width * height * Math.Max(1, fps);
        var mbps = Math.Clamp((int)Math.Round(pixelsPerSecond / (1920.0 * 1080 * 60) * 12), 5, 45);
        Add(psi, "-b:v", mbps + "M", "-maxrate", (mbps * 3 / 2) + "M", "-bufsize", (mbps * 2) + "M");
    }

    private static async Task AddIntroAsync(string ffmpeg, string intro, string content, string output, int width, int height, int fps, EncoderSelection encoder, CancellationToken token)
    {
        var filter = $"[0:v]scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,fps={fps},setsar=1,format=yuv420p[v0];[1:v]scale={width}:{height},fps={fps},setsar=1,format=yuv420p[v1];[v0][v1]concat=n=2:v=1:a=0[v]";
        var psi = StartInfo(ffmpeg); Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-i", intro, "-i", content, "-filter_complex", filter, "-map", "[v]", "-an", "-c:v", encoder.Codec); AddEncoderQuality(psi, encoder, width, height, fps); Add(psi, "-pix_fmt", "yuv420p", "-movflags", "+faststart", output);
        try { await RunAsync(psi, token, "FFmpeg custom-intro pass failed"); }
        catch when (encoder.Hardware && !token.IsCancellationRequested)
        {
            var software = encoder.Codec.StartsWith("hevc", StringComparison.OrdinalIgnoreCase) ? EncoderSelection.SoftwareH265 : EncoderSelection.SoftwareH264;
            psi = StartInfo(ffmpeg); Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-i", intro, "-i", content, "-filter_complex", filter, "-map", "[v]", "-an", "-c:v", software.Codec); AddEncoderQuality(psi, software, width, height, fps); Add(psi, "-pix_fmt", "yuv420p", "-movflags", "+faststart", output); await RunAsync(psi, token, "FFmpeg custom-intro fallback failed");
        }
    }

    private static async Task AddSoundtrackAsync(string ffmpeg, string video, string soundtrack, string output, float volume, bool loop, CancellationToken token)
    {
        var psi = StartInfo(ffmpeg); Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-i", video); if (loop) Add(psi, "-stream_loop", "-1");
        Add(psi, "-i", soundtrack, "-map", "0:v:0", "-map", "1:a:0", "-filter:a", $"volume={Math.Clamp(volume,0,1).ToString(CultureInfo.InvariantCulture)}", "-shortest", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output);
        await RunAsync(psi, token, "FFmpeg soundtrack pass failed");
    }

    private static ProcessStartInfo StartInfo(string ffmpeg) => new(ffmpeg) { UseShellExecute = false, RedirectStandardError = true, CreateNoWindow = true };
    private static async Task RunAsync(ProcessStartInfo psi, CancellationToken token, string errorPrefix)
    {
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start bundled FFmpeg."); var stderr = process.StandardError.ReadToEndAsync(); using var registration = token.Register(() => TryKill(process));
        await process.WaitForExitAsync(token); var error = await stderr; if (process.ExitCode != 0) throw new InvalidOperationException(errorPrefix + ": " + error.Trim());
    }
    private static void Add(ProcessStartInfo psi, params string[] args) { foreach (var arg in args) psi.ArgumentList.Add(arg); }
    private static void TryKill(Process process) { try { if (!process.HasExited) process.Kill(true); } catch { } }
    private static void TryDelete(string path) { try { if (File.Exists(path)) File.Delete(path); } catch { } }
}