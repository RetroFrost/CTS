using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace CubicalCompare.Windows;

public enum EncoderPreference { Auto, H264, H265 }
public enum IntroMode { Renderer, Custom, Disabled }

public sealed class StudioCard
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "Card 1";
    public string Value { get; set; } = "1";
    public string BadgeHeader { get; set; } = "";
    public string Description { get; set; } = "";
    public string Image { get; set; } = "";
    public double ImageX { get; set; }
    public double ImageY { get; set; }
    public double ImageScale { get; set; } = 1.0;
    public double ImageRotation { get; set; }
    public double ImageCropLeft { get; set; }
    public double ImageCropTop { get; set; }
    public double ImageCropRight { get; set; }
    public double ImageCropBottom { get; set; }
    public string ImageLayer { get; set; } = "behind";

    public override string ToString() => string.IsNullOrWhiteSpace(Title) ? "Untitled" : Title;
}

public sealed class StudioProject
{
    public string Name { get; set; } = "Untitled";
    public List<StudioCard> Cards { get; set; } = [new()];
    public int Width { get; set; } = 1920;
    public int Height { get; set; } = 1080;
    public int Fps { get; set; } = 60;
    public bool ShowBadges { get; set; } = true;
    public bool CreditsEnabled { get; set; } = true;
    public IntroMode IntroMode { get; set; } = IntroMode.Renderer;
    public string IntroVideo { get; set; } = "";
    public string Soundtrack { get; set; } = "";
    public float SoundtrackVolume { get; set; } = 0.75f;
    public bool SoundtrackLoop { get; set; } = true;
    public bool AutoLength { get; set; } = true;
    public double CustomLengthSeconds { get; set; } = 90.0;
    public EncoderPreference EncoderPreference { get; set; } = EncoderPreference.Auto;
    public string FontFamily { get; set; } = "";
    public string FontFile { get; set; } = "";

    public static StudioProject Load(string path) => FromJson(File.ReadAllText(path));
    public void Save(string path) => File.WriteAllText(path, ToJson());

    public string ToJson()
    {
        var root = new JsonObject
        {
            ["version"] = 6,
            ["name"] = Name,
            ["cards"] = new JsonArray(Cards.Select(card => (JsonNode?)new JsonObject
            {
                ["id"] = card.Id,
                ["title"] = card.Title,
                ["value"] = card.Value,
                ["badge_header"] = card.BadgeHeader,
                ["description"] = card.Description,
                ["image"] = card.Image,
                ["image_x"] = card.ImageX,
                ["image_y"] = card.ImageY,
                ["image_scale"] = card.ImageScale,
                ["image_rotation"] = card.ImageRotation,
                ["image_crop_left"] = card.ImageCropLeft,
                ["image_crop_top"] = card.ImageCropTop,
                ["image_crop_right"] = card.ImageCropRight,
                ["image_crop_bottom"] = card.ImageCropBottom,
                ["image_layer"] = card.ImageLayer,
            }).ToArray()),
            ["settings"] = new JsonObject
            {
                ["width"] = Width,
                ["height"] = Height,
                ["fps"] = Fps,
                ["auto_length"] = AutoLength,
                ["custom_length_seconds"] = CustomLengthSeconds,
                ["show_badges"] = ShowBadges,
                ["credits_enabled"] = CreditsEnabled,
                ["intro_mode"] = IntroMode switch { IntroMode.Custom => "custom", IntroMode.Disabled => "disabled", _ => "renderer" },
                ["intro_video"] = IntroVideo,
                ["soundtrack"] = Soundtrack,
                ["soundtrack_volume"] = SoundtrackVolume,
                ["soundtrack_loop"] = SoundtrackLoop,
                ["encoder_preference"] = EncoderPreference switch { EncoderPreference.H264 => "h264", EncoderPreference.H265 => "h265", _ => "auto" },
                ["font_family"] = FontFamily,
                ["font_file"] = FontFile,
                ["encoder_preset"] = "faster",
                ["encoder_crf"] = 18,
            },
        };
        return root.ToJsonString(new JsonSerializerOptions { WriteIndented = true });
    }

    public static StudioProject FromJson(string text)
    {
        using var doc = JsonDocument.Parse(text);
        var root = doc.RootElement;
        var settings = root.TryGetProperty("settings", out var s) && s.ValueKind == JsonValueKind.Object ? s : default;
        var project = new StudioProject
        {
            Name = root.String("name", "Untitled"),
            Width = settings.Int("width", 1920),
            Height = settings.Int("height", 1080),
            Fps = settings.Int("fps", 60),
            AutoLength = settings.Bool("auto_length", true),
            CustomLengthSeconds = settings.Double("custom_length_seconds", 90.0),
            ShowBadges = settings.Bool("show_badges", true),
            CreditsEnabled = settings.Bool("credits_enabled", true),
            IntroVideo = settings.String("intro_video", ""),
            Soundtrack = settings.String("soundtrack", ""),
            SoundtrackVolume = (float)settings.Double("soundtrack_volume", 0.75),
            SoundtrackLoop = settings.Bool("soundtrack_loop", true),
            FontFamily = settings.String("font_family", ""),
            FontFile = settings.String("font_file", ""),
        };
        project.IntroMode = settings.String("intro_mode", "renderer") switch
        {
            "custom" => IntroMode.Custom,
            "disabled" => IntroMode.Disabled,
            _ => IntroMode.Renderer,
        };
        project.EncoderPreference = settings.String("encoder_preference", "auto") switch
        {
            "h264" => EncoderPreference.H264,
            "h265" => EncoderPreference.H265,
            _ => EncoderPreference.Auto,
        };
        project.Cards.Clear();
        if (root.TryGetProperty("cards", out var cards) && cards.ValueKind == JsonValueKind.Array)
        {
            foreach (var card in cards.EnumerateArray())
            {
                project.Cards.Add(new StudioCard
                {
                    Id = card.String("id", Guid.NewGuid().ToString("N")),
                    Title = card.String("title", ""),
                    Value = card.String("value", ""),
                    BadgeHeader = card.String("badge_header", card.String("badgeHeader", "")),
                    Description = card.String("description", ""),
                    Image = card.String("image", ""),
                    ImageX = card.Double("image_x", 0),
                    ImageY = card.Double("image_y", 0),
                    ImageScale = card.Double("image_scale", 1),
                    ImageRotation = card.Double("image_rotation", 0),
                    ImageCropLeft = card.Double("image_crop_left", 0),
                    ImageCropTop = card.Double("image_crop_top", 0),
                    ImageCropRight = card.Double("image_crop_right", 0),
                    ImageCropBottom = card.Double("image_crop_bottom", 0),
                    ImageLayer = card.String("image_layer", "behind"),
                });
            }
        }
        if (project.Cards.Count == 0) project.Cards.Add(new StudioCard());
        return project;
    }
}

public sealed class RendererKeyframe
{
    public int Frame { get; init; }
    public float Value { get; init; }
    public string Easing { get; init; } = "linear";
}

public sealed class RendererTrack
{
    public string Target { get; init; } = "";
    public List<RendererKeyframe> Keyframes { get; init; } = [];

    public float? ValueAt(int frame)
    {
        if (Keyframes.Count == 0) return null;
        if (frame <= Keyframes[0].Frame) return Keyframes[0].Value;
        if (frame >= Keyframes[^1].Frame) return Keyframes[^1].Value;
        var lo = 1;
        var hi = Keyframes.Count - 1;
        while (lo < hi)
        {
            var mid = (lo + hi) >> 1;
            if (Keyframes[mid].Frame < frame) lo = mid + 1; else hi = mid;
        }
        var right = Keyframes[lo];
        var left = Keyframes[lo - 1];
        var raw = Math.Clamp((frame - left.Frame) / (float)Math.Max(1, right.Frame - left.Frame), 0, 1);
        var p = right.Easing.ToLowerInvariant() switch
        {
            "ease-in" or "easein" => raw * raw,
            "ease-out" or "easeout" => 1 - (1 - raw) * (1 - raw),
            "ease-in-out" or "easeinout" or "smoothstep" => raw * raw * (3 - 2 * raw),
            "cubic-in" => raw * raw * raw,
            "cubic-out" => 1 - (1 - raw) * (1 - raw) * (1 - raw),
            "hold" or "step" => 0,
            _ => raw,
        };
        return left.Value + (right.Value - left.Value) * p;
    }

    public float? ValueAtWindowed(int frame) => Keyframes.Count == 0 || frame < Keyframes[0].Frame || frame > Keyframes[^1].Frame ? null : ValueAt(frame);
}

public sealed class RendererSpec
{
    public string Id { get; set; } = "cubical.3.0.300.native";
    public string Name { get; set; } = "Cubical Compare 3.0.300 Native";
    public string Author { get; set; } = "Cubical Compare";
    public int FormatVersion { get; set; } = 1;
    public int RendererApi { get; set; } = 1;
    public string Engine { get; set; } = "native-standard";
    public string PrecisionMode { get; set; } = "interpolated";
    public string TimelineUnit { get; set; } = "frames";
    public string MinAppVersion { get; set; } = "2.0.7";
    public int ReferenceWidth { get; set; } = 1920;
    public int ReferenceHeight { get; set; } = 1080;
    public int ReferenceFps { get; set; } = 60;
    public int CanonicalCardCount { get; set; }
    public int CanonicalFrameCount { get; set; }
    public List<string> RequiredFeatures { get; set; } = [];
    public List<string> Tags { get; set; } = [];
    public List<int> PreviewFrames { get; set; } = [];
    public uint BackgroundColor { get; set; } = 0xFF000000;
    public uint TitleBackgroundColor { get; set; } = 0xFFF2F2F2;
    public uint DescriptionBackgroundColor { get; set; } = 0xFF635E57;
    public uint TitleTextColor { get; set; } = 0xFF161616;
    public uint DescriptionTextColor { get; set; } = 0xFFFAFAF8;
    public uint BadgeColor { get; set; } = 0xFFD3070D;
    public uint BadgeDarkColor { get; set; } = 0xFFA60008;
    public uint BadgeTextColor { get; set; } = 0xFFFFFFFF;
    public uint ShineColor { get; set; } = 0x70FFFFFF;
    public float SlotPitch { get; set; } = 476;
    public float BodyInset { get; set; } = 9;
    public float BodyWidth { get; set; } = 471;
    public float ImageHeight { get; set; } = 872;
    public float TitleHeight { get; set; } = 93;
    public float DescriptionTop { get; set; } = 965;
    public float TitleTextSize { get; set; } = 45;
    public float DescriptionTextSize { get; set; } = 26;
    public float BadgeCenterX { get; set; } = 240;
    public float BadgeCenterY { get; set; } = 198;
    public float BadgeScale { get; set; } = 1;
    public int ContinuousStartFrame { get; set; } = 528;
    public int ContinuousStepFrames { get; set; } = 214;
    public int BodySlideFrames { get; set; } = 80;
    public int LaterBadgeFallStartFrame { get; set; } = 122;
    public int LaterBadgeFallEndFrame { get; set; } = 206;
    public int ShineStartFrame { get; set; } = 131;
    public int ShineFrames { get; set; } = 43;
    public int EndWipeFrames { get; set; } = 43;
    public int EndRiseFrames { get; set; } = 11;
    public int EndHoldFrames { get; set; } = 268;
    public int FadeFrames { get; set; } = 79;
    public int BlackTailFrames { get; set; } = 8;
    public List<int> OpeningStarts { get; set; } = [0, 120, 240, 360];
    public List<int> OpeningEnds { get; set; } = [120, 240, 360, 528];
    public List<RendererTrack> Tracks { get; set; } = [];
    public RendererSceneV3? SceneV3 { get; set; }

    private Dictionary<string, RendererTrack>? _tracks;
    private RendererTrack? FindTrack(string target)
    {
        _tracks ??= Tracks.GroupBy(x => x.Target).ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);
        if (_tracks.TryGetValue(target, out var exact)) return exact;
        var bits = target.Split('.');
        if (bits.Length >= 3 && bits[0] == "card")
        {
            var wildcard = "card.*." + string.Join('.', bits.Skip(2));
            if (_tracks.TryGetValue(wildcard, out var common)) return common;
        }
        return null;
    }
    public float? Track(string target, int frame) => FindTrack(target)?.ValueAt(frame);
    public float? TrackWindowed(string target, int frame) => FindTrack(target)?.ValueAtWindowed(frame);
    public bool HasTrack(string target) => FindTrack(target) != null;
    public int? TrackStart(string target) => FindTrack(target)?.Keyframes.FirstOrDefault()?.Frame;
    public int? TrackEnd(string target) => FindTrack(target)?.Keyframes.LastOrDefault()?.Frame;
    public int OutroFrames => EndWipeFrames + EndRiseFrames + EndHoldFrames + FadeFrames + BlackTailFrames;

    public static RendererSpec BuiltIn() => new();
}

public sealed class RendererSceneV3
{
    public required JsonElement Root { get; init; }
    public required Dictionary<string, byte[]> Assets { get; init; }
    public required List<RendererObjectV3> Objects { get; init; }
    public required List<RendererSelectorV3> Selectors { get; init; }
    public required List<string> Layers { get; init; }
    public required Dictionary<string, JsonElement> Resources { get; init; }
    public int Frames { get; init; }
}

public sealed class RendererObjectV3
{
    public string Id { get; init; } = "";
    public string Kind { get; init; } = "custom";
    public int Frame { get; init; }
    public string? Resource { get; init; }
    public int LifespanStart { get; init; }
    public int LifespanEnd { get; init; }
    public JsonElement Properties { get; init; }
    public JsonElement Raw { get; init; }
}

public sealed class RendererSelectorV3
{
    public string Kind { get; init; } = "custom";
    public string RawSelector { get; init; } = "";
    public List<RendererConditionV3> Conditions { get; init; } = [];
    public int Specificity { get; init; }
    public int SourceOrder { get; init; }
    public string Timeline { get; init; } = "relative";
    public JsonElement Properties { get; init; }
}

public sealed record RendererConditionV3(string Key, string Op, object Value);
public sealed record RendererValidationReport(IReadOnlyList<string> Errors, IReadOnlyList<string> Warnings)
{
    public bool Compatible => Errors.Count == 0;
}
public sealed record RendererCandidate(byte[] Bytes, RendererSpec Spec, string Sha256, RendererValidationReport Report);

public static class JsonElementExtensions
{
    public static string String(this JsonElement element, string name, string fallback = "")
    {
        if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value))
            return value.ValueKind == JsonValueKind.String ? value.GetString() ?? fallback : value.ToString();
        return fallback;
    }
    public static int Int(this JsonElement element, string name, int fallback = 0)
    {
        if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value))
        {
            if (value.TryGetInt32(out var i)) return i;
            if (int.TryParse(value.ToString(), NumberStyles.Integer, CultureInfo.InvariantCulture, out i)) return i;
        }
        return fallback;
    }
    public static double Double(this JsonElement element, string name, double fallback = 0)
    {
        if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value))
        {
            if (value.TryGetDouble(out var d)) return d;
            if (double.TryParse(value.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out d)) return d;
        }
        return fallback;
    }
    public static bool Bool(this JsonElement element, string name, bool fallback = false)
    {
        if (element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value))
        {
            if (value.ValueKind == JsonValueKind.True) return true;
            if (value.ValueKind == JsonValueKind.False) return false;
            if (bool.TryParse(value.ToString(), out var b)) return b;
        }
        return fallback;
    }
}
