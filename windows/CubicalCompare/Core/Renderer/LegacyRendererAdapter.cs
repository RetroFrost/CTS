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
    double ImageCoordinateScaleX,
    double ImageCoordinateScaleY,
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

public readonly record struct PreviewTextRegion(
    int CardIndex,
    string Field,
    double X,
    double Y,
    double Width,
    double Height,
    double Rotation,
    double FontSize,
    bool Bold,
    string HorizontalAlignment,
    string VerticalAlignment,
    uint Argb,
    bool Wrap);

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
                ImageCoordinateScaleX: 1,
                ImageCoordinateScaleY: 1,
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
                slotX = cardIndex * _spec.SlotPitch - scroll;
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
                ImageCoordinateScaleX: 1,
                ImageCoordinateScaleY: 1,
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
            if (TryGetRibbonSmartCardGeometry(project, frame, cardIndex, slotX, out geometry))
                return true;
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

    public IReadOnlyList<PreviewTextRegion> PreviewTextRegions(ComparisonProject project, int frame)
    {
        ArgumentNullException.ThrowIfNull(project);
        frame = Math.Max(0, frame);
        var result = new List<PreviewTextRegion>();

        for (var cardIndex = 0; cardIndex < project.Cards.Count; cardIndex++)
        {
            if (!TryGetPreviewCardGeometry(project, frame, cardIndex, out var geometry))
                continue;

            var card = project.Cards[cardIndex];
            var exact = new List<PreviewTextRegion>();
            if (_spec.Engine == "ribbon-exact")
            {
                TryGetRibbonSmartCardTextRegions(project, frame, cardIndex, geometry.SlotX, exact);
                TryGetRibbonSmartBadgeTextRegions(project, frame, cardIndex, geometry.SlotX, exact);
            }
            else if (_spec.Engine == "scene-v3")
            {
                TryGetSceneSmartTextRegions(project, frame, cardIndex, exact);
            }

            if (!exact.Any(region => region.Field == "title") && !string.IsNullOrWhiteSpace(card.Title) && geometry.TitleHeight > 0)
            {
                var centered = _spec.Engine is "relationships-exact";
                result.Add(new PreviewTextRegion(
                    cardIndex,
                    "title",
                    geometry.TitleX + (centered ? 10 : 12),
                    geometry.TitleY + (centered ? 1 : 4),
                    Math.Max(1, geometry.TitleWidth - (centered ? 20 : 24)),
                    Math.Max(1, geometry.TitleHeight - (centered ? 2 : 8)),
                    0,
                    Math.Max(12, _spec.TitleTextSize),
                    true,
                    centered ? "center" : "left",
                    centered ? "center" : "top",
                    _spec.TitleTextColor,
                    true));
            }

            if (!exact.Any(region => region.Field == "description") && !string.IsNullOrWhiteSpace(card.Description) && geometry.DescriptionHeight > 0)
            {
                var centered = _spec.Engine is "relationships-exact" or "infinite-timeline-exact";
                var inset = centered ? 11d : 17d;
                var topInset = centered ? 4d : 8d;
                result.Add(new PreviewTextRegion(
                    cardIndex,
                    "description",
                    geometry.DescriptionX + inset,
                    geometry.DescriptionY + topInset,
                    Math.Max(1, geometry.DescriptionWidth - inset * 2),
                    Math.Max(1, geometry.DescriptionHeight - topInset - 4),
                    0,
                    Math.Max(12, _spec.DescriptionTextSize),
                    false,
                    centered ? "center" : "left",
                    centered ? "center" : "top",
                    _spec.DescriptionTextColor,
                    true));
            }

            if (!exact.Any(region => region.Field == "badgeHeader") &&
                project.ShowBadges &&
                !string.IsNullOrWhiteSpace(card.BadgeHeader) &&
                geometry.BadgeWidth > 0 &&
                geometry.BadgeHeight > 0)
            {
                result.Add(new PreviewTextRegion(
                    cardIndex,
                    "badgeHeader",
                    geometry.BadgeX + geometry.BadgeWidth * .14,
                    geometry.BadgeY + geometry.BadgeHeight * .16,
                    geometry.BadgeWidth * .72,
                    geometry.BadgeHeight * .25,
                    0,
                    Math.Max(12, 30 * Math.Max(.25, _spec.BadgeScale)),
                    true,
                    "center",
                    "center",
                    _spec.BadgeTextColor,
                    false));
            }

            if (!exact.Any(region => region.Field == "value") &&
                project.ShowBadges &&
                !string.IsNullOrWhiteSpace(card.Value) &&
                geometry.BadgeWidth > 0 &&
                geometry.BadgeHeight > 0)
            {
                result.Add(new PreviewTextRegion(
                    cardIndex,
                    "value",
                    geometry.BadgeX + geometry.BadgeWidth * .12,
                    geometry.BadgeY + geometry.BadgeHeight * .35,
                    geometry.BadgeWidth * .76,
                    geometry.BadgeHeight * .44,
                    0,
                    Math.Max(16, 58 * Math.Max(.25, _spec.BadgeScale)),
                    true,
                    "center",
                    "center",
                    _spec.BadgeTextColor,
                    false));
            }

            result.AddRange(exact);
        }

        return result;
    }

    private bool TryGetRibbonSmartCardGeometry(
        ComparisonProject project,
        int frame,
        int cardIndex,
        double cardX,
        out PreviewCardGeometry geometry)
    {
        geometry = default;
        var scene = _spec.SceneV3;
        if (scene is null) return false;

        var obj = scene.Objects.FirstOrDefault(candidate =>
            SceneCardIndex(candidate) == cardIndex &&
            frame >= candidate.LifespanStart &&
            frame <= candidate.LifespanEnd &&
            candidate.Resource is not null &&
            scene.Resources.TryGetValue(candidate.Resource, out var candidateResource) &&
            JsonString(candidateResource, "type", "").Equals("smart-card-animation", StringComparison.OrdinalIgnoreCase));
        if (obj is null || obj.Resource is null || !scene.Resources.TryGetValue(obj.Resource, out var resource))
            return false;

        var props = Legacy.V3Evaluator.Properties(scene, obj, frame);
        var sequenceRoot = SceneString(props, "sequenceRoot", JsonString(resource, "sequenceRoot", ""));
        if (string.IsNullOrWhiteSpace(sequenceRoot))
            return false;

        Legacy.SmartBadgeSequenceDefinition sequence;
        try { sequence = Legacy.SmartBadgeSequence.Load(scene, sequenceRoot); }
        catch { return false; }

        var selected = SelectSmartSequenceFrame(sequence, props, resource, frame, obj.Frame, _spec.ReferenceFps);
        if (selected is null) return false;

        var drawX = SceneNumber(props, "drawX", JsonDouble(resource, "drawX", _spec.BodyInset));
        var drawY = SceneNumber(props, "drawY", JsonDouble(resource, "drawY", 0));
        var drawWidth = SceneNumber(props, "drawWidth", JsonDouble(resource, "drawWidth", sequence.Width));
        var drawHeight = SceneNumber(props, "drawHeight", JsonDouble(resource, "drawHeight", sequence.Height));
        if (drawWidth <= 0 || drawHeight <= 0) return false;

        var scaleX = drawWidth / Math.Max(1, sequence.Width);
        var scaleY = drawHeight / Math.Max(1, sequence.Height);
        var artwork = sequence.Artwork?.DestAt(selected.TemplateFrame);
        if (artwork is null) return false;

        var title = SmartFieldBounds(sequence, selected.TemplateFrame, "title");
        var description = SmartFieldBounds(sequence, selected.TemplateFrame, "description", "desc");
        var fallback = StandardPreviewGeometry(project.Cards[cardIndex], cardX);

        geometry = new PreviewCardGeometry(
            SlotX: cardX,
            ArtworkX: cardX + drawX + artwork.X * scaleX,
            ArtworkY: drawY + artwork.Y * scaleY,
            ArtworkWidth: artwork.Width * scaleX,
            ArtworkHeight: artwork.Height * scaleY,
            ArtworkCover: true,
            ImageCoordinateScaleX: scaleX,
            ImageCoordinateScaleY: scaleY,
            TitleX: title is null ? fallback.TitleX : cardX + drawX + title.X * scaleX,
            TitleY: title is null ? fallback.TitleY : drawY + title.Y * scaleY,
            TitleWidth: title is null ? fallback.TitleWidth : title.Width * scaleX,
            TitleHeight: title is null ? fallback.TitleHeight : title.Height * scaleY,
            DescriptionX: description is null ? fallback.DescriptionX : cardX + drawX + description.X * scaleX,
            DescriptionY: description is null ? fallback.DescriptionY : drawY + description.Y * scaleY,
            DescriptionWidth: description is null ? fallback.DescriptionWidth : description.Width * scaleX,
            DescriptionHeight: description is null ? fallback.DescriptionHeight : description.Height * scaleY,
            BadgeX: fallback.BadgeX,
            BadgeY: fallback.BadgeY,
            BadgeWidth: fallback.BadgeWidth,
            BadgeHeight: fallback.BadgeHeight);
        return true;
    }

    private void TryGetRibbonSmartCardTextRegions(
        ComparisonProject project,
        int frame,
        int cardIndex,
        double cardX,
        List<PreviewTextRegion> output)
    {
        var scene = _spec.SceneV3;
        if (scene is null) return;

        var obj = scene.Objects.FirstOrDefault(candidate =>
            SceneCardIndex(candidate) == cardIndex &&
            frame >= candidate.LifespanStart &&
            frame <= candidate.LifespanEnd &&
            candidate.Resource is not null &&
            scene.Resources.TryGetValue(candidate.Resource, out var candidateResource) &&
            JsonString(candidateResource, "type", "").Equals("smart-card-animation", StringComparison.OrdinalIgnoreCase));
        if (obj is null || obj.Resource is null || !scene.Resources.TryGetValue(obj.Resource, out var resource))
            return;

        var props = Legacy.V3Evaluator.Properties(scene, obj, frame);
        var sequenceRoot = SceneString(props, "sequenceRoot", JsonString(resource, "sequenceRoot", ""));
        if (string.IsNullOrWhiteSpace(sequenceRoot)) return;

        Legacy.SmartBadgeSequenceDefinition sequence;
        try { sequence = Legacy.SmartBadgeSequence.Load(scene, sequenceRoot); }
        catch { return; }

        var selected = SelectSmartSequenceFrame(sequence, props, resource, frame, obj.Frame, _spec.ReferenceFps);
        if (selected is null) return;

        var drawX = SceneNumber(props, "drawX", JsonDouble(resource, "drawX", _spec.BodyInset));
        var drawY = SceneNumber(props, "drawY", JsonDouble(resource, "drawY", 0));
        var drawWidth = SceneNumber(props, "drawWidth", JsonDouble(resource, "drawWidth", sequence.Width));
        var drawHeight = SceneNumber(props, "drawHeight", JsonDouble(resource, "drawHeight", sequence.Height));
        if (drawWidth <= 0 || drawHeight <= 0) return;

        AppendSmartSequenceTextRegions(
            project,
            cardIndex,
            sequence,
            selected.TemplateFrame,
            cardX + drawX,
            drawY,
            drawWidth / Math.Max(1, sequence.Width),
            drawHeight / Math.Max(1, sequence.Height),
            output);
    }

    private void TryGetRibbonSmartBadgeTextRegions(
        ComparisonProject project,
        int frame,
        int cardIndex,
        double cardX,
        List<PreviewTextRegion> output)
    {
        if (_spec.SmartBadgeV2Manifest is not System.Text.Json.JsonElement manifest ||
            manifest.ValueKind != System.Text.Json.JsonValueKind.Object)
            return;

        var packId = JsonString(manifest, "defaultPack", "");
        int? explicitStart = null;
        var sequenceOffset = 0;
        if (manifest.TryGetProperty("cards", out var cards) &&
            cards.ValueKind == System.Text.Json.JsonValueKind.Object &&
            cards.TryGetProperty(cardIndex.ToString(System.Globalization.CultureInfo.InvariantCulture), out var selection))
        {
            if (selection.ValueKind == System.Text.Json.JsonValueKind.String)
                packId = selection.GetString() ?? packId;
            else if (selection.ValueKind == System.Text.Json.JsonValueKind.Object)
            {
                packId = JsonString(selection, "pack", packId);
                if (selection.TryGetProperty("startFrame", out var start) && start.TryGetInt32(out var parsedStart))
                    explicitStart = parsedStart;
                sequenceOffset = JsonInt(selection, "sequenceOffset", 0);
            }
        }

        if (string.IsNullOrWhiteSpace(packId) ||
            !manifest.TryGetProperty("packs", out var packs) ||
            packs.ValueKind != System.Text.Json.JsonValueKind.Object ||
            !packs.TryGetProperty(packId, out var packSelection))
            return;

        string asset;
        double drawX = 0, drawY = 0, drawWidth = 0, drawHeight = 0;
        if (packSelection.ValueKind == System.Text.Json.JsonValueKind.String)
        {
            asset = packSelection.GetString() ?? "";
        }
        else if (packSelection.ValueKind == System.Text.Json.JsonValueKind.Object)
        {
            asset = JsonString(packSelection, "asset", "");
            drawX = JsonDouble(packSelection, "drawX", 0);
            drawY = JsonDouble(packSelection, "drawY", 0);
            drawWidth = JsonDouble(packSelection, "drawWidth", 0);
            drawHeight = JsonDouble(packSelection, "drawHeight", 0);
        }
        else return;

        if (string.IsNullOrWhiteSpace(asset)) return;

        Legacy.SmartBadgeSequenceDefinition sequence;
        try { sequence = Legacy.SmartBadgeSequence.LoadArchive(_spec, asset); }
        catch { return; }

        if (drawWidth <= 0) drawWidth = sequence.Width;
        if (drawHeight <= 0) drawHeight = sequence.Height;

        var sequenceFrame = frame - (explicitStart ?? CardStart(cardIndex)) + sequenceOffset;
        var selected = sequence.SelectFrame(sequenceFrame);
        if (selected is null) return;

        AppendSmartSequenceTextRegions(
            project,
            cardIndex,
            sequence,
            selected.TemplateFrame,
            cardX + drawX,
            drawY,
            drawWidth / Math.Max(1, sequence.Width),
            drawHeight / Math.Max(1, sequence.Height),
            output);
    }

    private void TryGetSceneSmartTextRegions(
        ComparisonProject project,
        int frame,
        int cardIndex,
        List<PreviewTextRegion> output)
    {
        var scene = _spec.SceneV3;
        if (scene is null) return;

        foreach (var obj in scene.Objects)
        {
            if (SceneCardIndex(obj) != cardIndex ||
                frame < obj.LifespanStart ||
                frame > obj.LifespanEnd ||
                obj.Resource is null ||
                !scene.Resources.TryGetValue(obj.Resource, out var resource))
                continue;

            var type = JsonString(resource, "type", obj.Kind).ToLowerInvariant();
            if (type is not ("smart-card-animation" or "smart-badge-animation"))
                continue;

            var props = Legacy.V3Evaluator.Properties(scene, obj, frame);
            var sequenceRoot = SceneString(props, "sequenceRoot", JsonString(resource, "sequenceRoot", ""));
            if (string.IsNullOrWhiteSpace(sequenceRoot)) continue;

            Legacy.SmartBadgeSequenceDefinition sequence;
            try { sequence = Legacy.SmartBadgeSequence.Load(scene, sequenceRoot); }
            catch { continue; }

            var selected = SelectSmartSequenceFrame(sequence, props, resource, frame, obj.Frame, _spec.ReferenceFps);
            if (selected is null) continue;

            var drawX = SceneNumber(props, "drawX", JsonDouble(resource, "drawX", 0));
            var drawY = SceneNumber(props, "drawY", JsonDouble(resource, "drawY", 0));
            var drawWidth = SceneNumber(props, "drawWidth", JsonDouble(resource, "drawWidth", sequence.Width));
            var drawHeight = SceneNumber(props, "drawHeight", JsonDouble(resource, "drawHeight", sequence.Height));
            if (drawWidth <= 0 || drawHeight <= 0) continue;

            if (type == "smart-card-animation")
            {
                var pitch = JsonDouble(resource, "slotPitch", 480);
                var scroll = SceneNumber(props, "scroll", 0);
                var baseX = SceneNumber(props, "baseX", cardIndex * pitch);
                var offsetX = SceneNumber(props, "offsetX", 0);
                var offsetY = SceneNumber(props, "offsetY", 0);
                drawX += baseX - scroll + offsetX;
                drawY += offsetY;
            }

            AppendSmartSequenceTextRegions(
                project,
                cardIndex,
                sequence,
                selected.TemplateFrame,
                drawX,
                drawY,
                drawWidth / Math.Max(1, sequence.Width),
                drawHeight / Math.Max(1, sequence.Height),
                output);
        }
    }

    private static Legacy.SmartBadgeFrameSelection? SelectSmartSequenceFrame(
        Legacy.SmartBadgeSequenceDefinition sequence,
        Dictionary<string, object?> props,
        System.Text.Json.JsonElement resource,
        int globalFrame,
        int anchorFrame,
        int referenceFps)
    {
        int sequenceFrame;
        if (props.TryGetValue("sequenceFrame", out var explicitFrame) && explicitFrame is not null)
        {
            sequenceFrame = (int)Math.Round(SceneObjectNumber(explicitFrame, 0), MidpointRounding.AwayFromZero);
        }
        else
        {
            var frameLocked = SceneObjectBool(
                props.TryGetValue("frameLock", out var lockValue) ? lockValue :
                props.TryGetValue("frameLocked", out var lockedValue) ? lockedValue : null,
                JsonBool(resource, "frameLock", JsonBool(resource, "frameLocked", true)));
            sequenceFrame = frameLocked && sequence.Fps == Math.Max(1, referenceFps)
                ? globalFrame - anchorFrame
                : (int)Math.Floor((globalFrame - anchorFrame) * sequence.Fps / (double)Math.Max(1, referenceFps));
        }

        sequenceFrame += (int)Math.Round(
            props.TryGetValue("sequenceOffset", out var offset) ? SceneObjectNumber(offset, 0) : JsonDouble(resource, "sequenceOffset", 0),
            MidpointRounding.AwayFromZero);
        return sequence.SelectFrame(sequenceFrame);
    }

    private static Legacy.SmartBadgeRect? SmartFieldBounds(
        Legacy.SmartBadgeSequenceDefinition sequence,
        int templateFrame,
        params string[] sources)
    {
        foreach (var field in sequence.Fields)
        {
            var source = field.Source.Trim();
            if (sources.Any(candidate => source.Equals(candidate, StringComparison.OrdinalIgnoreCase)))
                return field.RectAt(templateFrame);
        }
        return null;
    }

    private static void AppendSmartSequenceTextRegions(
        ComparisonProject project,
        int cardIndex,
        Legacy.SmartBadgeSequenceDefinition sequence,
        int templateFrame,
        double originX,
        double originY,
        double scaleX,
        double scaleY,
        List<PreviewTextRegion> output)
    {
        var card = project.Cards[cardIndex];
        var seen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var field in sequence.Fields)
        {
            var rect = field.RectAt(templateFrame);
            if (rect is null || field.AlphaAt(templateFrame) <= .0001f)
                continue;

            var mapped = MapSmartField(field.Source);
            if (mapped is null || !seen.Add(mapped))
                continue;

            var sourceText = mapped switch
            {
                "title" => card.Title,
                "description" => card.Description,
                "badgeHeader" => card.BadgeHeader,
                "value" => card.Value,
                _ => "",
            };
            if (string.IsNullOrWhiteSpace(sourceText))
                continue;

            output.Add(new PreviewTextRegion(
                cardIndex,
                mapped,
                originX + rect.X * scaleX,
                originY + rect.Y * scaleY,
                rect.Width * scaleX,
                rect.Height * scaleY,
                field.RotationAt(templateFrame),
                field.FontSize * Math.Abs(scaleY),
                field.Bold,
                NormalizeAlign(field.Align),
                NormalizeVerticalAlign(field.VerticalAlign),
                ParseArgb(field.Color, 0xffffffffu),
                mapped == "description"));
        }
    }

    private static string? MapSmartField(string source) => source.Trim().ToLowerInvariant() switch
    {
        "title" => "title",
        "description" or "desc" => "description",
        "header" or "badgeheader" or "badge-header" => "badgeHeader",
        "value" or "primary" or "number" or "unit" or "suffix" or "fullvalue" or "full-value" or "raw" or "jsparse" => "value",
        _ => null,
    };

    private static string NormalizeAlign(string value) => value.Trim().ToLowerInvariant() switch
    {
        "left" => "left",
        "right" => "right",
        _ => "center",
    };

    private static string NormalizeVerticalAlign(string value) => value.Trim().ToLowerInvariant() switch
    {
        "top" => "top",
        "bottom" => "bottom",
        _ => "center",
    };

    private static uint ParseArgb(string value, uint fallback)
    {
        if (string.IsNullOrWhiteSpace(value)) return fallback;
        var text = value.Trim().TrimStart('#');
        try
        {
            return text.Length switch
            {
                6 => 0xff000000u | Convert.ToUInt32(text, 16),
                8 => Convert.ToUInt32(text, 16),
                _ => fallback,
            };
        }
        catch { return fallback; }
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
            ImageCoordinateScaleX: 1,
            ImageCoordinateScaleY: 1,
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

            var type = JsonString(resource, "type", obj.Kind).ToLowerInvariant();
            if (type != "relationships-card" && obj.Kind is not "card" and not "openingCard")
                continue;

            var props = Legacy.V3Evaluator.Properties(scene, obj, frame);
            if (type == "relationships-card")
            {
                var pitch = JsonDouble(resource, "slotPitch", 480);
                var width = JsonDouble(resource, "width", 474);
                var height = JsonDouble(resource, "height", _spec.ReferenceHeight);
                var imageHeight = JsonDouble(resource, "imageHeight", 789);
                var titleHeight = JsonDouble(resource, "titleHeight", 117);
                var dividerHeight = JsonDouble(resource, "dividerHeight", 8);
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
                    ImageCoordinateScaleX: 1,
                    ImageCoordinateScaleY: 1,
                    TitleX: x,
                    TitleY: imageHeight,
                    TitleWidth: width,
                    TitleHeight: titleHeight,
                    DescriptionX: x,
                    DescriptionY: descriptionTop,
                    DescriptionWidth: width,
                    DescriptionHeight: Math.Max(0, height - descriptionTop),
                    BadgeX: x + JsonDouble(resource, "centerX", width / 2) - JsonDouble(resource, "radiusX", 176),
                    BadgeY: JsonDouble(resource, "centerY", 192) - JsonDouble(resource, "radiusY", 172),
                    BadgeWidth: JsonDouble(resource, "radiusX", 176) * 2,
                    BadgeHeight: JsonDouble(resource, "radiusY", 172) * 2);
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

    private static string JsonString(System.Text.Json.JsonElement element, string key, string fallback)
    {
        if (element.ValueKind != System.Text.Json.JsonValueKind.Object ||
            !element.TryGetProperty(key, out var value) ||
            value.ValueKind != System.Text.Json.JsonValueKind.String)
            return fallback;
        return value.GetString() ?? fallback;
    }

    private static double JsonDouble(System.Text.Json.JsonElement element, string key, double fallback)
    {
        if (element.ValueKind != System.Text.Json.JsonValueKind.Object ||
            !element.TryGetProperty(key, out var value))
            return fallback;
        if (value.ValueKind == System.Text.Json.JsonValueKind.Number && value.TryGetDouble(out var number))
            return number;
        return double.TryParse(value.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var parsedText)
            ? parsedText
            : fallback;
    }

    private static string SceneString(Dictionary<string, object?> props, string key, string fallback)
    {
        if (!props.TryGetValue(key, out var value) || value is null) return fallback;
        if (value is System.Text.Json.JsonElement element)
            return element.ValueKind == System.Text.Json.JsonValueKind.String ? element.GetString() ?? fallback : element.ToString();
        return value.ToString() ?? fallback;
    }

    private static double SceneObjectNumber(object? value, double fallback)
    {
        if (value is null) return fallback;
        if (value is System.Text.Json.JsonElement element)
        {
            if (element.ValueKind == System.Text.Json.JsonValueKind.Number && element.TryGetDouble(out var parsed)) return parsed;
            return double.TryParse(element.ToString(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out parsed) ? parsed : fallback;
        }
        try { return Convert.ToDouble(value, System.Globalization.CultureInfo.InvariantCulture); }
        catch { return fallback; }
    }

    private static bool SceneObjectBool(object? value, bool fallback)
    {
        if (value is null) return fallback;
        if (value is bool boolean) return boolean;
        if (value is System.Text.Json.JsonElement element)
        {
            if (element.ValueKind == System.Text.Json.JsonValueKind.True) return true;
            if (element.ValueKind == System.Text.Json.JsonValueKind.False) return false;
            return bool.TryParse(element.ToString(), out var parsed) ? parsed : fallback;
        }
        return bool.TryParse(value.ToString(), out var valueParsed) ? valueParsed : fallback;
    }

    private static int JsonInt(System.Text.Json.JsonElement element, string key, int fallback)
    {
        if (element.ValueKind != System.Text.Json.JsonValueKind.Object || !element.TryGetProperty(key, out var value))
            return fallback;
        if (value.TryGetInt32(out var number)) return number;
        return int.TryParse(value.ToString(), System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out number)
            ? number
            : fallback;
    }

    private static bool JsonBool(System.Text.Json.JsonElement element, string key, bool fallback)
    {
        if (element.ValueKind != System.Text.Json.JsonValueKind.Object || !element.TryGetProperty(key, out var value))
            return fallback;
        if (value.ValueKind == System.Text.Json.JsonValueKind.True) return true;
        if (value.ValueKind == System.Text.Json.JsonValueKind.False) return false;
        return bool.TryParse(value.ToString(), out var parsed) ? parsed : fallback;
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
