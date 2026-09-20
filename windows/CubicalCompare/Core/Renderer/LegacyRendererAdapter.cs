using CubicalCompare.Core.Project;
using SkiaSharp;
using Legacy = CubicalCompare.Windows;

namespace CubicalCompare.Core.Renderer;

public readonly record struct PreviewCardGeometry(
    double SlotX,
    double ArtworkX,
    double ArtworkY,
    double ArtworkWidth,
    double ArtworkHeight,
    bool ArtworkCover,
    double TitleX,
    double TitleY,
    double TitleWidth,
    double TitleHeight,
    double DescriptionX,
    double DescriptionY,
    double DescriptionWidth,
    double DescriptionHeight,
    double BadgeX,
    double BadgeY,
    double BadgeWidth,
    double BadgeHeight);

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
    public double TitleHeight => _spec.TitleHeight;
    public double DescriptionTop => _spec.DescriptionTop;
    public double TitleTextSize => _spec.TitleTextSize;
    public double DescriptionTextSize => _spec.DescriptionTextSize;
    public double BadgeCenterX => _spec.BadgeCenterX;
    public double BadgeCenterY => _spec.BadgeCenterY;
    public double BadgeScale => _spec.BadgeScale;
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
        var result = new Dictionary<int, double>();
        for (var index = 0; index < project.Cards.Count; index++)
            if (TryGetPreviewCardGeometry(project, frame, index, out var geometry))
                result[index] = geometry.SlotX;
        return result;
    }

    /// <summary>
    /// Returns the editor geometry actually used by each supported renderer engine.
    /// This is the single source of truth for direct preview hit-testing and image transforms.
    /// </summary>
    public bool TryGetPreviewCardGeometry(
        ComparisonProject project,
        int frame,
        int cardIndex,
        out PreviewCardGeometry geometry)
    {
        ArgumentNullException.ThrowIfNull(project);
        geometry = default;
        if (cardIndex < 0 || cardIndex >= project.Cards.Count)
            return false;

        frame = Math.Max(0, frame);
        var card = project.Cards[cardIndex];

        if (_spec.Engine == "infinite-timeline-exact")
        {
            const double openingPitch = 480;
            const double conveyorPitch = 483;
            const double cardWidth = 474;
            const double titleTop = 471;
            const double titleBottom = 594;
            const double artTop = 732;
            const double scrollPerFrame = 3.2065854;
            const double fastScrollPerFrame = 24;
            const int fastScrollStart = 5265;
            int[] defaultOpening = [187, 261, 329, 398];

            double slotX;
            if (frame < _spec.ContinuousStartFrame)
            {
                if (cardIndex >= 4) return false;
                var startFrame = cardIndex < _spec.OpeningStarts.Count
                    ? _spec.OpeningStarts[cardIndex]
                    : defaultOpening[cardIndex];
                if (frame < startFrame) return false;
                slotX = cardIndex * openingPitch;
            }
            else
            {
                var tracked = _spec.Track("infinite.scroll", frame);
                double scroll;
                if (tracked is not null)
                {
                    scroll = tracked.Value;
                }
                else
                {
                    var normal = Math.Max(0, frame - _spec.ContinuousStartFrame) * scrollPerFrame;
                    if (frame <= fastScrollStart)
                    {
                        scroll = normal;
                    }
                    else
                    {
                        var atFast = Math.Max(0, fastScrollStart - _spec.ContinuousStartFrame) * scrollPerFrame;
                        scroll = atFast + (frame - fastScrollStart) * fastScrollPerFrame;
                    }
                }

                slotX = cardIndex * conveyorPitch - scroll;
                if (slotX >= _spec.ReferenceWidth || slotX + conveyorPitch <= 0)
                    return false;
            }

            geometry = new PreviewCardGeometry(
                SlotX: slotX,
                ArtworkX: slotX + 9,
                ArtworkY: artTop + 3,
                ArtworkWidth: cardWidth - 18,
                ArtworkHeight: _spec.ReferenceHeight - artTop - 6,
                ArtworkCover: false,
                TitleX: slotX,
                TitleY: titleTop,
                TitleWidth: cardWidth,
                TitleHeight: titleBottom - titleTop,
                DescriptionX: slotX,
                DescriptionY: titleBottom,
                DescriptionWidth: cardWidth,
                DescriptionHeight: artTop - titleBottom,
                BadgeX: slotX + 18,
                BadgeY: 12,
                BadgeWidth: 441,
                BadgeHeight: 429);
            return true;
        }

        if (_spec.Engine == "relationships-exact")
        {
            double slotX;
            if (frame < _spec.ContinuousStartFrame)
            {
                var startFrame = cardIndex < _spec.OpeningStarts.Count
                    ? _spec.OpeningStarts[cardIndex]
                    : 384 + cardIndex * 140;
                if (cardIndex >= 4 || frame < startFrame) return false;
                slotX = cardIndex * _spec.SlotPitch;
            }
            else
            {
                var segment = (frame - _spec.ContinuousStartFrame) / 4096;
                var scroll = _spec.Track($"relationships.scroll.{segment}", frame)
                    ?? ((frame - _spec.ContinuousStartFrame) * 2f);
                slotX = cardIndex * _spec.SlotPitch - scroll.Value;
                if (slotX <= -_spec.SlotPitch || slotX >= _spec.ReferenceWidth + _spec.SlotPitch)
                    return false;
            }

            var bodyLeft = slotX + _spec.BodyInset;
            var titleHeight = string.IsNullOrWhiteSpace(card.Title) ? 0 : _spec.TitleHeight;
            var descriptionTop = _spec.ImageHeight + titleHeight;
            geometry = new PreviewCardGeometry(
                SlotX: slotX,
                ArtworkX: bodyLeft,
                ArtworkY: 0,
                ArtworkWidth: _spec.BodyWidth,
                ArtworkHeight: _spec.ImageHeight,
                ArtworkCover: true,
                TitleX: bodyLeft,
                TitleY: _spec.ImageHeight,
                TitleWidth: _spec.BodyWidth,
                TitleHeight: titleHeight,
                DescriptionX: bodyLeft,
                DescriptionY: descriptionTop,
                DescriptionWidth: _spec.BodyWidth,
                DescriptionHeight: Math.Max(0, _spec.ReferenceHeight - descriptionTop),
                BadgeX: slotX + _spec.BadgeCenterX - 184 * _spec.BadgeScale,
                BadgeY: _spec.BadgeCenterY - 177 * _spec.BadgeScale,
                BadgeWidth: 368 * _spec.BadgeScale,
                BadgeHeight: 354 * _spec.BadgeScale);
            return true;
        }

        if (_spec.Engine == "scene-v3" && TryGetScenePreviewCardGeometry(project, frame, cardIndex, out geometry))
            return true;

        if (_spec.Engine == "ribbon-exact")
        {
            var positions = RibbonPreviewPositions(project, frame);
            if (!positions.TryGetValue(cardIndex, out var slotX))
                return false;
            geometry = StandardPreviewGeometry(card, slotX);
            return true;
        }

        if (_spec.Engine == "native-standard")
        {
            var step = Math.Max(1, _spec.ContinuousStepFrames);
            var scroll = frame / (double)step * _spec.SlotPitch;
            var slotX = cardIndex * _spec.SlotPitch - scroll;
            if (slotX <= -_spec.SlotPitch || slotX >= _spec.ReferenceWidth + _spec.SlotPitch)
                return false;
            geometry = StandardPreviewGeometry(card, slotX);
            return true;
        }

        // Safe fallback for legacy renderers that still use slot/body geometry.
        var fallbackStep = Math.Max(1, _spec.ContinuousStepFrames);
        var fallbackScroll = frame / (double)fallbackStep * _spec.SlotPitch;
        var fallbackX = cardIndex * _spec.SlotPitch - fallbackScroll;
        if (fallbackX <= -_spec.SlotPitch || fallbackX >= _spec.ReferenceWidth + _spec.SlotPitch)
            return false;
        geometry = StandardPreviewGeometry(card, fallbackX);
        return true;
    }

    private PreviewCardGeometry StandardPreviewGeometry(ComparisonCard card, double slotX)
    {
        var bodyLeft = slotX + _spec.BodyInset;
        var titleHeight = string.IsNullOrWhiteSpace(card.Title) ? 0 : _spec.TitleHeight;
        var descriptionTop = _spec.ImageHeight + titleHeight;
        var badgeSize = 380.0 * Math.Max(0.25, _spec.BadgeScale);
        return new PreviewCardGeometry(
            SlotX: slotX,
            ArtworkX: bodyLeft,
            ArtworkY: 0,
            ArtworkWidth: _spec.BodyWidth,
            ArtworkHeight: _spec.ImageHeight,
            ArtworkCover: true,
            TitleX: bodyLeft,
            TitleY: _spec.ImageHeight,
            TitleWidth: _spec.BodyWidth,
            TitleHeight: titleHeight,
            DescriptionX: bodyLeft,
            DescriptionY: descriptionTop,
            DescriptionWidth: _spec.BodyWidth,
            DescriptionHeight: Math.Max(0, _spec.ReferenceHeight - descriptionTop),
            BadgeX: slotX + _spec.BadgeCenterX - badgeSize / 2,
            BadgeY: _spec.BadgeCenterY - badgeSize / 2,
            BadgeWidth: badgeSize,
            BadgeHeight: badgeSize);
    }

    private Dictionary<int, double> RibbonPreviewPositions(ComparisonProject project, int frame)
    {
        var result = new Dictionary<int, double>();
        if (frame >= _spec.ContinuousStartFrame && project.Cards.Count > 4)
        {
            var segment = (frame - _spec.ContinuousStartFrame) / 512;
            var exact = Motion($"ribbon.scroll.{segment}", frame);
            var scroll = exact ?? ((frame - _spec.ContinuousStartFrame) / (double)Math.Max(1, _spec.ContinuousStepFrames) * _spec.SlotPitch);
            var first = Math.Max(0, (int)(scroll / _spec.SlotPitch) - 1);
            var last = Math.Min(project.Cards.Count - 1, (int)((scroll + _spec.ReferenceWidth) / _spec.SlotPitch) + 1);
            for (var i = first; i <= last; i++)
            {
                var x = i * _spec.SlotPitch - scroll;
                if (x > -_spec.SlotPitch && x < _spec.ReferenceWidth + _spec.SlotPitch)
                    result[i] = x;
            }
            return result;
        }

        var active = -1;
        for (var i = 0; i < Math.Min(4, project.Cards.Count); i++)
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

    private bool TryGetScenePreviewCardGeometry(
        ComparisonProject project,
        int frame,
        int cardIndex,
        out PreviewCardGeometry geometry)
    {
        geometry = default;
        var scene = _spec.SceneV3;
        if (scene is null) return false;

        foreach (var obj in scene.Objects)
        {
            if (frame < obj.LifespanStart || frame > obj.LifespanEnd)
                continue;
            var index = SceneCardIndex(obj);
            if (index != cardIndex || obj.Resource is null || !scene.Resources.TryGetValue(obj.Resource, out var resource))
                continue;

            var type = resource.String("type", obj.Kind).ToLowerInvariant();
            if (type != "relationships-card" && obj.Kind is not ("card" or "openingCard"))
                continue;

            var props = Legacy.V3Evaluator.Properties(scene, obj, frame);
            if (type == "relationships-card")
            {
                var pitch = resource.Double("slotPitch", 480);
                var width = resource.Double("width", 474);
                var height = resource.Double("height", _spec.ReferenceHeight);
                var imageHeight = resource.Double("imageHeight", 789);
                var titleHeight = resource.Double("titleHeight", 117);
                var dividerHeight = resource.Double("dividerHeight", 8);
                var scroll = SceneNumber(props, "scroll", 0);
                var baseX = SceneNumber(props, "baseX", cardIndex * pitch);
                var offsetX = SceneNumber(props, "offsetX", 0);
                var x = baseX - scroll + offsetX;
                if (x + width < 0 || x > _spec.ReferenceWidth)
                    continue;
                var descriptionTop = imageHeight + titleHeight + dividerHeight;
                geometry = new PreviewCardGeometry(
                    SlotX: x,
                    ArtworkX: x,
                    ArtworkY: 0,
                    ArtworkWidth: width,
                    ArtworkHeight: imageHeight,
                    ArtworkCover: true,
                    TitleX: x,
                    TitleY: imageHeight,
                    TitleWidth: width,
                    TitleHeight: titleHeight,
                    DescriptionX: x,
                    DescriptionY: descriptionTop,
                    DescriptionWidth: width,
                    DescriptionHeight: Math.Max(0, height - descriptionTop),
                    BadgeX: x + resource.Double("centerX", width / 2) - resource.Double("radiusX", 176),
                    BadgeY: resource.Double("centerY", 192) - resource.Double("radiusY", 172),
                    BadgeWidth: resource.Double("radiusX", 176) * 2,
                    BadgeHeight: resource.Double("radiusY", 172) * 2);
                return true;
            }

            var xValue = SceneNumber(props, "x", SceneNumber(props, "translateX", cardIndex * _spec.SlotPitch));
            geometry = StandardPreviewGeometry(project.Cards[cardIndex], xValue);
            return true;
        }

        return false;
    }

    private static int? SceneCardIndex(Legacy.RendererObjectV3 obj)
    {
        if (obj.Raw.ValueKind == System.Text.Json.JsonValueKind.Object)
        {
            if (obj.Raw.TryGetProperty("cardIndex", out var c) && c.TryGetInt32(out var ci)) return ci;
            if (obj.Raw.TryGetProperty("dataIndex", out var d) && d.TryGetInt32(out var di)) return di;
        }
        var at = obj.Id.LastIndexOf('@');
        return at >= 0 && int.TryParse(obj.Id[(at + 1)..], out var parsed) ? parsed : null;
    }

    private static double SceneNumber(Dictionary<string, object?> props, string key, double fallback)
    {
        if (!props.TryGetValue(key, out var value) || value is null) return fallback;
        if (value is System.Text.Json.JsonElement element)
        {
            if (element.ValueKind == System.Text.Json.JsonValueKind.Number && element.TryGetDouble(out var parsed)) return parsed;
            return double.TryParse(element.ToString(), out parsed) ? parsed : fallback;
        }
        try { return Convert.ToDouble(value); }
        catch { return fallback; }
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
