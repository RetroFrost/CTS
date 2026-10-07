using System.Text;
using System.Text.RegularExpressions;

namespace CubicalCompare.Core.Project.Patching;

public sealed record PatchChange(string? OldPath, string? NewPath, byte[]? Contents);

/// <summary>Prepares a complete text patch without writing any files. Context must match exactly.</summary>
public static class UnifiedPatch
{
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private static readonly Regex Hunk = new(@"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", RegexOptions.CultureInvariant);

    public static IReadOnlyList<PatchChange> Prepare(string root, string patch)
    {
        if (patch.Length > 32 * 1024 * 1024) throw new InvalidDataException("Patch exceeds 32 MB.");
        var lines = patch.Replace("\r\n", "\n").Split('\n');
        var changes = new List<PatchChange>();
        var touched = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        for (var i = 0; i < lines.Length; i++)
        {
            if (lines[i].StartsWith("GIT binary patch", StringComparison.Ordinal) || lines[i].StartsWith("Binary files ", StringComparison.Ordinal))
                throw new InvalidDataException("Binary patches are not supported. Use a release ZIP for binary assets.");
            if (!lines[i].StartsWith("--- ", StringComparison.Ordinal)) continue;
            var old = ReadPath(lines[i][4..], "a/");
            if (++i >= lines.Length || !lines[i].StartsWith("+++ ", StringComparison.Ordinal)) throw new InvalidDataException("Missing patch destination header.");
            var next = ReadPath(lines[i][4..], "b/");
            if (old is null && next is null) throw new InvalidDataException("Patch has no file path.");
            foreach (var name in new[] { old, next }.Where(x => x is not null).Distinct(StringComparer.OrdinalIgnoreCase))
            {
                Resolve(root, name!);
                if (!touched.Add(name!)) throw new InvalidDataException($"Duplicate patch target: {name}");
            }
            var oldFile = old is null ? null : Resolve(root, old);
            if (oldFile is not null && !File.Exists(oldFile)) throw new InvalidDataException($"Source file not found: {old}");
            if (next is not null && next != old && File.Exists(Resolve(root, next))) throw new InvalidDataException($"Destination already exists: {next}");
            var bytes = oldFile is null ? [] : File.ReadAllBytes(oldFile);
            if (bytes.Length > 32 * 1024 * 1024) throw new InvalidDataException("Patched source file exceeds 32 MB.");
            var bom = bytes.AsSpan().StartsWith(new byte[] { 239, 187, 191 });
            var text = Utf8.GetString(bytes.AsSpan(bom ? 3 : 0));
            if (text.Contains('\0')) throw new InvalidDataException($"Binary source file: {old}");
            var newline = text.Contains("\r\n", StringComparison.Ordinal) ? "\r\n" : "\n";
            var normalized = text.Replace("\r\n", "\n");
            var source = normalized.Length == 0 ? new List<string>() : normalized.Split('\n').ToList();
            var sourceEndsWithNewline = normalized.EndsWith('\n');
            var endsWithNewline = sourceEndsWithNewline;
            if (sourceEndsWithNewline) source.RemoveAt(source.Count - 1);
            var output = new List<string>();
            var cursor = 0;
            var hunks = 0;
            while (i + 1 < lines.Length && lines[i + 1].StartsWith("@@ ", StringComparison.Ordinal))
            {
                var match = Hunk.Match(lines[++i]);
                if (!match.Success) throw new InvalidDataException("Malformed patch hunk.");
                var oldStart = int.Parse(match.Groups[1].Value);
                var oldCount = match.Groups[2].Success ? int.Parse(match.Groups[2].Value) : 1;
                var newStart = int.Parse(match.Groups[3].Value);
                var newCount = match.Groups[4].Success ? int.Parse(match.Groups[4].Value) : 1;
                var start = oldCount == 0 ? oldStart : oldStart - 1;
                if (start < cursor || start > source.Count) throw new InvalidDataException($"Hunk position does not match {old ?? next}.");
                output.AddRange(source.GetRange(cursor, start - cursor));
                if (start > cursor) endsWithNewline = start < source.Count || sourceEndsWithNewline;
                cursor = start;
                if (output.Count != (newCount == 0 ? newStart : newStart - 1)) throw new InvalidDataException("Patch destination line number does not match.");
                var removed = 0;
                var added = 0;
                char previous = '\0';
                while (removed < oldCount || added < newCount)
                {
                    if (++i >= lines.Length || lines[i].Length == 0) throw new InvalidDataException("Truncated patch hunk.");
                    var line = lines[i];
                    if (line == "\\ No newline at end of file")
                    {
                        ValidateNoNewline(previous, cursor, source.Count);
                        if (previous != '-') endsWithNewline = false;
                        continue;
                    }
                    previous = line[0];
                    if (previous is ' ' or '-')
                    {
                        if (++removed > oldCount || cursor >= source.Count || source[cursor++] != line[1..])
                            throw new InvalidDataException($"Patch context differs in {old ?? next}. Use a patch for this app version.");
                    }
                    if (previous is ' ' or '+')
                    {
                        if (++added > newCount) throw new InvalidDataException("Patch hunk line count is invalid.");
                        output.Add(line[1..]);
                        endsWithNewline = previous == '+' || cursor < source.Count || sourceEndsWithNewline;
                    }
                    if (previous is not (' ' or '+' or '-')) throw new InvalidDataException("Unsupported patch hunk line.");
                }
                if (i + 1 < lines.Length && lines[i + 1] == "\\ No newline at end of file")
                {
                    i++;
                    ValidateNoNewline(previous, cursor, source.Count);
                    if (previous != '-') endsWithNewline = false;
                }
                hunks++;
            }
            if (hunks == 0) throw new InvalidDataException("A text patch must contain a hunk; mode-only and rename-only patches are not supported.");
            if (cursor < source.Count) endsWithNewline = sourceEndsWithNewline;
            output.AddRange(source.Skip(cursor));
            if (next is null && output.Count != 0) throw new InvalidDataException("Deleted file has remaining content.");
            var result = Utf8.GetBytes(string.Join(newline, output) + (output.Count > 0 && endsWithNewline ? newline : ""));
            if (bom) result = new byte[] { 239, 187, 191 }.Concat(result).ToArray();
            changes.Add(new(old, next, next is null ? null : result));
        }
        if (changes.Count == 0) throw new InvalidDataException("No supported unified-diff changes were found.");
        return changes;
    }

    private static void ValidateNoNewline(char previous, int cursor, int count)
    {
        if (previous == '\0' || (previous is ' ' or '-' && cursor != count)) throw new InvalidDataException("Invalid end-of-file marker.");
    }

    private static string? ReadPath(string value, string prefix)
    {
        var path = value.Split('\t')[0];
        if (path.StartsWith('"'))
        {
            try { path = System.Text.Json.JsonSerializer.Deserialize<string>(path)!; }
            catch (System.Text.Json.JsonException ex) { throw new InvalidDataException("Unsupported quoted patch path.", ex); }
        }
        if (path == "/dev/null") return null;
        return path.StartsWith(prefix, StringComparison.Ordinal) ? path[prefix.Length..] : path;
    }

    public static string Resolve(string root, string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.Contains('\\') || path.Contains(':') || path.StartsWith('/') ||
            path.Split('/').Any(x => x is "" or "." or ".." || x.EndsWith('.') || x.EndsWith(' ') || x.Equals(".git", StringComparison.OrdinalIgnoreCase)))
            throw new InvalidDataException($"Unsafe patch path: {path}");
        foreach (var component in path.Split('/'))
        {
            if (component.Any(ch => ch < 32 || "<>\"|?*".Contains(ch)) ||
                Regex.IsMatch(component.Split('.')[0], @"^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant))
                throw new InvalidDataException($"Invalid source path: {path}");
        }
        var canonical = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        var full = Path.GetFullPath(Path.Combine(canonical, path));
        if (!full.StartsWith(canonical, StringComparison.OrdinalIgnoreCase)) throw new InvalidDataException("Patch path escapes source workspace.");
        var parent = full;
        while (parent.Length >= canonical.Length)
        {
            if ((File.Exists(parent) || Directory.Exists(parent)) && (File.GetAttributes(parent) & FileAttributes.ReparsePoint) != 0)
                throw new InvalidDataException("Patch paths must not traverse links.");
            parent = Path.GetDirectoryName(parent) ?? "";
        }
        return full;
    }

    public static void Apply(string root, IReadOnlyList<PatchChange> changes)
    {
        // Caller owns an isolated, disposable workspace. Preparation has already validated every hunk.
        foreach (var change in changes)
        {
            if (change.NewPath is not null)
            {
                var path = Resolve(root, change.NewPath);
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.WriteAllBytes(path, change.Contents!);
            }
            if (change.OldPath is not null && change.OldPath != change.NewPath) File.Delete(Resolve(root, change.OldPath));
        }
    }
}
