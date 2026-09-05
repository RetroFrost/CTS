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
        var ffmpeg = FindFfmpeg();
        var width = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceWidth : project.Width;
        var height = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceHeight : project.Height;
        var fps = renderer.PrecisionMode == "frame-exact" ? renderer.ReferenceFps : project.Fps;
        using var engine = new RendererEngine();
        var frames = engine.FrameCount(project, renderer);
        var tempRoot = Path.Combine(Path.GetTempPath(), "CubicalCompare", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tempRoot);
        var rendered = Path.Combine(tempRoot, "content.mp4");
        var withIntro = Path.Combine(tempRoot, "with-intro.mp4");
        var finalVideo = rendered;
        try
        {
            progress?.Report((0, $"Rendering {frames:N0} frames"));
            await RenderContentAsync(ffmpeg, engine, project, renderer, rendered, width, height, fps, frames, progress, cancellationToken);

            if (project.IntroMode == IntroMode.Custom && File.Exists(project.IntroVideo))
            {
                progress?.Report((0.91, "Joining custom intro"));
                await AddIntroAsync(ffmpeg, project.IntroVideo, rendered, withIntro, width, height, fps, cancellationToken);
                finalVideo = withIntro;
            }

            if (!string.IsNullOrWhiteSpace(project.Soundtrack) && File.Exists(project.Soundtrack))
            {
                progress?.Report((0.95, "Adding soundtrack"));
                await AddSoundtrackAsync(ffmpeg, finalVideo, project.Soundtrack, destination, project.SoundtrackVolume, project.SoundtrackLoop, cancellationToken);
            }
            else
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(destination))!);
                File.Copy(finalVideo, destination, true);
            }
            progress?.Report((1, "Export complete"));
        }
        finally
        {
            try { Directory.Delete(tempRoot, true); } catch { }
        }
    }

    private static async Task RenderContentAsync(
        string ffmpeg,
        RendererEngine engine,
        StudioProject project,
        RendererSpec renderer,
        string output,
        int width,
        int height,
        int fps,
        int frames,
        IProgress<(double Progress, string Status)>? progress,
        CancellationToken cancellationToken)
    {
        var psi = new ProcessStartInfo(ffmpeg)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
        };
        Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s:v", $"{width}x{height}", "-r", fps.ToString(), "-i", "pipe:0", "-an");
        var codec = project.EncoderPreference == EncoderPreference.H265 ? "libx265" : "libx264";
        Add(psi, "-c:v", codec, "-preset", "faster", "-crf", "18", "-pix_fmt", "yuv420p", "-movflags", "+faststart", output);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start bundled FFmpeg.");
        var stderr = process.StandardError.ReadToEndAsync();
        using var registration = cancellationToken.Register(() => TryKill(process));
        try
        {
            for (var frame = 0; frame < frames; frame++)
            {
                cancellationToken.ThrowIfCancellationRequested();
                using var bitmap = engine.Render(project, renderer, frame, width, height);
                using var pixmap = bitmap.PeekPixels();
                var byteCount = checked(pixmap.RowBytes * pixmap.Height);
                var bytes = new byte[byteCount];
                Marshal.Copy(pixmap.GetPixels(), bytes, 0, byteCount);
                await process.StandardInput.BaseStream.WriteAsync(bytes, cancellationToken);
                if ((frame & 7) == 0 || frame + 1 == frames)
                {
                    var p = (frame + 1) / (double)frames * 0.90;
                    progress?.Report((p, $"Rendering frame {frame + 1:N0} / {frames:N0}"));
                }
            }
            await process.StandardInput.BaseStream.FlushAsync(cancellationToken);
            process.StandardInput.Close();
            await process.WaitForExitAsync(cancellationToken);
            var error = await stderr;
            if (process.ExitCode != 0) throw new InvalidOperationException("FFmpeg video encoder failed: " + error.Trim());
        }
        catch
        {
            TryKill(process);
            throw;
        }
    }

    private static async Task AddIntroAsync(string ffmpeg, string intro, string content, string output, int width, int height, int fps, CancellationToken token)
    {
        var filter = $"[0:v]scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2,fps={fps},setsar=1,format=yuv420p[v0];[1:v]scale={width}:{height},fps={fps},setsar=1,format=yuv420p[v1];[v0][v1]concat=n=2:v=1:a=0[v]";
        var psi = StartInfo(ffmpeg);
        Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-i", intro, "-i", content, "-filter_complex", filter, "-map", "[v]", "-an", "-c:v", "libx264", "-preset", "faster", "-crf", "18", "-pix_fmt", "yuv420p", output);
        await RunAsync(psi, token, "FFmpeg custom-intro pass failed");
    }

    private static async Task AddSoundtrackAsync(string ffmpeg, string video, string soundtrack, string output, float volume, bool loop, CancellationToken token)
    {
        var psi = StartInfo(ffmpeg);
        Add(psi, "-hide_banner", "-loglevel", "error", "-y", "-i", video);
        if (loop) Add(psi, "-stream_loop", "-1");
        Add(psi, "-i", soundtrack, "-map", "0:v:0", "-map", "1:a:0", "-filter:a", $"volume={Math.Clamp(volume, 0, 2).ToString(System.Globalization.CultureInfo.InvariantCulture)}", "-shortest", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", output);
        await RunAsync(psi, token, "FFmpeg soundtrack pass failed");
    }

    private static ProcessStartInfo StartInfo(string ffmpeg) => new(ffmpeg)
    {
        UseShellExecute = false,
        RedirectStandardError = true,
        CreateNoWindow = true,
    };

    private static async Task RunAsync(ProcessStartInfo psi, CancellationToken token, string errorPrefix)
    {
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start bundled FFmpeg.");
        var stderr = process.StandardError.ReadToEndAsync();
        using var registration = token.Register(() => TryKill(process));
        await process.WaitForExitAsync(token);
        var error = await stderr;
        if (process.ExitCode != 0) throw new InvalidOperationException(errorPrefix + ": " + error.Trim());
    }

    private static void Add(ProcessStartInfo psi, params string[] args)
    {
        foreach (var arg in args) psi.ArgumentList.Add(arg);
    }

    private static string FindFfmpeg()
    {
        var names = new[]
        {
            Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe"),
            Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe"),
        };
        foreach (var path in names) if (File.Exists(path)) return path;
        var pathEnv = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (var directory in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var path = Path.Combine(directory.Trim(), "ffmpeg.exe");
            if (File.Exists(path)) return path;
        }
        throw new FileNotFoundException("FFmpeg was not found. The official Windows build includes ffmpeg.exe beside CubicalCompare.exe.");
    }

    private static void TryKill(Process process)
    {
        try { if (!process.HasExited) process.Kill(true); } catch { }
    }
}
