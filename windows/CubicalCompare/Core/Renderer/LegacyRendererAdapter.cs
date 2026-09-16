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
        RendererV3PackageCompatibility.Normalize(spec);
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
    public double SlotPitch => _spec.SlotPitch;
    public double BodyInset => _spec.BodyInset;
    public double BodyWidth => _spec.BodyWidth;
    public double ImageHeight => _spec.ImageHeight;
    public RendererEmbeddedAudio? EmbeddedAudio => RendererV3PackageCompatibility.EmbeddedAudio(_spec);

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

    /// <summary>
    /// Returns a representative timeline frame for a project card. The editor uses this when the
    /// user changes card selection so a loaded renderer does not leave the preview parked on the
    /// previous card's artwork and make distinct imported images appear duplicated.
    /// </summary>
    public int PreviewFrameForCard(ComparisonProject project, int cardIndex)
    {
        ArgumentNullException.ThrowIfNull(project);
        if (project.Cards.Count == 0) return 0;

        cardIndex = Math.Clamp(cardIndex, 0, project.Cards.Count - 1);
        var frameCount = Math.Max(1, FrameCount(project));
        var lastFrame = frameCount - 1;
        int frame;

        if (_spec.Engine == "ribbon-exact")
        {
            if (cardIndex < 4 && cardIndex < _spec.OpeningStarts.Count)
            {
                var start = Math.Max(0, _spec.OpeningStarts[cardIndex]);
                var end = cardIndex < _spec.OpeningEnds.Count
                    ? Math.Max(start, _spec.OpeningEnds[cardIndex] - 1)
                    : start + Math.Max(1, _spec.BodySlideFrames);
                frame = Math.Min(end, start + Math.Max(1, _spec.BodySlideFrames));
            }
            else
            {
                var step = Math.Max(1, _spec.ContinuousStepFrames);
                var segment = Math.Max(0, cardIndex - 4);
                frame = _spec.ContinuousStartFrame + segment * step + Math.Max(1, step / 2);
            }
        }
        else if (_spec.Engine == "native-standard")
        {
            var step = Math.Max(1, _spec.ContinuousStepFrames);
            frame = cardIndex * step + Math.Max(1, step / 2);
        }
        else
        {
            frame = project.Cards.Count <= 1
                ? 0
                : (int)Math.Round(cardIndex * (double)lastFrame / (project.Cards.Count - 1));
        }

        return Math.Clamp(frame, 0, lastFrame);
    }

    /// <summary>
    /// Returns the exact card slot X positions used by the legacy renderer for the requested frame.
    /// The main-preview editor uses this instead of trying to reverse-engineer visible card positions.
    /// </summary>
    public IReadOnlyDictionary<int, double> VisibleCardSlotXs(ComparisonProject project, int frame)
    {
        ArgumentNullException.ThrowIfNull(project);
        frame = Math.Max(0, frame);
        var count = project.Cards.Count;
        var result = new Dictionary<int, double>();
        if (count == 0) return result;

        if (_spec.Engine == "ribbon-exact")
        {
            var contentEnd = Math.Max(_spec.ContinuousStartFrame, FrameCount(project) - _spec.OutroFrames);
            if (frame >= contentEnd) return result;

            if (frame >= _spec.ContinuousStartFrame && count > 4)
            {
                var segment = (frame - _spec.ContinuousStartFrame) / 512;
                var exact = Motion($"ribbon.scroll.{segment}", frame);
                var scroll = exact ?? ((frame - _spec.ContinuousStartFrame) / (double)Math.Max(1, _spec.ContinuousStepFrames) * _spec.SlotPitch);
                var first = Math.Max(0, (int)(scroll / _spec.SlotPitch) - 1);
                var last = Math.Min(count - 1, (int)((scroll + _spec.ReferenceWidth) / _spec.SlotPitch) + 1);
                for (var i = first; i <= last; i++)
                {
                    var x = i * _spec.SlotPitch - scroll;
                    if (x > -_spec.SlotPitch && x < _spec.ReferenceWidth + _spec.SlotPitch)
                        result[i] = x;
                }
                return result;
            }

            var active = -1;
            for (var i = 0; i < Math.Min(4, count); i++)
                if (frame >= CardStart(i)) active = i;
            if (active < 0) return result;

            for (var i = 0; i < active; i++)
                result[i] = i * _spec.SlotPitch;

            var local = frame - CardStart(active);
            var exactX = Motion($"ribbon.open.{active}.card.x", local);
            var progress = BodyProgress(local);
            result[active] = exactX ?? (active == 0
                ? Lerp(-_spec.SlotPitch, 0, progress)
                : Lerp((active - 1) * _spec.SlotPitch, active * _spec.SlotPitch, progress));
            return result;
        }

        if (_spec.Engine == "native-standard")
        {
            var step = Math.Max(1, _spec.ContinuousStepFrames);
            var scroll = frame / (double)step * _spec.SlotPitch;
            for (var i = 0; i < count; i++)
            {
                var x = i * _spec.SlotPitch - scroll;
                if (x > -_spec.SlotPitch && x < _spec.ReferenceWidth + _spec.SlotPitch)
                    result[i] = x;
            }
            return result;
        }

        return result;
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

    private int CardStart(int index)
    {
        if (index < _spec.OpeningStarts.Count) return _spec.OpeningStarts[index];
        return _spec.ContinuousStartFrame + Math.Max(0, index - 4) * _spec.ContinuousStepFrames;
    }

    private double BodyProgress(int local)
    {
        var exact = Motion("ribbon.body.progress", local);
        if (exact is not null) return Math.Clamp(exact.Value, 0, 1);
        var p = Math.Clamp(local / (double)Math.Max(1, _spec.BodySlideFrames), 0, 1);
        return p * p * (3 - 2 * p);
    }

    private double? Motion(string target, int frame)
    {
        var center = _spec.Track(target, frame);
        if (center is null) return null;
        if (_spec.PrecisionMode == "frame-exact") return center.Value;
        var previous = _spec.Track(target, frame - 1) ?? center;
        var next = _spec.Track(target, frame + 1) ?? center;
        return previous.Value * 0.20 + center.Value * 0.60 + next.Value * 0.20;
    }

    private static double Lerp(double a, double b, double p) => a + (b - a) * p;

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
