using System.IO;
using System.Text;

namespace CubicalCompareV5;

// M3U is a playlist of file references; it does not embed the audio data.
// Preserve user ordering and intentional repeat entries. UTF-8 is used even
// with .m3u; .m3u8 is accepted for import as well.
public static class M3uPlaylist
{
    public const int MaxTracks = 128;

    public static string Serialize(IEnumerable<string> paths, string playlistPath)
    {
        string baseDir = Path.GetDirectoryName(Path.GetFullPath(playlistPath))!;
        var items = paths.ToList();
        if (items.Count > MaxTracks) throw new InvalidDataException("The pattern has too many tracks.");
        var lines = new List<string> { "#EXTM3U" };
        foreach (var input in items)
        {
            string full = Path.GetFullPath(input);
            if (full.Contains('\r') || full.Contains('\n'))
                throw new InvalidDataException("An audio filename contains a newline.");
            lines.Add("#EXTINF:-1," + Path.GetFileName(full).Replace("\r", "").Replace("\n", ""));
            string relative = Path.GetRelativePath(baseDir, full);
            // Favour relocatable playlists on the same drive, with absolute
            // paths as fallback when relative paths are impossible.
            lines.Add(Path.IsPathRooted(relative) ? full : relative);
        }
        return string.Join("\r\n", lines) + "\r\n";
    }

    public static IReadOnlyList<string> Parse(string contents, string playlistPath)
    {
        string baseDir = Path.GetDirectoryName(Path.GetFullPath(playlistPath))!;
        var result = new List<string>();
        foreach (string raw in contents.Replace("\r\n", "\n").Replace('\r', '\n').Split('\n'))
        {
            var line = raw.Trim().TrimStart('\uFEFF');
            if (line.Length == 0 || line.StartsWith('#')) continue;
            if (line.StartsWith("http:", StringComparison.OrdinalIgnoreCase) ||
                line.StartsWith("https:", StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("Only local audio files are supported by this version.");
            string resolved;
            if (line.StartsWith("file://", StringComparison.OrdinalIgnoreCase))
            {
                if (!Uri.TryCreate(line, UriKind.Absolute, out var uri) || !uri.IsFile)
                    throw new InvalidDataException("Invalid file URL in the playlist.");
                resolved = uri.LocalPath;
            }
            else
                resolved = Path.IsPathFullyQualified(line) ? line : Path.Combine(baseDir, line);
            result.Add(Path.GetFullPath(resolved));
            if (result.Count > MaxTracks) throw new InvalidDataException("The playlist contains more than 128 tracks.");
        }
        return result;
    }

    public static void Save(string destination, IEnumerable<string> paths) =>
        File.WriteAllText(destination, Serialize(paths, destination), new UTF8Encoding(true));

    public static IReadOnlyList<string> Load(string path) =>
        Parse(File.ReadAllText(path, Encoding.UTF8), path);

    public static void RunRegressionChecks()
    {
        string baseDir = Path.Combine(Path.GetTempPath(), "cts-v5-playlist-check");
        string playlist = Path.Combine(baseDir, "My Music.m3u");
        var input = new[]
        {
            Path.Combine(baseDir, "one track.mp3"),
            Path.Combine(baseDir, "canción música.wav"),
            Path.Combine(baseDir, "one track.mp3")
        };
        var text = Serialize(input, playlist);
        var result = Parse(text, playlist);
        if (result.Count != input.Length || !result.SequenceEqual(input, StringComparer.OrdinalIgnoreCase))
            throw new InvalidDataException("M3U round-trip changed the soundtrack order or duplicates.");
        var comments = Parse("#EXTM3U\n#EXTINF:-1,Example\nsong.mp3\n#comment\n", playlist);
        if (comments.Count != 1 || comments[0] != Path.Combine(baseDir, "song.mp3"))
            throw new InvalidDataException("M3U comment parsing failed.");
        try
        {
            Parse("#EXTM3U\nhttps://example.com/song.mp3", playlist);
            throw new InvalidDataException("Remote playlist entries were accepted incorrectly.");
        }
        catch (InvalidDataException e) when (e.Message.Contains("Only local audio")) { }
    }
}
