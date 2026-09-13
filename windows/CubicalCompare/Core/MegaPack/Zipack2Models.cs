using System.Text.Json.Serialization;

namespace CubicalCompare.Core.MegaPack;

public sealed class Zipack2Manifest
{
    [JsonPropertyName("format")]
    public string Format { get; init; } = "cubical.megapack.zipack2";

    [JsonPropertyName("version")]
    public int Version { get; init; } = 2;

    [JsonPropertyName("name")]
    public string Name { get; init; } = "MegaPack";

    [JsonPropertyName("contactSheets")]
    public List<Zipack2ContactSheetDefinition> ContactSheets { get; init; } = [];

    [JsonPropertyName("cards")]
    public List<Zipack2CardDefinition> Cards { get; init; } = [];

    [JsonPropertyName("show_badges")]
    public bool ShowBadges { get; init; } = true;

    [JsonPropertyName("credits_enabled")]
    public bool CreditsEnabled { get; init; } = true;

    [JsonPropertyName("duration_seconds")]
    public double DurationSeconds { get; init; }
}

public sealed class Zipack2CardDefinition
{
    [JsonPropertyName("id")]
    public string Id { get; init; } = "";

    [JsonPropertyName("title")]
    public string Title { get; init; } = "";

    [JsonPropertyName("name")]
    public string Name { get; init; } = "";

    [JsonPropertyName("value")]
    public string Value { get; init; } = "";

    [JsonPropertyName("badge_header")]
    public string BadgeHeader { get; init; } = "";

    [JsonPropertyName("badgeHeader")]
    public string BadgeHeaderCamel { get; init; } = "";

    [JsonPropertyName("badge_primary")]
    public string BadgePrimary { get; init; } = "";

    [JsonPropertyName("badge_secondary")]
    public string BadgeSecondary { get; init; } = "";

    [JsonPropertyName("description")]
    public string Description { get; init; } = "";

    [JsonPropertyName("details")]
    public string Details { get; init; } = "";

    [JsonPropertyName("image_x")]
    public double ImageX { get; init; }

    [JsonPropertyName("image_y")]
    public double ImageY { get; init; }

    [JsonPropertyName("image_scale")]
    public double ImageScale { get; init; } = 1.0;

    [JsonPropertyName("image_rotation")]
    public double ImageRotation { get; init; }

    [JsonPropertyName("image_crop_left")]
    public double ImageCropLeft { get; init; }

    [JsonPropertyName("image_crop_top")]
    public double ImageCropTop { get; init; }

    [JsonPropertyName("image_crop_right")]
    public double ImageCropRight { get; init; }

    [JsonPropertyName("image_crop_bottom")]
    public double ImageCropBottom { get; init; }

    [JsonPropertyName("image_layer")]
    public string ImageLayer { get; init; } = "behind";

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
    public string Id { get; init; } = "";
    public string Title { get; init; } = "";
    public string Value { get; init; } = "";
    public string BadgeHeader { get; init; } = "";
    public string Description { get; init; } = "";
    public double ImageX { get; init; }
    public double ImageY { get; init; }
    public double ImageScale { get; init; } = 1.0;
    public double ImageRotation { get; init; }
    public double ImageCropLeft { get; init; }
    public double ImageCropTop { get; init; }
    public double ImageCropRight { get; init; }
    public double ImageCropBottom { get; init; }
    public string ImageLayer { get; init; } = "behind";
}

public sealed class Zipack2ContactSheetDefinition
{
    [JsonPropertyName("path")]
    public string Path { get; init; } = "";

    [JsonPropertyName("order")]
    public int Order { get; init; }

    [JsonPropertyName("expectedCardWidth")]
    public int? ExpectedCardWidth { get; init; }

    [JsonPropertyName("expectedCardHeight")]
    public int? ExpectedCardHeight { get; init; }

    [JsonPropertyName("separator")]
    public Zipack2SeparatorDefinition Separator { get; init; } = new();

    [JsonPropertyName("regions")]
    public List<Zipack2RegionDefinition> Regions { get; init; } = [];
}

public sealed class Zipack2SeparatorDefinition
{
    [JsonPropertyName("red")]
    public byte Red { get; init; } = 255;

    [JsonPropertyName("green")]
    public byte Green { get; init; } = 255;

    [JsonPropertyName("blue")]
    public byte Blue { get; init; } = 0;

    [JsonPropertyName("tolerance")]
    public int Tolerance { get; init; } = 20;

    [JsonPropertyName("minimumCoverage")]
    public double MinimumCoverage { get; init; } = 0.72;

    [JsonPropertyName("minimumCardWidth")]
    public int MinimumCardWidth { get; init; } = 48;

    [JsonPropertyName("minimumCardHeight")]
    public int MinimumCardHeight { get; init; } = 48;
}

public sealed class Zipack2RegionDefinition
{
    [JsonPropertyName("x")]
    public int X { get; init; }

    [JsonPropertyName("y")]
    public int Y { get; init; }

    [JsonPropertyName("width")]
    public int Width { get; init; }

    [JsonPropertyName("height")]
    public int Height { get; init; }

    [JsonPropertyName("order")]
    public int Order { get; init; }
}

public readonly record struct PixelRect(int X, int Y, int Width, int Height)
{
    public int Right => X + Width;
    public int Bottom => Y + Height;
}

public sealed class DetectedZipack2Card
{
    public required string SheetPath { get; init; }
    public required int SheetOrder { get; init; }
    public required int LocalIndex { get; init; }
    public required int GlobalIndex { get; set; }
    public required PixelRect Bounds { get; init; }
    public required string ExtractedPath { get; init; }
    public required double Confidence { get; init; }
    public Zipack2CardData? Data { get; set; }
}

public sealed class Zipack2SheetResult
{
    public required string Path { get; init; }
    public required int Order { get; init; }
    public required int Width { get; init; }
    public required int Height { get; init; }
    public required IReadOnlyList<DetectedZipack2Card> Cards { get; init; }
}

public sealed class Zipack2ImportResult
{
    public required string Name { get; init; }
    public required string SourcePath { get; init; }
    public required string ExtractionDirectory { get; init; }
    public required IReadOnlyList<Zipack2SheetResult> Sheets { get; init; }
    public required IReadOnlyList<DetectedZipack2Card> Cards { get; init; }
    public required bool ShowBadges { get; init; }
    public required bool CreditsEnabled { get; init; }
    public required double DurationSeconds { get; init; }
}
