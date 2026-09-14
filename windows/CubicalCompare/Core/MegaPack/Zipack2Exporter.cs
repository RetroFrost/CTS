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
    private const int MaxSheetDimension = 8192;
    private const int MaxCardsPerSheet = 24;

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

        var manifest = new Zipack2Manifest
        {
            Name = string.IsNullOrWhiteSpace(project.Name) ? "MegaPack" : project.Name.Trim(),
            Thumbnail = "thumbnail.png",
            ShowBadges = project.ShowBadges,
            CreditsEnabled = project.CreditsEnabled,
            DurationSeconds = project.AutoLength ? 0 : Math.Max(0, project.CustomLengthSeconds),
            Cards = project.Cards.Select(ToManifestCard).ToList(),
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
            if (bitmap.Width > MaxSheetDimension || bitmap.Height > MaxSheetDimension)
            {
                bitmap.Dispose();
                throw new InvalidDataException(
                    $"Card {index + 1} artwork is {bitmap.Width}×{bitmap.Height}. Zipack2 preserves full resolution and currently supports up to {MaxSheetDimension} px per dimension.");
            }

            result.Add(new ArtworkItem(index, bitmap));
        }
        return result;
    }

    private static List<SheetPlan> BuildSheetPlans(IReadOnlyList<ArtworkItem> items)
    {
        var plans = new List<SheetPlan>();
        var cursor = 0;
        while (cursor < items.Count)
        {
            var width = items[cursor].Bitmap.Width;
            var height = items[cursor].Bitmap.Height;
            var run = new List<ArtworkItem>();

            while (cursor < items.Count
                   && items[cursor].Bitmap.Width == width
                   && items[cursor].Bitmap.Height == height
                   && run.Count < MaxCardsPerSheet)
            {
                run.Add(items[cursor]);
                cursor++;
            }

            var maxColumns = Math.Max(1, (MaxSheetDimension + SeparatorSize) / (width + SeparatorSize));
            var maxRows = Math.Max(1, (MaxSheetDimension + SeparatorSize) / (height + SeparatorSize));
            var maxCapacity = Math.Max(1, Math.Min(MaxCardsPerSheet, maxColumns * maxRows));

            for (var offset = 0; offset < run.Count; offset += maxCapacity)
            {
                var chunk = run.Skip(offset).Take(maxCapacity).ToList();
                var idealColumns = Math.Max(1, (int)Math.Ceiling(Math.Sqrt(chunk.Count * (double)height / Math.Max(1, width))));
                var columns = Math.Min(maxColumns, idealColumns);
                var rows = (int)Math.Ceiling(chunk.Count / (double)columns);
                while (rows > maxRows && columns < maxColumns)
                {
                    columns++;
                    rows = (int)Math.Ceiling(chunk.Count / (double)columns);
                }
                plans.Add(new SheetPlan(width, height, columns, rows, chunk));
            }
        }
        return plans;
    }

    private static Zipack2ContactSheetDefinition RenderSheet(ZipArchive archive, SheetPlan plan, string path)
    {
        var sheetWidth = checked(plan.Columns * plan.CardWidth + Math.Max(0, plan.Columns - 1) * SeparatorSize);
        var sheetHeight = checked(plan.Rows * plan.CardHeight + Math.Max(0, plan.Rows - 1) * SeparatorSize);

        using var bitmap = new SKBitmap(new SKImageInfo(sheetWidth, sheetHeight, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(bitmap);
        canvas.Clear(new SKColor(255, 255, 0));

        var regions = new List<Zipack2RegionDefinition>(plan.Items.Count);
        for (var localIndex = 0; localIndex < plan.Items.Count; localIndex++)
        {
            var row = localIndex / plan.Columns;
            var column = localIndex % plan.Columns;
            var x = column * (plan.CardWidth + SeparatorSize);
            var y = row * (plan.CardHeight + SeparatorSize);
            var target = new SKRect(x, y, x + plan.CardWidth, y + plan.CardHeight);
            using var paint = new SKPaint { IsAntialias = false, FilterQuality = SKFilterQuality.None };
            canvas.DrawBitmap(plan.Items[localIndex].Bitmap, target, paint);

            regions.Add(new Zipack2RegionDefinition
            {
                X = x,
                Y = y,
                Width = plan.CardWidth,
                Height = plan.CardHeight,
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
            ExpectedCardWidth = plan.CardWidth,
            ExpectedCardHeight = plan.CardHeight,
            Separator = new Zipack2SeparatorDefinition
            {
                Red = 255,
                Green = 255,
                Blue = 0,
                Tolerance = 20,
                MinimumCoverage = 0.72,
                MinimumCardWidth = Math.Min(48, plan.CardWidth),
                MinimumCardHeight = Math.Min(48, plan.CardHeight),
            },
            Regions = regions,
        };
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
        int CardWidth,
        int CardHeight,
        int Columns,
        int Rows,
        IReadOnlyList<ArtworkItem> Items);
}
