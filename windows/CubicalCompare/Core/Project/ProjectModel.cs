namespace CubicalCompare.Core.Project;

public sealed class ComparisonProject
{
    public string Name { get; set; } = "Untitled comparison";
    public int Width { get; set; } = 1920;
    public int Height { get; set; } = 1080;
    public int Fps { get; set; } = 60;
    public bool ShowBadges { get; set; } = true;
    public bool CreditsEnabled { get; set; } = true;
    public bool AutoLength { get; set; } = true;
    public double CustomLengthSeconds { get; set; } = 90.0;
    public string RenderFontFamily { get; set; } = "Nexa";
    public string RenderFontFile { get; set; } = "";
    public string SoundtrackPath { get; set; } = "";
    public double SoundtrackVolume { get; set; } = 1.0;
    public bool SoundtrackLoop { get; set; } = true;

    // Keep this settable so projects can be safely round-tripped by System.Text.Json
    // for workspace recovery, file persistence and future interchange formats.
    public List<ComparisonCard> Cards { get; set; } = [];
}

public sealed class ComparisonCard
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Title { get; set; } = "Untitled";
    public string Value { get; set; } = "";
    public string BadgeHeader { get; set; } = "";
    public string Description { get; set; } = "";
    public string ImagePath { get; set; } = "";
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
