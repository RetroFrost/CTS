using System.IO.Compression;
using System.Runtime.CompilerServices;
using System.Text;
using System.Text.Json;

namespace CubicalCompare.Windows;

/// <summary>
/// Parses the Smart Features badge-sequence contract used by Renderer v3 packages.
///
/// The layout deliberately mirrors the useful part of Android's bootanimation.zip model:
/// a desc.txt file declares the canvas/FPS and one or more frame folders. Cubical Compare
/// adds smart.json beside it. smart.json marks replaceable badge text regions with the
/// literal token "jsparse"; only those regions are replaced with live project data.
///
/// Runtime frame PNG/WebP files are expected to be clean (no source text) and have a
/// transparent background. Authoring/calibration frames may contain "jsparse" labels,
/// but those are never composited during export.
/// </summary>
internal static class SmartBadgeSequence
{
    private static readonly ConditionalWeakTable<RendererSceneV3, Dictionary<string, SmartBadgeSequenceDefinition>> Cache = new();
    private static readonly ConditionalWeakTable<RendererSpec, Dictionary<string, SmartBadgeSequenceDefinition>> ArchiveCache = new();
    // SmartBadge v2 / SmartCard packs can contain thousands of source-locked frames.
    // Keep the count high enough for real bootanimation-style packs, while bounding
    // per-file and total expanded data to retain zip-bomb protection.
    private const int MaxArchiveEntries = 65_536;
    private const long MaxArchiveEntryBytes = 32L * 1024 * 1024;
    private const long MaxArchiveExpandedBytes = 512L * 1024 * 1024;

    public static SmartBadgeSequenceDefinition Load(RendererSceneV3 scene, string sequenceRoot)
    {
        ArgumentNullException.ThrowIfNull(scene);
        var root = Normalize(sequenceRoot);
        if (root.Length == 0)
            throw new InvalidDataException("Smart badge sequenceRoot is empty.");

        var map = Cache.GetValue(scene, _ => new Dictionary<string, SmartBadgeSequenceDefinition>(StringComparer.OrdinalIgnoreCase));
        lock (map)
        {
            if (map.TryGetValue(root, out var cached))
                return cached;

            var parsed = Parse(scene, root);
            map[root] = parsed;
            return parsed;
        }
    }

    public static IReadOnlyList<string> Validate(RendererSceneV3 scene, string sequenceRoot)
    {
        try
        {
            _ = Load(scene, sequenceRoot);
            return [];
        }
        catch (Exception ex)
        {
            return [ex.Message];
        }
    }

    public static SmartBadgeSequenceDefinition LoadArchive(RendererSpec spec, string archiveAsset)
    {
        ArgumentNullException.ThrowIfNull(spec);
        var normalizedAsset = Normalize(archiveAsset);
        if (normalizedAsset.Length == 0)
            throw new InvalidDataException("SmartBadge v2 pack asset is empty.");

        var map = ArchiveCache.GetValue(
            spec,
            _ => new Dictionary<string, SmartBadgeSequenceDefinition>(StringComparer.OrdinalIgnoreCase));
        lock (map)
        {
            if (map.TryGetValue(normalizedAsset, out var cached))
                return cached;

            var pair = spec.PackageAssets.FirstOrDefault(entry =>
                Normalize(entry.Key).Equals(normalizedAsset, StringComparison.OrdinalIgnoreCase) ||
                Normalize(entry.Key).EndsWith('/' + normalizedAsset, StringComparison.OrdinalIgnoreCase));
            if (pair.Value is null)
                throw new InvalidDataException($"SmartBadge v2 pack '{archiveAsset}' is missing from the renderer package.");

            var assets = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
            long expanded = 0;
            using (var memory = new MemoryStream(pair.Value, writable: false))
            using (var zip = new ZipArchive(memory, ZipArchiveMode.Read, leaveOpen: false))
            {
                var fileEntries = zip.Entries.Where(entry => !string.IsNullOrEmpty(entry.Name)).ToArray();
                if (fileEntries.Length == 0)
                    throw new InvalidDataException($"Smart Feature pack '{archiveAsset}' contains no files.");
                if (fileEntries.Length > MaxArchiveEntries)
                    throw new InvalidDataException(
                        $"Smart Feature pack '{archiveAsset}' contains {fileEntries.Length:N0} files; this build supports up to {MaxArchiveEntries:N0} frame assets per pack.");

                foreach (var entry in fileEntries)
                {
                    var name = Normalize(entry.FullName);
                    if (name.Length == 0 || name.Contains("..", StringComparison.Ordinal))
                        throw new InvalidDataException($"Smart Feature pack '{archiveAsset}' contains an unsafe path.");
                    if (entry.Length < 0 || entry.Length > MaxArchiveEntryBytes)
                        throw new InvalidDataException($"Smart Feature pack '{archiveAsset}' contains an oversized file '{name}'.");

                    checked { expanded += entry.Length; }
                    if (expanded > MaxArchiveExpandedBytes)
                        throw new InvalidDataException(
                            $"Smart Feature pack '{archiveAsset}' expands beyond the {MaxArchiveExpandedBytes / (1024 * 1024)} MiB safety limit.");

                    var assetPath = "pack/" + name.TrimStart('/');
                    if (assets.ContainsKey(assetPath))
                        throw new InvalidDataException($"Smart Feature pack '{archiveAsset}' contains a duplicate path '{name}'.");

                    using var input = entry.Open();
                    using var output = new MemoryStream();
                    var buffer = new byte[64 * 1024];
                    long copied = 0;
                    while (true)
                    {
                        var read = input.Read(buffer, 0, buffer.Length);
                        if (read <= 0) break;
                        copied += read;
                        if (copied > MaxArchiveEntryBytes)
                            throw new InvalidDataException($"Smart Feature pack '{archiveAsset}' contains an oversized file '{name}'.");
                        output.Write(buffer, 0, read);
                    }
                    assets.Add(assetPath, output.ToArray());
                }
            }

            using var emptyDocument = JsonDocument.Parse("{}");
            var scene = new RendererSceneV3
            {
                Root = emptyDocument.RootElement.Clone(),
                Assets = assets,
                Objects = [],
                Selectors = [],
                Layers = [],
                Resources = new Dictionary<string, JsonElement>(StringComparer.Ordinal),
                Frames = 1,
            };

            var parsed = Parse(scene, "pack") with
            {
                Root = "archive:" + normalizedAsset,
                Assets = assets,
            };
            map[normalizedAsset] = parsed;
            return parsed;
        }
    }

    public static IReadOnlyList<string> ValidateArchive(RendererSpec spec, string archiveAsset)
    {
        try
        {
            _ = LoadArchive(spec, archiveAsset);
            return [];
        }
        catch (Exception ex)
        {
            return [ex.Message];
        }
    }

    private static SmartBadgeSequenceDefinition Parse(RendererSceneV3 scene, string root)
    {
        var descPath = root + "/desc.txt";
        var smartPath = root + "/smart.json";
        var descBytes = FindAsset(scene, descPath)
            ?? throw new InvalidDataException($"Smart badge sequence is missing '{descPath}'.");
        var smartBytes = FindAsset(scene, smartPath)
            ?? throw new InvalidDataException($"Smart badge sequence is missing '{smartPath}'.");

        var descriptor = ParseDescriptor(scene, root, Encoding.UTF8.GetString(descBytes));
        using var smartDocument = JsonDocument.Parse(smartBytes);
        var smart = smartDocument.RootElement;
        if (smart.ValueKind != JsonValueKind.Object)
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' must be a JSON object.");

        var marker = smart.String("marker", "");
        if (!string.Equals(marker, "jsparse", StringComparison.Ordinal))
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' must use marker 'jsparse'.");

        if (!smart.Bool("transparentBackground", true))
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' must declare transparentBackground=true.");
        if (!smart.Bool("emptyText", true))
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' must declare emptyText=true so source text is never baked into project output.");

        var fields = new List<SmartBadgeFieldDefinition>();
        if (!smart.TryGetProperty("fields", out var fieldArray) || fieldArray.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' has no fields array.");

        foreach (var field in fieldArray.EnumerateArray())
        {
            if (field.ValueKind != JsonValueKind.Object)
                continue;

            var name = field.String("name", "").Trim();
            var token = field.String("token", "").Trim();
            if (name.Length == 0)
                throw new InvalidDataException($"Smart badge manifest '{smartPath}' contains an unnamed field.");
            if (!string.Equals(token, "jsparse", StringComparison.Ordinal))
                throw new InvalidDataException($"Smart badge field '{name}' must use token 'jsparse'.");

            var source = field.String("source", name).Trim();
            var rect = ReadRect(field, "rect");
            var rects = ReadRectTrack(field, "rects");
            if (rect is null && rects.Count == 0)
                throw new InvalidDataException($"Smart badge field '{name}' needs rect or rects geometry.");

            fields.Add(new SmartBadgeFieldDefinition(
                name,
                source,
                token,
                rect,
                rects,
                ReadNumberTrack(field, "alphas"),
                ReadNumberTrack(field, "rotations"),
                (float)Math.Max(1, field.Double("fontSize", 36)),
                (float)Math.Max(1, field.Double("minFontSize", 12)),
                field.Bool("bold", false),
                field.String("color", "#ffffff"),
                field.String("align", "center"),
                field.String("verticalAlign", "center"),
                field.Bool("shadow", false),
                field.String("shadowColor", "#aa000000"),
                (float)field.Double("shadowX", 2),
                (float)field.Double("shadowY", 3),
                (float)Math.Max(0, field.Double("shadowBlur", 0)),
                field.String("strokeColor", "#00000000"),
                (float)Math.Max(0, field.Double("strokeWidth", 0))));
        }

        if (fields.Count == 0)
            throw new InvalidDataException($"Smart badge manifest '{smartPath}' has no usable jsparse fields.");

        SmartSequenceArtworkDefinition? artwork = null;
        if (smart.TryGetProperty("artwork", out var artworkElement) &&
            artworkElement.ValueKind == JsonValueKind.Object)
        {
            var dest = ReadRect(artworkElement, "dest");
            var destRects = ReadRectTrack(artworkElement, "destRects");
            if (dest is null && destRects.Count == 0)
                throw new InvalidDataException(
                    $"Smart sequence artwork in '{smartPath}' needs dest or destRects geometry.");

            var clip = ReadRect(artworkElement, "clip");
            var clipRects = ReadRectTrack(artworkElement, "clipRects");
            artwork = new SmartSequenceArtworkDefinition(
                dest,
                destRects,
                clip,
                clipRects,
                ReadNumberTrack(artworkElement, "alphas"));
        }

        IReadOnlyList<string> overlayFrames = [];
        var overlayFolder = Normalize(smart.String("overlayFolder", ""));
        if (overlayFolder.Length > 0)
        {
            if (overlayFolder.Contains("..", StringComparison.Ordinal))
                throw new InvalidDataException($"Smart sequence overlay folder '{overlayFolder}' is unsafe.");
            overlayFrames = CollectFrameAssets(scene, root + "/" + overlayFolder, $"{root}/{overlayFolder}");
            if (overlayFrames.Count != descriptor.TotalTemplateFrames)
                throw new InvalidDataException(
                    $"Smart sequence overlay folder '{overlayFolder}' has {overlayFrames.Count} frames; expected {descriptor.TotalTemplateFrames}.");
        }

        return new SmartBadgeSequenceDefinition(
            root,
            descriptor.Width,
            descriptor.Height,
            descriptor.Fps,
            descriptor.Parts,
            fields,
            artwork,
            scene.Assets,
            overlayFrames);
    }

    private static SmartBadgeDescriptor ParseDescriptor(RendererSceneV3 scene, string root, string text)
    {
        var lines = text.Replace("\r", "")
            .Split('\n', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries)
            .Where(line => !line.StartsWith('#'))
            .ToArray();

        if (lines.Length == 0)
            throw new InvalidDataException($"Smart badge sequence '{root}' has an empty desc.txt.");

        var header = Split(lines[0]);
        if (header.Length < 3 ||
            !int.TryParse(header[0], out var width) ||
            !int.TryParse(header[1], out var height) ||
            !int.TryParse(header[2], out var fps) ||
            width <= 0 || height <= 0 || fps <= 0 || fps > 240)
            throw new InvalidDataException($"Smart badge sequence '{root}' has an invalid desc.txt header.");

        var parts = new List<SmartBadgePartDefinition>();
        var templateOffset = 0;
        for (var i = 1; i < lines.Length; i++)
        {
            var tokens = Split(lines[i]);
            if (tokens.Length < 4)
                throw new InvalidDataException($"Smart badge sequence '{root}' has an invalid part line: {lines[i]}");

            var type = tokens[0].Trim().ToLowerInvariant();
            if (type is not ("p" or "c"))
                throw new InvalidDataException($"Smart badge sequence '{root}' part type '{tokens[0]}' is unsupported.");
            if (!int.TryParse(tokens[1], out var count) || count < 0)
                throw new InvalidDataException($"Smart badge sequence '{root}' has an invalid loop count.");
            if (!int.TryParse(tokens[2], out var pause) || pause < 0)
                throw new InvalidDataException($"Smart badge sequence '{root}' has an invalid pause.");
            var folder = Normalize(tokens[3]);
            if (folder.Length == 0 || folder.Contains("..", StringComparison.Ordinal))
                throw new InvalidDataException($"Smart badge sequence '{root}' has an unsafe part folder.");

            var frames = CollectFrameAssets(scene, root + "/" + folder.Trim('/'), $"{root}/{folder}");
            parts.Add(new SmartBadgePartDefinition(type, count, pause, folder, frames, templateOffset));
            templateOffset += frames.Count;
        }

        if (parts.Count == 0)
            throw new InvalidDataException($"Smart badge sequence '{root}' has no animation parts.");

        return new SmartBadgeDescriptor(width, height, fps, parts);
    }

    private static string[] Split(string value) =>
        value.Split((char[]?)null, StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries);

    private static byte[]? FindAsset(RendererSceneV3 scene, string path)
    {
        var normalized = Normalize(path);
        foreach (var pair in scene.Assets)
        {
            var candidate = Normalize(pair.Key);
            if (candidate.Equals(normalized, StringComparison.OrdinalIgnoreCase) ||
                candidate.EndsWith('/' + normalized, StringComparison.OrdinalIgnoreCase))
                return pair.Value;
        }
        return null;
    }

    private static IReadOnlyList<string> CollectFrameAssets(RendererSceneV3 scene, string prefixRoot, string label)
    {
        var prefix = Normalize(prefixRoot).Trim('/') + "/";
        var frameCandidates = scene.Assets.Keys
            .Where(path => Normalize(path).StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
            .Where(IsFrameAsset)
            .ToArray();

        if (frameCandidates.Length == 0)
            throw new InvalidDataException($"Smart sequence '{label}' has no frame images.");

        var numbered = frameCandidates
            .Select(path => (
                Path: path,
                Parsed: int.TryParse(Path.GetFileNameWithoutExtension(path), out var number),
                Number: number))
            .ToArray();

        if (!numbered.All(x => x.Parsed))
            return frameCandidates
                .OrderBy(path => Path.GetFileName(path), StringComparer.OrdinalIgnoreCase)
                .ThenBy(path => path, StringComparer.OrdinalIgnoreCase)
                .ToArray();

        var ordered = numbered
            .OrderBy(x => x.Number)
            .ThenBy(x => x.Path, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        for (var frameIndex = 1; frameIndex < ordered.Length; frameIndex++)
        {
            var previous = ordered[frameIndex - 1].Number;
            var current = ordered[frameIndex].Number;
            if (current == previous)
                throw new InvalidDataException($"Smart sequence '{label}' contains duplicate frame index {current}.");
            if (current != previous + 1)
                throw new InvalidDataException(
                    $"Smart sequence '{label}' is missing frame index {previous + 1}; exact sequences must be contiguous.");
        }

        return ordered.Select(x => x.Path).ToArray();
    }

    private static bool IsFrameAsset(string path)
    {
        var extension = Path.GetExtension(path);
        return extension.Equals(".png", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".webp", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".jpg", StringComparison.OrdinalIgnoreCase) ||
               extension.Equals(".jpeg", StringComparison.OrdinalIgnoreCase);
    }

    private static SmartBadgeRect? ReadRect(JsonElement field, string property)
    {
        if (!field.TryGetProperty(property, out var value))
            return null;
        return ParseRect(value);
    }

    private static IReadOnlyList<SmartBadgeRect?> ReadRectTrack(JsonElement field, string property)
    {
        if (!field.TryGetProperty(property, out var value) || value.ValueKind != JsonValueKind.Array)
            return [];

        var output = new List<SmartBadgeRect?>();
        foreach (var item in value.EnumerateArray())
        {
            if (item.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
            {
                output.Add(null);
                continue;
            }
            output.Add(ParseRect(item));
        }
        return output;
    }

    private static SmartBadgeRect? ParseRect(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Array && value.GetArrayLength() >= 4)
        {
            var x = value[0].GetDouble();
            var y = value[1].GetDouble();
            var width = value[2].GetDouble();
            var height = value[3].GetDouble();
            if (width <= 0 || height <= 0) return null;
            return new SmartBadgeRect((float)x, (float)y, (float)width, (float)height);
        }

        if (value.ValueKind == JsonValueKind.Object)
        {
            var width = value.Double("width", value.Double("w", 0));
            var height = value.Double("height", value.Double("h", 0));
            if (width <= 0 || height <= 0) return null;
            return new SmartBadgeRect(
                (float)value.Double("x", 0),
                (float)value.Double("y", 0),
                (float)width,
                (float)height);
        }

        return null;
    }

    private static IReadOnlyList<float?> ReadNumberTrack(JsonElement field, string property)
    {
        if (!field.TryGetProperty(property, out var value) || value.ValueKind != JsonValueKind.Array)
            return [];

        var output = new List<float?>();
        foreach (var item in value.EnumerateArray())
        {
            if (item.ValueKind == JsonValueKind.Number && item.TryGetDouble(out var number))
                output.Add((float)number);
            else
                output.Add(null);
        }
        return output;
    }

    private static string Normalize(string? value) =>
        (value ?? "").Replace('\\', '/').Trim().Trim('/');

    private sealed record SmartBadgeDescriptor(
        int Width,
        int Height,
        int Fps,
        IReadOnlyList<SmartBadgePartDefinition> Parts)
    {
        public int TotalTemplateFrames => Parts.Sum(part => part.Frames.Count);
    }
}

internal sealed record SmartBadgePartDefinition(
    string Type,
    int Count,
    int Pause,
    string Folder,
    IReadOnlyList<string> Frames,
    int TemplateOffset);

internal sealed record SmartBadgeRect(float X, float Y, float Width, float Height)
{
    public float Right => X + Width;
    public float Bottom => Y + Height;
    public float MidX => X + Width / 2f;
    public float MidY => Y + Height / 2f;
}

internal sealed record SmartBadgeFieldDefinition(
    string Name,
    string Source,
    string Token,
    SmartBadgeRect? Rect,
    IReadOnlyList<SmartBadgeRect?> Rects,
    IReadOnlyList<float?> Alphas,
    IReadOnlyList<float?> Rotations,
    float FontSize,
    float MinFontSize,
    bool Bold,
    string Color,
    string Align,
    string VerticalAlign,
    bool Shadow,
    string ShadowColor,
    float ShadowX,
    float ShadowY,
    float ShadowBlur,
    string StrokeColor,
    float StrokeWidth)
{
    public SmartBadgeRect? RectAt(int templateFrame)
    {
        if (Rects.Count == 0) return Rect;
        if (templateFrame < 0) return null;
        return templateFrame < Rects.Count ? Rects[templateFrame] : Rects[^1];
    }

    public float AlphaAt(int templateFrame)
    {
        if (Alphas.Count == 0) return 1;
        if (templateFrame < 0) return 0;
        var value = templateFrame < Alphas.Count ? Alphas[templateFrame] : Alphas[^1];
        return Math.Clamp(value ?? 0, 0, 1);
    }

    public float RotationAt(int templateFrame)
    {
        if (Rotations.Count == 0 || templateFrame < 0) return 0;
        return templateFrame < Rotations.Count ? Rotations[templateFrame] ?? 0 : Rotations[^1] ?? 0;
    }
}

internal sealed record SmartSequenceArtworkDefinition(
    SmartBadgeRect? Dest,
    IReadOnlyList<SmartBadgeRect?> DestRects,
    SmartBadgeRect? Clip,
    IReadOnlyList<SmartBadgeRect?> ClipRects,
    IReadOnlyList<float?> Alphas)
{
    public SmartBadgeRect? DestAt(int templateFrame)
    {
        if (DestRects.Count == 0) return Dest;
        if (templateFrame < 0) return null;
        return templateFrame < DestRects.Count ? DestRects[templateFrame] : DestRects[^1];
    }

    public SmartBadgeRect? ClipAt(int templateFrame)
    {
        if (ClipRects.Count == 0) return Clip ?? DestAt(templateFrame);
        if (templateFrame < 0) return null;
        return templateFrame < ClipRects.Count ? ClipRects[templateFrame] : ClipRects[^1];
    }

    public float AlphaAt(int templateFrame)
    {
        if (Alphas.Count == 0) return 1;
        if (templateFrame < 0) return 0;
        var value = templateFrame < Alphas.Count ? Alphas[templateFrame] : Alphas[^1];
        return Math.Clamp(value ?? 0, 0, 1);
    }
}

internal sealed record SmartBadgeFrameSelection(string Asset, int TemplateFrame);

internal sealed record SmartBadgeSequenceDefinition(
    string Root,
    int Width,
    int Height,
    int Fps,
    IReadOnlyList<SmartBadgePartDefinition> Parts,
    IReadOnlyList<SmartBadgeFieldDefinition> Fields,
    SmartSequenceArtworkDefinition? Artwork,
    IReadOnlyDictionary<string, byte[]> Assets,
    IReadOnlyList<string> OverlayFrames)
{
    public string? OverlayAssetAt(int templateFrame) =>
        templateFrame >= 0 && templateFrame < OverlayFrames.Count ? OverlayFrames[templateFrame] : null;

    public SmartBadgeFrameSelection? FinalFrame()
    {
        for (var index = Parts.Count - 1; index >= 0; index--)
        {
            var part = Parts[index];
            if (part.Frames.Count == 0)
                continue;

            var frameIndex = part.Frames.Count - 1;
            return new SmartBadgeFrameSelection(
                part.Frames[frameIndex],
                part.TemplateOffset + frameIndex);
        }

        return null;
    }

    public bool HasInfinitePart => Parts.Any(part => part.Count == 0 && part.Frames.Count > 0);

    public SmartBadgeFrameSelection? SelectFrame(int frame)
    {
        if (frame < 0 || Parts.Count == 0) return null;

        var remaining = frame;
        SmartBadgeFrameSelection? last = null;

        foreach (var part in Parts)
        {
            var playLength = part.Frames.Count + part.Pause;
            if (playLength <= 0) continue;

            if (part.Count == 0)
            {
                var within = remaining % playLength;
                var index = Math.Min(within, part.Frames.Count - 1);
                return new SmartBadgeFrameSelection(part.Frames[index], part.TemplateOffset + index);
            }

            var duration = checked(playLength * part.Count);
            if (remaining < duration)
            {
                var within = remaining % playLength;
                var index = Math.Min(within, part.Frames.Count - 1);
                return new SmartBadgeFrameSelection(part.Frames[index], part.TemplateOffset + index);
            }

            remaining -= duration;
            last = new SmartBadgeFrameSelection(part.Frames[^1], part.TemplateOffset + part.Frames.Count - 1);
        }

        return last;
    }
}
