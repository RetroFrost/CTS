using CubicalCompare.Core.Project;
using SkiaSharp;
using Legacy = CubicalCompare.Windows;

namespace CubicalCompare.Core.Renderer;

/// <summary>
/// Isolates Renderer API v2/v3 from the Cubical Compare 4 renderer architecture.
/// The implementation behind this adapter is the proven 3.0.301 Windows evaluator,
/// preserved without UI dependencies so older renderer packages keep their authored behaviour.
/// </summary>
public sealed class LegacyRendererAdapter : IDisposable
{
    private readonly Legacy.RendererEngine _engine = new();
    private readonly Legacy.RendererSpec _spec;

    private LegacyRendererAdapter(Legacy.RendererSpec spec, string sourcePath)
    {
        _spec = spec;
        SourcePath = sourcePath;
    }

    public string SourcePath { get; }
    public string Id => _spec.Id;
    public string Name => _spec.Name;
    public int Api => _spec.RendererApi;
    public string Engine => _spec.Engine;
    public int ReferenceWidth => _spec.ReferenceWidth;
    public int ReferenceHeight => _spec.ReferenceHeight;
    public int ReferenceFps => _spec.ReferenceFps;

    public static LegacyRendererAdapter Load(string path)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var candidate = Legacy.RendererBundleReader.Inspect(path);
        if (candidate.Spec.RendererApi is not (2 or 3))
            throw new InvalidDataException($"Renderer API {candidate.Spec.RendererApi} is not handled by the v2/v3 compatibility adapter.");
        if (!candidate.Report.Compatible)
            throw new InvalidDataException(string.Join(Environment.NewLine, candidate.Report.Errors));
        return new LegacyRendererAdapter(candidate.Spec, path);
    }

    public int FrameCount(ComparisonProject project)
    {
        ArgumentNullException.ThrowIfNull(project);
        return _engine.FrameCount(ToLegacyProject(project), _spec);
    }

    public SKBitmap Render(ComparisonProject project, int frame, int? width = null, int? height = null)
    {
        ArgumentNullException.ThrowIfNull(project);
        var outputWidth = Math.Max(2, width ?? project.Width);
        var outputHeight = Math.Max(2, height ?? project.Height);
        return _engine.Render(ToLegacyProject(project), _spec, Math.Max(0, frame), outputWidth, outputHeight);
    }

    public byte[] RenderPng(ComparisonProject project, int frame, int? width = null, int? height = null)
    {
        using var bitmap = Render(project, frame, width, height);
        using var image = SKImage.FromBitmap(bitmap);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100)
            ?? throw new InvalidOperationException("The compatibility renderer could not encode its output frame.");
        return data.ToArray();
    }

    private Legacy.StudioProject ToLegacyProject(ComparisonProject project)
    {
        var legacy = new Legacy.StudioProject
        {
            Name = project.Name,
            Width = project.Width,
            Height = project.Height,
            Fps = project.Fps,
            ShowBadges = project.ShowBadges,
            CreditsEnabled = project.CreditsEnabled,
            AutoLength = project.AutoLength,
            CustomLengthSeconds = project.CustomLengthSeconds,
            FontFamily = project.RenderFontFamily,
            FontFile = project.RenderFontFile,
            Cards = project.Cards.Select(card => new Legacy.StudioCard
            {
                Id = card.Id,
                Title = card.Title,
                Value = card.Value,
                BadgeHeader = card.BadgeHeader,
                Description = card.Description,
                Image = card.ImagePath,
                ImageX = card.ImageX,
                ImageY = card.ImageY,
                ImageScale = card.ImageScale,
                ImageRotation = card.ImageRotation,
                ImageCropLeft = card.ImageCropLeft,
                ImageCropTop = card.ImageCropTop,
                ImageCropRight = card.ImageCropRight,
                ImageCropBottom = card.ImageCropBottom,
                ImageLayer = card.ImageLayer,
            }).ToList(),
        };
        return legacy;
    }

    public void Dispose() => _engine.Dispose();
}
