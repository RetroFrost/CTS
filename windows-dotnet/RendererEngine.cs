using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using SkiaSharp;

namespace CubicalCompare.Windows;

public sealed class RendererEngine : IDisposable
{
    private readonly Dictionary<string, SKBitmap> _imageCache = new(StringComparer.OrdinalIgnoreCase);

    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)
    {
        width = Math.Max(2, width);
        height = Math.Max(2, height);
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
        foreach (var pair in positions.OrderBy(x => x.Key)) DrawLegacyCard(canvas, project, project.Cards[pair.Key], pair.Value, spec);
        foreach (var pair in positions.OrderBy(x => x.Key)) DrawRibbonBadge(canvas, project, project.Cards[pair.Key], pair.Key, pair.Value, frame, spec);
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
        var objects = scene.Objects.Select((obj, i) => (obj, i)).OrderBy(x => rank.TryGetValue(x.obj.Id, out var r) ? r : int.MaxValue).ThenBy(x => x.i).Select(x => x.obj);
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
        if (index is int b && obj.Kind is "openingBadge" or "badge" or "laterBadge" or "openingText" or "badgeText" or "laterText" or "openingShine" or "shineBroad" or "shineCore" or "shadow")
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
        canvas.Save();
        ApplyClip(canvas, bound);
        ApplyTransform(canvas, bound);
        try
        {
            if (spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal) && obj.Kind is "openingCard" or "card") { DrawV3ProjectCard(canvas, project, obj, resource, (float)opacity); return; }
            if (spec.RequiredFeatures.Contains("project-card-data", StringComparer.Ordinal) && obj.Kind is "openingText" or "badgeText" or "laterText") { DrawV3ProjectBadgeText(canvas, project, obj, resource, bound, (float)opacity); return; }
            switch (type)
            {
                case "rect": DrawV3Rect(canvas, resource, bound, (float)opacity); break;
                case "ellipse": DrawV3Ellipse(canvas, resource, bound, (float)opacity); break;
                case "image": DrawV3Image(canvas, scene, resource, bound, (float)opacity); break;
                case "text": DrawV3Text(canvas, project, resource, bound, (float)opacity); break;
                case "text-raster": case "source-text-raster": DrawV3Raster(canvas, scene, resource, bound, (float)opacity); break;
                case "outro-overlay": case "exact-outro-overlay": DrawV3Outro(canvas, scene, resource, bound, frame, (float)opacity); break;
                case "independent-shadow": DrawV3IndependentShadow(canvas, project, spec, resource, bound, frame, (float)opacity); break;
                case "group": DrawV3Group(canvas, project, spec, obj, resource, bound, frame, (float)opacity); break;
                default: DrawV3Polygon(canvas, resource, bound, (float)opacity); break;
            }
        }
        finally { canvas.Restore(); }
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
        var x = (float)Number(Get(props, "x"), resource.Double("x", 0)); var y = (float)Number(Get(props, "y"), resource.Double("y", 0));
        var w = (float)Number(Get(props, "width"), resource.Double("width", bitmap.Width)); var h = (float)Number(Get(props, "height"), resource.Double("height", bitmap.Height));
        using var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High, Color = WithAlpha(SKColors.White, opacity), BlendMode = BlendMode(Get(props, "blendMode", "material.blend")) };
        canvas.DrawBitmap(bitmap, new SKRect(x, y, x + w, y + h), paint);
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
        using var paint = new SKPaint { Color = WithAlpha(SKColors.White, opacity), FilterQuality = SKFilterQuality.High };
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
        using var paint = new SKPaint { Color = new SKColor(0, 0, 0, (byte)Math.Round(opacity * 255)) }; canvas.DrawRect(0, 0, spec.ReferenceWidth, spec.ReferenceHeight, paint);
    }

    private SKPaint V3Paint(JsonElement resource, Dictionary<string, object?> props, float opacity)
    {
        var color = ParseColor(StringValue(Get(props, "color", "fill", "material.color")) ?? resource.String("color", resource.String("fill", "#ffffff")), SKColors.White);
        var stroke = Truthy(Get(props, "stroke"), false) || resource.String("style", "fill") == "stroke";
        var blur = (float)Number(Get(props, "blur", "filter.blur"), resource.Double("blur", 0));
        return new SKPaint
        {
            IsAntialias = true,
            Style = stroke ? SKPaintStyle.Stroke : SKPaintStyle.Fill,
            StrokeWidth = (float)Number(Get(props, "strokeWidth"), resource.Double("strokeWidth", 1)),
            Color = WithAlpha(color, opacity),
            BlendMode = BlendMode(Get(props, "blendMode", "material.blend")),
            ImageFilter = blur > 0 ? SKImageFilter.CreateBlur(blur, blur) : null,
        };
    }

    private void ApplyTransform(SKCanvas canvas, Dictionary<string, object?> props)
    {
        if (props.ContainsKey("matrix.m00") || props.ContainsKey("m00"))
        {
            var m = new SKMatrix { ScaleX = (float)Number(Get(props, "matrix.m00", "m00"), 1), SkewX = (float)Number(Get(props, "matrix.m01", "m01"), 0), TransX = (float)Number(Get(props, "matrix.tx", "tx"), 0), SkewY = (float)Number(Get(props, "matrix.m10", "m10"), 0), ScaleY = (float)Number(Get(props, "matrix.m11", "m11"), 1), TransY = (float)Number(Get(props, "matrix.ty", "ty"), 0), Persp2 = 1 };
            canvas.Concat(ref m); return;
        }
        var x = (float)Number(Get(props, "x", "transform.x", "translateX"), 0); var y = (float)Number(Get(props, "y", "transform.y", "translateY"), 0);
        var sx = (float)Number(Get(props, "scaleX", "transform.scaleX", "scale"), 1); var sy = (float)Number(Get(props, "scaleY", "transform.scaleY", "scale"), 1); var rotation = (float)Number(Get(props, "rotation", "transform.rotation"), 0);
        canvas.Translate(x, y); if (rotation != 0) canvas.RotateDegrees(rotation); if (sx != 1 || sy != 1) canvas.Scale(sx, sy);
    }
    private void ApplyClip(SKCanvas canvas, Dictionary<string, object?> props)
    {
        var points = Points(Get(props, "clip.points", "mask.points")); if (points != null && points.Count >= 3) { using var path = new SKPath(); path.MoveTo(points[0]); foreach (var p in points.Skip(1)) path.LineTo(p); path.Close(); canvas.ClipPath(path, SKClipOperation.Intersect, true); return; }
        if (Get(props, "clip.left") is not null || Get(props, "clip.right") is not null)
        {
            var left = (float)Number(Get(props, "clip.left"), 0); var top = (float)Number(Get(props, "clip.top"), 0); var right = (float)Number(Get(props, "clip.right"), 1920); var bottom = (float)Number(Get(props, "clip.bottom"), 1080); canvas.ClipRect(new SKRect(left, top, right, bottom), SKClipOperation.Intersect, false);
        }
    }

    private object? BindProjectValue(object? value, StudioProject project, RendererObjectV3 obj)
    {
        if (value is not string s || !s.StartsWith('$')) return value;
        var index = CardIndex(obj) ?? 0; var card = index >= 0 && index < project.Cards.Count ? project.Cards[index] : null;
        return s switch { "$card.title" or "$project.card.title" => card?.Title ?? "", "$card.value" or "$project.card.value" => card?.Value ?? "", "$card.badgeHeader" or "$project.card.badgeHeader" => card?.BadgeHeader ?? "", "$card.description" or "$project.card.description" => card?.Description ?? "", "$card.image" or "$project.card.image" => card?.Image ?? "", "$project.name" => project.Name, _ => value };
    }

    private void DrawImageCover(SKCanvas canvas, StudioCard card, SKRect dest) => DrawImage(canvas, card, dest, true, 1);
    private void DrawImageContain(SKCanvas canvas, StudioCard card, SKRect dest, float opacity) => DrawImage(canvas, card, dest, false, opacity);
    private void DrawImage(SKCanvas canvas, StudioCard card, SKRect dest, bool cover, float opacity)
    {
        var bitmap = LoadImage(card.Image); if (bitmap == null) return;
        var src = new SKRect((float)(bitmap.Width * Math.Clamp(card.ImageCropLeft, 0, .95)), (float)(bitmap.Height * Math.Clamp(card.ImageCropTop, 0, .95)), (float)(bitmap.Width * (1 - Math.Clamp(card.ImageCropRight, 0, .95))), (float)(bitmap.Height * (1 - Math.Clamp(card.ImageCropBottom, 0, .95))));
        if (src.Width < 1 || src.Height < 1) return; var baseScale = cover ? Math.Max(dest.Width / src.Width, dest.Height / src.Height) : Math.Min(dest.Width / src.Width, dest.Height / src.Height); var scale = baseScale * (float)Math.Clamp(card.ImageScale, .05, 12); var w = src.Width * scale; var h = src.Height * scale; var cx = dest.MidX + (float)card.ImageX; var cy = dest.MidY + (float)card.ImageY; var target = new SKRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.Save(); canvas.ClipRect(dest); if (card.ImageRotation != 0) canvas.RotateDegrees((float)card.ImageRotation, cx, cy); using var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High, Color = WithAlpha(SKColors.White, opacity) }; canvas.DrawBitmap(bitmap, src, target, paint); canvas.Restore();
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
    private static SKBlendMode BlendMode(object? value) => StringValue(value)?.ToLowerInvariant() switch { "multiply" => SKBlendMode.Multiply, "screen" => SKBlendMode.Screen, "add" or "plus" => SKBlendMode.Plus, "src" or "source" => SKBlendMode.Src, "dstover" or "destination-over" => SKBlendMode.DstOver, _ => SKBlendMode.SrcOver };
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

    public void Dispose() { foreach (var bitmap in _imageCache.Values.Distinct()) bitmap.Dispose(); _imageCache.Clear(); }
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
        var timeline = value.String("timeline", defaultTimeline); var frame = timeline == "relative" ? globalFrame - anchorFrame : globalFrame; var extrapolate = value.String("extrapolate", "none"); var interpolation = value.String("interpolation", "raw");
        if (value.TryGetProperty("dense", out dense))
        {
            int start; JsonElement values; if (dense.ValueKind == JsonValueKind.Array) { start = value.Int("start", 0); values = dense; } else if (dense.ValueKind == JsonValueKind.Object && dense.TryGetProperty("values", out values)) start = dense.Int("start", 0); else return UnsetValue.Instance;
            var index = frame - start; if (index >= 0 && index < values.GetArrayLength()) return JsonValue(values[index]); if (extrapolate == "hold" && values.GetArrayLength() > 0) return JsonValue(values[index < 0 ? 0 : values.GetArrayLength() - 1]); return UnsetValue.Instance;
        }
        if (track.ValueKind != JsonValueKind.Array || track.GetArrayLength() == 0) return UnsetValue.Instance; var keys = new List<(int Frame, JsonElement Value)>(); foreach (var item in track.EnumerateArray()) if (item.ValueKind == JsonValueKind.Array && item.GetArrayLength() >= 2) keys.Add((item[0].GetInt32(), item[1].Clone())); keys.Sort((a, b) => a.Frame.CompareTo(b.Frame)); if (keys.Count == 0) return UnsetValue.Instance;
        if (frame < keys[0].Frame) return extrapolate == "hold" ? JsonValue(keys[0].Value) : UnsetValue.Instance; if (frame > keys[^1].Frame) return extrapolate == "hold" ? JsonValue(keys[^1].Value) : UnsetValue.Instance; var exact = keys.FirstOrDefault(k => k.Frame == frame); if (exact.Value.ValueKind != JsonValueKind.Undefined) return JsonValue(exact.Value); if (interpolation == "raw") return UnsetValue.Instance; var right = keys.FindIndex(k => k.Frame > frame); if (right <= 0) return UnsetValue.Instance; var leftKey = keys[right - 1]; var rightKey = keys[right]; if (interpolation == "hold") return JsonValue(leftKey.Value); if (!leftKey.Value.TryGetDouble(out var lv) || !rightKey.Value.TryGetDouble(out var rv)) return UnsetValue.Instance; var p = (frame - leftKey.Frame) / (double)Math.Max(1, rightKey.Frame - leftKey.Frame); p = interpolation switch { "smoothstep" => p * p * (3 - 2 * p), "cubic-in" => p * p * p, "cubic-out" => 1 - Math.Pow(1 - p, 3), "cubic-in-out" => p < .5 ? 4 * p * p * p : 1 - Math.Pow(-2 * p + 2, 3) / 2, _ => p }; return lv + (rv - lv) * p;
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
