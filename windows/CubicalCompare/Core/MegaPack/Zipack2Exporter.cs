using System.IO.Compression;
using System.Text.Json;
using CubicalCompare.Core.Project;
using CubicalCompare.Core.Thumbnail;
using SkiaSharp;

namespace CubicalCompare.Core.MegaPack;

public sealed record Zipack2ExportResult(string Path, int ContactSheets, int Cards, string ThumbnailPath);

public static class Zipack2Exporter
{
    private const int SeparatorSize = 8;

    // There is deliberately no card-count or contact-sheet-dimension cap here. A sheet can contain
    // any number of artwork items; the only remaining bound is the amount of raster memory that can
    // be represented safely by this process. This keeps the format count-unlimited without letting a
    // malformed/absurd project ask Skia for an effectively unbounded native allocation.
    private const long MaxSheetRasterBytes = 1536L * 1024 * 1024;

    private static readonly HashSet<string> SoundtrackExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".wav", ".m4a", ".aac", ".wma",
    };

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
    };

    public static async Task<Zipack2ExportResult> ExportAsync(
        ComparisonProject project,
        string destinationPath,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(project);
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationPath);
        if (project.Cards.Count == 0)
            throw new InvalidOperationException("Add at least one card before exporting a MegaPack.");

        var parent = Path.GetDirectoryName(destinationPath);
        if (!string.IsNullOrWhiteSpace(parent)) Directory.CreateDirectory(parent);

        var work = BuildArtworkItems(project);
        var sheets = BuildSheetPlans(work);
        var thumbnail = AutoThumbnailGenerator.Generate(project);
        var soundtrackSource = ResolveSoundtrack(project.SoundtrackPath);
        var soundtrackArchivePath = soundtrackSource is null
            ? string.Empty
            : "audio/soundtrack" + Path.GetExtension(soundtrackSource).ToLowerInvariant();

        var manifest = new Zipack2Manifest
        {
            Name = string.IsNullOrWhiteSpace(project.Name) ? "MegaPack" : project.Name.Trim(),
            Thumbnail = "thumbnail.png",
            ShowBadges = project.ShowBadges,
            CreditsEnabled = project.CreditsEnabled,
            DurationSeconds = project.AutoLength ? 0 : Math.Max(0, project.CustomLengthSeconds),
            Cards = project.Cards.Select(ToManifestCard).ToList(),
            Soundtrack = soundtrackArchivePath,
            SoundtrackLoop = project.SoundtrackLoop,
            SoundtrackVolume = double.IsFinite(project.SoundtrackVolume) ? Math.Clamp(project.SoundtrackVolume, 0, 1) : 1.0,
        };

        var tempPath = destinationPath + ".partial-" + Guid.NewGuid().ToString("N");
        try
        {
            await using (var output = new FileStream(tempPath, FileMode.CreateNew, FileAccess.ReadWrite, FileShare.None, 128 * 1024, useAsync: true))
            using (var archive = new ZipArchive(output, ZipArchiveMode.Create, leaveOpen: true))
            {
                var sheetIndex = 0;
                foreach (var plan in sheets)
                {
                    cancellationToken.ThrowIfCancellationRequested();
                    var path = $"artwork/contact-sheet-{sheetIndex + 1:000}.png";
                    var definition = RenderSheet(archive, plan, path);
                    definition.Order = sheetIndex;
                    manifest.ContactSheets.Add(definition);
                    sheetIndex++;
                }

                await WriteBytesAsync(archive, "thumbnail.png", thumbnail.Png, CompressionLevel.NoCompression, cancellationToken);

                if (soundtrackSource is not null)
                    await WriteFileAsync(archive, soundtrackArchivePath, soundtrackSource, cancellationToken);

                var manifestBytes = JsonSerializer.SerializeToUtf8Bytes(manifest, JsonOptions);
                await WriteBytesAsync(archive, "manifest.json", manifestBytes, CompressionLevel.Optimal, cancellationToken);
            }

            File.Move(tempPath, destinationPath, overwrite: true);
            return new Zipack2ExportResult(destinationPath, sheets.Count, project.Cards.Count, thumbnail.AutoSavedPath);
        }
        catch
        {
            try { if (File.Exists(tempPath)) File.Delete(tempPath); } catch { }
            throw;
        }
        finally
        {
            foreach (var item in work) item.Dispose();
        }
    }

    private static string? ResolveSoundtrack(string path)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) return null;
        var extension = Path.GetExtension(path);
        if (!SoundtrackExtensions.Contains(extension))
            throw new InvalidDataException($"Soundtrack format '{extension}' cannot be bundled in a MegaPack.");
        return Path.GetFullPath(path);
    }

    private static Zipack2CardDefinition ToManifestCard(ComparisonCard card) => new()
    {
        Id = card.Id,
        Title = card.Title,
        Value = card.Value,
        BadgeHeader = card.BadgeHeader,
        Description = card.Description,
        ImageX = card.ImageX,
        ImageY = card.ImageY,
        ImageScale = card.ImageScale,
        ImageRotation = card.ImageRotation,
        ImageCropLeft = card.ImageCropLeft,
        ImageCropTop = card.ImageCropTop,
        ImageCropRight = card.ImageCropRight,
        ImageCropBottom = card.ImageCropBottom,
        ImageLayer = card.ImageLayer,
    };

    private static List<ArtworkItem> BuildArtworkItems(ComparisonProject project)
    {
        var result = new List<ArtworkItem>(project.Cards.Count);
        for (var index = 0; index < project.Cards.Count; index++)
        {
            var card = project.Cards[index];
            SKBitmap bitmap;
            if (!string.IsNullOrWhiteSpace(card.ImagePath) && File.Exists(card.ImagePath))
            {
                bitmap = SKBitmap.Decode(card.ImagePath)
                    ?? throw new InvalidDataException($"Card {index + 1} artwork is not a supported image.");
            }
            else
            {
                bitmap = RenderMissingArtwork(project, card);
            }

            if (bitmap.Width <= 0 || bitmap.Height <= 0)
            {
                bitmap.Dispose();
                throw new InvalidDataException($"Card {index + 1} artwork has invalid dimensions.");
            }

            result.Add(new ArtworkItem(index, bitmap));
        }
        return result;
    }

    private static List<SheetPlan> BuildSheetPlans(IReadOnlyList<ArtworkItem> items)
    {
        if (items.Count == 0) return [];

        // One plan intentionally contains every artwork item, including mixed native dimensions.
        // Regions in the manifest carry each tile's exact dimensions, so equal-size artwork is not
        // required and there is no arbitrary "images per sheet" count anymore.
        var cellWidth = items.Max(x => x.Bitmap.Width);
        var cellHeight = items.Max(x => x.Bitmap.Height);
        var (columns, rows) = ChooseGrid(items.Count, cellWidth, cellHeight);
        return [new SheetPlan(cellWidth, cellHeight, columns, rows, items.ToList())];
    }

    private static (int Columns, int Rows) ChooseGrid(int count, int cellWidth, int cellHeight)
    {
        if (count <= 0) return (1, 1);

        var unitWidth = Math.Max(1.0, cellWidth + SeparatorSize);
        var unitHeight = Math.Max(1.0, cellHeight + SeparatorSize);
        var ideal = Math.Sqrt(count * unitHeight / unitWidth);
        var center = Math.Clamp((int)Math.Round(ideal), 1, count);

        var bestColumns = center;
        var bestRows = (int)Math.Ceiling(count / (double)center);
        var bestScore = double.PositiveInfinity;

        var start = Math.Max(1, center - 32);
        var end = Math.Min(count, center + 32);
        for (var columns = start; columns <= end; columns++)
        {
            var rows = (int)Math.Ceiling(count / (double)columns);
            var sheetWidth = columns * unitWidth + SeparatorSize;
            var sheetHeight = rows * unitHeight + SeparatorSize;
            var aspectPenalty = Math.Abs(Math.Log(sheetWidth / Math.Max(1.0, sheetHeight)));
            var wastePenalty = (columns * rows - count) / (double)Math.Max(1, count) * 0.20;
            var score = aspectPenalty + wastePenalty;
            if (score >= bestScore) continue;

            bestScore = score;
            bestColumns = columns;
            bestRows = rows;
        }

        return (bestColumns, bestRows);
    }

    private static Zipack2ContactSheetDefinition RenderSheet(ZipArchive archive, SheetPlan plan, string path)
    {
        var sheetWidthLong = checked((long)plan.Columns * plan.CellWidth + (long)(plan.Columns + 1) * SeparatorSize);
        var sheetHeightLong = checked((long)plan.Rows * plan.CellHeight + (long)(plan.Rows + 1) * SeparatorSize);
        if (sheetWidthLong > int.MaxValue || sheetHeightLong > int.MaxValue)
            throw new InvalidOperationException("The contact sheet exceeds the raster dimension supported by this runtime.");

        var rasterBytes = checked(sheetWidthLong * sheetHeightLong * 4L);
        if (rasterBytes > MaxSheetRasterBytes)
        {
            throw new InvalidOperationException(
                $"This single contact sheet would need about {rasterBytes / (1024d * 1024d):N0} MiB of raster memory. " +
                "There is no image-count limit, but this sheet is too large to allocate safely as one bitmap on this machine.");
        }

        var sheetWidth = (int)sheetWidthLong;
        var sheetHeight = (int)sheetHeightLong;

        SKBitmap bitmap;
        try
        {
            bitmap = new SKBitmap(new SKImageInfo(sheetWidth, sheetHeight, SKColorType.Bgra8888, SKAlphaType.Premul));
        }
        catch (OutOfMemoryException ex)
        {
            throw new InvalidOperationException(
                "The contact sheet has no fixed image-count limit, but Windows could not allocate the raster for this many images at their current resolution.",
                ex);
        }

        using (bitmap)
        using (var canvas = new SKCanvas(bitmap))
        {
            if (bitmap.GetPixels() == IntPtr.Zero)
                throw new InvalidOperationException("Windows could not allocate the contact-sheet pixel buffer.");

            canvas.Clear(new SKColor(255, 255, 0));

            var regions = new List<Zipack2RegionDefinition>(plan.Items.Count);
            using var paint = new SKPaint { IsAntialias = false, FilterQuality = SKFilterQuality.None };

            for (var localIndex = 0; localIndex < plan.Items.Count; localIndex++)
            {
                var item = plan.Items[localIndex];
                var row = localIndex / plan.Columns;
                var column = localIndex % plan.Columns;
                var cellX = SeparatorSize + column * (plan.CellWidth + SeparatorSize);
                var cellY = SeparatorSize + row * (plan.CellHeight + SeparatorSize);

                // Keep every source image at its native resolution. Smaller images are centered in
                // their cell; the unused area remains the same yellow separator color.
                var x = cellX + (plan.CellWidth - item.Bitmap.Width) / 2;
                var y = cellY + (plan.CellHeight - item.Bitmap.Height) / 2;
                var target = new SKRect(x, y, x + item.Bitmap.Width, y + item.Bitmap.Height);
                canvas.DrawBitmap(item.Bitmap, target, paint);

                regions.Add(new Zipack2RegionDefinition
                {
                    X = x,
                    Y = y,
                    Width = item.Bitmap.Width,
                    Height = item.Bitmap.Height,
                    Order = localIndex,
                });
            }

            canvas.Flush();
            using var image = SKImage.FromBitmap(bitmap);
            using var data = image.Encode(SKEncodedImageFormat.Png, 100)
                ?? throw new InvalidOperationException("Could not encode a MegaPack contact sheet.");
            var entry = archive.CreateEntry(path, CompressionLevel.NoCompression);
            using (var stream = entry.Open()) data.SaveTo(stream);

            return new Zipack2ContactSheetDefinition
            {
                Path = path,
                ExpectedCardWidth = plan.CellWidth,
                ExpectedCardHeight = plan.CellHeight,
                Separator = new Zipack2SeparatorDefinition
                {
                    Red = 255,
                    Green = 255,
                    Blue = 0,
                    Tolerance = 20,
                    MinimumCoverage = 0.72,
                    MinimumCardWidth = Math.Min(48, plan.CellWidth),
                    MinimumCardHeight = Math.Min(48, plan.CellHeight),
                },
                Regions = regions,
            };
        }
    }

    private static SKBitmap RenderMissingArtwork(ComparisonProject project, ComparisonCard card)
    {
        const int width = 471;
        const int height = 872;
        var bitmap = new SKBitmap(new SKImageInfo(width, height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(bitmap);
        canvas.Clear(new SKColor(22, 22, 26));
        using var paint = new SKPaint
        {
            IsAntialias = true,
            Color = SKColors.White,
            TextAlign = SKTextAlign.Center,
            TextSize = 64,
            Typeface = SKTypeface.FromFamilyName(
                string.IsNullOrWhiteSpace(project.RenderFontFamily) ? "Segoe UI" : project.RenderFontFamily,
                SKFontStyle.Bold),
        };
        var label = string.IsNullOrWhiteSpace(card.Title) ? "?" : card.Title.Trim();
        var measured = Math.Max(1, paint.MeasureText(label));
        if (measured > width - 44) paint.TextSize *= (width - 44) / measured;
        canvas.DrawText(label, width / 2f, height / 2f, paint);
        return bitmap;
    }

    private static async Task WriteBytesAsync(
        ZipArchive archive,
        string path,
        byte[] bytes,
        CompressionLevel compression,
        CancellationToken cancellationToken)
    {
        var entry = archive.CreateEntry(path, compression);
        await using var stream = entry.Open();
        await stream.WriteAsync(bytes.AsMemory(), cancellationToken);
    }

    private static async Task WriteFileAsync(
        ZipArchive archive,
        string archivePath,
        string sourcePath,
        CancellationToken cancellationToken)
    {
        var entry = archive.CreateEntry(archivePath, CompressionLevel.NoCompression);
        await using var source = new FileStream(sourcePath, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, useAsync: true);
        await using var destination = entry.Open();
        await source.CopyToAsync(destination, 128 * 1024, cancellationToken);
    }

    private sealed class ArtworkItem : IDisposable
    {
        public ArtworkItem(int cardIndex, SKBitmap bitmap)
        {
            CardIndex = cardIndex;
            Bitmap = bitmap;
        }

        public int CardIndex { get; }
        public SKBitmap Bitmap { get; }
        public void Dispose() => Bitmap.Dispose();
    }

    private sealed record SheetPlan(
        int CellWidth,
        int CellHeight,
        int Columns,
        int Rows,
        IReadOnlyList<ArtworkItem> Items);
}
