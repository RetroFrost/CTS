using SkiaSharp;
using System.Diagnostics;
using System.Globalization;
using System.Text.RegularExpressions;

namespace CubicalCompare.Windows;

/// <summary>Frame-addressed custom-intro decoder used by the editor timeline.</summary>
public sealed class IntroVideoSource : IDisposable
{
    private sealed record CacheKey(string Path, long Stamp, int Frame, int Fps, int Width, int Height);
    private readonly Dictionary<CacheKey, SKBitmap> _frames = new();
    private readonly LinkedList<CacheKey> _lru = new();
    private readonly Dictionary<(string Path, long Stamp, int Fps), int> _counts = new();
    private const int MaxCachedFrames = 12;

    public int FrameCount(string path, int fps)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return 0;
        fps = Math.Max(1, fps);
        var full = Path.GetFullPath(path);
        var stamp = File.GetLastWriteTimeUtc(full).Ticks ^ new FileInfo(full).Length;
        var key = (full, stamp, fps);
        if (_counts.TryGetValue(key, out var cached)) return cached;
        var duration = ProbeDuration(full);
        var frames = Math.Max(1, (int)Math.Ceiling(duration * fps - 1e-7));
        _counts[key] = frames;
        return frames;
    }

    public SKBitmap Render(string path, int frame, int fps, int width, int height)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) throw new FileNotFoundException("The custom intro video no longer exists.", path);
        fps = Math.Max(1, fps); width = Math.Max(2, width); height = Math.Max(2, height); frame = Math.Max(0, frame);
        var full = Path.GetFullPath(path); var info = new FileInfo(full); var stamp = info.LastWriteTimeUtc.Ticks ^ info.Length;
        var key = new CacheKey(full, stamp, frame, fps, width, height);
        if (_frames.TryGetValue(key, out var hit)) { Touch(key); return hit.Copy(); }

        var ffmpeg = FfmpegLocator.Find();
        var seconds = frame / (double)fps;
        var psi = new ProcessStartInfo(ffmpeg) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (var arg in new[]
        {
            "-hide_banner", "-loglevel", "error", "-ss", seconds.ToString("0.#########", CultureInfo.InvariantCulture), "-i", full,
            "-frames:v", "1", "-vf", $"scale={width}:{height}:force_original_aspect_ratio=decrease,pad={width}:{height}:(ow-iw)/2:(oh-ih)/2:color=black,format=bgra",
            "-f", "rawvideo", "-pix_fmt", "bgra", "pipe:1",
        }) psi.ArgumentList.Add(arg);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start FFmpeg for custom intro preview.");
        var expected = checked(width * height * 4); var bytes = new byte[expected]; var offset = 0;
        while (offset < expected)
        {
            var count = process.StandardOutput.BaseStream.Read(bytes, offset, expected - offset); if (count <= 0) break; offset += count;
        }
        var error = process.StandardError.ReadToEnd();
        if (!process.WaitForExit(12000)) { try { process.Kill(true); } catch { } throw new TimeoutException("Custom intro preview decoder timed out."); }
        if (process.ExitCode != 0 || offset != expected) throw new InvalidDataException("Could not decode custom intro frame: " + error.Trim());
        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
        System.Runtime.InteropServices.Marshal.Copy(bytes, 0, bitmap.GetPixels(), bytes.Length);
        Add(key, bitmap.Copy());
        return bitmap;
    }

    private static double ProbeDuration(string path)
    {
        var ffmpeg = FfmpegLocator.Find();
        var psi = new ProcessStartInfo(ffmpeg) { UseShellExecute = false, RedirectStandardError = true, RedirectStandardOutput = true, CreateNoWindow = true };
        foreach (var arg in new[] { "-hide_banner", "-i", path }) psi.ArgumentList.Add(arg);
        using var process = Process.Start(psi) ?? throw new InvalidOperationException("Could not start FFmpeg to inspect the custom intro.");
        var error = process.StandardError.ReadToEnd(); _ = process.StandardOutput.ReadToEnd();
        if (!process.WaitForExit(8000)) { try { process.Kill(true); } catch { } throw new TimeoutException("Custom intro metadata probe timed out."); }
        var match = Regex.Match(error, @"Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)", RegexOptions.CultureInvariant);
        if (!match.Success) throw new InvalidDataException("Could not determine custom intro duration.");
        var hours = int.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture);
        var minutes = int.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture);
        var seconds = double.Parse(match.Groups[3].Value, CultureInfo.InvariantCulture);
        var total = hours * 3600 + minutes * 60 + seconds;
        if (!double.IsFinite(total) || total <= 0 || total > 24 * 3600) throw new InvalidDataException("Custom intro duration is invalid.");
        return total;
    }

    private void Add(CacheKey key, SKBitmap bitmap)
    {
        if (_frames.Remove(key, out var old)) old.Dispose();
        _frames[key] = bitmap; _lru.Remove(key); _lru.AddLast(key);
        while (_lru.Count > MaxCachedFrames)
        {
            var remove = _lru.First!.Value; _lru.RemoveFirst(); if (_frames.Remove(remove, out var stale)) stale.Dispose();
        }
    }
    private void Touch(CacheKey key) { _lru.Remove(key); _lru.AddLast(key); }
    public void Dispose() { foreach (var bitmap in _frames.Values) bitmap.Dispose(); _frames.Clear(); _lru.Clear(); _counts.Clear(); }
}