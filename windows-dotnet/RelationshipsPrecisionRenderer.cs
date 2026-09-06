using SkiaSharp;
using System.IO.Compression;
using System.Text.RegularExpressions;

namespace CubicalCompare.Windows;

/// <summary>
/// Skia port of Android RelationshipsPrecisionFrameRenderer. Enabled by the
/// relationships.exact.v2=true/1 renderer tag. Source-specific geometry, colors,
/// typefaces, waveform data, badge points, strokes, shine and motion stay in the
/// declarative renderer bundle rather than being hardcoded in the app.
/// </summary>
public sealed class RelationshipsPrecisionRenderer : IDisposable
{
    private const int W = 1920, H = 1080;
    private readonly Dictionary<string, SKBitmap> _images = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, SKTypeface> _typefaces = new(StringComparer.Ordinal);
    private string? _configKey;
    private ExactConfig? _config;

    public static bool Enabled(RendererSpec spec) => spec.Tags.Any(raw =>
    {
        var value = raw.Trim();
        return value.Equals("relationships.exact.v2=true", StringComparison.OrdinalIgnoreCase) ||
               value.Equals("relationships.exact.v2=1", StringComparison.OrdinalIgnoreCase);
    });

    public SKBitmap Render(StudioProject project, RendererSpec spec, int frame, int width, int height)
    {
        width = Math.Max(2, width); height = Math.Max(2, height);
        var cfg = Config(spec);
        var output = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(output);
        canvas.Scale(width / (float)W, height / (float)H);
        DrawReference(canvas, project, Math.Max(0, frame), spec, cfg);
        canvas.Flush();
        return output;
    }

    private ExactConfig Config(RendererSpec spec)
    {
        var key = spec.Id + ":" + string.Join("\u001f", spec.Tags);
        if (_config != null && _configKey == key) return _config;
        _configKey = key; _config = new ExactConfig(spec); return _config;
    }

    private void DrawReference(SKCanvas canvas, StudioProject project, int frame, RendererSpec spec, ExactConfig cfg)
    {
        canvas.Clear(Color(spec.BackgroundColor));
        DrawFooterWaveform(canvas, frame, cfg);
        if (project.Cards.Count == 0) { DrawIntroLogo(canvas, project, frame, spec, cfg); return; }
        var contentEnd = ContentEnd(project, spec);
        var first = spec.OpeningStarts.FirstOrDefault();
        if (frame < first) DrawIntroLogo(canvas, project, frame, spec, cfg);
        else if (frame < contentEnd)
        {
            if (frame < cfg.Int("intro.overlayUntilFrame", first)) DrawIntroLogo(canvas, project, frame, spec, cfg);
            DrawContent(canvas, project, frame, spec, cfg);
        }
        else DrawOutro(canvas, project, frame, contentEnd, spec, cfg);
    }

    private static int ContentEnd(StudioProject project, RendererSpec spec)
    {
        var canonical = spec.Track("relationships.content_end", 0);
        if (canonical != null && (spec.CanonicalCardCount <= 0 || project.Cards.Count == spec.CanonicalCardCount)) return (int)Math.Round(canonical.Value);
        if (project.Cards.Count <= 4) return (project.Cards.Count == 0 ? 0 : OpeningStart(spec, project.Cards.Count - 1)) + 180;
        return EntryFrame(project.Cards.Count, project.Cards.Count - 1, spec) + 340;
    }

    private static int OpeningStart(RendererSpec spec, int index) => index < spec.OpeningStarts.Count ? spec.OpeningStarts[index] : (spec.OpeningStarts.LastOrDefault() + index * 140);

    private static int EntryFrame(int projectSize, int index, RendererSpec spec)
    {
        if (index < 4) return OpeningStart(spec, index);
        var targetX = 1920f;
        var basis = index * spec.SlotPitch;
        var low = spec.ContinuousStartFrame;
        var high = (int)Math.Round(spec.Track("relationships.content_end", 0) ?? (low + Math.Max(4, projectSize) * 300f));
        for (var n = 0; n < 18; n++)
        {
            var mid = (low + high) / 2;
            var segment = (mid - spec.ContinuousStartFrame) / 4096;
            var scroll = spec.Track($"relationships.scroll.{segment}", mid) ?? ((mid - spec.ContinuousStartFrame) * 2f);
            if (basis - scroll <= targetX) high = mid; else low = mid + 1;
        }
        return high;
    }

    private void DrawFooterWaveform(SKCanvas canvas, int frame, ExactConfig cfg)
    {
        if (!cfg.Bool("footer.waveform.enabled", false)) return;
        var data = cfg.GzipBase64("footer.waveform.data.gzipBase64");
        if (data == null) return;
        var barCount = Math.Clamp(cfg.Int("footer.waveform.barCount", 87), 1, 512);
        var bytesPerBar = Math.Clamp(cfg.Int("footer.waveform.bytesPerBar", 2), 2, 3);
        var stride = checked(barCount * bytesPerBar);
        if (data.Length < stride) return;
        var frameCount = data.Length / stride;
        var sourceFrame = Math.Clamp(frame + cfg.Int("footer.waveform.frameOffset", 0), 0, frameCount - 1);
        var offset = sourceFrame * stride;
        var x0 = cfg.Float("footer.waveform.x0", 6);
        var step = cfg.Float("footer.waveform.step", 22);
        var width = Math.Max(.25f, cfg.Float("footer.waveform.width", 1));
        var baseline = cfg.Float("footer.waveform.baselineY", 1068);
        var baseColor = cfg.Color("footer.waveform.color", new SKColor(76, 76, 76));
        var globalAlpha = Math.Clamp(cfg.Float("footer.waveform.alpha", 1), 0, 1);
        using var paint = new SKPaint { IsAntialias = cfg.Bool("footer.waveform.antialias", false), Style = SKPaintStyle.Fill };
        for (var i = 0; i < barCount; i++)
        {
            var p = offset + i * bytesPerBar;
            var up = data[p]; var down = data[p + 1];
            var encodedAlpha = bytesPerBar >= 3 ? data[p + 2] / 255f : 1f;
            if (up == 0 && down == 0) continue;
            paint.Color = WithAlpha(baseColor, globalAlpha * encodedAlpha);
            var x = x0 + i * step;
            canvas.DrawRect(x, baseline - up, width, up + down + 1, paint);
        }
    }

    private void DrawIntroLogo(SKCanvas canvas, StudioProject project, int frame, RendererSpec spec, ExactConfig cfg)
    {
        if (!cfg.Bool("intro.enabled", false)) return;
        var fadeIn = Smooth(frame / Math.Max(1f, cfg.Float("intro.fadeInFrames", 36)));
        var legacyScale = frame < 90 ? 1.42f - .46f * Smooth(frame / 90f) : .96f + .04f * Smooth(Math.Max(0, 180 - frame) / 90f);
        var legacyAlpha = frame > 340 ? Math.Clamp((384 - frame) / 44f, 0, 1) : 1;
        var scale = spec.Track("relationships.intro.logo.scale", frame) ?? legacyScale;
        var alpha = Math.Clamp(spec.Track("relationships.intro.logo.alpha", frame) ?? legacyAlpha, 0, 1);
        var cx = cfg.Float("intro.logo.cx", 960); var cy = cfg.Float("intro.logo.cy", 470);
        var rx = cfg.Float("intro.logo.rx", 250); var ry = cfg.Float("intro.logo.ry", 118); var gap = cfg.Float("intro.logo.gap", 5);
        canvas.Save(); canvas.Scale(scale, scale, cx, cy);
        var layers = Math.Clamp(cfg.Int("intro.logo.layerCount", 0), 0, 12);
        if (layers > 0)
        {
            for (var layer = 0; layer < layers; layer++)
            {
                using var paint = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Stroke, StrokeCap = SKStrokeCap.Round, StrokeWidth = cfg.Float($"intro.logo.layer.{layer}.strokeWidth", cfg.Float("intro.logo.strokeWidth", 9)) };
                var layerAlpha = Math.Clamp(cfg.Float($"intro.logo.layer.{layer}.alpha", 1), 0, 1) * fadeIn * alpha;
                paint.Color = WithAlpha(cfg.Color($"intro.logo.layer.{layer}.leftColor", cfg.Color("intro.logo.leftColor", new SKColor(216, 235, 42))), layerAlpha);
                canvas.DrawArc(new SKRect(cx - rx, cy - ry, cx - gap, cy + ry), cfg.Float("intro.logo.leftStart", 42), cfg.Float("intro.logo.leftSweep", 276), false, paint);
                paint.Color = WithAlpha(cfg.Color($"intro.logo.layer.{layer}.rightColor", cfg.Color("intro.logo.rightColor", new SKColor(238, 111, 139))), layerAlpha);
                canvas.DrawArc(new SKRect(cx + gap, cy - ry, cx + rx, cy + ry), cfg.Float("intro.logo.rightStart", 222), cfg.Float("intro.logo.rightSweep", 276), false, paint);
            }
        }
        else
        {
            using var paint = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Stroke, StrokeCap = SKStrokeCap.Round, StrokeWidth = cfg.Float("intro.logo.strokeWidth", 9) };
            paint.Color = WithAlpha(cfg.Color("intro.logo.leftColor", new SKColor(216, 235, 42)), fadeIn * alpha);
            canvas.DrawArc(new SKRect(cx - rx, cy - ry, cx - gap, cy + ry), cfg.Float("intro.logo.leftStart", 42), cfg.Float("intro.logo.leftSweep", 276), false, paint);
            paint.Color = WithAlpha(cfg.Color("intro.logo.rightColor", new SKColor(238, 111, 139)), fadeIn * alpha);
            canvas.DrawArc(new SKRect(cx + gap, cy - ry, cx + rx, cy + ry), cfg.Float("intro.logo.rightStart", 222), cfg.Float("intro.logo.rightSweep", 276), false, paint);
        }
        using (var cross = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Stroke, StrokeCap = SKStrokeCap.Round, StrokeWidth = cfg.Float("intro.logo.crossStrokeWidth", 3), Color = WithAlpha(cfg.Color("intro.logo.crossColor", new SKColor(58, 58, 58)), fadeIn * alpha) })
        {
            var xx = cfg.Float("intro.logo.crossX", 168); var yy = cfg.Float("intro.logo.crossY", 86);
            canvas.DrawLine(cx - xx, cy - yy, cx + xx, cy + yy, cross); canvas.DrawLine(cx + xx, cy - yy, cx - xx, cy + yy, cross);
        }
        canvas.Restore();

        var full = cfg.String("intro.text", "Infinite\\nComparison").Replace("\\n", "\n");
        var chars = spec.Track("relationships.intro.text.chars", frame) is float tracked ? (int)Math.Round(tracked) : (int)((frame - cfg.Int("intro.text.startFrame", 170)) / Math.Max(.01f, cfg.Float("intro.text.framesPerChar", 2.4f)));
        if (chars <= 0) return;
        var visible = full[..Math.Clamp(chars, 0, full.Length)];
        using var text = TextPaint(project, spec, cfg, "intro", cfg.Float("intro.text.size", 34), WithAlpha(cfg.Color("intro.text.color", SKColors.White), alpha), false, "sans-serif-light");
        var y = cfg.Float("intro.text.y", 640); var lineGap = cfg.Float("intro.text.lineGap", 38);
        var spacing = cfg.Float("font.intro.letterSpacing", 0);
        foreach (var pair in visible.Split('\n').Select((line, index) => (line, index))) DrawText(canvas, pair.line, cx, y + pair.index * lineGap, text, SKTextAlign.Center, spacing);
    }

    private void DrawContent(SKCanvas canvas, StudioProject project, int frame, RendererSpec spec, ExactConfig cfg)
    {
        var positions = new Dictionary<int, float>();
        if (frame < spec.ContinuousStartFrame)
        {
            for (var i = 0; i < Math.Min(4, project.Cards.Count); i++) if (frame >= OpeningStart(spec, i)) positions[i] = spec.TrackWindowed($"card.{i}.x", frame) ?? i * spec.SlotPitch;
        }
        else
        {
            var segment = (frame - spec.ContinuousStartFrame) / 4096;
            var scroll = spec.Track($"relationships.scroll.{segment}", frame) ?? ((frame - spec.ContinuousStartFrame) * 2f);
            for (var i = 0; i < project.Cards.Count; i++)
            {
                var baseX = i * spec.SlotPitch - scroll;
                var x = spec.TrackWindowed($"card.{i}.x", frame) ?? baseX;
                if (x > -spec.SlotPitch * 2 && x < W + spec.SlotPitch * 2) positions[i] = x;
            }
        }

        foreach (var pair in positions)
        {
            var i = pair.Key; var x = pair.Value; var y = spec.TrackWindowed($"card.{i}.y", frame) ?? 0;
            var local = frame - EntryFrame(project.Cards.Count, i, spec);
            var uniform = spec.TrackWindowed($"card.{i}.body.scale", frame) ?? spec.Track("relationships.card.body.scale", local) ?? 1;
            var sx = spec.TrackWindowed($"card.{i}.body.scaleX", frame) ?? spec.Track("relationships.card.body.scaleX", local) ?? uniform;
            var sy = spec.TrackWindowed($"card.{i}.body.scaleY", frame) ?? spec.Track("relationships.card.body.scaleY", local) ?? uniform;
            var pivotX = x + cfg.Float("card.body.pivotX", spec.BodyInset + spec.BodyWidth / 2); var pivotY = cfg.Float("card.body.pivotY", 540);
            canvas.Save(); canvas.Translate(0, y); canvas.Scale(sx, sy, pivotX, pivotY); DrawCardBody(canvas, project, project.Cards[i], x, spec, cfg, frame, i); canvas.Restore();
        }
        if (project.CreditsEnabled && frame >= spec.OpeningStarts.FirstOrDefault() && frame < spec.ContinuousStartFrame) DrawDisclaimer(canvas, project, frame, spec, cfg);
        foreach (var pair in positions) DrawBadge(canvas, project, pair.Key, pair.Value, frame, spec, cfg);
        foreach (var pair in positions)
        {
            if (!project.Cards[pair.Key].ImageLayer.Equals("front", StringComparison.OrdinalIgnoreCase)) continue;
            var i = pair.Key; var x = pair.Value; var y = spec.TrackWindowed($"card.{i}.y", frame) ?? 0;
            var local = frame - EntryFrame(project.Cards.Count, i, spec);
            var uniform = spec.TrackWindowed($"card.{i}.body.scale", frame) ?? spec.Track("relationships.card.body.scale", local) ?? 1;
            var sx = spec.TrackWindowed($"card.{i}.body.scaleX", frame) ?? spec.Track("relationships.card.body.scaleX", local) ?? uniform;
            var sy = spec.TrackWindowed($"card.{i}.body.scaleY", frame) ?? spec.Track("relationships.card.body.scaleY", local) ?? uniform;
            var pivotX = x + cfg.Float("card.body.pivotX", spec.BodyInset + spec.BodyWidth / 2); var pivotY = cfg.Float("card.body.pivotY", 540);
            canvas.Save(); canvas.Translate(0, y); canvas.Scale(sx, sy, pivotX, pivotY); DrawFrontArtwork(canvas, project.Cards[i], x, spec, cfg); canvas.Restore();
        }
    }

    private void DrawCardBody(SKCanvas canvas, StudioProject project, StudioCard card, float slotX, RendererSpec spec, ExactConfig cfg, int frame, int index)
    {
        var left = slotX + spec.BodyInset; var right = left + spec.BodyWidth;
        var absolute = cfg.Bool("card.absoluteBands", true); var hasTitle = !string.IsNullOrWhiteSpace(card.Title); var hasDescription = !string.IsNullOrWhiteSpace(card.Description);
        float imageBottom, titleTop, titleBottom, descriptionTop;
        if (absolute)
        {
            imageBottom = spec.ImageHeight; titleTop = imageBottom; titleBottom = titleTop + (hasTitle ? spec.TitleHeight : 0); descriptionTop = hasDescription ? spec.DescriptionTop : titleBottom;
        }
        else
        {
            var desc = hasDescription ? cfg.Float("card.legacyDescriptionHeight", 115) : 0; var title = hasTitle ? spec.TitleHeight : 0;
            imageBottom = H - desc - title; titleTop = imageBottom; titleBottom = titleTop + title; descriptionTop = titleBottom;
        }
        var imageRect = new SKRect(left, 0, right, imageBottom); var topRadius = Math.Max(0, cfg.Float("card.image.topRadius", 0));
        using (var paint = Shape(cfg.Color("card.imageFallbackColor", new SKColor(30, 30, 30)))) canvas.DrawPath(PanelPath(imageRect, topRadius, topRadius, 0, 0), paint);
        var local = frame - EntryFrame(int.MaxValue, index, spec); var legacyReveal = index < 4 ? Math.Clamp((local - 52) / 42f, 0, 1) : 1;
        var reveal = Math.Clamp(spec.TrackWindowed($"card.{index}.body.reveal", frame) ?? spec.Track("relationships.card.reveal", local) ?? legacyReveal, 0, 1);
        if (!card.ImageLayer.Equals("front", StringComparison.OrdinalIgnoreCase) && reveal > 0)
        {
            canvas.Save(); canvas.ClipPath(PanelPath(imageRect, topRadius, topRadius, 0, 0), antialias: true); canvas.ClipRect(new SKRect(left, 0, right, imageBottom * reveal)); DrawArtwork(canvas, card, imageRect); canvas.Restore();
        }
        if (hasTitle)
        {
            var rect = new SKRect(left, titleTop, right, titleBottom); var r = Math.Max(0, cfg.Float("card.title.topRadius", 0)); using var fill = Shape(Color(spec.TitleBackgroundColor)); canvas.DrawPath(PanelPath(rect, r, r, 0, 0), fill);
            var alpha = Math.Clamp(spec.TrackWindowed($"card.{index}.title.alpha", frame) ?? spec.Track("relationships.card.title.alpha", local) ?? 1, 0, 1);
            var trackedChars = spec.TrackWindowed($"card.{index}.title.chars", frame) ?? spec.Track("relationships.card.title.chars", local); var title = trackedChars == null ? card.Title : card.Title[..Math.Clamp((int)Math.Round(trackedChars.Value), 0, card.Title.Length)];
            if (title.Length > 0 && alpha > 0) DrawFitted(canvas, project, spec, cfg, "title", title, new SKRect(left + cfg.Float("card.title.padX", 10), titleTop + cfg.Float("card.title.padTop", 1), right - cfg.Float("card.title.padX", 10), titleBottom - cfg.Float("card.title.padBottom", 1)), Color(spec.TitleTextColor), spec.TitleTextSize, true, cfg.Int("card.title.maxLines", 1), cfg.Float("card.title.lineHeight", .92f), alpha, cfg.Float("font.title.letterSpacing", 0));
        }
        if (hasDescription && descriptionTop > titleBottom) { using var divider = Shape(cfg.Color("card.divider.color", Color(spec.DescriptionBackgroundColor))); canvas.DrawRect(left, titleBottom, right - left, descriptionTop - titleBottom, divider); }
        if (hasDescription)
        {
            var rect = new SKRect(left, descriptionTop, right, H); var r = Math.Max(0, cfg.Float("card.description.bottomRadius", 0)); using var fill = Shape(Color(spec.DescriptionBackgroundColor)); canvas.DrawPath(PanelPath(rect, 0, 0, r, r), fill);
            var alpha = Math.Clamp(spec.TrackWindowed($"card.{index}.description.alpha", frame) ?? spec.Track("relationships.card.description.alpha", local) ?? 1, 0, 1);
            var trackedChars = spec.TrackWindowed($"card.{index}.description.chars", frame) ?? spec.Track("relationships.card.description.chars", local); var desc = trackedChars == null ? card.Description : card.Description[..Math.Clamp((int)Math.Round(trackedChars.Value), 0, card.Description.Length)];
            if (desc.Length > 0 && alpha > 0) DrawFitted(canvas, project, spec, cfg, "description", desc, new SKRect(left + cfg.Float("card.description.padX", 11), descriptionTop + cfg.Float("card.description.padTop", 4), right - cfg.Float("card.description.padX", 11), H - cfg.Float("card.description.padBottom", 4)), Color(spec.DescriptionTextColor), spec.DescriptionTextSize, false, cfg.Int("card.description.maxLines", 4), cfg.Float("card.description.lineHeight", .92f), alpha, cfg.Float("font.description.letterSpacing", 0));
        }
    }

    private static SKPath PanelPath(SKRect rect, float tl, float tr, float br, float bl)
    {
        tl = Math.Clamp(tl, 0, Math.Min(rect.Width, rect.Height) / 2); tr = Math.Clamp(tr, 0, Math.Min(rect.Width, rect.Height) / 2); br = Math.Clamp(br, 0, Math.Min(rect.Width, rect.Height) / 2); bl = Math.Clamp(bl, 0, Math.Min(rect.Width, rect.Height) / 2);
        var p = new SKPath(); p.MoveTo(rect.Left + tl, rect.Top); p.LineTo(rect.Right - tr, rect.Top); if (tr > 0) p.QuadTo(rect.Right, rect.Top, rect.Right, rect.Top + tr); else p.LineTo(rect.Right, rect.Top);
        p.LineTo(rect.Right, rect.Bottom - br); if (br > 0) p.QuadTo(rect.Right, rect.Bottom, rect.Right - br, rect.Bottom); else p.LineTo(rect.Right, rect.Bottom);
        p.LineTo(rect.Left + bl, rect.Bottom); if (bl > 0) p.QuadTo(rect.Left, rect.Bottom, rect.Left, rect.Bottom - bl); else p.LineTo(rect.Left, rect.Bottom);
        p.LineTo(rect.Left, rect.Top + tl); if (tl > 0) p.QuadTo(rect.Left, rect.Top, rect.Left + tl, rect.Top); p.Close(); return p;
    }

    private void DrawFrontArtwork(SKCanvas canvas, StudioCard card, float slotX, RendererSpec spec, ExactConfig cfg)
    {
        var absolute = cfg.Bool("card.absoluteBands", true); var bottom = absolute ? spec.ImageHeight : H - (string.IsNullOrWhiteSpace(card.Description) ? 0 : cfg.Float("card.legacyDescriptionHeight", 115)) - (string.IsNullOrWhiteSpace(card.Title) ? 0 : spec.TitleHeight);
        var rect = new SKRect(slotX + spec.BodyInset, 0, slotX + spec.BodyInset + spec.BodyWidth, bottom); var radius = Math.Max(0, cfg.Float("card.image.topRadius", 0)); canvas.Save(); canvas.ClipPath(PanelPath(rect, radius, radius, 0, 0), antialias: true); DrawArtwork(canvas, card, rect); canvas.Restore();
    }

    private void DrawArtwork(SKCanvas canvas, StudioCard card, SKRect destination)
    {
        var bitmap = LoadImage(card.Image); if (bitmap == null) return;
        var left = (float)(bitmap.Width * Math.Clamp(card.ImageCropLeft, 0, .95)); var top = (float)(bitmap.Height * Math.Clamp(card.ImageCropTop, 0, .95)); var right = (float)(bitmap.Width * (1 - Math.Clamp(card.ImageCropRight, 0, .95))); var bottom = (float)(bitmap.Height * (1 - Math.Clamp(card.ImageCropBottom, 0, .95)));
        var source = new SKRect(left, top, Math.Max(left + 1, right), Math.Max(top + 1, bottom)); var scale = Math.Max(destination.Width / source.Width, destination.Height / source.Height) * (float)Math.Clamp(card.ImageScale, .05, 12); var w = source.Width * scale; var h = source.Height * scale; var cx = destination.MidX + (float)card.ImageX; var cy = destination.MidY + (float)card.ImageY; var target = new SKRect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        canvas.Save(); canvas.ClipRect(destination); if (card.ImageRotation != 0) canvas.RotateDegrees((float)card.ImageRotation, cx, cy); using var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High }; canvas.DrawBitmap(bitmap, source, target, paint); canvas.Restore();
    }

    private SKBitmap? LoadImage(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || path.StartsWith("http://", StringComparison.OrdinalIgnoreCase) || path.StartsWith("https://", StringComparison.OrdinalIgnoreCase) || !File.Exists(path)) return null;
        if (_images.TryGetValue(path, out var hit)) return hit; try { var bitmap = SKBitmap.Decode(path); if (bitmap != null) _images[path] = bitmap; return bitmap; } catch { return null; }
    }

    private void DrawBadge(SKCanvas canvas, StudioProject project, int index, float cardX, int frame, RendererSpec spec, ExactConfig cfg)
    {
        if (!project.ShowBadges) return; var card = project.Cards[index]; if (string.IsNullOrWhiteSpace(card.Value) && string.IsNullOrWhiteSpace(card.BadgeHeader)) return;
        var local = frame - EntryFrame(project.Cards.Count, index, spec); if (local < 0) return;
        var scale = Math.Clamp(spec.TrackWindowed($"card.{index}.badge.scale", frame) ?? spec.Track("relationships.badge.scale", local) ?? (local < 45 ? Smooth(local / 45f) : 1), 0, cfg.Float("badge.maxScale", 2));
        var y = spec.TrackWindowed($"card.{index}.badge.y", frame) ?? spec.Track("relationships.badge.y", local) ?? 0; var x = spec.TrackWindowed($"card.{index}.badge.x", frame) ?? 0;
        var cx = spec.BadgeCenterX; var cy = spec.BadgeCenterY; canvas.Save(); canvas.Translate(cardX + x, y); canvas.Scale(scale * spec.BadgeScale, scale * spec.BadgeScale, cx, cy); using var path = BadgePath(cfg, cx, cy);
        var shadowColor = cfg.Color("badge.shadow.color", SKColors.Transparent); var radius = Math.Max(0, cfg.Float("badge.shadow.radius", 0)); var dx = cfg.Float("badge.shadow.dx", 0); var dy = cfg.Float("badge.shadow.dy", 0); if (shadowColor.Alpha > 0 && (radius > 0 || dx != 0 || dy != 0)) DrawBadgeShadow(canvas, path, shadowColor, radius, dx, dy);
        var top = cfg.Color("badge.gradient.top", Color(spec.BadgeColor)); var bottom = cfg.Color("badge.gradient.bottom", Color(spec.BadgeColor)); using (var fill = Shape(Color(spec.BadgeColor)))
        {
            if (!top.Equals(bottom) || cfg.Has("badge.gradient.top") || cfg.Has("badge.gradient.bottom")) fill.Shader = SKShader.CreateLinearGradient(new SKPoint(cx, cy + cfg.Float("badge.gradient.startY", -cfg.Float("badge.radiusY", 177))), new SKPoint(cx, cy + cfg.Float("badge.gradient.endY", cfg.Float("badge.radiusY", 177))), new[] { top, bottom }, new[] { 0f, 1f }, SKShaderTileMode.Clamp);
            canvas.DrawPath(path, fill);
        }
        var strokes = Math.Clamp(cfg.Int("badge.stroke.count", 1), 0, 8); for (var layer = 0; layer < strokes; layer++) { var width = strokes == 1 ? cfg.Float("badge.stroke.width", 4) : cfg.Float($"badge.stroke.{layer}.width", 4); if (width <= 0) continue; using var stroke = new SKPaint { IsAntialias = true, Style = SKPaintStyle.Stroke, StrokeWidth = width, Color = strokes == 1 ? cfg.Color("badge.stroke.color", Color(spec.BadgeDarkColor)) : cfg.Color($"badge.stroke.{layer}.color", Color(spec.BadgeDarkColor)) }; canvas.DrawPath(path, stroke); }
        DrawBadgeShine(canvas, path, index, frame, local, spec, cfg); var textAlpha = Math.Clamp(spec.TrackWindowed($"card.{index}.badge.text.alpha", frame) ?? spec.Track("relationships.badge.text.alpha", local) ?? 1, 0, 1); if (textAlpha > 0) DrawBadgeText(canvas, project, card, spec, cfg, textAlpha); canvas.Restore();
    }

    private static SKPath BadgePath(ExactConfig cfg, float cx, float cy)
    {
        var declared = cfg.FloatList("badge.points"); var path = new SKPath();
        if (declared.Count >= 6 && declared.Count % 2 == 0) { for (var i = 0; i < declared.Count; i += 2) { var x = cx + declared[i]; var y = cy + declared[i + 1]; if (i == 0) path.MoveTo(x, y); else path.LineTo(x, y); } path.Close(); return path; }
        var rx = cfg.Float("badge.radiusX", 184); var ry = cfg.Float("badge.radiusY", 177); var corner = cfg.Float("badge.cornerX", 92); var upper = cfg.Float("badge.upperCornerY", 88); var lower = cfg.Float("badge.lowerCornerY", 86);
        var pts = new[] { new SKPoint(cx - corner, cy - ry), new SKPoint(cx + corner, cy - ry), new SKPoint(cx + rx, cy - upper), new SKPoint(cx + rx, cy + lower), new SKPoint(cx + corner, cy + ry), new SKPoint(cx - corner, cy + ry), new SKPoint(cx - rx, cy + lower), new SKPoint(cx - rx, cy - upper) };
        path.MoveTo(pts[0]); foreach (var point in pts.Skip(1)) path.LineTo(point); path.Close(); return path;
    }

    private static void DrawBadgeShadow(SKCanvas canvas, SKPath badge, SKColor color, float radius, float dx, float dy)
    {
        using var shifted = new SKPath(); shifted.AddPath(badge); shifted.Transform(SKMatrix.CreateTranslation(dx, dy));
        canvas.Save(); canvas.ClipPath(badge, SKClipOperation.Difference, true);
        using var paint = Shape(color); if (radius > 0) paint.ImageFilter = SKImageFilter.CreateBlur(radius, radius); canvas.DrawPath(shifted, paint); canvas.Restore();
    }

    private static void DrawBadgeShine(SKCanvas canvas, SKPath badge, int index, int frame, int local, RendererSpec spec, ExactConfig cfg)
    {
        var shineX = spec.TrackWindowed($"card.{index}.badge.shine.x", frame) ?? spec.Track("relationships.badge.shine.x", local); if (shineX == null) return;
        var alpha = spec.TrackWindowed($"card.{index}.badge.shine.alpha", frame) ?? spec.Track("relationships.badge.shine.alpha", local) ?? 1; if (alpha <= 0) return;
        var width = Math.Max(0, cfg.Float("badge.shine.width", 78)); var slant = cfg.Float("badge.shine.slant", 52); var top = spec.BadgeCenterY - cfg.Float("badge.radiusY", 177) - 8; var bottom = spec.BadgeCenterY + cfg.Float("badge.radiusY", 177) + 8; var x = spec.BadgeCenterX + shineX.Value;
        var color = WithAlpha(Color(spec.ShineColor), Math.Max(0, cfg.Float("badge.shine.alpha", 1)) * Math.Clamp(alpha, 0, 1)); using var shine = new SKPath(); shine.MoveTo(x - width / 2, top); shine.LineTo(x + width / 2, top); shine.LineTo(x + width / 2 + slant, bottom); shine.LineTo(x - width / 2 + slant, bottom); shine.Close();
        canvas.Save(); canvas.ClipPath(badge, antialias: true); using var paint = Shape(color); var feather = Math.Clamp(cfg.Float("badge.shine.feather", 0), 0, .49f); if (feather > 0) { var transparent = new SKColor(color.Red, color.Green, color.Blue, 0); paint.Shader = SKShader.CreateLinearGradient(new SKPoint(x - width / 2, cfg.Float("badge.shine.gradientStartY", 0)), new SKPoint(x + width / 2, cfg.Float("badge.shine.gradientEndY", 0)), new[] { transparent, color, color, transparent }, new[] { 0f, feather, 1 - feather, 1f }, SKShaderTileMode.Clamp); } canvas.DrawPath(shine, paint); canvas.Restore();
    }

    private void DrawBadgeText(SKCanvas canvas, StudioProject project, StudioCard card, RendererSpec spec, ExactConfig cfg, float alpha)
    {
        var parts = Regex.Split(card.Value.Trim(), "\\s+", RegexOptions.CultureInvariant); var primary = parts.FirstOrDefault() ?? ""; var unit = parts.Length > 1 ? string.Join(' ', parts.Skip(1)) : cfg.String("badge.defaultUnit", "People"); var color = WithAlpha(Color(spec.BadgeTextColor), Math.Clamp(alpha, 0, 1));
        DrawBadgeLine(canvas, project, spec, cfg, "badgeHeader", string.IsNullOrWhiteSpace(card.BadgeHeader) ? cfg.String("badge.defaultHeader", "1 in") : card.BadgeHeader, spec.BadgeCenterX, spec.BadgeCenterY + cfg.Float("badge.header.y", -75), spec.TitleTextSize > 0 ? spec.TitleTextSize : 31, cfg.Float("badge.header.minSize", 12), cfg.Float("badge.header.maxWidth", 230), color);
        DrawBadgeLine(canvas, project, spec, cfg, "badgeValue", primary, spec.BadgeCenterX, spec.BadgeCenterY + cfg.Float("badge.value.y", 12), cfg.Float("badge.value.size", 72), cfg.Float("badge.value.minSize", 18), cfg.Float("badge.value.maxWidth", 300), color);
        DrawBadgeLine(canvas, project, spec, cfg, "badgeUnit", unit, spec.BadgeCenterX, spec.BadgeCenterY + cfg.Float("badge.unit.y", 70), cfg.Float("badge.unit.size", 29), cfg.Float("badge.unit.minSize", 12), cfg.Float("badge.unit.maxWidth", 245), color);
    }

    private void DrawBadgeLine(SKCanvas canvas, StudioProject project, RendererSpec spec, ExactConfig cfg, string role, string text, float x, float y, float preferred, float min, float maxWidth, SKColor color)
    {
        if (string.IsNullOrWhiteSpace(text)) return; using var paint = TextPaint(project, spec, cfg, role, preferred, color, false, cfg.String("font.badge.family", "sans-serif")); var spacing = cfg.Float($"font.{role}.letterSpacing", cfg.Float("font.badge.letterSpacing", 0)); var measured = Measure(paint, text, spacing); if (measured > maxWidth) paint.TextSize = Math.Max(min, preferred * maxWidth / measured); DrawText(canvas, text, x, y, paint, SKTextAlign.Center, spacing);
    }

    private void DrawDisclaimer(SKCanvas canvas, StudioProject project, int frame, RendererSpec spec, ExactConfig cfg)
    {
        var first = spec.OpeningStarts.FirstOrDefault(); var p = Math.Clamp((frame - first) / Math.Max(1f, cfg.Float("disclaimer.slideFrames", 70)), 0, 1); var legacyX = cfg.Float("disclaimer.restX", 1450) + cfg.Float("disclaimer.travelX", 470) * (1 - Smooth(p)); var x = spec.Track("relationships.disclaimer.x", frame) ?? legacyX; var alpha = Math.Clamp(spec.Track("relationships.disclaimer.alpha", frame) ?? 1, 0, 1); if (x >= W || alpha <= 0) return;
        var bg = cfg.Color("disclaimer.background", new SKColor(22, 22, 22)); using (var paint = Shape(WithAlpha(bg, alpha)))
        {
            var start = WithAlpha(cfg.Color("disclaimer.gradient.startColor", bg), alpha); var end = WithAlpha(cfg.Color("disclaimer.gradient.endColor", bg), alpha); if (cfg.Has("disclaimer.gradient.startColor") || cfg.Has("disclaimer.gradient.endColor")) paint.Shader = SKShader.CreateLinearGradient(new SKPoint(cfg.Float("disclaimer.gradient.startX", x), cfg.Float("disclaimer.gradient.startY", 0)), new SKPoint(cfg.Float("disclaimer.gradient.endX", W), cfg.Float("disclaimer.gradient.endY", 0)), new[] { start, end }, new[] { 0f, 1f }, SKShaderTileMode.Clamp); canvas.DrawRect(x, 0, W - x, H, paint);
        }
        var border = cfg.Float("disclaimer.border.width", 0); if (border > 0) using (var paint = Shape(WithAlpha(cfg.Color("disclaimer.border.color", new SKColor(74, 74, 74)), alpha))) canvas.DrawRect(x, 0, border, H, paint);
        var left = x + cfg.Float("disclaimer.padX", 30); var y = cfg.Float("disclaimer.y", 220); var gap = cfg.Float("disclaimer.lineGap", 38); var header = cfg.String("disclaimer.header", "DISCLAIMER:"); var lines = cfg.String("disclaimer.lines", "This|comparison video|is based on public|data, surveys,|public comments|& discussions and|approximate|estimations that|might be|subjected to some|degree of error.").Split('|'); using var text = TextPaint(project, spec, cfg, "disclaimer", cfg.Float("disclaimer.textSize", 26), WithAlpha(cfg.Color("disclaimer.textColor", SKColors.LightGray), alpha), false, "sans-serif"); var spacing = cfg.Float("font.disclaimer.letterSpacing", 0); if (header.Length > 0) { text.Color = WithAlpha(cfg.Color("disclaimer.headerColor", new SKColor(178, 0, 22)), alpha); DrawText(canvas, header, left, y, text, SKTextAlign.Left, spacing); } if (lines.Length > 0) { var bodyX = left + (header.Length == 0 ? 0 : Measure(text, header, spacing) + cfg.Float("disclaimer.headerGap", 9)); text.Color = WithAlpha(cfg.Color("disclaimer.textColor", SKColors.LightGray), alpha); DrawText(canvas, lines[0], bodyX, y, text, SKTextAlign.Left, spacing); foreach (var line in lines.Skip(1)) { y += gap; DrawText(canvas, line, left, y, text, SKTextAlign.Left, spacing); } }
    }

    private void DrawOutro(SKCanvas canvas, StudioProject project, int frame, int contentEnd, RendererSpec spec, ExactConfig cfg)
    {
        var local = frame - contentEnd; var last = project.Cards[^1]; var index = project.Cards.Count - 1; var cardX = spec.Track("relationships.outro.card.x", frame) ?? (local < 80 ? Lerp(320, 781, Smooth(local / 80f)) : 781); DrawCardBody(canvas, project, last, cardX, spec, cfg, frame, index); DrawBadge(canvas, project, index, cardX, frame, spec, cfg);
        var panelAlpha = Math.Clamp(spec.Track("relationships.outro.panel.alpha", frame) ?? (local >= cfg.Int("outro.panel.start", 58) ? 1 : 0), 0, 1); if (panelAlpha > 0) { var left = cfg.Float("outro.panel.left", 1290); var top = cfg.Float("outro.panel.top", 180); var right = cfg.Float("outro.panel.right", 1732); var bottom = cfg.Float("outro.panel.bottom", 910); using var panel = Shape(WithAlpha(cfg.Color("outro.panel.color", new SKColor(28, 28, 28)), panelAlpha)); var radius = Math.Max(0, cfg.Float("outro.panel.radius", 0)); if (radius > 0) canvas.DrawRoundRect(new SKRect(left, top, right, bottom), radius, radius, panel); else canvas.DrawRect(left, top, right - left, bottom - top, panel); using var label = TextPaint(project, spec, cfg, "outroPanel", cfg.Float("outro.panel.labelSize", 31), WithAlpha(cfg.Color("outro.panel.labelColor", new SKColor(145,145,145)), panelAlpha), false, "sans-serif-light"); DrawText(canvas, cfg.String("outro.panel.label", "WATCH MORE"), cfg.Float("outro.panel.labelX", (left + right) / 2), cfg.Float("outro.panel.labelY", 235), label, SKTextAlign.Center, cfg.Float("font.outroPanel.letterSpacing",0)); }
        var question = cfg.String("outro.question", "Which relationship type\\nare you in right now?").Replace("\\n", "\n"); var qChars = spec.Track("relationships.outro.question.chars", frame) is float q ? (int)Math.Round(q) : (int)(Math.Max(0, local - cfg.Int("outro.question.start", 70)) * cfg.Float("outro.question.charsPerFrame", .52f)); if (qChars > 0) DrawTyped(canvas, project, spec, cfg, "outroQuestion", question[..Math.Clamp(qChars,0,question.Length)], cfg.Float("outro.question.x",40), cfg.Float("outro.question.y",390), cfg.Float("outro.question.size",37), cfg.Color("outro.question.color",SKColors.White), true, cfg.Float("outro.question.lineGap",7));
        var comment = cfg.String("outro.comment", "Comment below!"); var cChars = spec.Track("relationships.outro.comment.chars", frame) is float cc ? (int)Math.Round(cc) : (int)(Math.Max(0, local - cfg.Int("outro.comment.start",225)) * cfg.Float("outro.comment.charsPerFrame",.6f)); if(cChars>0) DrawTyped(canvas,project,spec,cfg,"outroComment",comment[..Math.Clamp(cChars,0,comment.Length)],cfg.Float("outro.comment.x",40),cfg.Float("outro.comment.y",500),cfg.Float("outro.comment.size",37),cfg.Color("outro.comment.color",new SKColor(244,159,0)),false,cfg.Float("outro.comment.lineGap",7));
        var subscribe = Math.Clamp(spec.Track("relationships.outro.subscribe.alpha", frame) ?? (local >= cfg.Int("outro.subscribe.start",290)?1:0),0,1); if(subscribe>0){using var a=TextPaint(project,spec,cfg,"outroSubscribe",cfg.Float("outro.subscribe.size",34),WithAlpha(cfg.Color("outro.subscribe.color",new SKColor(224,10,34)),subscribe),true,"sans-serif");DrawText(canvas,cfg.String("outro.subscribe.text","SUBSCRIBE"),cfg.Float("outro.subscribe.x",40),cfg.Float("outro.subscribe.y",900),a,SKTextAlign.Left,cfg.Float("font.outroSubscribe.letterSpacing",0));using var b=TextPaint(project,spec,cfg,"outroSubscribeRest",cfg.Float("outro.subscribe.restSize",30),WithAlpha(cfg.Color("outro.subscribe.restColor",SKColors.LightGray),subscribe),false,"sans-serif-light");DrawText(canvas,cfg.String("outro.subscribe.rest1","for more"),cfg.Float("outro.subscribe.rest1X",220),cfg.Float("outro.subscribe.y",900),b,SKTextAlign.Left,cfg.Float("font.outroSubscribeRest.letterSpacing",0));DrawText(canvas,cfg.String("outro.subscribe.rest2","comparison videos."),cfg.Float("outro.subscribe.x",40),cfg.Float("outro.subscribe.rest2Y",944),b,SKTextAlign.Left,cfg.Float("font.outroSubscribeRest.letterSpacing",0));}
        var trackedFade = spec.Track("relationships.outro.fade.alpha",frame); var total = spec.CanonicalFrameCount>0?spec.CanonicalFrameCount:contentEnd+Math.Max(300,spec.OutroFrames); var fadeStart=Math.Max(0,total-contentEnd-42);var fade=trackedFade!=null?Math.Clamp(trackedFade.Value,0,1):(local>=fadeStart?Math.Clamp((local-fadeStart)/42f,0,1):0);if(fade>0)using(var paint=Shape(new SKColor(0,0,0,(byte)Math.Round(255*fade))))canvas.DrawRect(0,0,W,H,paint);
    }

    private void DrawTyped(SKCanvas canvas, StudioProject project, RendererSpec spec, ExactConfig cfg, string role, string text, float x, float y, float size, SKColor color, bool bold, float lineGap)
    {
        using var paint=TextPaint(project,spec,cfg,role,size,color,bold,bold?"sans-serif":"sans-serif-light");var spacing=cfg.Float($"font.{role}.letterSpacing",0);var line=0;foreach(var part in text.Split('\n'))DrawText(canvas,part,x,y+line++*(size+lineGap),paint,SKTextAlign.Left,spacing);
    }

    private void DrawFitted(SKCanvas canvas, StudioProject project, RendererSpec spec, ExactConfig cfg, string role, string text, SKRect box, SKColor color, float preferred, bool bold, int maxLines, float lineHeightScale, float alpha, float spacing)
    {
        using var paint=TextPaint(project,spec,cfg,role,preferred,WithAlpha(color,alpha),bold,bold?"sans-serif":"sans-serif");var size=preferred;List<string> lines=[];while(true){paint.TextSize=size;lines=Wrap(text,paint,box.Width,spacing);if((lines.Count<=maxLines&&lines.All(line=>Measure(paint,line,spacing)<=box.Width))||size<=8)break;size-=.5f;}paint.TextSize=size;var metrics=paint.FontMetrics;var lineHeight=(metrics.Descent-metrics.Ascent)*lineHeightScale;var y=box.MidY-(lines.Count-1)*lineHeight/2-(metrics.Ascent+metrics.Descent)/2;foreach(var line in lines.Take(maxLines)){DrawText(canvas,line,box.MidX,y,paint,SKTextAlign.Center,spacing);y+=lineHeight;}
    }

    private SKPaint TextPaint(StudioProject project, RendererSpec spec, ExactConfig cfg, string role, float size, SKColor color, bool bold, string fallback)
    {
        var face=ResolveTypeface(project,spec,cfg,role,fallback,bold);return new SKPaint{IsAntialias=true,SubpixelText=true,Typeface=face,TextSize=size,Color=color};
    }

    private SKTypeface ResolveTypeface(StudioProject project, RendererSpec spec, ExactConfig cfg, string role, string fallback, bool bold)
    {
        if(!string.IsNullOrWhiteSpace(project.FontFile)&&File.Exists(project.FontFile)){try{return SKTypeface.FromFile(project.FontFile)??SKTypeface.Default;}catch{}}
        if(!string.IsNullOrWhiteSpace(project.FontFamily)){try{return SKTypeface.FromFamilyName(project.FontFamily,bold?SKFontStyle.Bold:SKFontStyle.Normal)??SKTypeface.Default;}catch{}}
        var asset=cfg.StringOrNull($"font.{role}.asset");if(asset!=null){var encoded=cfg.StringOrNull($"font.asset.{asset}.base64");if(!string.IsNullOrWhiteSpace(encoded)){var key=spec.Id+":"+asset+":"+encoded.GetHashCode();if(_typefaces.TryGetValue(key,out var cached))return cached;try{using var data=SKData.CreateCopy(Convert.FromBase64String(encoded));var typeface=SKTypeface.FromData(data);if(typeface!=null){_typefaces[key]=typeface;return typeface;}}catch{}}}
        var family=cfg.String($"font.{role}.family",fallback);var style=cfg.String($"font.{role}.style","").ToLowerInvariant() switch{"bold"=>SKFontStyle.Bold,"italic"=>SKFontStyle.Italic,"bolditalic" or "bold_italic" or "bold-italic"=>SKFontStyle.BoldItalic,"normal"=>SKFontStyle.Normal,_=>bold?SKFontStyle.Bold:SKFontStyle.Normal};try{return SKTypeface.FromFamilyName(family,style)??SKTypeface.Default;}catch{return SKTypeface.Default;}
    }

    private static void DrawText(SKCanvas canvas,string text,float x,float y,SKPaint paint,SKTextAlign align,float letterSpacing)
    {
        if(string.IsNullOrEmpty(text))return;if(Math.Abs(letterSpacing)<.00001f){paint.TextAlign=align;canvas.DrawText(text,x,y,paint);return;}var spacing=letterSpacing*paint.TextSize;var width=Measure(paint,text,letterSpacing);var cursor=align switch{SKTextAlign.Center=>x-width/2,SKTextAlign.Right=>x-width,_=>x};paint.TextAlign=SKTextAlign.Left;for(var i=0;i<text.Length;i++){var s=text[i].ToString();canvas.DrawText(s,cursor,y,paint);cursor+=paint.MeasureText(s)+(i+1<text.Length?spacing:0);}
    }
    private static float Measure(SKPaint paint,string text,float letterSpacing)=>paint.MeasureText(text)+Math.Max(0,text.Length-1)*letterSpacing*paint.TextSize;
    private static List<string> Wrap(string text,SKPaint paint,float width,float spacing){var lines=new List<string>();foreach(var paragraph in text.Split('\n')){var words=Regex.Split(paragraph.Trim(),"\\s+",RegexOptions.CultureInvariant).Where(x=>x.Length>0);var current="";foreach(var word in words){var trial=current.Length==0?word:current+" "+word;if(current.Length==0||Measure(paint,trial,spacing)<=width)current=trial;else{lines.Add(current);current=word;}}if(current.Length>0)lines.Add(current);else if(paragraph.Length==0)lines.Add("");}return lines;}
    private static SKPaint Shape(SKColor color)=>new(){IsAntialias=true,Style=SKPaintStyle.Fill,Color=color};
    private static float Smooth(float value){var p=Math.Clamp(value,0,1);return p*p*(3-2*p);}private static float Lerp(float a,float b,float p)=>a+(b-a)*Math.Clamp(p,0,1);
    private static SKColor WithAlpha(SKColor color,float alpha)=>new(color.Red,color.Green,color.Blue,(byte)Math.Clamp((int)Math.Round(color.Alpha*Math.Clamp(alpha,0,1)),0,255));
    private static SKColor Color(uint argb)=>new((byte)(argb>>16),(byte)(argb>>8),(byte)argb,(byte)(argb>>24));

    public void Dispose(){foreach(var bitmap in _images.Values.Distinct())bitmap.Dispose();_images.Clear();foreach(var typeface in _typefaces.Values.Distinct())typeface.Dispose();_typefaces.Clear();}

    private sealed class ExactConfig
    {
        private readonly Dictionary<string,string> _values=new(StringComparer.Ordinal);private readonly Dictionary<string,byte[]?> _binary=new(StringComparer.Ordinal);
        public ExactConfig(RendererSpec spec){foreach(var raw in spec.Tags){var split=raw.IndexOf('=');if(split>0)_values[raw[..split].Trim()]=raw[(split+1)..];}}
        public bool Has(string key)=>_values.ContainsKey(key);public string String(string key,string fallback)=>_values.TryGetValue(key,out var v)?v:fallback;public string? StringOrNull(string key)=>_values.TryGetValue(key,out var v)?v:null;
        public bool Bool(string key,bool fallback){if(!_values.TryGetValue(key,out var value))return fallback;return value.Trim().ToLowerInvariant() switch{"1" or "true" or "yes" or "on"=>true,"0" or "false" or "no" or "off"=>false,_=>fallback};}
        public int Int(string key,int fallback)=>_values.TryGetValue(key,out var value)&&int.TryParse(value.Trim(),out var parsed)?parsed:fallback;
        public float Float(string key,float fallback)=>_values.TryGetValue(key,out var value)&&float.TryParse(value.Trim(),System.Globalization.NumberStyles.Float,System.Globalization.CultureInfo.InvariantCulture,out var parsed)&&float.IsFinite(parsed)?parsed:fallback;
        public SKColor Color(string key,SKColor fallback){if(!_values.TryGetValue(key,out var raw))return fallback;raw=raw.Trim();try{if(raw.StartsWith('#')){var hex=raw[1..];if(hex.Length==6)return new SKColor(Convert.ToByte(hex[..2],16),Convert.ToByte(hex.Substring(2,2),16),Convert.ToByte(hex.Substring(4,2),16));if(hex.Length==8)return new SKColor(Convert.ToByte(hex.Substring(2,2),16),Convert.ToByte(hex.Substring(4,2),16),Convert.ToByte(hex.Substring(6,2),16),Convert.ToByte(hex[..2],16));}var number=raw.StartsWith("0x",StringComparison.OrdinalIgnoreCase)?Convert.ToUInt32(raw[2..],16):Convert.ToUInt32(raw,System.Globalization.CultureInfo.InvariantCulture);return RelationshipsPrecisionRenderer.Color(number);}catch{return fallback;}}
        public List<float> FloatList(string key)=>_values.TryGetValue(key,out var raw)?raw.Split(',').Select(s=>float.TryParse(s.Trim(),System.Globalization.NumberStyles.Float,System.Globalization.CultureInfo.InvariantCulture,out var value)&&float.IsFinite(value)?(float?)value:null).Where(v=>v.HasValue).Select(v=>v!.Value).ToList():[];
        public byte[]? GzipBase64(string key){if(_binary.TryGetValue(key,out var cached))return cached;if(!_values.TryGetValue(key,out var encoded))return _binary[key]=null;try{var compressed=Convert.FromBase64String(encoded);using var input=new MemoryStream(compressed);using var gzip=new GZipStream(input,CompressionMode.Decompress);using var output=new MemoryStream();gzip.CopyTo(output);if(output.Length>64*1024*1024)throw new InvalidDataException("Renderer waveform data is too large.");return _binary[key]=output.ToArray();}catch{return _binary[key]=null;}}
    }
}