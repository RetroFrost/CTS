using System.Buffers.Binary;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CubicalCompare.Windows;

public static class RendererBundleReader
{
    private const int MaxFileBytes = 128 * 1024 * 1024;
    private const int MaxManifestBytes = 64 * 1024 * 1024;

    public static RendererCandidate Inspect(string path)
    {
        var bytes = File.ReadAllBytes(path);
        if (bytes.Length > MaxFileBytes) throw new InvalidDataException("Renderer file is too large.");
        var spec = Read(bytes);
        var structural = ValidateStructure(spec);
        var compatibility = RendererCapabilities.Report(spec);
        var errors = structural.Errors.Concat(compatibility.Errors).Distinct().ToArray();
        var warnings = structural.Warnings.Concat(compatibility.Warnings).Distinct().ToArray();
        var sha = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        return new RendererCandidate(bytes, spec, sha, new RendererValidationReport(errors, warnings));
    }

    public static RendererSpec Read(byte[] bytes)
    {
        if (bytes.Length > MaxFileBytes) throw new InvalidDataException("Renderer file is too large.");
        if (bytes.Length >= 8 && Encoding.ASCII.GetString(bytes, 0, 8) == "CCRNDR03") return ReadV3Container(bytes, new Dictionary<string, byte[]>(), bytes);
        if (bytes.Length >= 2 && bytes[0] == (byte)'P' && bytes[1] == (byte)'K') return ReadV3Package(bytes);
        return ReadLegacy(bytes);
    }

    private static RendererSpec ReadLegacy(byte[] bytes)
    {
        var json = ReadContainerJson(bytes, "CCRNDR01", "renderer");
        using var doc = JsonDocument.Parse(json);
        var j = doc.RootElement;
        var d = RendererSpec.BuiltIn();
        var id = j.String("id", d.Id);
        var spec = new RendererSpec
        {
            Id = id,
            Name = j.String("name", d.Name),
            Author = j.String("author", d.Author),
            FormatVersion = j.Int("formatVersion", d.FormatVersion),
            RendererApi = j.Int("rendererApi", j.Int("formatVersion", 1) >= 2 ? 2 : 1),
            Engine = j.String("engine", id.StartsWith("ribbon.", StringComparison.OrdinalIgnoreCase) ? "ribbon-exact" : d.Engine),
            PrecisionMode = j.String("precisionMode", id.StartsWith("ribbon.", StringComparison.OrdinalIgnoreCase) ? "frame-exact" : d.PrecisionMode),
            TimelineUnit = j.String("timelineUnit", "frames"),
            MinAppVersion = j.String("minAppVersion", d.MinAppVersion),
            ReferenceWidth = j.Int("referenceWidth", 1920),
            ReferenceHeight = j.Int("referenceHeight", 1080),
            ReferenceFps = j.Int("referenceFps", 60),
            CanonicalCardCount = j.Int("canonicalCardCount", 0),
            CanonicalFrameCount = j.Int("canonicalFrameCount", 0),
            BackgroundColor = Color(j, "backgroundColor", d.BackgroundColor),
            TitleBackgroundColor = Color(j, "titleBackgroundColor", d.TitleBackgroundColor),
            DescriptionBackgroundColor = Color(j, "descriptionBackgroundColor", d.DescriptionBackgroundColor),
            TitleTextColor = Color(j, "titleTextColor", d.TitleTextColor),
            DescriptionTextColor = Color(j, "descriptionTextColor", d.DescriptionTextColor),
            BadgeColor = Color(j, "badgeColor", d.BadgeColor),
            BadgeDarkColor = Color(j, "badgeDarkColor", d.BadgeDarkColor),
            BadgeTextColor = Color(j, "badgeTextColor", d.BadgeTextColor),
            ShineColor = Color(j, "shineColor", d.ShineColor),
            SlotPitch = Float(j, "slotPitch", d.SlotPitch),
            BodyInset = Float(j, "bodyInset", d.BodyInset),
            BodyWidth = Float(j, "bodyWidth", d.BodyWidth),
            ImageHeight = Float(j, "imageHeight", d.ImageHeight),
            TitleHeight = Float(j, "titleHeight", d.TitleHeight),
            DescriptionTop = Float(j, "descriptionTop", d.DescriptionTop),
            TitleTextSize = Float(j, "titleTextSize", d.TitleTextSize),
            DescriptionTextSize = Float(j, "descriptionTextSize", d.DescriptionTextSize),
            BadgeCenterX = Float(j, "badgeCenterX", d.BadgeCenterX),
            BadgeCenterY = Float(j, "badgeCenterY", d.BadgeCenterY),
            BadgeScale = Float(j, "badgeScale", d.BadgeScale),
            ContinuousStartFrame = j.Int("continuousStartFrame", d.ContinuousStartFrame),
            ContinuousStepFrames = j.Int("continuousStepFrames", d.ContinuousStepFrames),
            BodySlideFrames = j.Int("bodySlideFrames", d.BodySlideFrames),
            LaterBadgeFallStartFrame = j.Int("laterBadgeFallStartFrame", d.LaterBadgeFallStartFrame),
            LaterBadgeFallEndFrame = j.Int("laterBadgeFallEndFrame", d.LaterBadgeFallEndFrame),
            ShineStartFrame = j.Int("shineStartFrame", d.ShineStartFrame),
            ShineFrames = j.Int("shineFrames", d.ShineFrames),
            EndWipeFrames = j.Int("endWipeFrames", d.EndWipeFrames),
            EndRiseFrames = j.Int("endRiseFrames", d.EndRiseFrames),
            EndHoldFrames = j.Int("endHoldFrames", d.EndHoldFrames),
            FadeFrames = j.Int("fadeFrames", d.FadeFrames),
            BlackTailFrames = j.Int("blackTailFrames", d.BlackTailFrames),
            OpeningStarts = IntArray(j, "openingStarts", d.OpeningStarts),
            OpeningEnds = IntArray(j, "openingEnds", d.OpeningEnds),
            RequiredFeatures = StringArray(j, "requiredFeatures"),
            Tags = StringArray(j, "tags"),
            PreviewFrames = IntArray(j, "previewFrames", []),
            Tracks = ParseTracks(j),
        };
        return spec;
    }

    private static RendererSpec ReadV3Package(byte[] packageBytes)
    {
        var entries = new Dictionary<string, byte[]>(StringComparer.OrdinalIgnoreCase);
        using var memory = new MemoryStream(packageBytes, writable: false);
        using var zip = new ZipArchive(memory, ZipArchiveMode.Read, leaveOpen: false);
        if (zip.Entries.Count > 2048) throw new InvalidDataException("Renderer v3 package contains too many files.");
        foreach (var entry in zip.Entries)
        {
            if (string.IsNullOrEmpty(entry.Name)) continue;
            var name = SafeEntryName(entry.FullName);
            using var input = entry.Open();
            using var output = new MemoryStream();
            CopyLimited(input, output, MaxFileBytes);
            entries[name] = output.ToArray();
        }
        var sceneEntry = entries.FirstOrDefault(x => x.Key.Equals("renderer.renderer3", StringComparison.OrdinalIgnoreCase));
        if (sceneEntry.Value == null) sceneEntry = entries.FirstOrDefault(x => x.Key.Equals("manifest.renderer3", StringComparison.OrdinalIgnoreCase));
        if (sceneEntry.Value == null) sceneEntry = entries.FirstOrDefault(x => x.Key.EndsWith(".renderer3", StringComparison.OrdinalIgnoreCase));
        if (sceneEntry.Value == null) throw new InvalidDataException("Renderer v3 ZIP has no .renderer3 scene file.");
        var assets = entries.Where(x => !x.Key.Equals(sceneEntry.Key, StringComparison.OrdinalIgnoreCase)).ToDictionary(x => x.Key, x => x.Value, StringComparer.OrdinalIgnoreCase);
        return ReadV3Container(sceneEntry.Value, assets, packageBytes);
    }

    private static RendererSpec ReadV3Container(byte[] container, Dictionary<string, byte[]> assets, byte[] originalBytes)
    {
        var json = ReadContainerJson(container, "CCRNDR03", "Renderer v3");
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement.Clone();
        var api = root.Int("api", root.Int("rendererApi", 3));
        if (api != 3) throw new InvalidDataException($"Renderer scene requires API {api}; this loader handles API 3.");
        var id = root.String("id", "");
        if (string.IsNullOrWhiteSpace(id)) throw new InvalidDataException("Renderer v3 scene has no id.");
        if (!Regex.IsMatch(id, "^[A-Za-z0-9._-]{1,128}$")) throw new InvalidDataException("Invalid Renderer v3 id.");
        var canvas = root.TryGetProperty("canvas", out var c) && c.ValueKind == JsonValueKind.Object ? c : default;
        var reference = root.TryGetProperty("reference", out var r) && r.ValueKind == JsonValueKind.Object ? r : default;
        var width = canvas.Int("width", reference.Int("width", 1920));
        var height = canvas.Int("height", reference.Int("height", 1080));
        var fps = canvas.Int("fps", reference.Int("fps", 60));
        var timeline = root.TryGetProperty("timeline", out var t) && t.ValueKind == JsonValueKind.Object ? t : default;
        var frames = timeline.Int("frames", reference.Int("frameCount", 0));
        if (frames <= 0) throw new InvalidDataException("Renderer v3 timeline must declare frames.");
        if (timeline.String("clock", "absolute") != "absolute") throw new InvalidDataException("Renderer v3 requires an absolute integer frame clock.");
        if (timeline.Bool("implicitAnimation", false)) throw new InvalidDataException("Renderer v3 implicit animation must be disabled.");

        var resources = new Dictionary<string, JsonElement>(StringComparer.Ordinal);
        if (root.TryGetProperty("resources", out var resourcesElement) && resourcesElement.ValueKind == JsonValueKind.Object)
            foreach (var property in resourcesElement.EnumerateObject()) resources[property.Name] = property.Value.Clone();

        var objects = new List<RendererObjectV3>();
        if (root.TryGetProperty("objects", out var objectsElement) && objectsElement.ValueKind == JsonValueKind.Array)
        {
            var index = 0;
            foreach (var item in objectsElement.EnumerateArray())
            {
                var frame = item.Int("frame", 0);
                var life = item.TryGetProperty("lifespan", out var l) && l.ValueKind == JsonValueKind.Object ? l : default;
                var start = life.Int("start", frame);
                var end = life.Int("end", frames - 1);
                if (start < 0 || start >= frames || end < start || end >= frames) throw new InvalidDataException("Invalid Renderer v3 object lifespan.");
                var props = item.TryGetProperty("properties", out var p) && p.ValueKind == JsonValueKind.Object ? p.Clone() : EmptyObject();
                objects.Add(new RendererObjectV3
                {
                    Id = item.String("id", $"object-{index}"),
                    Kind = item.String("kind", "custom"),
                    Frame = frame,
                    Resource = item.String("resource", "") is { Length: > 0 } resource ? resource : null,
                    LifespanStart = start,
                    LifespanEnd = end,
                    Properties = props,
                    Raw = item.Clone(),
                });
                index++;
            }
        }
        if (objects.Select(x => x.Id).Distinct(StringComparer.Ordinal).Count() != objects.Count) throw new InvalidDataException("Renderer v3 object ids must be unique.");

        var selectors = new List<RendererSelectorV3>();
        if (root.TryGetProperty("selectors", out var selectorsElement) && selectorsElement.ValueKind == JsonValueKind.Array)
        {
            var order = 0;
            foreach (var item in selectorsElement.EnumerateArray()) selectors.Add(ParseSelector(item, order++));
        }
        var layers = root.TryGetProperty("layers", out var layersElement) && layersElement.ValueKind == JsonValueKind.Array
            ? layersElement.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString()!).ToList()
            : [];
        var checkpoints = new List<int>();
        if (root.TryGetProperty("checkpoints", out var checkpointsElement) && checkpointsElement.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in checkpointsElement.EnumerateArray())
            {
                var value = item.ValueKind == JsonValueKind.Number && item.TryGetInt32(out var i) ? i : item.ValueKind == JsonValueKind.Object ? item.Int("frame", -1) : -1;
                if (value >= 0 && value < frames) checkpoints.Add(value);
            }
        }
        var features = new List<string>();
        features.AddRange(StringArray(root, "features"));
        features.AddRange(StringArray(root, "requiredFeatures"));
        features = features.Where(x => !string.IsNullOrWhiteSpace(x)).Distinct(StringComparer.Ordinal).ToList();
        var geometry = root.TryGetProperty("geometry", out var g) && g.ValueKind == JsonValueKind.Object ? g : default;
        var minimum = root.String("minAppVersion", root.String("minimumAppVersion", "3.0.300"));
        var scene = new RendererSceneV3
        {
            Root = root,
            Assets = assets,
            Objects = objects,
            Selectors = selectors,
            Layers = layers,
            Resources = resources,
            Frames = frames,
        };
        return new RendererSpec
        {
            Id = id,
            Name = root.String("name", id),
            Author = root.String("author", "Cubical Compare"),
            FormatVersion = 3,
            RendererApi = 3,
            Engine = "scene-v3",
            PrecisionMode = "frame-exact",
            TimelineUnit = "frames",
            MinAppVersion = minimum,
            ReferenceWidth = width,
            ReferenceHeight = height,
            ReferenceFps = fps,
            CanonicalCardCount = reference.Int("cardCount", objects.Count(x => x.Kind == "card")),
            CanonicalFrameCount = frames,
            RequiredFeatures = features,
            Tags = ["renderer-api-v3", "scene-v3"],
            PreviewFrames = checkpoints,
            SlotPitch = (float)geometry.Double("slotPitch", 476),
            BodyInset = (float)geometry.Double("bodyInset", 9),
            BodyWidth = (float)geometry.Double("bodyWidth", 471),
            ImageHeight = (float)geometry.Double("imageHeight", geometry.Double("topFieldHeight", 872)),
            DescriptionTop = (float)geometry.Double("descriptionTop", 965),
            SceneV3 = scene,
        };
    }

    public static RendererValidationReport ValidateStructure(RendererSpec spec)
    {
        var errors = new List<string>();
        var warnings = new List<string>();
        if (!Regex.IsMatch(spec.Id, "^[A-Za-z0-9._-]{1,128}$")) errors.Add("Invalid renderer id.");
        if (string.IsNullOrWhiteSpace(spec.Name) || spec.Name.Length > 160) errors.Add("Invalid renderer name.");
        if (spec.Author.Length > 160) errors.Add("Invalid renderer author.");
        if (spec.FormatVersion is < 1 or > 3) errors.Add($"Unsupported renderer schema {spec.FormatVersion}.");
        if (spec.RendererApi is < 1 or > 32) errors.Add("Invalid renderer API.");
        if (!Regex.IsMatch(spec.Engine, "^[A-Za-z0-9._-]{1,64}$")) errors.Add("Invalid renderer engine.");
        if (spec.PrecisionMode is not ("interpolated" or "frame-exact")) errors.Add("Invalid precision mode.");
        if (spec.TimelineUnit is not ("frames" or "milliseconds" or "normalized")) errors.Add("Invalid timeline unit.");
        if (spec.ReferenceWidth is < 1 or > 16384 || spec.ReferenceHeight is < 1 or > 16384) errors.Add("Invalid reference resolution.");
        if (spec.ReferenceFps is < 1 or > 240) errors.Add("Invalid reference FPS.");
        if (spec.Tracks.Count > 256) errors.Add("Too many renderer tracks.");
        var targets = new HashSet<string>(StringComparer.Ordinal);
        foreach (var track in spec.Tracks)
        {
            if (!targets.Add(track.Target)) errors.Add($"Duplicate renderer track '{track.Target}'.");
            if (track.Keyframes.Count is < 1 or > 4096) errors.Add($"Invalid keyframe count for '{track.Target}'.");
            var previous = -1;
            foreach (var key in track.Keyframes)
            {
                if (key.Frame < previous || !float.IsFinite(key.Value)) errors.Add($"Invalid keyframe in '{track.Target}'.");
                previous = key.Frame;
            }
        }
        if (spec.PrecisionMode == "frame-exact" && spec.CanonicalFrameCount == 0) warnings.Add("Frame-exact renderer does not declare a canonical frame count.");
        if (spec.PrecisionMode == "frame-exact" && spec.PreviewFrames.Count == 0) warnings.Add("Frame-exact renderer has no preview checkpoints.");
        return new RendererValidationReport(errors.Distinct().ToArray(), warnings.Distinct().ToArray());
    }

    private static string ReadContainerJson(byte[] bytes, string expectedMagic, string label)
    {
        if (bytes.Length < 20) throw new InvalidDataException($"Not a Cubical Compare {label} file.");
        if (Encoding.ASCII.GetString(bytes, 0, 8) != expectedMagic) throw new InvalidDataException($"Not a Cubical Compare {label} file.");
        var version = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(8, 4));
        if (version != 1) throw new InvalidDataException($"Unsupported {label} container version.");
        var length = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(12, 4));
        var expectedCrc = unchecked((uint)BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4)));
        if (length <= 0 || length > MaxFileBytes - 20 || bytes.Length != 20 + length) throw new InvalidDataException($"Invalid {label} payload length.");
        var payload = bytes.AsSpan(20, length).ToArray();
        if (Crc32.Compute(payload) != expectedCrc) throw new InvalidDataException($"{label} checksum failed.");
        using var input = new GZipStream(new MemoryStream(payload, writable: false), CompressionMode.Decompress);
        using var output = new MemoryStream();
        CopyLimited(input, output, MaxManifestBytes);
        return Encoding.UTF8.GetString(output.ToArray());
    }

    private static RendererSelectorV3 ParseSelector(JsonElement item, int sourceOrder)
    {
        var raw = item.String("select", "");
        var match = Regex.Match(raw, "^([A-Za-z_][A-Za-z0-9_.-]*)\\[(.*)]$");
        if (!match.Success) throw new InvalidDataException($"Invalid Renderer v3 selector '{raw}'.");
        var conditions = new List<RendererConditionV3>();
        var specificity = 100;
        var body = match.Groups[2].Value.Trim();
        if (!string.IsNullOrWhiteSpace(body) && body != "*")
        {
            foreach (var token in body.Split(',').Select(x => x.Trim()).Where(x => x.Length > 0))
            {
                if (token.StartsWith("frame=", StringComparison.Ordinal) && token.Contains("..", StringComparison.Ordinal))
                {
                    var parts = token[6..].Split("..", 2);
                    conditions.Add(new("frame", ">=", int.Parse(parts[0])));
                    conditions.Add(new("frame", "<=", int.Parse(parts[1])));
                    specificity = Math.Max(specificity, 320);
                    continue;
                }
                var condition = Regex.Match(token, "^([A-Za-z_][A-Za-z0-9_.-]*)(>=|<=|!=|=|>|<)(.+)$");
                if (!condition.Success) throw new InvalidDataException($"Invalid Renderer v3 selector condition '{token}'.");
                var key = condition.Groups[1].Value;
                var op = condition.Groups[2].Value;
                var value = Scalar(condition.Groups[3].Value);
                conditions.Add(new(key, op, value));
                specificity = Math.Max(specificity, key == "frame" && op == "=" ? 500 : key is "every" or "from" or "to" ? 360 : key == "frame" ? 280 : 220);
            }
        }
        var props = item.TryGetProperty("properties", out var p) && p.ValueKind == JsonValueKind.Object ? p.Clone() : EmptyObject();
        return new RendererSelectorV3
        {
            RawSelector = raw,
            Kind = match.Groups[1].Value,
            Conditions = conditions,
            Specificity = specificity + conditions.Count,
            SourceOrder = sourceOrder,
            Timeline = item.String("timeline", "relative"),
            Properties = props,
        };
    }

    private static object Scalar(string value)
    {
        var v = value.Trim();
        if (int.TryParse(v, out var i)) return i;
        if (double.TryParse(v, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var d)) return d;
        if (bool.TryParse(v, out var b)) return b;
        return v;
    }

    private static List<RendererTrack> ParseTracks(JsonElement j)
    {
        var result = new List<RendererTrack>();
        if (!j.TryGetProperty("tracks", out var tracks) || tracks.ValueKind != JsonValueKind.Array) return result;
        foreach (var item in tracks.EnumerateArray())
        {
            var keys = new List<RendererKeyframe>();
            if (item.TryGetProperty("keyframes", out var frames) && frames.ValueKind == JsonValueKind.Array)
                foreach (var frame in frames.EnumerateArray())
                    keys.Add(new RendererKeyframe { Frame = frame.Int("timeMs", 0), Value = (float)frame.Double("value", 0), Easing = frame.String("easing", "linear") });
            result.Add(new RendererTrack { Target = item.String("target", ""), Keyframes = keys });
        }
        return result;
    }

    private static uint Color(JsonElement j, string key, uint fallback)
    {
        if (!j.TryGetProperty(key, out var value)) return fallback;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var n)) return unchecked((uint)n);
        return fallback;
    }
    private static float Float(JsonElement j, string key, float fallback) => (float)j.Double(key, fallback);
    private static List<string> StringArray(JsonElement j, string key) => j.TryGetProperty(key, out var a) && a.ValueKind == JsonValueKind.Array ? a.EnumerateArray().Select(x => x.ToString()).Where(x => x.Length > 0).ToList() : [];
    private static List<int> IntArray(JsonElement j, string key, List<int> fallback) => j.TryGetProperty(key, out var a) && a.ValueKind == JsonValueKind.Array ? a.EnumerateArray().Select(x => x.TryGetInt32(out var i) ? i : 0).ToList() : [.. fallback];
    private static string SafeEntryName(string value)
    {
        var normalized = value.Replace('\\', '/').TrimStart('/');
        if (string.IsNullOrWhiteSpace(normalized) || normalized.Split('/').Any(x => x == "..")) throw new InvalidDataException("Unsafe renderer v3 package path.");
        return normalized;
    }
    private static void CopyLimited(Stream input, Stream output, int limit)
    {
        var buffer = new byte[16384];
        var total = 0;
        while (true)
        {
            var count = input.Read(buffer, 0, buffer.Length);
            if (count <= 0) break;
            total += count;
            if (total > limit) throw new InvalidDataException("Renderer data is too large.");
            output.Write(buffer, 0, count);
        }
    }
    private static JsonElement EmptyObject()
    {
        using var doc = JsonDocument.Parse("{}");
        return doc.RootElement.Clone();
    }
}

public static class RendererCapabilities
{
    public const string AppVersion = "3.0.300";
    public const int RendererApi = 3;

    private static readonly HashSet<string> Engines = new(StringComparer.Ordinal)
    {
        "native-standard", "infinite-timeline-exact", "ribbon-exact", "relationships-exact", "scene-v3",
    };

    // Every capability declared here has an implementation path in RendererEngine or
    // is a deterministic validation/clock contract enforced by this Windows port.
    private static readonly HashSet<string> Features = new(StringComparer.Ordinal)
    {
        "exact-scroll-track", "frame-exact", "affine-badge-transform", "layered-artwork", "artwork-transform",
        "ribbon-artwork-region-v1", "custom-outro", "custom-intro", "per-frame-keyframes", "per-badge-affine-transform",
        "frame-addressed-shine", "ribbon-glass-shine-v1", "preview-frames", "source-30fps", "direct-gpu-canvas",
        "per-frame-polygon-vertices", "full-2d-transforms", "shine-geometry-tracks", "arbitrary-masks",
        "animated-rect-clip", "track-interpolation-modes", "per-item-animation", "exact-text-tracks",
        "source-baked-text-raster", "explicit-layer-order", "independent-shadow-resources", "independent-shadow-resource",
        "dense-frame-data", "raw-frame-tracks", "frame-addressed-objects", "frame-addressed-selectors",
        "property-level-selector-inheritance", "deterministic-selector-precedence", "zero-implicit-animation",
        "generic-renderer-resources", "group-transforms", "resource-lifespans", "selector-shared-behaviour",
        "renderer-owned-geometry", "renderer-owned-materials", "blend-compositing-modes", "per-frame-filter-tracks",
        "exact-artwork-transforms", "absolute-integer-frame-clock", "single-scene-preview-export-contract",
        "preview-export-identical-path", "reference-resolution-fps-lock", "frame-checkpoints", "pixel-diff-audit-contract",
        "selector-cascade-inspection", "exact-outro-overlay", "renderer-api-v3-scene-ir", "renderer-v3-sidecar-resources",
        "renderer-v3-zip-package", "project-card-data", "relationships-exact-v2", "relationships-footer-waveform",
        "relationships-rich-typography", "relationships-shadow-mask-v1", "relationships-shadow-outside-v2",
        "relationships-single-owner-pass-v1", "relationships-windowed-card-tracks-v1", "infinite-timeline-source-v1",
        "infinite-timeline-source-v2",
    };

    public static RendererValidationReport Report(RendererSpec spec)
    {
        var errors = new List<string>();
        var warnings = new List<string>();
        if (spec.RendererApi > RendererApi) errors.Add($"Requires renderer API {spec.RendererApi}; this build supports API {RendererApi}.");
        if (!Engines.Contains(spec.Engine)) errors.Add($"Renderer engine '{spec.Engine}' is not available in this build.");
        var missing = spec.RequiredFeatures.Where(x => !Features.Contains(x)).ToArray();
        if (missing.Length > 0) errors.Add("Unsupported renderer features: " + string.Join(", ", missing));
        if (CompareVersions(AppVersion, spec.MinAppVersion) < 0) errors.Add($"Requires Cubical Compare {spec.MinAppVersion} or newer.");
        if (spec.ReferenceWidth != 1920 || spec.ReferenceHeight != 1080) warnings.Add($"Reference canvas is {spec.ReferenceWidth}×{spec.ReferenceHeight}; exports may be scaled.");
        if (spec.ReferenceFps != 60) warnings.Add($"Reference frame rate is {spec.ReferenceFps} fps.");
        if (spec.PrecisionMode == "frame-exact" && spec.TimelineUnit != "frames") errors.Add("Frame-exact renderers must use frame timeline units.");
        return new RendererValidationReport(errors, warnings);
    }

    public static int CompareVersions(string a, string b)
    {
        static int[] Parts(string value) => value.Split('.').Select(part => int.TryParse(new string(part.TakeWhile(char.IsDigit).ToArray()), out var n) ? n : 0).ToArray();
        var aa = Parts(a); var bb = Parts(b);
        for (var i = 0; i < Math.Max(aa.Length, bb.Length); i++)
        {
            var av = i < aa.Length ? aa[i] : 0; var bv = i < bb.Length ? bb[i] : 0;
            if (av != bv) return av.CompareTo(bv);
        }
        return 0;
    }
}

public sealed class RendererStore
{
    private readonly string _dir;
    private readonly string _library;
    private readonly string _active;
    private readonly string _previous;

    public RendererStore()
    {
        _dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "CubicalCompare", "renderers");
        _library = Path.Combine(_dir, "library");
        _active = Path.Combine(_dir, "active.renderer");
        _previous = Path.Combine(_dir, "previous.renderer");
    }

    public RendererSpec Active()
    {
        if (!File.Exists(_active)) return RendererSpec.BuiltIn();
        try { return RendererBundleReader.Read(File.ReadAllBytes(_active)); }
        catch { return RendererSpec.BuiltIn(); }
    }

    public string Install(RendererCandidate candidate)
    {
        if (!candidate.Report.Compatible) throw new InvalidOperationException(string.Join(Environment.NewLine, candidate.Report.Errors));
        Directory.CreateDirectory(_library);
        var file = Path.Combine(_library, Sanitize(candidate.Spec.Id) + ".renderer");
        AtomicWrite(file, candidate.Bytes);
        return file;
    }

    public RendererSpec Activate(RendererCandidate candidate)
    {
        Install(candidate);
        Directory.CreateDirectory(_dir);
        if (File.Exists(_active)) File.Copy(_active, _previous, true);
        AtomicWrite(_active, candidate.Bytes);
        return RendererBundleReader.Read(candidate.Bytes);
    }

    public RendererSpec Reset()
    {
        Directory.CreateDirectory(_dir);
        if (File.Exists(_active)) File.Copy(_active, _previous, true);
        if (File.Exists(_active)) File.Delete(_active);
        return RendererSpec.BuiltIn();
    }

    private static string Sanitize(string value) => string.Concat(value.Select(c => char.IsLetterOrDigit(c) || c is '.' or '_' or '-' ? c : '_'));
    private static void AtomicWrite(string path, byte[] bytes)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp";
        File.WriteAllBytes(temp, bytes);
        File.Move(temp, path, true);
    }
}

internal static class Crc32
{
    private static readonly uint[] Table = BuildTable();
    private static uint[] BuildTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < table.Length; i++)
        {
            var c = i;
            for (var k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        return table;
    }
    public static uint Compute(ReadOnlySpan<byte> data)
    {
        var crc = 0xFFFFFFFFu;
        foreach (var b in data) crc = Table[(crc ^ b) & 0xFF] ^ (crc >> 8);
        return crc ^ 0xFFFFFFFFu;
    }
}
