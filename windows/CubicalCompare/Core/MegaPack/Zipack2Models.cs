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
}
