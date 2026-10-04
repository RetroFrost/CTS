using SkiaSharp;

namespace CubicalCompare.Windows;

public static class RendererExportRegressionChecks
{
    public static void Run()
    {
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
            Parallel.For(0, 2, _ =>
            {
                using var engine = new RendererEngine();
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
}
