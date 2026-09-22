using CubicalCompare.Core.Project;
using SkiaSharp;

namespace CubicalCompare.Core.Thumbnail;

public sealed record GeneratedThumbnail(byte[] Png, string AutoSavedPath, IReadOnlyList<int> CardIndices);

public static class AutoThumbnailGenerator
{
    public const int Width = 1280;
    public const int Height = 720;
    private const int TopHeight = 360;
    private const int TitleStripHeight = 64;

    public static GeneratedThumbnail Generate(ComparisonProject project)
    {
        ArgumentNullException.ThrowIfNull(project);

        using var bitmap = new SKBitmap(new SKImageInfo(Width, Height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(bitmap);
        canvas.Clear(SKColors.Black);

        var indices = PickCards(project.Cards.Count);
        if (indices.Count == 0)
        {
            DrawEmpty(canvas, project);
        }
        else
        {
            DrawArtworkBand(canvas, project, indices);
            DrawTopCards(canvas, project, indices);
        }

        canvas.Flush();
        using var image = SKImage.FromBitmap(bitmap);
        using var encoded = image.Encode(SKEncodedImageFormat.Png, 100)
            ?? throw new InvalidOperationException("Could not encode the automatic thumbnail.");
        var png = encoded.ToArray();

        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "CubicalCompare",
            "Thumbnails");
        Directory.CreateDirectory(directory);
        var path = Path.Combine(directory, "auto-thumbnail.png");
        File.WriteAllBytes(path, png);

        return new GeneratedThumbnail(png, path, indices);
    }

    private static List<int> PickCards(int count)
    {
        if (count <= 0) return [];
        if (count == 1) return [0];
        if (count == 2) return [0, 1];

        // Keep the selection deterministic and representative of the whole comparison.
        // This gives the thumbnail a beginning / middle / extreme progression.
        var middle = (count - 1) / 2;
        return [0, middle, count - 1];
    }

    private static void DrawEmpty(SKCanvas canvas, ComparisonProject project)
    {
        using var paint = TextPaint(project, 76, SKColors.White, bold: true);
        paint.TextAlign = SKTextAlign.Center;
        canvas.DrawText("CUBICAL COMPARE", Width / 2f, Height / 2f, paint);
        paint.TextSize = 34;
        paint.Color = new SKColor(180, 180, 180);
        canvas.DrawText("Add cards to generate a thumbnail", Width / 2f, Height / 2f + 62, paint);
    }

    private static void DrawArtworkBand(SKCanvas canvas, ComparisonProject project, IReadOnlyList<int> indices)
    {
        var count = indices.Count;
        for (var slot = 0; slot < count; slot++)
        {
            var left = (int)Math.Round(slot * Width / (double)count);
            var right = (int)Math.Round((slot + 1) * Width / (double)count);
            var rect = new SKRect(left, TopHeight, right, Height);
            var card = project.Cards[indices[slot]];

            using (var background = new SKPaint { Color = new SKColor(5, 7, 14), Style = SKPaintStyle.Fill })
                canvas.DrawRect(rect, background);

            if (!DrawArtwork(canvas, card, rect))
                DrawFallbackArtwork(canvas, project, card, rect);

            using var shade = new SKPaint { Color = new SKColor(0, 0, 0, 28), Style = SKPaintStyle.Fill };
            canvas.DrawRect(rect, shade);
        }
    }

    private static void DrawTopCards(SKCanvas canvas, ComparisonProject project, IReadOnlyList<int> indices)
    {
        var count = indices.Count;
        for (var slot = 0; slot < count; slot++)
        {
            var left = (int)Math.Round(slot * Width / (double)count);
            var right = (int)Math.Round((slot + 1) * Width / (double)count);
            var card = project.Cards[indices[slot]];

            using (var top = new SKPaint { Color = new SKColor(34, 34, 34), Style = SKPaintStyle.Fill })
                canvas.DrawRect(left, 0, right - left, TopHeight - TitleStripHeight, top);

            DrawBadge(canvas, project, card, (left + right) / 2f, 145f, Math.Min(125f, (right - left) * .29f));

            using (var strip = new SKPaint { Color = new SKColor(244, 244, 244), Style = SKPaintStyle.Fill })
                canvas.DrawRect(left, TopHeight - TitleStripHeight, right - left, TitleStripHeight, strip);

            DrawFittedCentredText(
                canvas,
                project,
                string.IsNullOrWhiteSpace(card.Title) ? "Untitled" : card.Title.Trim(),
                new SKRect(left + 12, TopHeight - TitleStripHeight + 3, right - 12, TopHeight - 3),
                SKColors.Black,
                preferred: 48,
                minimum: 25,
                bold: false);

            if (slot > 0)
            {
                using var separator = new SKPaint { Color = SKColors.Black, StrokeWidth = 7, Style = SKPaintStyle.Stroke };
                canvas.DrawLine(left, 0, left, Height, separator);
            }
        }
    }

    private static void DrawBadge(SKCanvas canvas, ComparisonProject project, ComparisonCard card, float cx, float cy, float radius)
    {
        var rx = radius;
        var ry = radius * .88f;

        using (var glow = new SKPaint
        {
            IsAntialias = true,
            Color = new SKColor(255, 18, 32, 155),
            MaskFilter = SKMaskFilter.CreateBlur(SKBlurStyle.Normal, 30),
        })
        {
            canvas.DrawOval(new SKRect(cx - rx * 1.06f, cy + ry * .45f, cx + rx * 1.06f, cy + ry * 1.38f), glow);
        }

        using var path = Hexagon(cx, cy, rx, ry);
        using (var shadow = new SKPaint { IsAntialias = true, Color = new SKColor(120, 0, 8), Style = SKPaintStyle.Fill })
        {
            canvas.Save();
            canvas.Translate(0, 12);
            canvas.DrawPath(path, shadow);
            canvas.Restore();
        }

        using (var fill = new SKPaint { IsAntialias = true, Color = new SKColor(255, 15, 22), Style = SKPaintStyle.Fill })
            canvas.DrawPath(path, fill);

        using (var edge = new SKPaint { IsAntialias = true, Color = new SKColor(175, 0, 8), Style = SKPaintStyle.Stroke, StrokeWidth = 5 })
            canvas.DrawPath(path, edge);

        var (header, primary, secondary) = SplitBadgeText(card);
        using var text = TextPaint(project, 54, SKColors.White, bold: false);
        text.TextAlign = SKTextAlign.Center;

        if (!string.IsNullOrWhiteSpace(header))
        {
            DrawBadgeLine(canvas, header.ToUpperInvariant(), cx, cy - 58, text, 27, 18, rx * 1.4f);
            DrawBadgeLine(canvas, primary, cx, cy + 13, text, 58, 30, rx * 1.46f);
            if (!string.IsNullOrWhiteSpace(secondary))
                DrawBadgeLine(canvas, secondary.ToUpperInvariant(), cx, cy + 62, text, 25, 16, rx * 1.4f);
        }
        else
        {
            DrawBadgeLine(canvas, primary, cx, string.IsNullOrWhiteSpace(secondary) ? cy + 22 : cy + 4, text, 66, 32, rx * 1.46f);
            if (!string.IsNullOrWhiteSpace(secondary))
                DrawBadgeLine(canvas, secondary.ToUpperInvariant(), cx, cy + 57, text, 27, 16, rx * 1.42f);
        }
    }

    private static (string Header, string Primary, string Secondary) SplitBadgeText(ComparisonCard card)
    {
        var header = card.BadgeHeader?.Trim() ?? "";
        var value = card.Value?.Trim() ?? "";
        if (string.IsNullOrWhiteSpace(value)) value = "?";

        if (!string.IsNullOrWhiteSpace(header))
            return (header, value, "");

        var parts = value.Split(' ', 2, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (parts.Length == 1) return ("", parts[0], "");
        return ("", parts[0], parts[1]);
    }

    private static void DrawBadgeLine(SKCanvas canvas, string text, float x, float y, SKPaint paint, float preferred, float minimum, float maxWidth)
    {
        if (string.IsNullOrWhiteSpace(text)) return;
        paint.TextSize = preferred;
        var measured = Math.Max(1f, paint.MeasureText(text));
        if (measured > maxWidth)
            paint.TextSize = Math.Max(minimum, preferred * maxWidth / measured);
        canvas.DrawText(text, x, y, paint);
    }

    private static SKPath Hexagon(float cx, float cy, float rx, float ry)
    {
        var path = new SKPath();
        path.MoveTo(cx, cy - ry);
        path.LineTo(cx + rx * .86f, cy - ry * .5f);
        path.LineTo(cx + rx * .86f, cy + ry * .5f);
        path.LineTo(cx, cy + ry);
        path.LineTo(cx - rx * .86f, cy + ry * .5f);
        path.LineTo(cx - rx * .86f, cy - ry * .5f);
        path.Close();
        return path;
    }

    private static bool DrawArtwork(SKCanvas canvas, ComparisonCard card, SKRect destination)
    {
        if (string.IsNullOrWhiteSpace(card.ImagePath)) return false;

        try
        {
            var resolved = WebImageSource.ResolveToLocalFile(card.ImagePath);
            if (string.IsNullOrWhiteSpace(resolved) || !File.Exists(resolved)) return false;
            using var bitmap = SKBitmap.Decode(resolved);
            if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0) return false;

            var cropLeft = Math.Clamp(card.ImageCropLeft, 0, .95);
            var cropTop = Math.Clamp(card.ImageCropTop, 0, .95);
            var cropRight = Math.Clamp(card.ImageCropRight, 0, .95);
            var cropBottom = Math.Clamp(card.ImageCropBottom, 0, .95);
            var src = new SKRect(
                (float)(bitmap.Width * cropLeft),
                (float)(bitmap.Height * cropTop),
                (float)(bitmap.Width * (1 - cropRight)),
                (float)(bitmap.Height * (1 - cropBottom)));
            if (src.Width < 1 || src.Height < 1) return false;

            var transparent = bitmap.AlphaType != SKAlphaType.Opaque;
            var baseScale = transparent
                ? Math.Min(destination.Width * .92f / src.Width, destination.Height * .92f / src.Height)
                : Math.Max(destination.Width / src.Width, destination.Height / src.Height);
            var scale = baseScale * (float)Math.Clamp(card.ImageScale, .45, 2.5);
            var width = src.Width * scale;
            var height = src.Height * scale;
            var offsetScale = destination.Width / 471f;
            var cx = destination.MidX + (float)card.ImageX * offsetScale;
            var cy = destination.MidY + (float)card.ImageY * offsetScale;
            var target = new SKRect(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);

            canvas.Save();
            canvas.ClipRect(destination);
            if (Math.Abs(card.ImageRotation) > .001)
                canvas.RotateDegrees((float)card.ImageRotation, cx, cy);
            using var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High };
            canvas.DrawBitmap(bitmap, src, target, paint);
            canvas.Restore();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static void DrawFallbackArtwork(SKCanvas canvas, ComparisonProject project, ComparisonCard card, SKRect destination)
    {
        var glyph = FallbackGlyph(card);
        using var glow = TextPaint(project, 230, new SKColor(30, 162, 255), bold: true);
        glow.TextAlign = SKTextAlign.Center;
        glow.MaskFilter = SKMaskFilter.CreateBlur(SKBlurStyle.Normal, 18);
        canvas.DrawText(glyph, destination.MidX, destination.MidY + 78, glow);

        using var text = TextPaint(project, 230, SKColors.White, bold: true);
        text.TextAlign = SKTextAlign.Center;
        var measured = Math.Max(1f, text.MeasureText(glyph));
        if (measured > destination.Width * .86f)
            text.TextSize *= destination.Width * .86f / measured;
        canvas.DrawText(glyph, destination.MidX, destination.MidY + 78, text);
    }

    private static string FallbackGlyph(ComparisonCard card)
    {
        var value = card.Value?.Trim() ?? "";
        if (value.Contains('∞')) return "∞";
        if (value == "?") return "?";

        var words = (card.Title ?? "")
            .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (words.Length >= 2)
            return string.Concat(words.Take(2).Select(x => char.ToUpperInvariant(x[0])));
        if (words.Length == 1 && words[0].Length > 0)
            return char.ToUpperInvariant(words[0][0]).ToString();
        return "?";
    }

    private static void DrawFittedCentredText(SKCanvas canvas, ComparisonProject project, string text, SKRect box, SKColor color, float preferred, float minimum, bool bold)
    {
        using var paint = TextPaint(project, preferred, color, bold);
        paint.TextAlign = SKTextAlign.Center;
        var measured = Math.Max(1f, paint.MeasureText(text));
        if (measured > box.Width)
            paint.TextSize = Math.Max(minimum, preferred * box.Width / measured);
        var metrics = paint.FontMetrics;
        var baseline = box.MidY - (metrics.Ascent + metrics.Descent) / 2f;
        canvas.DrawText(text, box.MidX, baseline, paint);
    }

    private static SKPaint TextPaint(ComparisonProject project, float size, SKColor color, bool bold)
    {
        SKTypeface typeface;
        try
        {
            typeface = !string.IsNullOrWhiteSpace(project.RenderFontFile) && File.Exists(project.RenderFontFile)
                ? SKTypeface.FromFile(project.RenderFontFile)
                : SKTypeface.FromFamilyName(
                    string.IsNullOrWhiteSpace(project.RenderFontFamily) ? "Segoe UI" : project.RenderFontFamily,
                    bold ? SKFontStyle.Bold : SKFontStyle.Normal);
        }
        catch
        {
            typeface = SKTypeface.Default;
        }

        return new SKPaint
        {
            IsAntialias = true,
            SubpixelText = true,
            Typeface = typeface,
            TextSize = size,
            Color = color,
        };
    }
}
