using System.Diagnostics;

namespace CubicalCompare.Windows;

public sealed record EncoderSelection(string Codec, string DisplayName, bool Hardware)
{
    public static readonly EncoderSelection SoftwareH264 = new("libx264", "H.264 software", false);
    public static readonly EncoderSelection SoftwareH265 = new("libx265", "H.265 software", false);
}

public static class FfmpegLocator
{
    public static string Find()
    {
        foreach (var path in new[]
        {
            Path.Combine(AppContext.BaseDirectory, "ffmpeg.exe"),
            Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe"),
        }) if (File.Exists(path)) return path;
        foreach (var directory in (Environment.GetEnvironmentVariable("PATH") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
        {
            var path = Path.Combine(directory.Trim(), "ffmpeg.exe");
            if (File.Exists(path)) return path;
        }
        throw new FileNotFoundException("FFmpeg was not found. The official Windows build includes ffmpeg.exe beside CubicalCompare.exe.");
    }
}

/// <summary>
/// Chooses a real usable Windows encoder, not merely one listed by FFmpeg. Hardware
/// candidates are smoke-tested because FFmpeg can advertise an encoder when the
/// matching GPU/driver is unavailable. Results are cached for the process lifetime.
/// </summary>
public static class HardwareEncoderSelector
{
    private static readonly object Gate = new();
    private static Dictionary<string, bool>? _usable;

    public static EncoderSelection Resolve(EncoderPreference preference)
    {
        var available = UsableEncoders();
        if (preference == EncoderPreference.H265)
            return First(available,
                ("hevc_nvenc", "H.265 NVIDIA NVENC"),
                ("hevc_qsv", "H.265 Intel Quick Sync"),
                ("hevc_amf", "H.265 AMD AMF")) ?? EncoderSelection.SoftwareH265;
        if (preference == EncoderPreference.H264)
            return First(available,
                ("h264_nvenc", "H.264 NVIDIA NVENC"),
                ("h264_qsv", "H.264 Intel Quick Sync"),
                ("h264_amf", "H.264 AMD AMF")) ?? EncoderSelection.SoftwareH264;
        // Auto intentionally prefers the most interoperable hardware H.264 path.
        return First(available,
            ("h264_nvenc", "H.264 NVIDIA NVENC"),
            ("h264_qsv", "H.264 Intel Quick Sync"),
            ("h264_amf", "H.264 AMD AMF")) ?? EncoderSelection.SoftwareH264;
    }

    public static string Describe(EncoderPreference preference) => Resolve(preference).DisplayName;

    private static EncoderSelection? First(Dictionary<string, bool> available, params (string Codec, string Name)[] candidates)
    {
        foreach (var candidate in candidates)
            if (available.GetValueOrDefault(candidate.Codec)) return new EncoderSelection(candidate.Codec, candidate.Name, true);
        return null;
    }

    private static Dictionary<string, bool> UsableEncoders()
    {
        lock (Gate)
        {
            if (_usable != null) return _usable;
            var result = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            string ffmpeg;
            try { ffmpeg = FfmpegLocator.Find(); }
            catch { return _usable = result; }
            var listed = ListEncoders(ffmpeg);
            foreach (var codec in new[] { "h264_nvenc", "h264_qsv", "h264_amf", "hevc_nvenc", "hevc_qsv", "hevc_amf" })
                result[codec] = listed.Contains(codec) && SmokeTest(ffmpeg, codec);
            return _usable = result;
        }
    }

    private static HashSet<string> ListEncoders(string ffmpeg)
    {
        try
        {
            using var process = Process.Start(new ProcessStartInfo(ffmpeg)
            {
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true,
                ArgumentList = { "-hide_banner", "-encoders" },
            });
            if (process == null) return [];
            var output = process.StandardOutput.ReadToEnd() + process.StandardError.ReadToEnd();
            if (!process.WaitForExit(5000)) { try { process.Kill(true); } catch { } return []; }
            var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var line in output.Split('\n'))
            {
                var parts = line.Trim().Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 2 && parts[0].Length >= 6 && parts[0].Contains('V')) set.Add(parts[1]);
            }
            return set;
        }
        catch { return []; }
    }

    private static bool SmokeTest(string ffmpeg, string codec)
    {
        try
        {
            var psi = new ProcessStartInfo(ffmpeg) { UseShellExecute = false, RedirectStandardError = true, CreateNoWindow = true };
            foreach (var arg in new[] { "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "color=c=black:s=64x64:r=1:d=1", "-frames:v", "1", "-c:v", codec, "-f", "null", "-" }) psi.ArgumentList.Add(arg);
            using var process = Process.Start(psi);
            if (process == null) return false;
            _ = process.StandardError.ReadToEndAsync();
            if (!process.WaitForExit(6500)) { try { process.Kill(true); } catch { } return false; }
            return process.ExitCode == 0;
        }
        catch { return false; }
    }
}