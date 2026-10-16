using System.Text;
using System.Text.RegularExpressions;

namespace CubicalCompare.Core.Project;

/// <summary>
/// Portable M3U/M3U8 soundtrack-order patterns. Deliberately stores only track paths:
/// the files themselves remain at their original locations and are not copied.
/// </summary>
public static class M3uPlaylistService
{
    public const int MaxTracks = 256;
    public const int MaxPlaylistBytes = 1_048_576;

    public static string Serialize(string playlistPath, IReadOnlyList<string> tracks)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(playlistPath);
        ArgumentNullException.ThrowIfNull(tracks);
        if (tracks.Count is < 1 or > MaxTracks)
            throw new InvalidDataException($"M3U needs 1–{MaxTracks} ordered tracks.");

        var baseFolder = Path.GetDirectoryName(Path.GetFullPath(playlistPath))
            ?? throw new InvalidDataException("The playlist has no parent folder.");
        var lines = new List<string>(tracks.Count + 1) { "#EXTM3U" };

        foreach (var track in tracks)
        {
            if (string.IsNullOrWhiteSpace(track))
                throw new InvalidDataException("M3U cannot contain an empty audio path.");
            var fullPath = Path.GetFullPath(track);
            var relative = Path.GetRelativePath(baseFolder, fullPath)
                .Replace(Path.DirectorySeparatorChar, '/');
            // Escape a leading # because that denotes an M3U comment.
            if (relative.StartsWith('#'))
                relative = "./" + relative;
            lines.Add(relative);
        }

        var content = string.Join("\n", lines) + "\n";
        if (Encoding.UTF8.GetByteCount(content) > MaxPlaylistBytes)
            throw new InvalidDataException("The M3U playlist is too large.");
        return content;
    }

    public static IReadOnlyList<string> Parse(string playlistPath, string contents)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(playlistPath);
        ArgumentNullException.ThrowIfNull(contents);
        if (Encoding.UTF8.GetByteCount(contents) > MaxPlaylistBytes)
            throw new InvalidDataException("The M3U playlist is too large.");

        var baseFolder = Path.GetDirectoryName(Path.GetFullPath(playlistPath))
            ?? throw new InvalidDataException("The playlist has no parent folder.");
        var tracks = new List<string>();

        foreach (var raw in contents.Split('\n'))
        {
            var entry = raw.Trim().TrimStart('\uFEFF');
            if (entry.Length == 0 || entry.StartsWith('#'))
                continue;
            if (entry.IndexOf('\0') >= 0 || entry.Contains("://", StringComparison.Ordinal)
                || Regex.IsMatch(entry, @"^[a-zA-Z][a-zA-Z0-9+.-]*:", RegexOptions.CultureInvariant)
                    && !Regex.IsMatch(entry, @"^[a-zA-Z]:[/\\]", RegexOptions.CultureInvariant))
                throw new InvalidDataException("M3U only supports local audio files, not network URLs.");

            // Both Windows and portable playlists commonly use forward slashes.
            entry = entry.Replace('/', Path.DirectorySeparatorChar);
            var resolved = Path.GetFullPath(Path.IsPathFullyQualified(entry)
                ? entry : Path.Combine(baseFolder, entry));
            tracks.Add(resolved);
            if (tracks.Count > MaxTracks)
                throw new InvalidDataException($"M3U is limited to {MaxTracks} tracks.");
        }

        if (tracks.Count == 0)
            throw new InvalidDataException("The M3U playlist has no audio tracks.");
        // A repeated track is intentional: preserve its place in the creator's pattern.
        return tracks;
    }

    public static async Task SaveAsync(string playlistPath, IReadOnlyList<string> tracks)
    {
        var content = Serialize(playlistPath, tracks);
        var temporary = Path.Combine(
            Path.GetDirectoryName(Path.GetFullPath(playlistPath))!,
            ".cc-m3u-" + Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await File.WriteAllTextAsync(temporary, content, new UTF8Encoding(false));
            File.Move(temporary, playlistPath, overwrite: true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public static async Task<IReadOnlyList<string>> LoadAsync(string playlistPath)
    {
        var info = new FileInfo(playlistPath);
        if (!info.Exists) throw new FileNotFoundException("M3U playlist not found.", playlistPath);
        if (info.Length > MaxPlaylistBytes)
            throw new InvalidDataException("The M3U playlist is too large.");
        var contents = await File.ReadAllTextAsync(playlistPath, Encoding.UTF8);
        return Parse(playlistPath, contents);
    }
}
