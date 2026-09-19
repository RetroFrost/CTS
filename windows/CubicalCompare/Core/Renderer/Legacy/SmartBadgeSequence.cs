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

        return new SmartBadgeSequenceDefinition(
            root,
            descriptor.Width,
            descriptor.Height,
            descriptor.Fps,
            descriptor.Parts,
            fields);
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

            var prefix = root + "/" + folder.Trim('/') + "/";
            var frames = scene.Assets.Keys
                .Where(path => Normalize(path).StartsWith(prefix, StringComparison.OrdinalIgnoreCase))
                .Where(IsFrameAsset)
                .OrderBy(path => Path.GetFileName(path), StringComparer.OrdinalIgnoreCase)
                .ThenBy(path => path, StringComparer.OrdinalIgnoreCase)
                .ToArray();

            if (frames.Length == 0)
                throw new InvalidDataException($"Smart badge sequence '{root}' part '{folder}' has no frame images.");

            parts.Add(new SmartBadgePartDefinition(type, count, pause, folder, frames, templateOffset));
            templateOffset += frames.Length;
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
        IReadOnlyList<SmartBadgePartDefinition> Parts);
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

internal sealed record SmartBadgeFrameSelection(string Asset, int TemplateFrame);

internal sealed record SmartBadgeSequenceDefinition(
    string Root,
    int Width,
    int Height,
    int Fps,
    IReadOnlyList<SmartBadgePartDefinition> Parts,
    IReadOnlyList<SmartBadgeFieldDefinition> Fields)
{
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
