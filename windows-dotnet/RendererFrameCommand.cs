using System.Text.Json;
using SkiaSharp;

namespace CubicalCompare.Windows;

/// <summary>Render exact integer checkpoints through the production preview/export compositor.</summary>
public static class RendererFrameCommand
{
    public static int Run(string[] args)
    {
        try
        {
            if (args.Length != 5) throw new ArgumentException("Usage: --render-frames renderer.renderer3 project.json output-directory 0,188,450");
            var spec = RendererBundleReader.Read(File.ReadAllBytes(args[1]));
            var project = StudioProject.Load(args[2]);
            var root = Path.GetDirectoryName(Path.GetFullPath(args[2]))!;
            foreach (var card in project.Cards)
                if (!string.IsNullOrEmpty(card.Image) && !Path.IsPathRooted(card.Image)) card.Image = Path.Combine(root, card.Image);
            Directory.CreateDirectory(args[3]);
            using var renderer = new RendererEngine();
            var frames = args[4].Split(',').Select(int.Parse).ToArray();
            foreach (var frame in frames)
            {
                if (frame < 0 || frame >= spec.CanonicalFrameCount) throw new ArgumentOutOfRangeException(nameof(frame));
                using var bitmap = renderer.Render(project, spec, frame, spec.ReferenceWidth, spec.ReferenceHeight);
                using var image = SKImage.FromBitmap(bitmap);
                using var png = image.Encode(SKEncodedImageFormat.Png, 100);
                File.WriteAllBytes(Path.Combine(args[3], $"frame-{frame:D5}.png"), png.ToArray());
            }
            File.WriteAllText(Path.Combine(args[3], "render-report.json"), JsonSerializer.Serialize(new { width=spec.ReferenceWidth, height=spec.ReferenceHeight, fps=spec.ReferenceFps, frames }));
            return 0;
        }
        catch (Exception e)
        {
            File.WriteAllText(Path.Combine(Path.GetTempPath(), "CubicalCompare-render-failure.txt"), e.ToString());
            return 1;
        }
    }
}
