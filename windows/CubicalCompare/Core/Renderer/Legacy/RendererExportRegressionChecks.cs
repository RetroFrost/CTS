using SkiaSharp;
using System.Text.Json;

namespace CubicalCompare.Windows;

public static class RendererExportRegressionChecks
{
    public static void Run()
    {
        RunRibbonTiming();
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
                var expected = local >= 2 ? SKColors.Green : SKColors.Black;
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

}
