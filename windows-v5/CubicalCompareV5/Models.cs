using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json.Serialization;

namespace CubicalCompareV5;

public sealed class ArtworkTransformState
{
    // Stored in 960x540 design-stage units; shared by the editor and PNG/MP4 rendering.
    public double Scale { get; set; } = 1.0;
    public double X { get; set; }
    public double Y { get; set; }
    public double Rotation { get; set; }
    public bool FlipHorizontal { get; set; }
    public bool FlipVertical { get; set; }

    public ArtworkTransformState Clone() => new()
    {
        Scale = Scale, X = X, Y = Y, Rotation = Rotation,
        FlipHorizontal = FlipHorizontal, FlipVertical = FlipVertical
    };
}

public sealed class ComparisonCard : INotifyPropertyChanged
{
    private string _title = "New comparison";
    private string _badge = "0";
    private string _badgeHeader = "Value";
    private string _badgeUnit = "";
    private string _description = "";
    private string _badgeColor = "#EC394D";
    private string _artworkBase64 = "";
    private string _strokesBase64 = "";
    private string _thumbnailArtworkBase64 = "";
    private string _thumbnailStrokesBase64 = "";

    public string Title { get => _title; set => Set(ref _title, value); }
    public string Badge { get => _badge; set => Set(ref _badge, value); }
    public string BadgeHeader { get => _badgeHeader; set => Set(ref _badgeHeader, value); }
    public string BadgeUnit { get => _badgeUnit; set => Set(ref _badgeUnit, value); }
    public string Description { get => _description; set => Set(ref _description, value); }
    public string BadgeColor { get => _badgeColor; set => Set(ref _badgeColor, value); }
    public string ArtworkBase64 { get => _artworkBase64; set => Set(ref _artworkBase64, value); }
    public string StrokesBase64 { get => _strokesBase64; set => Set(ref _strokesBase64, value); }
    // Empty thumbnail artwork means reusing the video card's image, not a blank thumbnail.
    public string ThumbnailArtworkBase64 { get => _thumbnailArtworkBase64; set => Set(ref _thumbnailArtworkBase64, value); }
    public string ThumbnailStrokesBase64 { get => _thumbnailStrokesBase64; set => Set(ref _thumbnailStrokesBase64, value); }
    public ArtworkTransformState ImageTransform { get; set; } = new();
    public ArtworkTransformState ThumbnailTransform { get; set; } = new();

    public event PropertyChangedEventHandler? PropertyChanged;
    private void Set(ref string field, string value, [CallerMemberName] string? property = null)
    {
        if (field == value) return;
        field = value ?? "";
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(property));
    }
}

public sealed class ComparisonProject
{
    public int FormatVersion { get; set; } = 5;
    public ObservableCollection<ComparisonCard> Cards { get; set; } = new();
}

public sealed class AppSettings
{
    public string Theme { get; set; } = "System";
    public string Language { get; set; } = "en";
    public bool SoftwareRendering { get; set; }
}