using SkiaSharp;
using System.Text.Json;

namespace CubicalCompare.Windows;

public static class RendererExportRegressionChecks
{
    public static void Run()
    {
        RunArtworkScaling();
        RunRibbonTiming();
        RunTextCache();
        var directory = Path.Combine(Path.GetTempPath(), "CubicalCompare-render-cache-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var paths = new List<string>();
            for (var index = 0; index < 40; index++)
            {
                using var bitmap = new SKBitmap(1024, 1024);
                bitmap.Erase(new SKColor((byte)(index * 5), 80, 180));
                using var image = SKImage.FromBitmap(bitmap);
                using var data = image.Encode(SKEncodedImageFormat.Png, 100);
                var path = Path.Combine(directory, index + ".png");
                File.WriteAllBytes(path, data.ToArray());
                paths.Add(path);
            }
            Parallel.For(0, 2, workerIndex =>
            {
                using var engine = new RendererEngine { UseFastImageSampling = workerIndex == 1 };
                var card = new StudioCard { Title = "", Value = "" };
                var project = new StudioProject { Cards = [card], ShowBadges = false };
                var spec = new RendererSpec { Engine = "standard" };
                SKColor first = default;
                for (var index = 0; index < paths.Count; index++)
                {
                    card.Image = paths[index];
                    using var frame = engine.Render(project, spec, 0, 192, 108);
                    if (index == 0) first = frame.GetPixel(20, 20);
                    if (engine.DecodedImageCacheBytes <= 0 || engine.DecodedImageCacheBytes > RendererEngine.DecodedImageCacheBudgetBytes)
                        throw new InvalidOperationException("Decoded artwork cache exceeded its native memory budget.");
                }
                card.Image = paths[0];
                using var repeated = engine.Render(project, spec, 0, 192, 108);
                if (repeated.GetPixel(20, 20) != first)
                    throw new InvalidOperationException("Re-decoding evicted artwork changed rendered pixels.");
                engine.Dispose();
                try { using var frame = engine.Render(project, spec, 0, 192, 108); }
                catch (ObjectDisposedException) { return; }
                throw new InvalidOperationException("A disposed renderer remained usable.");
            });
        }
        finally { Directory.Delete(directory, recursive: true); }
    }
    private static void RunArtworkScaling()
    {
        var path = Path.Combine(Path.GetTempPath(), "CubicalCompare-scale-" + Guid.NewGuid().ToString("N") + ".png");
        try
        {
            using (var source = new SKBitmap(10, 10))
            {
                source.Erase(SKColors.Red);
                using var image = SKImage.FromBitmap(source);
                using var data = image.Encode(SKEncodedImageFormat.Png, 100);
                File.WriteAllBytes(path, data.ToArray());
            }
            var draw = typeof(RendererEngine).GetMethod("DrawImage", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
            foreach (var fast in new[] { false, true })
            {
                using var engine = new RendererEngine { UseFastImageSampling = fast };
                foreach (var scale in new[] { 0d, .01, .04, 1, 12, 20, 10000 })
                {
                    using var bitmap = new SKBitmap(512, 512);
                    using var canvas = new SKCanvas(bitmap);
                    canvas.Clear(SKColors.Black);
                    float baseSize = scale < 1 ? 100 : 10;
                    var dest = new SKRect(256 - baseSize / 2, 256 - baseSize / 2, 256 + baseSize / 2, 256 + baseSize / 2);
                    draw.Invoke(engine, [canvas, new StudioCard { Image = path, ImageScale = scale }, dest, false, 1f, (SKRect?)new SKRect(0, 0, 512, 512)]);
                    var count = 0;
                    for (var x = 0; x < 512; x++) if (bitmap.GetPixel(x, 256).Red > 30) count++;
                    var expected = Math.Min(512, baseSize * scale);
                    if (Math.Abs(count - expected) > 2)
                        throw new InvalidOperationException($"Artwork scale {scale} rendered {count}px, expected {expected}px (fast={fast}).");
                }
            }
        }
        finally { if (File.Exists(path)) File.Delete(path); }
    }

    private static void RunRibbonTiming()
    {
        static JsonElement Json(string value) => JsonDocument.Parse(value).RootElement.Clone();
        var spec = new RendererSpec
        {
            Engine = "ribbon-exact", ReferenceWidth = 64, ReferenceHeight = 36,
            CanonicalCardCount = 110, CanonicalFrameCount = 23862,
            ContinuousStartFrame = 521, ContinuousStepFrames = 214,
            OpeningEnds = [120, 240, 390, 521],
            EndWipeFrames = 0, EndRiseFrames = 0, EndHoldFrames = 429, FadeFrames = 0, BlackTailFrames = 0,
            RequiredFeatures = ["project-card-data", "ribbon-scene-overlays-v1"],
            SceneV3 = new RendererSceneV3
            {
                Root = Json("{}"), Assets = new(), Selectors = [], Layers = [], Frames = 23862,
                Resources = new() { ["last-card"] = Json("""{"type":"rect","width":64,"height":36}""") },
                Objects = [new RendererObjectV3
                {
                    Id = "last-card", Kind = "ribbonOverlay", Resource = "last-card", Frame = 23433,
                    LifespanStart = 23433, LifespanEnd = 23861,
                    Raw = Json("""{"cardIndex":109,"ribbonPhase":"outro"}"""),
                    Properties = Json("""{"color":"$card.value","opacity":{"track":[[23433,0],[23435,1]],"timeline":"absolute"}}"""),
                }],
            },
        };
        using var engine = new RendererEngine();
        foreach (var test in new[] { (0, 1), (1, 777), (2, 897), (3, 1047), (4, 1178), (5, 1392), (40, 8882), (110, 23862), (150, 32422) })
        {
            var project = new StudioProject { Cards = Enumerable.Range(0, test.Item1).Select(_ => new StudioCard { Value = "#ff0000" }).ToList() };
            if (engine.FrameCount(project, spec) != test.Item2)
                throw new InvalidOperationException($"Ribbon length ignored {test.Item1} live cards.");
            if (test.Item1 == 0) continue;
            project.Cards[^1].Value = "#00ff00";
            var contentEnd = test.Item2 - spec.OutroFrames;
            foreach (var local in new[] { 428, 2, 214, 0, 7, 2 })
            {
                using var frame = engine.Render(project, spec, contentEnd + local, 64, 36);
                var expected = local >= 2 ? new SKColor(0, 255, 0) : SKColors.Black;
                // The reference background is black. This tests direct/reverse
                // seeking, absolute outro tracks, and last-card rebinding together.
                if (frame.GetPixel(32, 18) != expected)
                    throw new InvalidOperationException($"Ribbon outro clock/binding failed with {test.Item1} cards at {local}.");
            }
            project.AutoLength = false;
            project.CustomLengthSeconds = 60;
            if (engine.FrameCount(project, spec) != 3600)
                throw new InvalidOperationException("Explicit custom duration was overridden.");
        }
    }

    private static void RunTextCache()
    {
        using var engine = new RendererEngine();
        var card = new StudioCard { Title = "An editable title that needs wrapping", Description = "A longer editable description must retain the same line breaks when fonts and card data change." };
        var project = new StudioProject { Cards = [card], ShowBadges = false };
        var spec = new RendererSpec { Engine = "standard" };
        using var first = engine.Render(project, spec, 0, 320, 180);
        var faces = engine.CachedTypefaceCount;
        var layouts = engine.CachedTextLayoutCount;
        using var repeated = engine.Render(project, spec, 0, 320, 180);
        if (faces == 0 || layouts == 0 || engine.CachedTypefaceCount != faces || engine.CachedTextLayoutCount != layouts)
            throw new InvalidOperationException("Repeated frames did not reuse native fonts and text layouts.");
        for (var y = 0; y < first.Height; y++)
            for (var x = 0; x < first.Width; x++)
                if (first.GetPixel(x, y) != repeated.GetPixel(x, y))
                    throw new InvalidOperationException("Cached text changed rendered pixels.");
        for (var index = 0; index < 80; index++)
        {
            project.FontFamily = "Cache regression font " + index;
            card.Description = "Changed description " + index;
            using var frame = engine.Render(project, spec, 0, 320, 180);
            if (engine.CachedTypefaceCount > 32 || engine.CachedTextLayoutCount > 512)
                throw new InvalidOperationException("Font or layout caches exceeded their entry budgets.");
        }
        engine.Dispose();
        if (engine.CachedTypefaceCount != 0 || engine.CachedTextLayoutCount != 0)
            throw new InvalidOperationException("Native fonts were retained after renderer disposal.");
    }

}
