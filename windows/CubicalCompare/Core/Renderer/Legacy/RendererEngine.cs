using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using SkiaSharp;

namespace CubicalCompare.Windows;

public sealed class RendererEngine : IDisposable
{
    private readonly Dictionary<string, SKBitmap> _imageCache = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, SKRect?> _sequenceOpaqueBoundsCache = new(StringComparer.OrdinalIgnoreCase);
    private readonly InfiniteTimelineRenderer _infinite = new();
    private readonly RelationshipsRenderer _relationships = new();

    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)
    {
        width = Math.Max(2, width);
        height = Math.Max(2, height);
        if (spec.Engine == "infinite-timeline-exact")
        {
            if (CubicalCompare.Core.Renderer.InternalCodeOverrideManager.TryRender(
                    spec.Engine, project, spec, Math.Max(0, frame), width, height, out var overridden))
                return overridden;
            return _infinite.Render(project, spec, Math.Max(0, frame), width, height);
        }
        if (spec.Engine == "relationships-exact")
        {
            if (CubicalCompare.Core.Renderer.InternalCodeOverrideManager.TryRender(
                    spec.Engine, project, spec, Math.Max(0, frame), width, height, out var overridden))
                return overridden;
            return _relationships.Render(project, spec, Math.Max(0, frame), width, height);
        }
        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(bitmap);
        canvas.Clear(SKColors.Black);
        var sx = width / (float)Math.Max(1, spec.ReferenceWidth);
        var sy = height / (float)Math.Max(1, spec.ReferenceHeight);
        canvas.Scale(sx, sy);
        if (spec.Engine == "scene-v3" && spec.SceneV3 != null) DrawSceneV3(canvas, project, spec, Math.Max(0, frame));
        else if (spec.Engine == "ribbon-exact") DrawRibbon(canvas, project, spec, Math.Max(0, frame));
        else DrawStandard(canvas, project, spec, Math.Max(0, frame));
        canvas.Flush();
        return bitmap;
    }

    public int FrameCount(StudioProject project, RendererSpec spec)
    {
        if (!project.AutoLength) return Math.Max(1, (int)Math.Round(project.CustomLengthSeconds * spec.ReferenceFps));
        if (spec.Engine == "infinite-timeline-exact")
        {
            if (CubicalCompare.Core.Renderer.InternalCodeOverrideManager.TryFrameCount(
                    spec.Engine, project, spec, out var overridden))
                return overridden;
            return _infinite.FrameCount(project, spec);
        }
        if (spec.Engine == "relationships-exact")
        {
            if (CubicalCompare.Core.Renderer.InternalCodeOverrideManager.TryFrameCount(
                    spec.Engine, project, spec, out var overridden))
                return overridden;
            return _relationships.FrameCount(project, spec);
        }
        if (spec.Engine == "scene-v3" && spec.SceneV3 != null && spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal))
        {
            var lastIndex = Math.Max(0, project.Cards.Count - 1);
            var lastCard = spec.SceneV3.Objects.FirstOrDefault(obj =>
                obj.Kind is "card" or "relationshipsCard" &&
                CardIndex(obj) == lastIndex);
            if (lastCard != null) return Math.Clamp(lastCard.LifespanEnd + 1, 1, spec.SceneV3.Frames);
        }
        if (spec.CanonicalFrameCount > 0) return spec.CanonicalFrameCount;
        if (spec.Engine == "ribbon-exact")
        {
            var scrollCards = Math.Max(0, project.Cards.Count - 4);
            return Math.Max(1, spec.ContinuousStartFrame + scrollCards * spec.ContinuousStepFrames + spec.OutroFrames);
        }
        return Math.Max(1, project.Cards.Count * Math.Max(1, spec.ContinuousStepFrames) + spec.OutroFrames);
    }

    private void DrawStandard(SKCanvas canvas, StudioProject project, RendererSpec spec, int frame)
    {
        canvas.Clear(ToSkColor(spec.BackgroundColor));
        if (project.Cards.Count == 0) return;
        var step = Math.Max(1, spec.ContinuousStepFrames);
        var scroll = frame / (float)step * spec.SlotPitch;
        for (var i = 0; i < project.Cards.Count; i++)
        {
            var x = i * spec.SlotPitch - scroll;
            if (x < -spec.SlotPitch || x > spec.ReferenceWidth + spec.SlotPitch) continue;
            DrawLegacyCard(canvas, project, project.Cards[i], x, spec);
        }
    }

    private void DrawRibbon(SKCanvas canvas, StudioProject project, RendererSpec spec, int frame)
    {
        canvas.Clear(RibbonBackground(spec, frame));
        if (project.Cards.Count == 0) return;
        var contentEnd = Math.Max(spec.ContinuousStartFrame, FrameCount(project, spec) - spec.OutroFrames);
        if (frame >= contentEnd)
        {
            DrawRibbonOutro(canvas, project, spec, frame - contentEnd);
            return;
        }
        var positions = RibbonPositions(project, spec, frame);
        foreach (var pair in positions.OrderBy(x => x.Key))
        {
            if (!TryDrawRibbonSmartCard(canvas, project, project.Cards[pair.Key], pair.Key, pair.Value, frame, spec))
                DrawLegacyCard(canvas, project, project.Cards[pair.Key], pair.Value, spec);
        }
        foreach (var pair in positions.OrderBy(x => x.Key))
            DrawRibbonBadge(canvas, project, project.Cards[pair.Key], pair.Key, pair.Value, frame, spec);
    }

    private bool TryDrawRibbonSmartCard(
        SKCanvas canvas,
        StudioProject project,
        StudioCard card,
        int index,
        float cardX,
        int globalFrame,
        RendererSpec spec)
    {
        var scene = spec.SceneV3;
        if (scene is null) return false;

        var obj = scene.Objects.FirstOrDefault(candidate =>
            CardIndex(candidate) == index &&
            candidate.Resource is not null &&
            scene.Resources.TryGetValue(candidate.Resource, out var candidateResource) &&
            candidateResource.String("type", "").Equals("smart-card-animation", StringComparison.OrdinalIgnoreCase));
        if (obj is null || globalFrame < obj.LifespanStart || globalFrame > obj.LifespanEnd)
            return false;

        if (obj.Resource is null || !scene.Resources.TryGetValue(obj.Resource, out var resource))
            return false;

        var props = V3Evaluator.Properties(scene, obj, globalFrame)
            .ToDictionary(pair => pair.Key, pair => BindProjectValue(pair.Value, project, obj), StringComparer.Ordinal);

        var sequenceRoot = StringValue(Get(props, "sequenceRoot")) ?? resource.String("sequenceRoot", "");
        if (string.IsNullOrWhiteSpace(sequenceRoot))
            return false;

        SmartBadgeSequenceDefinition sequence;
        try
        {
            sequence = SmartBadgeSequence.Load(scene, sequenceRoot);
        }
        catch
        {
            // A declared SmartCard is authoritative for this card. Fail closed
            // rather than silently drawing a different legacy card underneath it.
            return true;
        }

        var explicitFrame = Get(props, "sequenceFrame");
        var frameLocked = Truthy(
            Get(props, "frameLock", "frameLocked"),
            resource.Bool("frameLock", resource.Bool("frameLocked", true)));
        int sequenceFrame;
        if (explicitFrame is not null)
            sequenceFrame = (int)Math.Round(Number(explicitFrame), MidpointRounding.AwayFromZero);
        else if (frameLocked && sequence.Fps == spec.ReferenceFps)
            sequenceFrame = globalFrame - obj.Frame;
        else
            sequenceFrame = (int)Math.Floor(
                (globalFrame - obj.Frame) * sequence.Fps / (double)Math.Max(1, spec.ReferenceFps));

        sequenceFrame += (int)Math.Round(
            Number(Get(props, "sequenceOffset"), resource.Int("sequenceOffset", 0)),
            MidpointRounding.AwayFromZero);

        var selected = sequence.SelectFrame(sequenceFrame);
        if (selected is null) return true;
        var basePlate = DecodeSequenceBitmap(sequence, selected.Asset);
        if (basePlate is null) return true;

        var drawX = (float)Number(Get(props, "drawX"), resource.Double("drawX", spec.BodyInset));
        var drawY = (float)Number(Get(props, "drawY"), resource.Double("drawY", 0));
        var drawWidth = (float)Number(Get(props, "drawWidth"), resource.Double("drawWidth", sequence.Width));
        var drawHeight = (float)Number(Get(props, "drawHeight"), resource.Double("drawHeight", sequence.Height));
        if (drawWidth <= 0 || drawHeight <= 0) return true;

        canvas.Save();
        canvas.Translate(cardX + drawX, drawY);
        canvas.Scale(drawWidth / Math.Max(1, sequence.Width), drawHeight / Math.Max(1, sequence.Height));

        using (var basePaint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = FilterQuality(
                StringValue(Get(props, "sampling", "filterMode")) ?? resource.String("sampling", "high")),
            Color = SKColors.White,
            BlendMode = SKBlendMode.SrcOver,
        })
        {
            canvas.DrawBitmap(basePlate, new SKRect(0, 0, sequence.Width, sequence.Height), basePaint);
        }

        var clipLiveContent = Truthy(
            Get(props, "clipLiveContent"),
            resource.Bool("clipLiveContent", false));
        var textOutsideArtworkClip = spec.RequiredFeatures.Contains(
            "smart-card-text-outside-artwork-clip-v1",
            StringComparer.Ordinal);
        var contentClip = sequence.Artwork?.ClipAt(selected.TemplateFrame);
        var liveClipSaved = false;
        if (clipLiveContent && contentClip is not null)
        {
            canvas.Save();
            liveClipSaved = true;
            canvas.ClipRect(
                new SKRect(contentClip.X, contentClip.Y, contentClip.Right, contentClip.Bottom),
                SKClipOperation.Intersect,
                false);
        }

        if (sequence.Artwork is not null && !string.IsNullOrWhiteSpace(card.Image))
        {
            var dest = sequence.Artwork.DestAt(selected.TemplateFrame);
            var clip = sequence.Artwork.ClipAt(selected.TemplateFrame);
            var alpha = Math.Clamp(sequence.Artwork.AlphaAt(selected.TemplateFrame), 0, 1);
            if (dest is not null && clip is not null && alpha > 0.0001f)
            {
                canvas.Save();
                canvas.ClipRect(
                    new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom),
                    SKClipOperation.Intersect,
                    false);
                if (alpha < 0.9999f)
                {
                    using var layerPaint = new SKPaint
                    {
                        Color = new SKColor(255, 255, 255, AlphaByte(alpha)),
                    };
                    canvas.SaveLayer(layerPaint);
                    DrawImageCover(
                        canvas,
                        card,
                        new SKRect(dest.X, dest.Y, dest.Right, dest.Bottom),
                        new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom));
                    canvas.Restore();
                }
                else
                {
                    DrawImageCover(
                        canvas,
                        card,
                        new SKRect(dest.X, dest.Y, dest.Right, dest.Bottom),
                        new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom));
                }
                canvas.Restore();
            }
        }

        // New SmartCard contract: the artwork reveal/mask can stay clipped while
        // title/description/jsparse fields remain in their authored card regions.
        if (liveClipSaved && textOutsideArtworkClip)
        {
            canvas.Restore();
            liveClipSaved = false;
        }

        foreach (var field in sequence.Fields)
            DrawSmartBadgeField(canvas, project, card, field, selected.TemplateFrame, 1);

        if (liveClipSaved)
            canvas.Restore();

        // Overlay/glass/shine is deliberately outside the live-content clip and
        // composites last so it illuminates both text and artwork.
        DrawSmartSequenceOverlay(canvas, sequence, selected.TemplateFrame, 1);

        canvas.Restore();
        return true;
    }

    private SKColor RibbonBackground(RendererSpec spec, int frame)
    {
        var gray = Motion(spec, "ribbon.background.gray", frame);
        var r = Motion(spec, "ribbon.background.r", frame);
        var g = Motion(spec, "ribbon.background.g", frame);
        var b = Motion(spec, "ribbon.background.b", frame);
        if (gray == null && r == null && g == null && b == null) return ToSkColor(spec.BackgroundColor);
        var fallback = gray ?? 0;
        return new SKColor((byte)Math.Clamp((int)Math.Round(r ?? fallback), 0, 255), (byte)Math.Clamp((int)Math.Round(g ?? fallback), 0, 255), (byte)Math.Clamp((int)Math.Round(b ?? fallback), 0, 255));
    }

    private Dictionary<int, float> RibbonPositions(StudioProject project, RendererSpec spec, int frame)
    {
        var result = new Dictionary<int, float>();
        if (frame >= spec.ContinuousStartFrame && project.Cards.Count > 4)
        {
            var segment = (frame - spec.ContinuousStartFrame) / 512;
            var exact = Motion(spec, $"ribbon.scroll.{segment}", frame);
            var scroll = exact ?? ((frame - spec.ContinuousStartFrame) / (float)Math.Max(1, spec.ContinuousStepFrames) * spec.SlotPitch);
            var first = Math.Max(0, (int)(scroll / spec.SlotPitch) - 1);
            var last = Math.Min(project.Cards.Count - 1, (int)((scroll + spec.ReferenceWidth) / spec.SlotPitch) + 1);
            for (var i = first; i <= last; i++)
            {
                var x = i * spec.SlotPitch - scroll;
                if (x > -spec.SlotPitch && x < spec.ReferenceWidth + spec.SlotPitch) result[i] = x;
            }
            return result;
        }
        var active = -1;
        for (var i = 0; i < Math.Min(4, project.Cards.Count); i++) if (frame >= CardStart(spec, i)) active = i;
        if (active < 0) return result;
        for (var i = 0; i < active; i++) result[i] = i * spec.SlotPitch;
        var local = frame - CardStart(spec, active);
        var exactX = Motion(spec, $"ribbon.open.{active}.card.x", local);
        var progress = BodyProgress(spec, local);
        result[active] = exactX ?? (active == 0 ? Lerp(-spec.SlotPitch, 0, progress) : Lerp((active - 1) * spec.SlotPitch, active * spec.SlotPitch, progress));
        return result;
    }

    private int CardStart(RendererSpec spec, int index)
    {
        if (index < spec.OpeningStarts.Count) return spec.OpeningStarts[index];
        return spec.ContinuousStartFrame + Math.Max(0, index - 4) * spec.ContinuousStepFrames;
    }

    private float BodyProgress(RendererSpec spec, int local)
    {
        var exact = Motion(spec, "ribbon.body.progress", local);
        if (exact != null) return Math.Clamp(exact.Value, 0, 1);
        var p = Math.Clamp(local / (float)Math.Max(1, spec.BodySlideFrames), 0, 1);
        return p * p * (3 - 2 * p);
    }

    private float? Motion(RendererSpec spec, string target, int frame)
    {
        var centre = spec.Track(target, frame);
        if (centre == null) return null;
        if (spec.PrecisionMode == "frame-exact") return centre;
        var previous = spec.Track(target, frame - 1) ?? centre;
        var next = spec.Track(target, frame + 1) ?? centre;
        return previous * 0.20f + centre * 0.60f + next * 0.20f;
    }

    private void DrawLegacyCard(SKCanvas canvas, StudioProject project, StudioCard card, float slotX, RendererSpec spec)
    {
        var left = slotX + spec.BodyInset;
        var right = left + spec.BodyWidth;
        var titleHeight = string.IsNullOrWhiteSpace(card.Title) ? 0 : spec.TitleHeight;
        var imageBottom = Math.Clamp(spec.ImageHeight, 0, spec.ReferenceHeight);
        using var paint = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Fill };
        paint.Color = new SKColor(0, 105, 211);
        canvas.DrawRect(left, 0, spec.BodyWidth, imageBottom, paint);
        if (!string.IsNullOrWhiteSpace(card.Image)) DrawImageCover(canvas, card, new SKRect(left, 0, right, imageBottom));
        var cursor = imageBottom;
        if (titleHeight > 0)
        {
            paint.Color = ToSkColor(spec.TitleBackgroundColor);
            canvas.DrawRect(left, cursor, spec.BodyWidth, titleHeight, paint);
            DrawFitText(canvas, project, card.Title, new SKRect(left + 12, cursor + 4, right - 12, cursor + titleHeight - 4), ToSkColor(spec.TitleTextColor), spec.TitleTextSize, true, 2);
            cursor += titleHeight;
        }
        if (!string.IsNullOrWhiteSpace(card.Description))
        {
            paint.Color = ToSkColor(spec.DescriptionBackgroundColor);
            canvas.DrawRect(left, cursor, spec.BodyWidth, Math.Max(0, spec.ReferenceHeight - cursor), paint);
            DrawFitText(canvas, project, card.Description, new SKRect(left + 17, cursor + 8, right - 17, spec.ReferenceHeight - 8), ToSkColor(spec.DescriptionTextColor), spec.DescriptionTextSize, false, 5);
        }
    }

    private void DrawRibbonBadge(SKCanvas canvas, StudioProject project, StudioCard card, int index, float cardX, int globalFrame, RendererSpec spec)
    {
        if (!project.ShowBadges || (string.IsNullOrWhiteSpace(card.Value) && string.IsNullOrWhiteSpace(card.BadgeHeader))) return;

        // SmartBadge v2 badge packs are complete card-local bootanimations. Each card
        // selects its own nested ZIP from smartbadge-v2.json, so the pack owns badge
        // reveal/shape/shine/text geometry instead of being forced through one global
        // procedural badge animation.
        if (TryDrawRibbonSmartBadgeV2(canvas, project, card, index, cardX, globalFrame, spec))
            return;

        var local = globalFrame - CardStart(spec, index);
        var visible = index < 4 ? spec.TrackWindowed($"ribbon.open.{index}.visible", local) ?? spec.Track($"ribbon.open.{index}.visible", local) : null;
        var affine = index < 4 && new[] { "m00", "m01", "m10", "m11", "tx", "ty" }.Any(c => spec.HasTrack($"ribbon.open.{index}.{c}"));
        if (visible != null && visible <= 0.001f) return;
        if (index < 4 && visible == null && !affine && local < 35) return;
        if (index >= 4 && local < (spec.TrackStart($"ribbon.card.{index}.badge.y") ?? spec.LaterBadgeFallStartFrame)) return;

        canvas.Save();
        canvas.Translate(cardX, 0);
        if (index < 4)
        {
            var prefix = affine ? $"ribbon.open.{index}" : "ribbon.open";
            var matrix = new SKMatrix
            {
                ScaleX = Motion(spec, $"{prefix}.m00", local) ?? 1,
                SkewX = Motion(spec, $"{prefix}.m01", local) ?? 0,
                TransX = Motion(spec, $"{prefix}.tx", local) ?? 0,
                SkewY = Motion(spec, $"{prefix}.m10", local) ?? 0,
                ScaleY = Motion(spec, $"{prefix}.m11", local) ?? 1,
                TransY = Motion(spec, $"{prefix}.ty", local) ?? 0,
                Persp2 = 1,
            };
            canvas.Concat(ref matrix);
        }
        else canvas.Translate(0, Motion(spec, $"ribbon.card.{index}.badge.y", local) ?? Motion(spec, "ribbon.later.badge.y", local) ?? 0);
        var scale = (Motion(spec, $"ribbon.card.{index}.badge.scale", local) ?? 1) * spec.BadgeScale;
        canvas.Scale(scale, scale, spec.BadgeCenterX, spec.BadgeCenterY);
        DrawBadgeShape(canvas, project, card, index, local, spec);
        canvas.Restore();
    }

    private bool TryDrawRibbonSmartBadgeV2(
        SKCanvas canvas,
        StudioProject project,
        StudioCard card,
        int index,
        float cardX,
        int globalFrame,
        RendererSpec spec)
    {
        if (spec.SmartBadgeV2Manifest is not JsonElement manifest ||
            manifest.ValueKind != JsonValueKind.Object)
            return false;

        var defaultPack = manifest.String("defaultPack", "");
        string packId = defaultPack;
        int? explicitStart = null;
        int sequenceOffset = 0;
        JsonElement cardSelection = default;
        var hasCardSelection = false;

        if (manifest.TryGetProperty("cards", out var cards) && cards.ValueKind == JsonValueKind.Object)
        {
            if (cards.TryGetProperty(index.ToString(CultureInfo.InvariantCulture), out cardSelection))
            {
                hasCardSelection = cardSelection.ValueKind == JsonValueKind.Object;
                if (cardSelection.ValueKind == JsonValueKind.String)
                {
                    packId = cardSelection.GetString() ?? packId;
                }
                else if (hasCardSelection)
                {
                    packId = cardSelection.String("pack", packId);
                    if (cardSelection.TryGetProperty("startFrame", out var startElement) &&
                        startElement.TryGetInt32(out var authoredStartFrame))
                        explicitStart = authoredStartFrame;
                    sequenceOffset = cardSelection.Int("sequenceOffset", 0);
                }
            }
        }

        if (string.IsNullOrWhiteSpace(packId) ||
            !manifest.TryGetProperty("packs", out var packs) ||
            packs.ValueKind != JsonValueKind.Object ||
            !packs.TryGetProperty(packId, out var packSelection))
            return false;

        string asset;
        float drawX = 0;
        float drawY = 0;
        float? drawWidth = null;
        float? drawHeight = null;
        var entryMotion = "";
        var entryAnchor = "";
        var settledHold = false;

        if (packSelection.ValueKind == JsonValueKind.String)
        {
            asset = packSelection.GetString() ?? "";
        }
        else if (packSelection.ValueKind == JsonValueKind.Object)
        {
            asset = packSelection.String("asset", "");
            drawX = (float)packSelection.Double("drawX", 0);
            drawY = (float)packSelection.Double("drawY", 0);
            var configuredWidth = packSelection.Double("drawWidth", 0);
            var configuredHeight = packSelection.Double("drawHeight", 0);
            if (configuredWidth > 0) drawWidth = (float)configuredWidth;
            if (configuredHeight > 0) drawHeight = (float)configuredHeight;
            entryMotion = packSelection.String("entryMotion", "");
            entryAnchor = packSelection.String("entryAnchor", "");
            settledHold = packSelection.Bool("settledHold", false);
        }
        else
        {
            return false;
        }

        // A card selection may override placement/motion without duplicating a pack.
        if (hasCardSelection)
        {
            drawX = (float)cardSelection.Double("drawX", drawX);
            drawY = (float)cardSelection.Double("drawY", drawY);
            var cardWidth = cardSelection.Double("drawWidth", drawWidth ?? 0);
            var cardHeight = cardSelection.Double("drawHeight", drawHeight ?? 0);
            if (cardWidth > 0) drawWidth = (float)cardWidth;
            if (cardHeight > 0) drawHeight = (float)cardHeight;
            entryMotion = cardSelection.String("entryMotion", entryMotion);
            entryAnchor = cardSelection.String("entryAnchor", entryAnchor);
            settledHold = cardSelection.Bool("settledHold", settledHold);
        }

        if (string.IsNullOrWhiteSpace(asset))
            return false;

        SmartBadgeSequenceDefinition sequence;
        try
        {
            sequence = SmartBadgeSequence.LoadArchive(spec, asset);
        }
        catch
        {
            // The compatibility report exposes the exact validation error. During a
            // render we fail closed rather than silently changing authored badges.
            return true;
        }

        var startFrame = explicitStart ?? CardStart(spec, index);
        var sequenceFrame = globalFrame - startFrame + sequenceOffset;
        if (sequenceFrame < 0)
            return true;

        var selected = sequence.SelectFrame(sequenceFrame);
        if (selected is null && settledHold)
            selected = sequence.FinalFrame();
        if (selected is null)
            return true;

        var bitmap = DecodeSequenceBitmap(sequence, selected.Asset);
        if (bitmap is null)
            return true;

        var width = drawWidth ?? sequence.Width;
        var height = drawHeight ?? sequence.Height;
        if (width <= 0 || height <= 0)
            return true;

        // top-to-final is authored by the sequence's per-frame vertical geometry.
        // final-x is enforced by the runtime so a top-entry sequence cannot drift
        // sideways just because individual PNG bounds differ by a pixel or two.
        var finalXCorrection = 0f;
        var lockFinalX =
            entryAnchor.Equals("final-x", StringComparison.OrdinalIgnoreCase) ||
            entryMotion.Equals("top-to-final", StringComparison.OrdinalIgnoreCase);
        if (lockFinalX)
        {
            var final = sequence.FinalFrame();
            if (final is not null)
            {
                var finalBitmap = DecodeSequenceBitmap(sequence, final.Asset);
                if (finalBitmap is not null)
                {
                    var currentBounds = SequenceOpaqueBounds(sequence, selected.Asset, bitmap);
                    var finalBounds = SequenceOpaqueBounds(sequence, final.Asset, finalBitmap);
                    if (currentBounds is SKRect current && finalBounds is SKRect target)
                        finalXCorrection = target.MidX - current.MidX;
                }
            }
        }

        canvas.Save();
        canvas.Translate(cardX + drawX + finalXCorrection, drawY);
        canvas.Scale(width / Math.Max(1, sequence.Width), height / Math.Max(1, sequence.Height));

        using (var paint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = SKFilterQuality.High,
            Color = SKColors.White,
            BlendMode = SKBlendMode.SrcOver,
        })
        {
            canvas.DrawBitmap(bitmap, new SKRect(0, 0, sequence.Width, sequence.Height), paint);
        }

        foreach (var field in sequence.Fields)
            DrawSmartBadgeField(canvas, project, card, field, selected.TemplateFrame, 1);

        DrawSmartSequenceOverlay(canvas, sequence, selected.TemplateFrame, 1);

        canvas.Restore();
        return true;
    }

    private SKRect? SequenceOpaqueBounds(
        SmartBadgeSequenceDefinition sequence,
        string asset,
        SKBitmap bitmap)
    {
        var key = sequence.Root + "|" + asset;
        if (_sequenceOpaqueBoundsCache.TryGetValue(key, out var cached))
            return cached;

        var left = bitmap.Width;
        var top = bitmap.Height;
        var right = -1;
        var bottom = -1;

        for (var y = 0; y < bitmap.Height; y++)
        {
            for (var x = 0; x < bitmap.Width; x++)
            {
                if (bitmap.GetPixel(x, y).Alpha == 0)
                    continue;

                if (x < left) left = x;
                if (x > right) right = x;
                if (y < top) top = y;
                if (y > bottom) bottom = y;
            }
        }

        SKRect? result = right >= left && bottom >= top
            ? new SKRect(left, top, right + 1, bottom + 1)
            : null;
        _sequenceOpaqueBoundsCache[key] = result;
        return result;
    }

    private void DrawBadgeShape(SKCanvas canvas, StudioProject project, StudioCard card, int index, int local, RendererSpec spec)
    {
        using var path = new SKPath();
        path.MoveTo(224, 16); path.LineTo(396, 104); path.LineTo(396, 292); path.LineTo(252, 380); path.LineTo(72, 292); path.LineTo(72, 104); path.Close();
        using var shadow = new SKPaint { IsAntialias = true, Color = new SKColor(0, 0, 0, 115), ImageFilter = SKImageFilter.CreateBlur(8, 8) };
        canvas.Save(); canvas.Translate(6, 9); canvas.DrawPath(path, shadow); canvas.Restore();
        using var fill = new SKPaint { IsAntialias = true, Color = ToSkColor(spec.BadgeColor) };
        canvas.DrawPath(path, fill);
        using var stroke = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Stroke, StrokeWidth = 2, Color = WithAlpha(ToSkColor(spec.BadgeDarkColor), 145) };
        canvas.DrawPath(path, stroke);
        DrawRibbonBadgeText(canvas, project, card, index, local, spec);
        DrawRibbonShine(canvas, path, index, local, spec);
    }

    private void DrawRibbonBadgeText(SKCanvas canvas, StudioProject project, StudioCard card, int index, int local, RendererSpec spec)
    {
        var header = card.BadgeHeader.Trim().ToUpperInvariant();
        var words = Regex.Split(card.Value.Trim(), "\\s+").Where(x => x.Length > 0).ToArray();
        var primary = words.FirstOrDefault() ?? "";
        var unit = words.Length > 1 ? string.Join(' ', words.Skip(1)) : "";
        var prefix = index < 4 ? $"ribbon.open.{index}" : $"ribbon.card.{index}";
        var progress = Motion(spec, $"{prefix}.text.progress", local) ?? Math.Clamp((local - 40) / 26f, 0, 1);
        if (progress <= 0) return;
        var alpha = (byte)Math.Clamp((int)Math.Round(255 * (Motion(spec, $"{prefix}.text.alpha", local) ?? Math.Min(1, progress * 1.75f))), 0, 255);
        using var paint = TextPaint(project, 34, SKColors.White, true);
        paint.Color = WithAlpha(SKColors.White, alpha);
        if (header.Length > 0)
        {
            paint.TextSize = 32; DrawCentered(canvas, header, spec.BadgeCenterX, 118 + (Motion(spec, $"{prefix}.text.0.y", local) ?? 0), paint, 264);
            paint.TextSize = 78; DrawCentered(canvas, primary, spec.BadgeCenterX, 225 + (Motion(spec, $"{prefix}.text.1.y", local) ?? 0), paint, 264);
            if (unit.Length > 0) { paint.TextSize = 40; DrawCentered(canvas, unit, spec.BadgeCenterX, 292 + (Motion(spec, $"{prefix}.text.2.y", local) ?? 0), paint, 264); }
        }
        else
        {
            paint.TextSize = 72; DrawCentered(canvas, primary, spec.BadgeCenterX, unit.Length > 0 ? 168 : 219, paint, 264);
            if (unit.Length > 0) { paint.TextSize = 40; DrawCentered(canvas, unit, spec.BadgeCenterX, 250, paint, 264); }
        }
    }

    private void DrawRibbonShine(SKCanvas canvas, SKPath badge, int index, int local, RendererSpec spec)
    {
        var progress = index < 4 ? Motion(spec, $"ribbon.open.{index}.shine.progress", local) : Motion(spec, $"ribbon.card.{index}.shine.progress", local) ?? Motion(spec, "ribbon.later.shine.progress", local);
        var alpha = index < 4 ? Motion(spec, $"ribbon.open.{index}.shine.alpha", local) : Motion(spec, $"ribbon.card.{index}.shine.alpha", local) ?? Motion(spec, "ribbon.later.shine.alpha", local);
        if (progress == null)
        {
            var p = (local - spec.ShineStartFrame) / (float)Math.Max(1, spec.ShineFrames);
            if (p < 0 || p > 1) return;
            progress = p;
        }
        if (progress < 0 || progress > 1) return;
        var a = Math.Clamp(alpha ?? (float)Math.Sin(progress.Value * Math.PI), 0, 1);
        if (a <= 0.001f) return;
        var x = Lerp(-170, 520, progress.Value);
        canvas.Save();
        canvas.ClipPath(badge, SKClipOperation.Intersect, true);
        using var broad = new SKPaint { IsAntialias = true, Color = new SKColor(255, 255, 255, (byte)(95 * a)), ImageFilter = SKImageFilter.CreateBlur(8.5f, 8.5f) };
        using var core = new SKPaint { IsAntialias = true, Color = new SKColor(255, 255, 255, (byte)(180 * a)), ImageFilter = SKImageFilter.CreateBlur(2.4f, 2.4f) };
        canvas.RotateDegrees(-26, spec.BadgeCenterX, spec.BadgeCenterY);
        canvas.DrawRect(x, -100, 82, 620, broad);
        canvas.DrawRect(x + 24, -100, 28, 620, core);
        canvas.Restore();
    }

    private void DrawRibbonOutro(SKCanvas canvas, StudioProject project, RendererSpec spec, int local)
    {
        canvas.Clear(ToSkColor(spec.BackgroundColor));
        var fadeStart = spec.EndWipeFrames + spec.EndRiseFrames + spec.EndHoldFrames;
        if (local >= fadeStart)
        {
            var p = Math.Clamp((local - fadeStart) / (float)Math.Max(1, spec.FadeFrames), 0, 1);
            using var paint = new SKPaint { Color = new SKColor(0, 0, 0, (byte)Math.Round(255 * p)) };
            canvas.DrawRect(0, 0, spec.ReferenceWidth, spec.ReferenceHeight, paint);
        }
    }

    private void DrawSceneV3(SKCanvas canvas, StudioProject project, RendererSpec spec, int frame)
    {
        var scene = spec.SceneV3!;
        var background = scene.Root.String("background", "#000000");
        canvas.Clear(ParseColor(background, SKColors.Black));
        var rank = scene.Layers.Select((id, i) => (id, i)).ToDictionary(x => x.id, x => x.i, StringComparer.Ordinal);
        var objects = scene.Objects
            .Select((obj, i) => (obj, i, z: ObjectZIndex(obj)))
            .OrderBy(x => rank.TryGetValue(x.obj.Id, out var r) ? r : int.MaxValue)
            .ThenBy(x => x.z)
            .ThenBy(x => x.i)
            .Select(x => x.obj);
        foreach (var obj in objects)
        {
            if (frame < obj.LifespanStart || frame > obj.LifespanEnd) continue;
            if (!ShouldRenderProjectObject(project, spec, obj)) continue;
            var props = V3Evaluator.Properties(scene, obj, frame);
            if (!Truthy(props.TryGetValue("visible", out var v) ? v : null, true)) continue;
            DrawV3Object(canvas, project, spec, obj, props, frame);
        }
        DrawV3EndFade(canvas, project, spec, frame);
    }

    private bool ShouldRenderProjectObject(StudioProject project, RendererSpec spec, RendererObjectV3 obj)
    {
        if (!spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal)) return true;
        if (obj.Kind is "endingOverlay" or "fade") return false;
        var index = CardIndex(obj);
        if (index is int i && (i < 0 || i >= project.Cards.Count)) return false;
        var smartBadge = obj.Resource is not null &&
            spec.SceneV3 is not null &&
            spec.SceneV3.Resources.TryGetValue(obj.Resource, out var badgeResource) &&
            badgeResource.String("type", "").Equals("smart-badge-animation", StringComparison.OrdinalIgnoreCase);
        if (index is int b && (smartBadge || obj.Kind is "openingBadge" or "badge" or "laterBadge" or "openingText" or "badgeText" or "laterText" or "openingShine" or "shineBroad" or "shineCore" or "shadow" or "relationshipsBadge"))
        {
            var card = project.Cards[b];
            if (!project.ShowBadges || (string.IsNullOrWhiteSpace(card.Value) && string.IsNullOrWhiteSpace(card.BadgeHeader))) return false;
        }
        return true;
    }

    private int? CardIndex(RendererObjectV3 obj)
    {
        if (obj.Raw.ValueKind == JsonValueKind.Object)
        {
            if (obj.Raw.TryGetProperty("cardIndex", out var c) && c.TryGetInt32(out var ci)) return ci;
            if (obj.Raw.TryGetProperty("dataIndex", out var d) && d.TryGetInt32(out var di)) return di;
        }
        var at = obj.Id.LastIndexOf('@');
        if (at >= 0 && int.TryParse(obj.Id[(at + 1)..], out var parsed)) return parsed;
        return null;
    }

    private void DrawV3Object(SKCanvas canvas, StudioProject project, RendererSpec spec, RendererObjectV3 obj, Dictionary<string, object?> props, int frame)
    {
        var scene = spec.SceneV3!;
        scene.Resources.TryGetValue(obj.Resource ?? "", out var resource);
        var type = resource.ValueKind == JsonValueKind.Object ? resource.String("type", obj.Kind).ToLowerInvariant() : obj.Kind.ToLowerInvariant();
        var bound = props.ToDictionary(x => x.Key, x => BindProjectValue(x.Value, project, obj), StringComparer.Ordinal);
        var opacity = Math.Clamp(Number(Get(bound, "opacity", "material.alpha"), 1), 0, 1);
        if (opacity <= 0.0001) return;
        var ownsTransform = type is "relationships-card" or "relationships-badge" or "smart-card-animation";
        canvas.Save();
        if (!ownsTransform)
        {
            var localClip = Truthy(Get(bound, "clip.local", "clip.afterTransform"), false);
            if (localClip)
            {
                ApplyTransform(canvas, bound);
                ApplyClip(canvas, bound);
            }
            else
            {
                ApplyClip(canvas, bound);
                ApplyTransform(canvas, bound);
            }
        }
        try
        {
            if (spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal) &&
                obj.Kind is "openingCard" or "card" &&
                type != "relationships-card")
            {
                DrawV3ProjectCard(canvas, project, obj, resource, (float)opacity);
                return;
            }
            if (spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal) && obj.Kind is "openingText" or "badgeText" or "laterText") { DrawV3ProjectBadgeText(canvas, project, obj, resource, bound, (float)opacity); return; }
            switch (type)
            {
                case "relationships-card": DrawV3RelationshipsCard(canvas, project, obj, resource, bound, (float)opacity); break;
                case "relationships-badge": DrawV3RelationshipsBadge(canvas, project, obj, resource, bound, (float)opacity); break;
                case "smart-card-animation": DrawV3SmartCardAnimation(canvas, project, spec, obj, resource, bound, frame, (float)opacity); break;
                case "smart-badge-animation": DrawV3SmartBadgeAnimation(canvas, project, spec, obj, resource, bound, frame, (float)opacity); break;
                case "rect": DrawV3Rect(canvas, resource, bound, (float)opacity); break;
                case "ellipse": DrawV3Ellipse(canvas, resource, bound, (float)opacity); break;
                case "image": DrawV3Image(canvas, scene, resource, bound, (float)opacity); break;
                case "text": DrawV3Text(canvas, project, resource, bound, (float)opacity); break;
                case "text-raster": case "source-text-raster": DrawV3Raster(canvas, scene, resource, bound, (float)opacity); break;
                case "outro-overlay":
                case "exact-outro-overlay":
                case "source-exact-outro-overlay":
                case "exact-opening-overlay":
                case "source-exact-opening-overlay":
                    DrawV3Outro(canvas, scene, resource, bound, frame, (float)opacity);
                    break;
                case "independent-shadow": DrawV3IndependentShadow(canvas, project, spec, resource, bound, frame, (float)opacity); break;
                case "group": DrawV3Group(canvas, project, spec, obj, resource, bound, frame, (float)opacity); break;
                default: DrawV3Polygon(canvas, resource, bound, (float)opacity); break;
            }
        }
        finally { canvas.Restore(); }
    }

    private void DrawV3RelationshipsCard(
        SKCanvas canvas,
        StudioProject project,
        RendererObjectV3 obj,
        JsonElement resource,
        Dictionary<string, object?> props,
        float opacity)
    {
        var index = CardIndex(obj);
        if (index is null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];

        var pitch = (float)resource.Double("slotPitch", 480);
        var width = (float)resource.Double("width", 474);
        var height = (float)resource.Double("height", 1080);
        var imageHeight = (float)resource.Double("imageHeight", 789);
        var titleHeight = (float)resource.Double("titleHeight", 117);
        var dividerHeight = (float)resource.Double("dividerHeight", 8);
        var pivotY = (float)resource.Double("pivotY", height / 2f);
        var scroll = (float)Number(Get(props, "scroll"), 0);
        var baseX = (float)Number(Get(props, "baseX"), index.Value * pitch);
        var offsetX = (float)Number(Get(props, "offsetX"), 0);
        var scale = (float)Math.Clamp(Number(Get(props, "cardScale", "scale"), 1), 0, 2.5);
        var artworkReveal = (float)Math.Clamp(Number(Get(props, "artworkReveal"), 1), 0, 1);
        var titleReveal = (float)Math.Clamp(Number(Get(props, "titleReveal"), 1), 0, 1);
        var descriptionReveal = (float)Math.Clamp(Number(Get(props, "descriptionReveal"), 1), 0, 1);
        var cornerRadius = (float)Math.Max(0, resource.Double("cornerRadius", 8));
        var revealShineEnabled = resource.Bool("artworkRevealShine", true);
        var revealShineProgress = (float)Math.Clamp(
            Number(Get(props, "artworkRevealShineProgress", "revealShineProgress"), artworkReveal),
            0,
            1);
        var revealShineOpacity = (float)Math.Clamp(
            Number(Get(props, "artworkRevealShineOpacity", "revealShineOpacity"),
                resource.Double("artworkRevealShineOpacity", 1)),
            0,
            1);
        var x = baseX - scroll + offsetX;
        var scaledHalfWidth = width * scale * 0.5f;
        var scaledCenterX = x + width * 0.5f;
        if (scaledCenterX + scaledHalfWidth < -4 || scaledCenterX - scaledHalfWidth > 1924) return;

        canvas.Save();
        canvas.Translate(x, 0);
        canvas.Scale(scale, scale, width / 2f, pivotY);

        var outerRect = new SKRect(0, 0, width, height);
        using var outerRound = new SKRoundRect(outerRect, cornerRadius, cornerRadius);
        var shadowOpacity = (float)Math.Clamp(resource.Double("shadowOpacity", 0.30), 0, 1);
        var shadowBlur = (float)Math.Max(0, resource.Double("shadowBlur", 6));
        var shadowOffsetY = (float)resource.Double("shadowOffsetY", 4);
        if (shadowOpacity > 0 && shadowBlur > 0)
        {
            using var cardShadow = new SKPaint
            {
                IsAntialias = true,
                Color = new SKColor(0, 0, 0, AlphaByte(opacity * shadowOpacity)),
                ImageFilter = SKImageFilter.CreateBlur(shadowBlur, shadowBlur),
            };
            canvas.Save();
            canvas.Translate(0, shadowOffsetY);
            canvas.DrawRoundRect(outerRound, cardShadow);
            canvas.Restore();
        }

        canvas.ClipRoundRect(outerRound, SKClipOperation.Intersect, true);

        var topColor = ParseColor(resource.String("topBackground", "#252525"), new SKColor(37, 37, 37));
        var titleColor = ParseColor(resource.String("titleBackground", "#f4f2f0"), new SKColor(244, 242, 240));
        var dividerColor = ParseColor(resource.String("dividerColor", "#d57e00"), new SKColor(213, 126, 0));
        var descriptionColor = ParseColor(resource.String("descriptionBackground", "#1b1b1b"), new SKColor(27, 27, 27));
        using var fill = new SKPaint { IsAntialias = false, Style = SKPaintStyle.Fill };

        fill.Color = WithAlpha(topColor, opacity);
        canvas.DrawRect(0, 0, width, imageHeight, fill);

        if (!string.IsNullOrWhiteSpace(card.Image) && artworkReveal > 0)
        {
            canvas.Save();
            canvas.ClipRect(new SKRect(0, 0, width, imageHeight * artworkReveal), SKClipOperation.Intersect, false);
            DrawImageCover(canvas, card, new SKRect(0, 0, width, imageHeight));
            canvas.Restore();
        }

        if (revealShineEnabled &&
            revealShineOpacity > 0 &&
            revealShineProgress > 0.0001f &&
            revealShineProgress < 0.9999f)
        {
            DrawRelationshipsCardRevealShine(
                canvas,
                width,
                imageHeight,
                revealShineProgress,
                revealShineOpacity * opacity,
                resource);
        }

        var titleTop = imageHeight;
        fill.Color = WithAlpha(titleColor, opacity);
        if (cornerRadius > 0)
        {
            using var titleRound = new SKRoundRect(
                new SKRect(0, titleTop, width, titleTop + titleHeight),
                cornerRadius,
                cornerRadius);
            canvas.DrawRoundRect(titleRound, fill);
            // Reference cards only round the top of this white band.
            canvas.DrawRect(0, titleTop + cornerRadius, width, Math.Max(0, titleHeight - cornerRadius), fill);
        }
        else
        {
            canvas.DrawRect(0, titleTop, width, titleHeight, fill);
        }

        var dividerTop = titleTop + titleHeight;
        fill.Color = WithAlpha(dividerColor, opacity);
        canvas.DrawRect(0, dividerTop, width, dividerHeight, fill);

        var descriptionTop = dividerTop + dividerHeight;
        fill.Color = WithAlpha(descriptionColor, opacity);
        canvas.DrawRect(0, descriptionTop, width, Math.Max(0, height - descriptionTop), fill);

        if (!string.IsNullOrWhiteSpace(card.Title) && titleReveal > 0)
        {
            canvas.Save();
            canvas.ClipRect(new SKRect(0, titleTop, width, titleTop + titleHeight * titleReveal), SKClipOperation.Intersect, false);
            using var title = TextPaint(project, (float)resource.Double("titleTextSize", 49), WithAlpha(ParseColor(resource.String("titleText", "#111111"), new SKColor(17, 17, 17)), opacity), true);
            DrawCentered(canvas, card.Title, width / 2f, titleTop + titleHeight * 0.69f, title, width - 24);
            canvas.Restore();
        }

        if (!string.IsNullOrWhiteSpace(card.Description) && descriptionReveal > 0)
        {
            canvas.Save();
            var revealBottom = descriptionTop + (height - descriptionTop) * descriptionReveal;
            canvas.ClipRect(new SKRect(0, descriptionTop, width, revealBottom), SKClipOperation.Intersect, false);
            DrawRelationshipsDescription(
                canvas,
                project,
                card.Description,
                new SKRect(16, descriptionTop + 10, width - 16, height - 10),
                WithAlpha(ParseColor(resource.String("descriptionText", "#f5f5f5"), new SKColor(245, 245, 245)), opacity),
                (float)resource.Double("descriptionTextSize", 28));
            canvas.Restore();
        }

        canvas.Restore();
    }

    private static void DrawRelationshipsCardRevealShine(
        SKCanvas canvas,
        float width,
        float imageHeight,
        float progress,
        float opacity,
        JsonElement resource)
    {
        var edgeY = imageHeight * Math.Clamp(progress, 0, 1);
        var tailHeight = (float)Math.Max(1, resource.Double("artworkRevealShineTail", 32));
        var coreHeight = (float)Math.Max(1, resource.Double("artworkRevealShineCore", 4));
        var coreAlpha = (float)Math.Clamp(resource.Double("artworkRevealShineCoreAlpha", 0.055), 0, 1);
        var tailAlpha = (float)Math.Clamp(resource.Double("artworkRevealShineTailAlpha", 0.050), 0, 1);
        var finalOpacity = Math.Clamp(opacity, 0, 1);

        // Measured from the reference: a narrow bright edge rides the artwork
        // reveal boundary, followed by a soft ~30 px tail over the unrevealed
        // dark card body. It spans the full card width rather than the badge.
        var colors = new[]
        {
            new SKColor(255, 255, 255, AlphaByte(finalOpacity * coreAlpha)),
            new SKColor(255, 255, 255, AlphaByte(finalOpacity * tailAlpha)),
            new SKColor(255, 255, 255, 0),
        };
        var stops = new[] { 0f, Math.Min(0.35f, coreHeight / (coreHeight + tailHeight)), 1f };
        using var shader = SKShader.CreateLinearGradient(
            new SKPoint(0, edgeY - coreHeight),
            new SKPoint(0, edgeY + tailHeight),
            colors,
            stops,
            SKShaderTileMode.Clamp);
        using var shine = new SKPaint
        {
            IsAntialias = false,
            Shader = shader,
            BlendMode = SKBlendMode.SrcOver,
        };
        canvas.DrawRect(0, edgeY - coreHeight, width, coreHeight + tailHeight, shine);
    }

    private void DrawRelationshipsDescription(SKCanvas canvas, StudioProject project, string text, SKRect box, SKColor color, float preferred)
    {
        if (string.IsNullOrWhiteSpace(text) || box.Width < 2 || box.Height < 2) return;
        using var paint = TextPaint(project, preferred, color, false);
        paint.TextAlign = SKTextAlign.Center;
        var size = preferred;
        List<string> lines = [];
        while (size >= 12)
        {
            paint.TextSize = size;
            lines = Wrap(text, paint, box.Width);
            if (lines.Count <= 4 && lines.Count * size * 1.08f <= box.Height) break;
            size -= 1;
        }
        paint.TextSize = size;
        var lineHeight = size * 1.08f;
        var total = lines.Take(4).Count() * lineHeight;
        var y = box.MidY - total / 2f + size;
        foreach (var line in lines.Take(4))
        {
            canvas.DrawText(line, box.MidX, y, paint);
            y += lineHeight;
        }
    }

    private void DrawV3RelationshipsBadge(
        SKCanvas canvas,
        StudioProject project,
        RendererObjectV3 obj,
        JsonElement resource,
        Dictionary<string, object?> props,
        float opacity)
    {
        var index = CardIndex(obj);
        if (index is null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];
        if (!project.ShowBadges || (string.IsNullOrWhiteSpace(card.Value) && string.IsNullOrWhiteSpace(card.BadgeHeader))) return;

        var pitch = (float)resource.Double("slotPitch", 480);
        var width = (float)resource.Double("cardWidth", 474);
        var cx = (float)resource.Double("centerX", 237);
        var cy = (float)resource.Double("centerY", 192);
        var rx = (float)resource.Double("radiusX", 176);
        var ry = (float)resource.Double("radiusY", 172);
        var scroll = (float)Number(Get(props, "scroll"), 0);
        var baseX = (float)Number(Get(props, "baseX"), index.Value * pitch);
        var offsetX = (float)Number(Get(props, "offsetX"), 0);
        var offsetY = (float)Number(Get(props, "offsetY"), 0);
        var scale = (float)Math.Clamp(Number(Get(props, "badgeScale", "scale"), 1), 0, 2.5);
        var textReveal = (float)Math.Clamp(Number(Get(props, "textReveal"), 1), 0, 1);
        var shineProgress = Number(Get(props, "shineProgress"), -1);
        var shineMode = StringValue(Get(props, "shineMode")) ?? resource.String("shineMode", "none");
        var x = baseX - scroll + offsetX;
        var scaledHalfWidth = rx * scale + 12;
        var badgeCenterX = x + cx;
        if (badgeCenterX + scaledHalfWidth < -4 || badgeCenterX - scaledHalfWidth > 1924) return;

        using var path = RelationshipsBadgePath(cx, cy, rx, ry);
        canvas.Save();
        canvas.Translate(x, offsetY);
        canvas.Scale(scale, scale, cx, cy);

        using (var shadow = new SKPaint
        {
            IsAntialias = true,
            Color = new SKColor(0, 0, 0, AlphaByte(opacity * 0.46)),
            ImageFilter = SKImageFilter.CreateBlur(7, 7),
        })
        {
            canvas.Save();
            canvas.Translate(5, 8);
            canvas.DrawPath(path, shadow);
            canvas.Restore();
        }

        using (var fill = new SKPaint
        {
            IsAntialias = true,
            Color = new SKColor(211, 15, 14, AlphaByte(opacity)),
        })
            canvas.DrawPath(path, fill);

        using (var border = new SKPaint
        {
            IsAntialias = true,
            Style = SKPaintStyle.Stroke,
            StrokeWidth = (float)resource.Double("borderWidth", 2),
            Color = WithAlpha(ParseColor(resource.String("borderColor", "#d1aa54"), new SKColor(209, 170, 84)), opacity),
        })
            canvas.DrawPath(path, border);

        if (textReveal > 0)
        {
            canvas.Save();
            var textTop = cy - ry + 16;
            var textBottom = cy + ry - 18;
            var revealTop = textBottom - (textBottom - textTop) * textReveal;
            canvas.ClipRect(new SKRect(cx - rx - 8, revealTop, cx + rx + 8, textBottom + 4), SKClipOperation.Intersect, false);
            DrawRelationshipsBadgeText(canvas, project, card, cx, cy, opacity);
            canvas.Restore();
        }

        if (shineProgress >= 0 && shineProgress <= 1)
            DrawRelationshipsBadgeShine(canvas, path, cx, cy, rx, ry, (float)shineProgress, shineMode, opacity);

        canvas.Restore();
    }

    private static SKPath RelationshipsBadgePath(float cx, float cy, float rx, float ry)
    {
        var shoulder = rx * 0.50f;
        var cut = ry * 0.50f;
        var points = new[]
        {
            new SKPoint(cx - shoulder, cy - ry),
            new SKPoint(cx + shoulder, cy - ry),
            new SKPoint(cx + rx, cy - cut),
            new SKPoint(cx + rx, cy + cut),
            new SKPoint(cx + shoulder, cy + ry),
            new SKPoint(cx - shoulder, cy + ry),
            new SKPoint(cx - rx, cy + cut),
            new SKPoint(cx - rx, cy - cut),
        };
        var path = new SKPath();
        path.MoveTo(points[0]);
        foreach (var point in points.Skip(1)) path.LineTo(point);
        path.Close();
        return path;
    }

    private void DrawRelationshipsBadgeText(SKCanvas canvas, StudioProject project, StudioCard card, float cx, float cy, float opacity)
    {
        var words = Regex.Split(card.Value.Trim(), "\\s+").Where(x => x.Length > 0).ToArray();
        var primary = words.FirstOrDefault() ?? "";
        var unit = words.Length > 1 ? string.Join(' ', words.Skip(1)) : "People";
        var header = string.IsNullOrWhiteSpace(card.BadgeHeader) ? "1 in" : card.BadgeHeader.Trim();

        void DrawLine(string value, float y, float size, float maxWidth)
        {
            if (string.IsNullOrWhiteSpace(value)) return;
            using var shadow = TextPaint(project, size, new SKColor(0, 0, 0, AlphaByte(opacity * 0.72)), false);
            DrawCentered(canvas, value, cx + 2, y + 3, shadow, maxWidth);
            using var text = TextPaint(project, size, new SKColor(255, 255, 255, AlphaByte(opacity)), false);
            DrawCentered(canvas, value, cx, y, text, maxWidth);
        }

        DrawLine(header, cy - 95, 39, 230);
        DrawLine(primary, cy + 30, 91, 285);
        DrawLine(unit, cy + 91, 39, 250);
    }

    private static void DrawRelationshipsBadgeShine(
        SKCanvas canvas,
        SKPath path,
        float cx,
        float cy,
        float rx,
        float ry,
        float progress,
        string mode,
        float opacity)
    {
        canvas.Save();
        canvas.ClipPath(path, SKClipOperation.Intersect, true);

        if (mode.Equals("opening", StringComparison.OrdinalIgnoreCase))
        {
            var y = Lerp(cy + ry + 62, cy - ry - 62, progress);
            var alpha = AlphaByte(opacity * 0.86);
            var colors = new[]
            {
                new SKColor(255, 255, 255, 0),
                new SKColor(255, 255, 255, (byte)(alpha * 0.38)),
                new SKColor(255, 255, 255, alpha),
                new SKColor(255, 255, 255, (byte)(alpha * 0.38)),
                new SKColor(255, 255, 255, 0),
            };
            var stops = new[] { 0f, 0.24f, 0.50f, 0.76f, 1f };
            using var shader = SKShader.CreateLinearGradient(
                new SKPoint(0, y - 66),
                new SKPoint(0, y + 66),
                colors,
                stops,
                SKShaderTileMode.Clamp);
            using var shine = new SKPaint { IsAntialias = true, Shader = shader };
            canvas.DrawRect(cx - rx - 30, y - 70, rx * 2 + 60, 140, shine);
        }
        else
        {
            canvas.RotateDegrees(-22, cx, cy);
            var x = Lerp(cx - rx - 92, cx + rx + 92, progress);
            var alpha = AlphaByte(opacity * 0.78);
            var colors = new[]
            {
                new SKColor(255, 255, 255, 0),
                new SKColor(255, 255, 255, (byte)(alpha * 0.30)),
                new SKColor(255, 255, 255, alpha),
                new SKColor(255, 255, 255, (byte)(alpha * 0.30)),
                new SKColor(255, 255, 255, 0),
            };
            var stops = new[] { 0f, 0.22f, 0.50f, 0.78f, 1f };
            using var shader = SKShader.CreateLinearGradient(
                new SKPoint(x - 54, 0),
                new SKPoint(x + 54, 0),
                colors,
                stops,
                SKShaderTileMode.Clamp);
            using var shine = new SKPaint { IsAntialias = true, Shader = shader };
            canvas.DrawRect(x - 58, cy - ry - 100, 116, ry * 2 + 200, shine);
        }

        canvas.Restore();
    }

    private void DrawV3SmartCardAnimation(
        SKCanvas canvas,
        StudioProject project,
        RendererSpec spec,
        RendererObjectV3 obj,
        JsonElement resource,
        Dictionary<string, object?> props,
        int frame,
        float opacity)
    {
        var index = CardIndex(obj);
        if (index is null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];

        var sequenceRoot = StringValue(Get(props, "sequenceRoot")) ?? resource.String("sequenceRoot", "");
        if (string.IsNullOrWhiteSpace(sequenceRoot)) return;

        SmartBadgeSequenceDefinition sequence;
        try
        {
            sequence = SmartBadgeSequence.Load(spec.SceneV3!, sequenceRoot);
        }
        catch
        {
            return;
        }

        var explicitFrame = Get(props, "sequenceFrame");
        var frameLocked = Truthy(
            Get(props, "frameLock", "frameLocked"),
            resource.Bool("frameLock", resource.Bool("frameLocked", true)));

        int sequenceFrame;
        if (explicitFrame is not null)
        {
            sequenceFrame = (int)Math.Round(Number(explicitFrame), MidpointRounding.AwayFromZero);
        }
        else if (frameLocked && sequence.Fps == spec.ReferenceFps)
        {
            sequenceFrame = frame - obj.Frame;
        }
        else
        {
            sequenceFrame = (int)Math.Floor(
                (frame - obj.Frame) * sequence.Fps / (double)Math.Max(1, spec.ReferenceFps));
        }

        sequenceFrame += (int)Math.Round(
            Number(Get(props, "sequenceOffset"), resource.Int("sequenceOffset", 0)),
            MidpointRounding.AwayFromZero);

        var selected = sequence.SelectFrame(sequenceFrame);
        if (selected is null) return;

        var plate = DecodeSequenceBitmap(sequence, selected.Asset);
        if (plate is null) return;

        var pitch = (float)resource.Double("slotPitch", 480);
        var scroll = (float)Number(Get(props, "scroll"), 0);
        var baseX = (float)Number(Get(props, "baseX"), index.Value * pitch);
        var offsetX = (float)Number(Get(props, "offsetX"), 0);
        var offsetY = (float)Number(Get(props, "offsetY"), 0);
        var localX = (float)Number(Get(props, "drawX"), resource.Double("drawX", 0));
        var localY = (float)Number(Get(props, "drawY"), resource.Double("drawY", 0));
        var drawWidth = (float)Number(Get(props, "drawWidth"), resource.Double("drawWidth", sequence.Width));
        var drawHeight = (float)Number(Get(props, "drawHeight"), resource.Double("drawHeight", sequence.Height));
        if (drawWidth <= 0 || drawHeight <= 0) return;

        var x = baseX - scroll + offsetX;
        if (x + localX + drawWidth < -4 || x + localX > spec.ReferenceWidth + 4) return;

        canvas.Save();
        canvas.Translate(x + localX, offsetY + localY);
        canvas.Scale(drawWidth / Math.Max(1, sequence.Width), drawHeight / Math.Max(1, sequence.Height));

        // SmartCard compositing order is strict:
        //   1. base/card-shell plate
        //   2. live project artwork
        //   3. live jsparse title/description fields
        //   4. overlay/glass/shine plate
        // Keeping the shine last is what makes it travel across both the artwork
        // and the live text exactly like the source instead of sitting underneath.
        using (var platePaint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = FilterQuality(
                StringValue(Get(props, "sampling", "filterMode")) ?? resource.String("sampling", "high")),
            Color = new SKColor(255, 255, 255, AlphaByte(opacity)),
            BlendMode = BlendMode(Get(props, "blendMode", "material.blend")),
        })
        {
            canvas.DrawBitmap(
                plate,
                new SKRect(0, 0, sequence.Width, sequence.Height),
                platePaint);
        }

        if (sequence.Artwork is not null && !string.IsNullOrWhiteSpace(card.Image))
        {
            var dest = sequence.Artwork.DestAt(selected.TemplateFrame);
            var clip = sequence.Artwork.ClipAt(selected.TemplateFrame);
            var alpha = Math.Clamp(sequence.Artwork.AlphaAt(selected.TemplateFrame) * opacity, 0, 1);
            if (dest is not null && clip is not null && alpha > 0.0001f)
            {
                canvas.Save();
                canvas.ClipRect(
                    new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom),
                    SKClipOperation.Intersect,
                    false);

                if (alpha < 0.9999f)
                {
                    using var layer = new SKPaint
                    {
                        Color = new SKColor(255, 255, 255, AlphaByte(alpha)),
                    };
                    canvas.SaveLayer(layer);
                    DrawImageCover(
                        canvas,
                        card,
                        new SKRect(dest.X, dest.Y, dest.Right, dest.Bottom),
                        new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom));
                    canvas.Restore();
                }
                else
                {
                    DrawImageCover(
                        canvas,
                        card,
                        new SKRect(dest.X, dest.Y, dest.Right, dest.Bottom),
                        new SKRect(clip.X, clip.Y, clip.Right, clip.Bottom));
                }

                canvas.Restore();
            }
        }

        foreach (var field in sequence.Fields)
            DrawSmartBadgeField(canvas, project, card, field, selected.TemplateFrame, opacity);

        DrawSmartSequenceOverlay(canvas, sequence, selected.TemplateFrame, opacity);

        canvas.Restore();
    }

    private void DrawV3SmartBadgeAnimation(
        SKCanvas canvas,
        StudioProject project,
        RendererSpec spec,
        RendererObjectV3 obj,
        JsonElement resource,
        Dictionary<string, object?> props,
        int frame,
        float opacity)
    {
        var index = CardIndex(obj);
        if (index is null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];
        if (!project.ShowBadges || (string.IsNullOrWhiteSpace(card.Value) && string.IsNullOrWhiteSpace(card.BadgeHeader))) return;

        var sequenceRoot = StringValue(Get(props, "sequenceRoot")) ?? resource.String("sequenceRoot", "");
        if (string.IsNullOrWhiteSpace(sequenceRoot)) return;

        SmartBadgeSequenceDefinition sequence;
        try
        {
            sequence = SmartBadgeSequence.Load(spec.SceneV3!, sequenceRoot);
        }
        catch
        {
            return;
        }

        var explicitFrame = Get(props, "sequenceFrame");
        var frameLocked = Truthy(
            Get(props, "frameLock", "frameLocked"),
            resource.Bool("frameLock", resource.Bool("frameLocked", true)));
        int sequenceFrame;
        if (explicitFrame is not null)
        {
            sequenceFrame = (int)Math.Round(Number(explicitFrame), MidpointRounding.AwayFromZero);
        }
        else if (frameLocked && sequence.Fps == spec.ReferenceFps)
        {
            // Source-exact mode: one renderer frame selects exactly one sequence frame.
            // No timer interpolation, resampling, or skipped frame indexes.
            sequenceFrame = frame - obj.Frame;
        }
        else
        {
            sequenceFrame = (int)Math.Floor(
                (frame - obj.Frame) * sequence.Fps / (double)Math.Max(1, spec.ReferenceFps));
        }
        sequenceFrame += (int)Math.Round(
            Number(Get(props, "sequenceOffset"), resource.Int("sequenceOffset", 0)),
            MidpointRounding.AwayFromZero);

        var settledHold = Truthy(
            Get(props, "settledHold"),
            resource.Bool("settledHold", false));
        var selected = sequence.SelectFrame(sequenceFrame);
        if (selected is null && settledHold)
            selected = sequence.FinalFrame();
        if (selected is null) return;
        var bitmap = DecodeSequenceBitmap(sequence, selected.Asset);
        if (bitmap is null) return;

        var drawX = (float)Number(Get(props, "drawX"), resource.Double("drawX", 0));
        var drawY = (float)Number(Get(props, "drawY"), resource.Double("drawY", 0));
        var drawWidth = (float)Number(Get(props, "drawWidth"), resource.Double("drawWidth", sequence.Width));
        var drawHeight = (float)Number(Get(props, "drawHeight"), resource.Double("drawHeight", sequence.Height));
        if (drawWidth <= 0 || drawHeight <= 0) return;

        var entryMotion = StringValue(Get(props, "entryMotion")) ?? resource.String("entryMotion", "");
        var entryAnchor = StringValue(Get(props, "entryAnchor")) ?? resource.String("entryAnchor", "");
        var finalXCorrection = 0f;
        var lockFinalX =
            entryAnchor.Equals("final-x", StringComparison.OrdinalIgnoreCase) ||
            entryMotion.Equals("top-to-final", StringComparison.OrdinalIgnoreCase);
        if (lockFinalX)
        {
            var final = sequence.FinalFrame();
            if (final is not null)
            {
                var finalBitmap = DecodeSequenceBitmap(sequence, final.Asset);
                if (finalBitmap is not null)
                {
                    var currentBounds = SequenceOpaqueBounds(sequence, selected.Asset, bitmap);
                    var finalBounds = SequenceOpaqueBounds(sequence, final.Asset, finalBitmap);
                    if (currentBounds is SKRect current && finalBounds is SKRect target)
                        finalXCorrection = target.MidX - current.MidX;
                }
            }
        }

        var scaledXCorrection = finalXCorrection * drawWidth / Math.Max(1, sequence.Width);
        using (var paint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = FilterQuality(StringValue(Get(props, "sampling", "filterMode")) ?? resource.String("sampling", "high")),
            Color = new SKColor(255, 255, 255, AlphaByte(opacity)),
            BlendMode = BlendMode(Get(props, "blendMode", "material.blend")),
        })
        {
            canvas.DrawBitmap(
                bitmap,
                new SKRect(
                    drawX + scaledXCorrection,
                    drawY,
                    drawX + scaledXCorrection + drawWidth,
                    drawY + drawHeight),
                paint);
        }

        canvas.Save();
        canvas.Translate(drawX + scaledXCorrection, drawY);
        canvas.Scale(drawWidth / Math.Max(1, sequence.Width), drawHeight / Math.Max(1, sequence.Height));
        foreach (var field in sequence.Fields)
            DrawSmartBadgeField(canvas, project, card, field, selected.TemplateFrame, opacity);
        DrawSmartSequenceOverlay(canvas, sequence, selected.TemplateFrame, opacity);
        canvas.Restore();
    }

    private void DrawSmartSequenceOverlay(
        SKCanvas canvas,
        SmartBadgeSequenceDefinition sequence,
        int templateFrame,
        float opacity)
    {
        var overlayAsset = sequence.OverlayAssetAt(templateFrame);
        if (string.IsNullOrWhiteSpace(overlayAsset)) return;
        var overlay = DecodeSequenceBitmap(sequence, overlayAsset);
        if (overlay is null) return;

        using var paint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = SKFilterQuality.High,
            Color = new SKColor(255, 255, 255, AlphaByte(opacity)),
            BlendMode = SKBlendMode.SrcOver,
        };
        canvas.DrawBitmap(
            overlay,
            new SKRect(0, 0, sequence.Width, sequence.Height),
            paint);
    }

    private void DrawSmartBadgeField(
        SKCanvas canvas,
        StudioProject project,
        StudioCard card,
        SmartBadgeFieldDefinition field,
        int templateFrame,
        float parentOpacity)
    {
        var rect = field.RectAt(templateFrame);
        if (rect is null) return;
        var fieldOpacity = Math.Clamp(field.AlphaAt(templateFrame) * parentOpacity, 0, 1);
        if (fieldOpacity <= 0.0001f) return;

        var text = SmartBadgeFieldValue(card, field.Source);
        if (string.IsNullOrWhiteSpace(text)) return;

        var color = WithAlpha(ParseColor(field.Color, SKColors.White), fieldOpacity);

        if (field.Source.Trim().Equals("description", StringComparison.OrdinalIgnoreCase))
        {
            canvas.Save();
            var descriptionRotation = field.RotationAt(templateFrame);
            if (Math.Abs(descriptionRotation) > 0.001f)
                canvas.RotateDegrees(descriptionRotation, rect.MidX, rect.MidY);

            DrawRelationshipsDescription(
                canvas,
                project,
                text,
                new SKRect(rect.X, rect.Y, rect.Right, rect.Bottom),
                color,
                field.FontSize);
            canvas.Restore();
            return;
        }
        using var paint = TextPaint(project, field.FontSize, color, field.Bold);
        paint.TextAlign = field.Align.Trim().ToLowerInvariant() switch
        {
            "left" => SKTextAlign.Left,
            "right" => SKTextAlign.Right,
            _ => SKTextAlign.Center,
        };

        var size = field.FontSize;
        while (size > field.MinFontSize)
        {
            paint.TextSize = size;
            var metrics = paint.FontMetrics;
            var textHeight = metrics.Descent - metrics.Ascent;
            if (paint.MeasureText(text) <= rect.Width && textHeight <= rect.Height)
                break;
            size = Math.Max(field.MinFontSize, size - 1);
        }
        paint.TextSize = size;

        var fontMetrics = paint.FontMetrics;
        var x = paint.TextAlign switch
        {
            SKTextAlign.Left => rect.X,
            SKTextAlign.Right => rect.Right,
            _ => rect.MidX,
        };
        var y = field.VerticalAlign.Trim().ToLowerInvariant() switch
        {
            "top" => rect.Y - fontMetrics.Ascent,
            "bottom" => rect.Bottom - fontMetrics.Descent,
            _ => rect.MidY - (fontMetrics.Ascent + fontMetrics.Descent) / 2f,
        };

        canvas.Save();
        var rotation = field.RotationAt(templateFrame);
        if (Math.Abs(rotation) > 0.001f)
            canvas.RotateDegrees(rotation, rect.MidX, rect.MidY);

        if (field.Shadow)
        {
            using var shadow = TextPaint(project, size, WithAlpha(ParseColor(field.ShadowColor, new SKColor(0, 0, 0, 170)), fieldOpacity), field.Bold);
            shadow.TextAlign = paint.TextAlign;
            if (field.ShadowBlur > 0)
                shadow.ImageFilter = SKImageFilter.CreateBlur(field.ShadowBlur, field.ShadowBlur);
            canvas.DrawText(text, x + field.ShadowX, y + field.ShadowY, shadow);
        }

        if (field.StrokeWidth > 0)
        {
            using var stroke = TextPaint(project, size, WithAlpha(ParseColor(field.StrokeColor, SKColors.Transparent), fieldOpacity), field.Bold);
            stroke.TextAlign = paint.TextAlign;
            stroke.Style = SKPaintStyle.Stroke;
            stroke.StrokeWidth = field.StrokeWidth;
            stroke.StrokeJoin = SKStrokeJoin.Round;
            canvas.DrawText(text, x, y, stroke);
        }

        canvas.DrawText(text, x, y, paint);
        canvas.Restore();
    }

    private static string SmartBadgeFieldValue(StudioCard card, string source)
    {
        var words = Regex.Split(card.Value.Trim(), "\\s+", RegexOptions.CultureInvariant)
            .Where(value => value.Length > 0)
            .ToArray();
        var primary = words.FirstOrDefault() ?? "";
        var unit = words.Length > 1 ? string.Join(' ', words.Skip(1)) : "People";

        return source.Trim().ToLowerInvariant() switch
        {
            "header" or "badgeheader" or "badge-header" =>
                string.IsNullOrWhiteSpace(card.BadgeHeader) ? "1 in" : card.BadgeHeader.Trim(),
            "value" or "primary" or "number" => primary,
            "unit" or "suffix" => unit,
            "fullvalue" or "full-value" or "raw" => card.Value.Trim(),
            "title" => card.Title.Trim(),
            "description" or "desc" => card.Description.Trim(),
            _ => source.Equals("jsparse", StringComparison.Ordinal) ? primary : primary,
        };
    }

    private void DrawV3ProjectCard(SKCanvas canvas, StudioProject project, RendererObjectV3 obj, JsonElement resource, float opacity)
    {
        var index = CardIndex(obj); if (index == null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];
        var width = (float)resource.Double("width", 470); var height = (float)resource.Double("height", 1080);
        var top = (float)resource.Double("topFieldHeight", 476); var titleH = string.IsNullOrWhiteSpace(card.Title) ? 0 : (float)resource.Double("titleHeight", 101);
        using var paint = new SKPaint { IsAntialias = true, Color = WithAlpha(ParseColor(resource.String("topBackground", "#1d1d1d"), new SKColor(29,29,29)), opacity) };
        canvas.DrawRect(0, 0, width, top, paint);
        var cursor = top;
        if (titleH > 0)
        {
            paint.Color = WithAlpha(ParseColor(resource.String("titleBackground", "#d8d6d0"), new SKColor(216,214,208)), opacity);
            canvas.DrawRect(0, cursor, width, titleH, paint);
            DrawFitText(canvas, project, card.Title, new SKRect(12, cursor + 4, width - 12, cursor + titleH - 4), WithAlpha(ParseColor(resource.String("titleText", "#111111"), new SKColor(17,17,17)), opacity), (float)resource.Double("titleTextSize", 31), true, 2);
            cursor += titleH;
        }
        paint.Color = WithAlpha(ParseColor(resource.String("descriptionBackground", "#6c6760"), new SKColor(108,103,96)), opacity);
        canvas.DrawRect(0, cursor, width, Math.Max(0, height - cursor), paint);
        var descH = string.IsNullOrWhiteSpace(card.Description) ? 0 : Math.Min(165, (height - cursor) * 0.34f);
        if (descH > 0) DrawFitText(canvas, project, card.Description, new SKRect(14, cursor + 8, width - 14, cursor + descH - 5), WithAlpha(ParseColor(resource.String("descriptionText", "#e6e3dd"), new SKColor(230,227,221)), opacity), (float)resource.Double("descriptionTextSize", 23), false, 4);
        if (!string.IsNullOrWhiteSpace(card.Image)) DrawImageContain(canvas, card, new SKRect(16, cursor + descH + 8, width - 16, height - 16), opacity);
    }

    private void DrawV3ProjectBadgeText(SKCanvas canvas, StudioProject project, RendererObjectV3 obj, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var index = CardIndex(obj); if (index == null || index < 0 || index >= project.Cards.Count) return;
        var card = project.Cards[index.Value];
        var width = (float)Number(Get(props, "width"), resource.Double("width", 477));
        var height = (float)Number(Get(props, "height"), resource.Double("height", 420));
        var x = (float)Number(Get(props, "x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y"), resource.Double("y", 0));
        using var paint = TextPaint(project, 58, WithAlpha(SKColors.White, opacity), true);
        var center = x + width / 2;
        if (!string.IsNullOrWhiteSpace(card.BadgeHeader)) { paint.TextSize = 24; DrawCentered(canvas, card.BadgeHeader, center, y + height * 0.41f, paint, width * 0.56f); }
        var words = Regex.Split(card.Value.Trim(), "\\s+").Where(v => v.Length > 0).ToArray();
        paint.TextSize = 58; DrawCentered(canvas, words.FirstOrDefault() ?? "", center, y + height * 0.60f, paint, width * 0.56f);
        if (words.Length > 1) { paint.TextSize = 28; DrawCentered(canvas, string.Join(' ', words.Skip(1)), center, y + height * 0.72f, paint, width * 0.56f); }
    }

    private void DrawV3Rect(SKCanvas canvas, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var x = (float)Number(Get(props, "x", "geometry.x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y", "geometry.y"), resource.Double("y", 0));
        var w = (float)Number(Get(props, "width", "geometry.width"), resource.Double("width", 0)); var h = (float)Number(Get(props, "height", "geometry.height"), resource.Double("height", 0));
        using var paint = V3Paint(resource, props, opacity); var radius = (float)Number(Get(props, "radius", "cornerRadius"), resource.Double("radius", 0));
        if (radius > 0) canvas.DrawRoundRect(new SKRect(x, y, x + w, y + h), radius, radius, paint); else canvas.DrawRect(x, y, w, h, paint);
    }
    private void DrawV3Ellipse(SKCanvas canvas, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var x = (float)Number(Get(props, "x", "geometry.x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y", "geometry.y"), resource.Double("y", 0));
        var w = (float)Number(Get(props, "width", "geometry.width"), resource.Double("width", 0)); var h = (float)Number(Get(props, "height", "geometry.height"), resource.Double("height", 0));
        using var paint = V3Paint(resource, props, opacity); canvas.DrawOval(new SKRect(x, y, x + w, y + h), paint);
    }
    private void DrawV3Polygon(SKCanvas canvas, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var points = Points(Get(props, "geometry.points", "points")) ?? (resource.ValueKind == JsonValueKind.Object && resource.TryGetProperty("points", out var p) ? Points(p) : null);
        if (points == null || points.Count < 3) return;
        using var path = new SKPath(); path.MoveTo(points[0]); foreach (var point in points.Skip(1)) path.LineTo(point); path.Close();
        using var paint = V3Paint(resource, props, opacity); canvas.DrawPath(path, paint);
    }
    private void DrawV3Image(SKCanvas canvas, RendererSceneV3 scene, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var source = StringValue(Get(props, "source", "asset", "relativeAsset")) ?? resource.String("source", resource.String("asset", resource.String("relativeAsset", "")));
        var bitmap = DecodeSceneBitmap(scene, source); if (bitmap == null) return;

        var srcLeft = (float)Number(Get(props, "source.left", "crop.left"), resource.Double("sourceLeft", 0));
        var srcTop = (float)Number(Get(props, "source.top", "crop.top"), resource.Double("sourceTop", 0));
        var srcRight = (float)Number(Get(props, "source.right", "crop.right"), resource.Double("sourceRight", bitmap.Width));
        var srcBottom = (float)Number(Get(props, "source.bottom", "crop.bottom"), resource.Double("sourceBottom", bitmap.Height));
        srcLeft = Math.Clamp(srcLeft, 0, bitmap.Width);
        srcTop = Math.Clamp(srcTop, 0, bitmap.Height);
        srcRight = Math.Clamp(srcRight, srcLeft, bitmap.Width);
        srcBottom = Math.Clamp(srcBottom, srcTop, bitmap.Height);
        var src = new SKRect(srcLeft, srcTop, srcRight, srcBottom);
        if (src.Width <= 0 || src.Height <= 0) return;

        var x = (float)Number(Get(props, "x"), resource.Double("x", 0));
        var y = (float)Number(Get(props, "y"), resource.Double("y", 0));
        var w = (float)Number(Get(props, "width"), resource.Double("width", src.Width));
        var h = (float)Number(Get(props, "height"), resource.Double("height", src.Height));
        var snap = Truthy(Get(props, "pixelSnap", "transform.pixelSnap"), resource.Bool("pixelSnap", false));
        if (snap)
        {
            var right = MathF.Round(x + w);
            var bottom = MathF.Round(y + h);
            x = MathF.Round(x);
            y = MathF.Round(y);
            w = right - x;
            h = bottom - y;
        }

        var filter = FilterQuality(StringValue(Get(props, "sampling", "filterMode")) ?? resource.String("sampling", "high"));
        using var paint = new SKPaint
        {
            IsAntialias = filter != SKFilterQuality.None,
            FilterQuality = filter,
            Color = new SKColor(255, 255, 255, AlphaByte(opacity)),
            BlendMode = BlendMode(Get(props, "blendMode", "material.blend")),
        };
        canvas.DrawBitmap(bitmap, src, new SKRect(x, y, x + w, y + h), paint);
    }
    private void DrawV3Raster(SKCanvas canvas, RendererSceneV3 scene, JsonElement resource, Dictionary<string, object?> props, float opacity) => DrawV3Image(canvas, scene, resource, props, opacity);
    private void DrawV3Text(SKCanvas canvas, StudioProject project, JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var text = StringValue(Get(props, "text", "value")) ?? resource.String("text", "");
        var x = (float)Number(Get(props, "x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y"), resource.Double("y", 0));
        var size = (float)Number(Get(props, "size", "textSize"), resource.Double("size", 32));
        var color = WithAlpha(ParseColor(StringValue(Get(props, "color", "material.color")) ?? resource.String("color", "#ffffff"), SKColors.White), opacity);
        using var paint = TextPaint(project, size, color, Truthy(Get(props, "bold"), resource.Bool("bold", false))); canvas.DrawText(text, x, y, paint);
    }
    private void DrawV3Outro(SKCanvas canvas, RendererSceneV3 scene, JsonElement resource, Dictionary<string, object?> props, int frame, float opacity)
    {
        var start = (int)Number(Get(props, "startFrame"), resource.Int("startFrame", -1)); var end = (int)Number(Get(props, "endFrame"), resource.Int("endFrame", -1));
        if (frame < start || frame > end) return; var local = frame - start; string? asset = null;
        if (resource.TryGetProperty("frames", out var frames))
        {
            if (frames.ValueKind == JsonValueKind.Object && (frames.TryGetProperty(frame.ToString(CultureInfo.InvariantCulture), out var a) || frames.TryGetProperty(local.ToString(CultureInfo.InvariantCulture), out a))) asset = a.GetString();
            else if (frames.ValueKind == JsonValueKind.Array && local >= 0 && local < frames.GetArrayLength()) asset = frames[local].GetString();
        }
        asset ??= resource.String("assetPattern", "").Replace("{frame}", frame.ToString(CultureInfo.InvariantCulture)).Replace("{local}", local.ToString(CultureInfo.InvariantCulture));
        var bitmap = DecodeSceneBitmap(scene, asset ?? ""); if (bitmap == null) return;
        using var paint = new SKPaint { Color = new SKColor(255, 255, 255, AlphaByte(opacity)), FilterQuality = FilterQuality(StringValue(Get(props, "sampling", "filterMode")) ?? resource.String("sampling", "high")) };
        var x = (float)Number(Get(props, "x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y"), resource.Double("y", 0));
        var w = (float)Number(Get(props, "width"), resource.Double("width", bitmap.Width)); var h = (float)Number(Get(props, "height"), resource.Double("height", bitmap.Height));
        canvas.DrawBitmap(bitmap, new SKRect(x, y, x + w, y + h), paint);
    }
    private void DrawV3IndependentShadow(SKCanvas canvas, StudioProject project, RendererSpec spec, JsonElement resource, Dictionary<string, object?> props, int frame, float opacity)
    {
        var targetId = StringValue(Get(props, "target", "shadow.target")) ?? resource.String("target", resource.String("sourceObject", ""));
        var target = spec.SceneV3!.Objects.FirstOrDefault(x => x.Id == targetId); if (target == null) return;
        var targetProps = V3Evaluator.Properties(spec.SceneV3!, target, frame); spec.SceneV3.Resources.TryGetValue(target.Resource ?? "", out var targetResource);
        var points = Points(Get(targetProps, "geometry.points", "points")); if (points == null || points.Count < 3) return;
        using var path = new SKPath(); path.MoveTo(points[0]); foreach (var point in points.Skip(1)) path.LineTo(point); path.Close();
        var blur = (float)Number(Get(props, "blur", "shadow.blur"), resource.Double("blur", 0)); var dx = (float)Number(Get(props, "offsetX", "shadow.offsetX"), resource.Double("offsetX", 0)); var dy = (float)Number(Get(props, "offsetY", "shadow.offsetY"), resource.Double("offsetY", 0));
        using var paint = new SKPaint { IsAntialias = true, Color = WithAlpha(ParseColor(resource.String("color", "#000000"), SKColors.Black), opacity), ImageFilter = blur > 0 ? SKImageFilter.CreateBlur(blur, blur) : null };
        canvas.Save(); ApplyTransform(canvas, targetProps); canvas.Translate(dx, dy); canvas.DrawPath(path, paint); canvas.Restore();
    }
    private void DrawV3Group(SKCanvas canvas, StudioProject project, RendererSpec spec, RendererObjectV3 obj, JsonElement resource, Dictionary<string, object?> props, int frame, float opacity)
    {
        if (!resource.TryGetProperty("children", out var children) || children.ValueKind != JsonValueKind.Array) return;
        foreach (var child in children.EnumerateArray())
        {
            var id = child.GetString(); if (id == null || !spec.SceneV3!.Resources.TryGetValue(id, out var childRes)) continue;
            var type = childRes.String("type", "custom"); var faux = new RendererObjectV3 { Id = obj.Id + "/" + id, Kind = type, Frame = obj.Frame, LifespanStart = obj.LifespanStart, LifespanEnd = obj.LifespanEnd, Properties = childRes.TryGetProperty("properties", out var cp) ? cp.Clone() : EmptyJson(), Raw = obj.Raw };
            DrawV3Object(canvas, project, spec, faux, V3Evaluator.Flatten(childRes.TryGetProperty("properties", out cp) ? cp : EmptyJson()).ToDictionary(x => x.Key, x => (object?)x.Value), frame);
        }
    }

    private void DrawV3EndFade(SKCanvas canvas, StudioProject project, RendererSpec spec, int frame)
    {
        if (!spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal)) return;
        var fade = spec.SceneV3!.Objects.FirstOrDefault(x => x.Kind == "fade"); if (fade == null) return;
        var total = FrameCount(project, spec); var length = Math.Max(1, fade.LifespanEnd - fade.LifespanStart + 1); var start = Math.Max(0, total - length); if (frame < start || frame >= total) return;
        var sourceFrame = fade.LifespanStart + (frame - start); var props = V3Evaluator.Properties(spec.SceneV3!, fade, sourceFrame); var opacity = Math.Clamp(Number(Get(props, "opacity"), 0), 0, 1);
        using var paint = new SKPaint { Color = new SKColor(0, 0, 0, AlphaByte(opacity)) }; canvas.DrawRect(0, 0, spec.ReferenceWidth, spec.ReferenceHeight, paint);
    }

    private SKPaint V3Paint(JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var color = ParseColor(StringValue(Get(props, "color", "fill", "material.color")) ?? resource.String("color", resource.String("fill", "#ffffff")), SKColors.White);
        var stroke = Truthy(Get(props, "stroke"), false) || resource.String("style", "fill") == "stroke";
        var blur = (float)Number(Get(props, "blur", "filter.blur"), resource.Double("blur", 0));
        return new SKPaint
        {
            IsAntialias = Truthy(Get(props, "antialias", "geometry.antialias"), true),
            Style = stroke ? SKPaintStyle.Stroke : SKPaintStyle.Fill,
            StrokeWidth = (float)Number(Get(props, "strokeWidth"), resource.Double("strokeWidth", 1)),
            Color = new SKColor(color.Red, color.Green, color.Blue, AlphaByte(opacity * (color.Alpha / 255f))),
            BlendMode = BlendMode(Get(props, "blendMode", "material.blend")),
            ImageFilter = blur > 0 ? SKImageFilter.CreateBlur(blur, blur) : null,
        };
    }

    private void ApplyTransform(SKCanvas canvas, Dictionary<string, object?> props)
    {
        if (props.ContainsKey("matrix.m00") || props.ContainsKey("m00"))
        {
            var tx = (float)Number(Get(props, "matrix.tx", "tx"), 0);
            var ty = (float)Number(Get(props, "matrix.ty", "ty"), 0);
            if (Truthy(Get(props, "pixelSnap", "transform.pixelSnap"), false))
            {
                tx = MathF.Round(tx);
                ty = MathF.Round(ty);
            }
            var m = new SKMatrix { ScaleX = (float)Number(Get(props, "matrix.m00", "m00"), 1), SkewX = (float)Number(Get(props, "matrix.m01", "m01"), 0), TransX = tx, SkewY = (float)Number(Get(props, "matrix.m10", "m10"), 0), ScaleY = (float)Number(Get(props, "matrix.m11", "m11"), 1), TransY = ty, Persp2 = 1 };
            canvas.Concat(ref m); return;
        }
        var x = (float)Number(Get(props, "x", "transform.x", "translateX"), 0); var y = (float)Number(Get(props, "y", "transform.y", "translateY"), 0);
        if (Truthy(Get(props, "pixelSnap", "transform.pixelSnap"), false))
        {
            x = MathF.Round(x);
            y = MathF.Round(y);
        }
        var sx = (float)Number(Get(props, "scaleX", "transform.scaleX", "scale"), 1); var sy = (float)Number(Get(props, "scaleY", "transform.scaleY", "scale"), 1); var rotation = (float)Number(Get(props, "rotation", "transform.rotation"), 0);
        canvas.Translate(x, y); if (rotation != 0) canvas.RotateDegrees(rotation); if (sx != 1 || sy != 1) canvas.Scale(sx, sy);
    }
    private void ApplyClip(SKCanvas canvas, Dictionary<string, object?> props)
    {
        var antialias = Truthy(Get(props, "clip.antialias", "mask.antialias"), false);
        var points = Points(Get(props, "clip.points", "mask.points")); if (points != null && points.Count >= 3) { using var path = new SKPath(); path.MoveTo(points[0]); foreach (var p in points.Skip(1)) path.LineTo(p); path.Close(); canvas.ClipPath(path, SKClipOperation.Intersect, antialias); return; }
        if (Get(props, "clip.left") is not null || Get(props, "clip.right") is not null)
        {
            var left = (float)Number(Get(props, "clip.left"), 0); var top = (float)Number(Get(props, "clip.top"), 0); var right = (float)Number(Get(props, "clip.right"), 1920); var bottom = (float)Number(Get(props, "clip.bottom"), 1080); canvas.ClipRect(new SKRect(left, top, right, bottom), SKClipOperation.Intersect, antialias);
        }
    }

    private static int ObjectZIndex(RendererObjectV3 obj)
    {
        if (obj.Properties.ValueKind == JsonValueKind.Object &&
            obj.Properties.TryGetProperty("zIndex", out var z) &&
            z.TryGetInt32(out var value))
            return value;
        if (obj.Raw.ValueKind == JsonValueKind.Object &&
            obj.Raw.TryGetProperty("zIndex", out z) &&
            z.TryGetInt32(out value))
            return value;
        return 0;
    }

    private static SKFilterQuality FilterQuality(string? value) => value?.Trim().ToLowerInvariant() switch
    {
        "nearest" or "none" or "point" => SKFilterQuality.None,
        "low" => SKFilterQuality.Low,
        "medium" => SKFilterQuality.Medium,
        _ => SKFilterQuality.High,
    };

    private static byte AlphaByte(double opacity) =>
        (byte)Math.Clamp((int)Math.Round(Math.Clamp(opacity, 0d, 1d) * 255d, MidpointRounding.AwayFromZero), 0, 255);

    private object? BindProjectValue(object? value, StudioProject project, RendererObjectV3 obj)
    {
        if (value is not string s || !s.StartsWith('$')) return value;
        var index = CardIndex(obj) ?? 0; var card = index >= 0 && index < project.Cards.Count ? project.Cards[index] : null;
        return s switch { "$card.title" or "$project.card.title" => card?.Title ?? "", "$card.value" or "$project.card.value" => card?.Value ?? "", "$card.badgeHeader" or "$project.card.badgeHeader" => card?.BadgeHeader ?? "", "$card.description" or "$project.card.description" => card?.Description ?? "", "$card.image" or "$project.card.image" => card?.Image ?? "", "$project.name" => project.Name, _ => value };
    }

    private void DrawImageCover(
        SKCanvas canvas,
        StudioCard card,
        SKRect dest,
        SKRect? visibleClip = null) =>
        DrawImage(canvas, card, dest, true, 1, visibleClip);

    private void DrawImageContain(
        SKCanvas canvas,
        StudioCard card,
        SKRect dest,
        float opacity,
        SKRect? visibleClip = null) =>
        DrawImage(canvas, card, dest, false, opacity, visibleClip);

    private void DrawImage(
        SKCanvas canvas,
        StudioCard card,
        SKRect dest,
        bool cover,
        float opacity,
        SKRect? visibleClip = null)
    {
        var bitmap = LoadImage(card.Image);
        if (bitmap == null) return;

        var src = new SKRect(
            (float)(bitmap.Width * Math.Clamp(card.ImageCropLeft, 0, .95)),
            (float)(bitmap.Height * Math.Clamp(card.ImageCropTop, 0, .95)),
            (float)(bitmap.Width * (1 - Math.Clamp(card.ImageCropRight, 0, .95))),
            (float)(bitmap.Height * (1 - Math.Clamp(card.ImageCropBottom, 0, .95))));
        if (src.Width < 1 || src.Height < 1) return;

        // 'dest' is the renderer-authored base layout rectangle. It defines the
        // scale=1 crop/center but must not also become an implicit hard mask.
        // SmartCards often use a smaller source composition rectangle inside a
        // much larger artwork/reveal clip. Using dest as the mask made ImageScale
        // appear capped and prevented artwork from expanding behind the badge.
        var baseScale = cover
            ? Math.Max(dest.Width / src.Width, dest.Height / src.Height)
            : Math.Min(dest.Width / src.Width, dest.Height / src.Height);
        var scale = baseScale * (float)Math.Clamp(card.ImageScale, .05, 12);
        var w = src.Width * scale;
        var h = src.Height * scale;
        var cx = dest.MidX + (float)card.ImageX;
        var cy = dest.MidY + (float)card.ImageY;
        var target = new SKRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        var clip = visibleClip ?? dest;

        canvas.Save();
        canvas.ClipRect(clip);
        if (card.ImageRotation != 0)
            canvas.RotateDegrees((float)card.ImageRotation, cx, cy);
        using var paint = new SKPaint
        {
            IsAntialias = true,
            FilterQuality = SKFilterQuality.High,
            Color = WithAlpha(SKColors.White, opacity),
        };
        canvas.DrawBitmap(bitmap, src, target, paint);
        canvas.Restore();
    }

    private SKBitmap? LoadImage(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || path.StartsWith("https://", StringComparison.OrdinalIgnoreCase) || !File.Exists(path)) return null;
        if (_imageCache.TryGetValue(path, out var cached)) return cached;
        try { var bitmap = SKBitmap.Decode(path); if (bitmap != null) _imageCache[path] = bitmap; return bitmap; } catch { return null; }
    }
    private SKBitmap? DecodeSceneBitmap(RendererSceneV3 scene, string source)
    {
        if (string.IsNullOrWhiteSpace(source)) return null; var normalized = source.Replace('\\', '/').TrimStart('.', '/'); var pair = scene.Assets.FirstOrDefault(x => x.Key.Equals(normalized, StringComparison.OrdinalIgnoreCase) || x.Key.EndsWith('/' + normalized, StringComparison.OrdinalIgnoreCase)); if (pair.Value == null) return null; var cacheKey = "asset:" + pair.Key; if (_imageCache.TryGetValue(cacheKey, out var cached)) return cached; try { var bitmap = SKBitmap.Decode(pair.Value); if (bitmap != null) _imageCache[cacheKey] = bitmap; return bitmap; } catch { return null; }
    }

    private SKBitmap? DecodeSequenceBitmap(SmartBadgeSequenceDefinition sequence, string source)
    {
        if (string.IsNullOrWhiteSpace(source)) return null;
        var normalized = source.Replace('\\', '/').TrimStart('.', '/');
        var pair = sequence.Assets.FirstOrDefault(x =>
            x.Key.Equals(normalized, StringComparison.OrdinalIgnoreCase) ||
            x.Key.EndsWith('/' + normalized, StringComparison.OrdinalIgnoreCase));
        if (pair.Value is null) return null;

        var cacheKey = "sequence:" + sequence.Root + ":" + pair.Key;
        if (_imageCache.TryGetValue(cacheKey, out var cached)) return cached;
        try
        {
            var bitmap = SKBitmap.Decode(pair.Value);
            if (bitmap != null) _imageCache[cacheKey] = bitmap;
            return bitmap;
        }
        catch
        {
            return null;
        }
    }

    private SKPaint TextPaint(StudioProject project, float size, SKColor color, bool bold)
    {
        SKTypeface typeface; try { typeface = !string.IsNullOrWhiteSpace(project.FontFile) && File.Exists(project.FontFile) ? SKTypeface.FromFile(project.FontFile) : SKTypeface.FromFamilyName(string.IsNullOrWhiteSpace(project.FontFamily) ? "Segoe UI" : project.FontFamily, bold ? SKFontStyle.Bold : SKFontStyle.Normal); } catch { typeface = SKTypeface.Default; }
        return new SKPaint { IsAntialias = true, SubpixelText = true, Typeface = typeface, TextSize = size, Color = color, TextAlign = SKTextAlign.Left };
    }
    private void DrawFitText(SKCanvas canvas, StudioProject project, string text, SKRect box, SKColor color, float preferred, bool bold, int maxLines)
    {
        if (string.IsNullOrWhiteSpace(text) || box.Width <= 1 || box.Height <= 1) return; using var paint = TextPaint(project, preferred, color, bold); var size = preferred; List<string> lines = [];
        while (size >= 12) { paint.TextSize = size; lines = Wrap(text, paint, box.Width); if (lines.Count <= maxLines && lines.Count * size * 1.15f <= box.Height) break; size -= 1; }
        paint.TextSize = size; var y = box.Top + size; foreach (var line in lines.Take(maxLines)) { canvas.DrawText(line, box.Left, y, paint); y += size * 1.15f; }
    }
    private static List<string> Wrap(string text, SKPaint paint, float width)
    {
        var output = new List<string>(); foreach (var paragraph in text.Replace("\r", "").Split('\n')) { var current = ""; foreach (var word in paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries)) { var candidate = current.Length == 0 ? word : current + " " + word; if (paint.MeasureText(candidate) <= width || current.Length == 0) current = candidate; else { output.Add(current); current = word; } } if (current.Length > 0) output.Add(current); } return output;
    }
    private static void DrawCentered(SKCanvas canvas, string text, float x, float y, SKPaint paint, float maxWidth)
    {
        if (string.IsNullOrWhiteSpace(text)) return; var size = paint.TextSize; while (paint.MeasureText(text) > maxWidth && size > 12) { size -= 1; paint.TextSize = size; } paint.TextAlign = SKTextAlign.Center; canvas.DrawText(text, x, y, paint); paint.TextAlign = SKTextAlign.Left;
    }

    private static SKColor ToSkColor(uint argb) => new((byte)(argb >> 16), (byte)(argb >> 8), (byte)argb, (byte)(argb >> 24));
    private static SKColor WithAlpha(SKColor color, float opacity) => new(color.Red, color.Green, color.Blue, (byte)Math.Clamp((int)Math.Round(color.Alpha * opacity), 0, 255));
    private static SKColor WithAlpha(SKColor color, byte alpha) => new(color.Red, color.Green, color.Blue, alpha);
    private static SKColor ParseColor(string? value, SKColor fallback)
    {
        if (string.IsNullOrWhiteSpace(value)) return fallback; if (SKColor.TryParse(value, out var parsed)) return parsed; if (value.StartsWith("0x", StringComparison.OrdinalIgnoreCase) && uint.TryParse(value[2..], NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var n)) return ToSkColor(n); return fallback;
    }
    private static SKBlendMode BlendMode(object? value) => StringValue(value)?.Trim().ToLowerInvariant() switch
    {
        "clear" => SKBlendMode.Clear,
        "src" or "source" => SKBlendMode.Src,
        "dst" or "destination" => SKBlendMode.Dst,
        "srcover" or "source-over" => SKBlendMode.SrcOver,
        "dstover" or "destination-over" => SKBlendMode.DstOver,
        "srcin" or "source-in" => SKBlendMode.SrcIn,
        "dstin" or "destination-in" => SKBlendMode.DstIn,
        "srcout" or "source-out" => SKBlendMode.SrcOut,
        "dstout" or "destination-out" => SKBlendMode.DstOut,
        "srcatop" or "source-atop" => SKBlendMode.SrcATop,
        "dstatop" or "destination-atop" => SKBlendMode.DstATop,
        "xor" => SKBlendMode.Xor,
        "multiply" => SKBlendMode.Multiply,
        "screen" => SKBlendMode.Screen,
        "overlay" => SKBlendMode.Overlay,
        "darken" => SKBlendMode.Darken,
        "lighten" => SKBlendMode.Lighten,
        "colordodge" or "color-dodge" => SKBlendMode.ColorDodge,
        "colorburn" or "color-burn" => SKBlendMode.ColorBurn,
        "hardlight" or "hard-light" => SKBlendMode.HardLight,
        "softlight" or "soft-light" => SKBlendMode.SoftLight,
        "difference" => SKBlendMode.Difference,
        "exclusion" => SKBlendMode.Exclusion,
        "add" or "plus" => SKBlendMode.Plus,
        _ => SKBlendMode.SrcOver,
    };
    private static float Lerp(float a, float b, float p) => a + (b - a) * p;
    private static object? Get(Dictionary<string, object?> props, params string[] keys) { foreach (var key in keys) if (props.TryGetValue(key, out var value)) return value; return null; }
    private static double Number(object? value, double fallback = 0)
    {
        if (value is null) return fallback; if (value is JsonElement e) { if (e.ValueKind == JsonValueKind.Number && e.TryGetDouble(out var d)) return d; if (double.TryParse(e.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out d)) return d; return fallback; } return Convert.ToDouble(value, CultureInfo.InvariantCulture);
    }
    private static string? StringValue(object? value) => value switch { null => null, string s => s, JsonElement e when e.ValueKind == JsonValueKind.String => e.GetString(), JsonElement e => e.ToString(), _ => value.ToString() };
    private static bool Truthy(object? value, bool fallback = false) => value switch { null => fallback, bool b => b, JsonElement e when e.ValueKind == JsonValueKind.True => true, JsonElement e when e.ValueKind == JsonValueKind.False => false, JsonElement e when e.ValueKind == JsonValueKind.Number && e.TryGetDouble(out var d) => Math.Abs(d) > 0.000001, JsonElement e when e.ValueKind == JsonValueKind.String => !string.IsNullOrWhiteSpace(e.GetString()) && e.GetString() != "0" && !string.Equals(e.GetString(), "false", StringComparison.OrdinalIgnoreCase), double d => Math.Abs(d) > 0.000001, float f => Math.Abs(f) > 0.000001, int i => i != 0, string s => !string.IsNullOrWhiteSpace(s) && s != "0" && !string.Equals(s, "false", StringComparison.OrdinalIgnoreCase), _ => true };
    private static List<SKPoint>? Points(object? value)
    {
        if (value is JsonElement e && e.ValueKind == JsonValueKind.Array)
        {
            var result = new List<SKPoint>(); foreach (var item in e.EnumerateArray()) { if (item.ValueKind == JsonValueKind.Array && item.GetArrayLength() >= 2) result.Add(new SKPoint((float)item[0].GetDouble(), (float)item[1].GetDouble())); else if (item.ValueKind == JsonValueKind.Object) result.Add(new SKPoint((float)item.Double("x", 0), (float)item.Double("y", 0))); } return result;
        }
        if (value is IEnumerable<object?> list) { var result = new List<SKPoint>(); foreach (var item in list) if (item is IEnumerable<object?> pair) { var a = pair.ToArray(); if (a.Length >= 2) result.Add(new SKPoint((float)Number(a[0]), (float)Number(a[1]))); } return result; }
        return null;
    }
    private static JsonElement EmptyJson() { using var d = JsonDocument.Parse("{}"); return d.RootElement.Clone(); }

    public void Dispose() { _infinite.Dispose(); _relationships.Dispose(); foreach (var bitmap in _imageCache.Values.Distinct()) bitmap.Dispose(); _imageCache.Clear(); _sequenceOpaqueBoundsCache.Clear(); }
}

internal static class V3Evaluator
{
    private sealed record Winner(int Specificity, int Order, object? Value, string Timeline);

    public static Dictionary<string, object?> Properties(RendererSceneV3 scene, RendererObjectV3 obj, int frame)
    {
        var winners = new Dictionary<string, Winner>(StringComparer.Ordinal);
        if (obj.Resource != null && scene.Resources.TryGetValue(obj.Resource, out var resource) && resource.TryGetProperty("properties", out var rp) && rp.ValueKind == JsonValueKind.Object)
            foreach (var pair in Flatten(rp)) winners[pair.Key] = new Winner(0, -1, pair.Value, "absolute");
        foreach (var selector in scene.Selectors)
        {
            if (!Matches(selector, obj)) continue;
            foreach (var pair in Flatten(selector.Properties))
            {
                var candidate = new Winner(selector.Specificity, selector.SourceOrder, pair.Value, selector.Timeline);
                if (!winners.TryGetValue(pair.Key, out var existing) || candidate.Specificity > existing.Specificity || candidate.Specificity == existing.Specificity && candidate.Order >= existing.Order) winners[pair.Key] = candidate;
            }
        }
        foreach (var pair in Flatten(obj.Properties)) winners[pair.Key] = new Winner(1000, int.MaxValue, pair.Value, obj.Raw.String("timeline", "relative"));
        var result = new Dictionary<string, object?>(StringComparer.Ordinal);
        foreach (var pair in winners)
        {
            var value = Evaluate(pair.Value.Value, frame, obj.Frame, pair.Value.Timeline);
            if (value is not UnsetValue) result[pair.Key] = value;
        }
        return result;
    }

    public static Dictionary<string, JsonElement> Flatten(JsonElement root, string prefix = "")
    {
        var output = new Dictionary<string, JsonElement>(StringComparer.Ordinal); if (root.ValueKind != JsonValueKind.Object) return output;
        foreach (var property in root.EnumerateObject())
        {
            var path = string.IsNullOrWhiteSpace(prefix) ? property.Name : prefix + "." + property.Name; var value = property.Value;
            if (value.ValueKind == JsonValueKind.Object && !IsTrackDescriptor(value)) foreach (var child in Flatten(value, path)) output[child.Key] = child.Value; else output[path] = value.Clone();
        }
        return output;
    }

    private static object? Evaluate(object? raw, int globalFrame, int anchorFrame, string defaultTimeline)
    {
        if (raw is not JsonElement value) return raw; if (value.ValueKind != JsonValueKind.Object) return JsonValue(value);
        if (value.TryGetProperty("value", out var staticValue) && !value.TryGetProperty("track", out _) && !value.TryGetProperty("dense", out _)) return JsonValue(staticValue);
        if (!value.TryGetProperty("track", out var track) && !value.TryGetProperty("dense", out var dense)) return value.Clone();
        var timeline = value.String("timeline", defaultTimeline);
        var frame = timeline == "relative" ? globalFrame - anchorFrame : globalFrame;
        frame += value.Int("frameOffset", 0);
        var extrapolate = value.String("extrapolate", "none");
        var interpolation = value.String("interpolation", "raw");
        if (value.TryGetProperty("dense", out dense))
        {
            int start; JsonElement values;
            if (dense.ValueKind == JsonValueKind.Array) { start = value.Int("start", 0); values = dense; }
            else if (dense.ValueKind == JsonValueKind.Object && dense.TryGetProperty("values", out values)) start = dense.Int("start", value.Int("start", 0));
            else return UnsetValue.Instance;
            var stride = Math.Max(1, value.Int("stride", dense.ValueKind == JsonValueKind.Object ? dense.Int("stride", 1) : 1));
            var relative = frame - start;
            if (relative >= 0)
            {
                var index = relative / stride;
                var exactSample = relative % stride == 0;
                if (index >= 0 && index < values.GetArrayLength())
                {
                    if (stride == 1 || exactSample || interpolation is "hold" or "step")
                        return JsonValue(values[index]);
                    if (interpolation is "linear" or "smoothstep" or "cubic-in" or "cubic-out" or "cubic-in-out")
                    {
                        var next = Math.Min(index + 1, values.GetArrayLength() - 1);
                        if (values[index].TryGetDouble(out var denseLeft) && values[next].TryGetDouble(out var denseRight))
                        {
                            var denseProgress = (relative % stride) / (double)stride;
                            denseProgress = interpolation switch
                            {
                                "smoothstep" => denseProgress * denseProgress * (3 - 2 * denseProgress),
                                "cubic-in" => denseProgress * denseProgress * denseProgress,
                                "cubic-out" => 1 - Math.Pow(1 - denseProgress, 3),
                                "cubic-in-out" => denseProgress < .5 ? 4 * denseProgress * denseProgress * denseProgress : 1 - Math.Pow(-2 * denseProgress + 2, 3) / 2,
                                _ => denseProgress,
                            };
                            return denseLeft + (denseRight - denseLeft) * denseProgress;
                        }
                    }
                    if (interpolation != "raw") return JsonValue(values[index]);
                }
            }
            if (extrapolate == "hold" && values.GetArrayLength() > 0) return JsonValue(values[relative < 0 ? 0 : values.GetArrayLength() - 1]);
            return UnsetValue.Instance;
        }
        if (track.ValueKind != JsonValueKind.Array || track.GetArrayLength() == 0) return UnsetValue.Instance; var keys = new List<(int Frame, JsonElement Value)>(); foreach (var item in track.EnumerateArray()) if (item.ValueKind == JsonValueKind.Array && item.GetArrayLength() >= 2) keys.Add((item[0].GetInt32(), item[1].Clone())); keys.Sort((a, b) => a.Frame.CompareTo(b.Frame)); if (keys.Count == 0) return UnsetValue.Instance;
        if (frame < keys[0].Frame) return extrapolate == "hold" ? JsonValue(keys[0].Value) : UnsetValue.Instance; if (frame > keys[^1].Frame) return extrapolate == "hold" ? JsonValue(keys[^1].Value) : UnsetValue.Instance; var exact = keys.FirstOrDefault(k => k.Frame == frame); if (exact.Value.ValueKind != JsonValueKind.Undefined) return JsonValue(exact.Value); if (interpolation == "raw") return UnsetValue.Instance; var right = keys.FindIndex(k => k.Frame > frame); if (right <= 0) return UnsetValue.Instance; var leftKey = keys[right - 1]; var rightKey = keys[right]; if (interpolation is "hold" or "step") return JsonValue(leftKey.Value); if (!leftKey.Value.TryGetDouble(out var lv) || !rightKey.Value.TryGetDouble(out var rv)) return UnsetValue.Instance; var p = (frame - leftKey.Frame) / (double)Math.Max(1, rightKey.Frame - leftKey.Frame); p = interpolation switch { "smoothstep" => p * p * (3 - 2 * p), "cubic-in" => p * p * p, "cubic-out" => 1 - Math.Pow(1 - p, 3), "cubic-in-out" => p < .5 ? 4 * p * p * p : 1 - Math.Pow(-2 * p + 2, 3) / 2, _ => p }; return lv + (rv - lv) * p;
    }

    private static bool Matches(RendererSelectorV3 selector, RendererObjectV3 obj)
    {
        if (selector.Kind != obj.Kind) return false; var every = selector.Conditions.FirstOrDefault(c => c.Key == "every" && c.Op == "=")?.Value; var from = selector.Conditions.FirstOrDefault(c => c.Key == "from" && c.Op == "=")?.Value; var to = selector.Conditions.FirstOrDefault(c => c.Key == "to" && c.Op == "=")?.Value;
        if (every != null) { var step = Convert.ToInt32(every); var start = from == null ? 0 : Convert.ToInt32(from); var end = to == null ? int.MaxValue : Convert.ToInt32(to); if (step <= 0 || obj.Frame < start || obj.Frame > end || (obj.Frame - start) % step != 0) return false; }
        foreach (var condition in selector.Conditions.Where(c => c.Key is not ("every" or "from" or "to"))) { object? lhs = condition.Key switch { "frame" => obj.Frame, "id" => obj.Id, "kind" => obj.Kind, _ => obj.Raw.ValueKind == JsonValueKind.Object && obj.Raw.TryGetProperty(condition.Key, out var value) ? JsonValue(value) : null }; if (!Compare(lhs, condition.Op, condition.Value)) return false; } return true;
    }
    private static bool Compare(object? lhs, string op, object rhs)
    {
        if (op == "=") return string.Equals(lhs?.ToString(), rhs.ToString(), StringComparison.Ordinal); if (op == "!=") return !string.Equals(lhs?.ToString(), rhs.ToString(), StringComparison.Ordinal); if (!double.TryParse(lhs?.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var l) || !double.TryParse(rhs.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var r)) return false; return op switch { ">=" => l >= r, "<=" => l <= r, ">" => l > r, "<" => l < r, _ => false };
    }
    private static bool IsTrackDescriptor(JsonElement value) => value.ValueKind == JsonValueKind.Object && (value.TryGetProperty("track", out _) || value.TryGetProperty("dense", out _) || value.TryGetProperty("value", out _));
    private static object? JsonValue(JsonElement value) => value.ValueKind switch { JsonValueKind.Null or JsonValueKind.Undefined => null, JsonValueKind.True => true, JsonValueKind.False => false, JsonValueKind.Number when value.TryGetInt64(out var i) => i, JsonValueKind.Number when value.TryGetDouble(out var d) => d, JsonValueKind.String => value.GetString(), _ => value.Clone() };
    private sealed class UnsetValue { public static readonly UnsetValue Instance = new(); private UnsetValue() { } }
}
