using SixLabors.ImageSharp;
using SixLabors.ImageSharp.PixelFormats;

namespace CubicalCompare.Core.MegaPack;

public static class ContactSheetDetector
{
    private readonly record struct Run(int Start, int End)
    {
        public int Length => End - Start + 1;
    }

    public static IReadOnlyList<PixelRect> Detect(Image<Rgba32> image, Zipack2SeparatorDefinition settings)
    {
        ArgumentNullException.ThrowIfNull(image);
        ArgumentNullException.ThrowIfNull(settings);

        var yellowPerColumn = new int[image.Width];
        var yellowPerRow = new int[image.Height];

        image.ProcessPixelRows(accessor =>
        {
            for (var y = 0; y < accessor.Height; y++)
            {
                var row = accessor.GetRowSpan(y);
                var rowCount = 0;
                for (var x = 0; x < row.Length; x++)
                {
                    if (!IsSeparatorPixel(row[x], settings)) continue;
                    yellowPerColumn[x]++;
                    rowCount++;
                }
                yellowPerRow[y] = rowCount;
            }
        });

        var verticalRuns = CollapseRuns(
            yellowPerColumn,
            value => value / (double)Math.Max(1, image.Height) >= settings.MinimumCoverage);
        var horizontalRuns = CollapseRuns(
            yellowPerRow,
            value => value / (double)Math.Max(1, image.Width) >= settings.MinimumCoverage);

        var columns = BuildContentSpans(image.Width, verticalRuns, settings.MinimumCardWidth);
        var rows = BuildContentSpans(image.Height, horizontalRuns, settings.MinimumCardHeight);

        if (columns.Count == 0) columns.Add((0, image.Width));
        if (rows.Count == 0) rows.Add((0, image.Height));

        var results = new List<PixelRect>(columns.Count * rows.Count);
        foreach (var row in rows)
        {
            foreach (var column in columns)
            {
                var rect = new PixelRect(column.Start, row.Start, column.Length, row.Length);
                if (rect.Width < settings.MinimumCardWidth || rect.Height < settings.MinimumCardHeight) continue;
                if (IsMostlySeparator(image, rect, settings)) continue;
                results.Add(rect);
            }
        }

        return results
            .OrderBy(x => x.Y)
            .ThenBy(x => x.X)
            .ToArray();
    }

    public static IReadOnlyList<PixelRect> ValidatePredefinedRegions(
        Image<Rgba32> image,
        IEnumerable<Zipack2RegionDefinition> regions,
        Zipack2SeparatorDefinition settings)
    {
        return regions
            .OrderBy(x => x.Order)
            .Select(region => new PixelRect(region.X, region.Y, region.Width, region.Height))
            .Where(rect => rect.X >= 0 && rect.Y >= 0 && rect.Width >= settings.MinimumCardWidth && rect.Height >= settings.MinimumCardHeight)
            .Where(rect => rect.Right <= image.Width && rect.Bottom <= image.Height)
            .ToArray();
    }

    private static bool IsSeparatorPixel(Rgba32 pixel, Zipack2SeparatorDefinition settings)
    {
        if (pixel.A < 96) return false;
        var tolerance = Math.Clamp(settings.Tolerance, 0, 96);
        return Math.Abs(pixel.R - settings.Red) <= tolerance
            && Math.Abs(pixel.G - settings.Green) <= tolerance
            && Math.Abs(pixel.B - settings.Blue) <= tolerance;
    }

    private static List<Run> CollapseRuns(int[] values, Func<int, bool> qualifies)
    {
        var runs = new List<Run>();
        var start = -1;
        for (var i = 0; i < values.Length; i++)
        {
            if (qualifies(values[i]))
            {
                if (start < 0) start = i;
                continue;
            }

            if (start >= 0)
            {
                runs.Add(new Run(start, i - 1));
                start = -1;
            }
        }

        if (start >= 0) runs.Add(new Run(start, values.Length - 1));
        return runs;
    }

    private static List<(int Start, int Length)> BuildContentSpans(int size, IReadOnlyList<Run> separators, int minimumLength)
    {
        var spans = new List<(int Start, int Length)>();
        if (size <= 0) return spans;
        if (separators.Count == 0)
        {
            if (size >= minimumLength) spans.Add((0, size));
            return spans;
        }

        if (separators[0].Start >= minimumLength)
            spans.Add((0, separators[0].Start));

        for (var i = 0; i < separators.Count - 1; i++)
        {
            var start = separators[i].End + 1;
            var endExclusive = separators[i + 1].Start;
            var length = endExclusive - start;
            if (length >= minimumLength) spans.Add((start, length));
        }

        var tailStart = separators[^1].End + 1;
        var tailLength = size - tailStart;
        if (tailLength >= minimumLength) spans.Add((tailStart, tailLength));
        return spans;
    }

    private static bool IsMostlySeparator(Image<Rgba32> image, PixelRect rect, Zipack2SeparatorDefinition settings)
    {
        var sampleStepX = Math.Max(1, rect.Width / 20);
        var sampleStepY = Math.Max(1, rect.Height / 20);
        var sampled = 0;
        var separators = 0;

        image.ProcessPixelRows(accessor =>
        {
            for (var y = rect.Y; y < rect.Bottom; y += sampleStepY)
            {
                var row = accessor.GetRowSpan(y);
                for (var x = rect.X; x < rect.Right; x += sampleStepX)
                {
                    sampled++;
                    if (IsSeparatorPixel(row[x], settings)) separators++;
                }
            }
        });

        return sampled > 0 && separators / (double)sampled > 0.92;
    }
}
