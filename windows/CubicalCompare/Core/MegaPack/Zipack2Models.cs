using System.Text.Json.Serialization;

namespace CubicalCompare.Core.MegaPack;

public sealed class Zipack2Manifest
{
    [JsonPropertyName("format")]
    public string Format { get; set; } = "cubical.megapack.zipack2";

    [JsonPropertyName("version")]
    public int Version { get; set; } = 2;

    [JsonPropertyName("name")]
    public string Name { get; set; } = "MegaPack";

    [JsonPropertyName("thumbnail")]
    public string Thumbnail { get; set; } = "";

    [JsonPropertyName("contactSheets")]
    public List<Zipack2ContactSheetDefinition> ContactSheets { get; set; } = [];

    [JsonPropertyName("cards")]
    public List<Zipack2CardDefinition> Cards { get; set; } = [];

    [JsonPropertyName("show_badges")]
    public bool ShowBadges { get; set; } = true;

    [JsonPropertyName("credits_enabled")]
    public bool CreditsEnabled { get; set; } = true;

    [JsonPropertyName("duration_seconds")]
    public double DurationSeconds { get; set; }
}

public sealed class Zipack2CardDefinition
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("title")]
    public string Title { get; set; } = "";

    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("value")]
    public string Value { get; set; } = "";

    [JsonPropertyName("badge_header")]
    public string BadgeHeader { get; set; } = "";

    [JsonPropertyName("badgeHeader")]
    public string BadgeHeaderCamel { get; set; } = "";

    [JsonPropertyName("badge_primary")]
    public string BadgePrimary { get; set; } = "";

    [JsonPropertyName("badge_secondary")]
    public string BadgeSecondary { get; set; } = "";

    [JsonPropertyName("description")]
    public string Description { get; set; } = "";

    [JsonPropertyName("details")]
    public string Details { get; set; } = "";

    [JsonPropertyName("image_x")]
    public double ImageX { get; set; }

    [JsonPropertyName("image_y")]
    public double ImageY { get; set; }

    [JsonPropertyName("image_scale")]
    public double ImageScale { get; set; } = 1.0;

    [JsonPropertyName("image_rotation")]
    public double ImageRotation { get; set; }

    [JsonPropertyName("image_crop_left")]
    public double ImageCropLeft { get; set; }

    [JsonPropertyName("image_crop_top")]
    public double ImageCropTop { get; set; }

    [JsonPropertyName("image_crop_right")]
    public double ImageCropRight { get; set; }

    [JsonPropertyName("image_crop_bottom")]
    public double ImageCropBottom { get; set; }

    [JsonPropertyName("image_layer")]
    public string ImageLayer { get; set; } = "behind";

    public Zipack2CardData Normalize(int index)
    {
        var primary = !string.IsNullOrWhiteSpace(Value) ? Value.Trim() : BadgePrimary.Trim();
        var value = string.Join(' ', new[] { primary, BadgeSecondary.Trim() }.Where(x => x.Length > 0));
        return new Zipack2CardData
        {
            Id = string.IsNullOrWhiteSpace(Id) ? $"zipack2-{index + 1:D4}" : Id.Trim(),
            Title = First(Title, Name),
            Value = value,
            BadgeHeader = First(BadgeHeader, BadgeHeaderCamel),
            Description = First(Description, Details),
            ImageX = Finite(ImageX, 0, -4000, 4000),
            ImageY = Finite(ImageY, 0, -4000, 4000),
            ImageScale = Finite(ImageScale, 1, .05, 12),
            ImageRotation = Finite(ImageRotation, 0, -360, 360),
            ImageCropLeft = Finite(ImageCropLeft, 0, 0, .95),
            ImageCropTop = Finite(ImageCropTop, 0, 0, .95),
            ImageCropRight = Finite(ImageCropRight, 0, 0, .95),
            ImageCropBottom = Finite(ImageCropBottom, 0, 0, .95),
            ImageLayer = ImageLayer.Equals("front", StringComparison.OrdinalIgnoreCase) ? "front" : "behind",
        };
    }

    private static string First(params string[] values) => values.FirstOrDefault(x => !string.IsNullOrWhiteSpace(x))?.Trim() ?? "";
    private static double Finite(double value, double fallback, double min, double max) => double.IsFinite(value) ? Math.Clamp(value, min, max) : fallback;
}

public sealed class Zipack2CardData
{
    public string Id { get; set; } = "";
    public string Title { get; set; } = "";
    public string Value { get; set; } = "";
    public string BadgeHeader { get; set; } = "";
    public string Description { get; set; } = "";
    public double ImageX { get; set; }
    public double ImageY { get; set; }
    public double ImageScale { get; set; } = 1.0;
    public double ImageRotation { get; set; }
    public double ImageCropLeft { get; set; }
    public double ImageCropTop { get; set; }
    public double ImageCropRight { get; set; }
    public double ImageCropBottom { get; set; }
    public string ImageLayer { get; set; } = "behind";
}

public sealed class Zipack2ContactSheetDefinition
{
    [JsonPropertyName("path")]
    public string Path { get; set; } = "";

    [JsonPropertyName("order")]
    public int Order { get; set; }

    [JsonPropertyName("expectedCardWidth")]
    public int? ExpectedCardWidth { get; set; }

    [JsonPropertyName("expectedCardHeight")]
    public int? ExpectedCardHeight { get; set; }

    [JsonPropertyName("separator")]
    public Zipack2SeparatorDefinition Separator { get; set; } = new();

    [JsonPropertyName("regions")]
    public List<Zipack2RegionDefinition> Regions { get; set; } = [];
}

public sealed class Zipack2SeparatorDefinition
{
    [JsonPropertyName("red")]
    public byte Red { get; set; } = 255;

    [JsonPropertyName("green")]
    public byte Green { get; set; } = 255;

    [JsonPropertyName("blue")]
    public byte Blue { get; set; } = 0;

    [JsonPropertyName("tolerance")]
    public int Tolerance { get; set; } = 20;

    [JsonPropertyName("minimumCoverage")]
    public double MinimumCoverage { get; set; } = 0.72;

    [JsonPropertyName("minimumCardWidth")]
    public int MinimumCardWidth { get; set; } = 48;

    [JsonPropertyName("minimumCardHeight")]
    public int MinimumCardHeight { get; set; } = 48;
}

public sealed class Zipack2RegionDefinition
{
    [JsonPropertyName("x")]
    public int X { get; set; }

    [JsonPropertyName("y")]
    public int Y { get; set; }

    [JsonPropertyName("width")]
    public int Width { get; set; }

    [JsonPropertyName("height")]
    public int Height { get; set; }

    [JsonPropertyName("order")]
    public int Order { get; set; }
}

public readonly record struct PixelRect(int X, int Y, int Width, int Height)
{
    public int Right => X + Width;
    public int Bottom => Y + Height;
}

public sealed class DetectedZipack2Card
{
    public string SheetPath { get; set; } = "";
    public int SheetOrder { get; set; }
    public int LocalIndex { get; set; }
    public int GlobalIndex { get; set; }
    public PixelRect Bounds { get; set; }
    public string ExtractedPath { get; set; } = "";
    public double Confidence { get; set; }
    public Zipack2CardData? Data { get; set; }
}

public sealed class Zipack2SheetResult
{
    public string Path { get; set; } = "";
    public int Order { get; set; }
    public int Width { get; set; }
    public int Height { get; set; }
    public IReadOnlyList<DetectedZipack2Card> Cards { get; set; } = [];
}

public sealed class Zipack2ImportResult
{
    public string Name { get; set; } = "MegaPack";
    public string SourcePath { get; set; } = "";
    public string ExtractionDirectory { get; set; } = "";
    public IReadOnlyList<Zipack2SheetResult> Sheets { get; set; } = [];
    public IReadOnlyList<DetectedZipack2Card> Cards { get; set; } = [];
    public bool ShowBadges { get; set; } = true;
    public bool CreditsEnabled { get; set; } = true;
    public double DurationSeconds { get; set; }
}
