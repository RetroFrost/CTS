using System.Globalization;
using Microsoft.VisualBasic.FileIO;

namespace CubicalCompare.Core.Project;

public sealed class CsvImportResult
{
    public List<ComparisonCard> Cards { get; init; } = [];
    public List<string> Warnings { get; init; } = [];
    public double? DurationSeconds { get; init; }
}

public static class CsvImportService
{
    private static readonly Dictionary<string, string[]> Aliases = new(StringComparer.OrdinalIgnoreCase)
    {
        ["title"] = ["title", "name", "item", "subject", "card title", "heading"],
        ["value"] = ["value", "badge", "amount", "number", "age", "rank"],
        ["badge_value"] = ["badge value", "badge_value", "badge primary", "badge_primary", "primary"],
        ["badge_header"] = ["badge header", "badge_header", "header", "badge label", "label"],
        ["badge_unit"] = ["badge unit", "badge_unit", "badge secondary", "badge_secondary", "unit", "suffix"],
        ["description"] = ["description", "details", "desc", "summary", "caption", "text"],
        ["image"] = ["image", "image url", "image_url", "image path", "artwork", "artwork url", "artwork_url", "image link", "web image url", "web artwork url", "highlight image", "icon", "icon url", "icon_url", "picture", "photo", "thumbnail", "web url", "url"],
        ["image_x"] = ["image x", "image_x", "artwork x", "x"],
        ["image_y"] = ["image y", "image_y", "artwork y", "y"],
        ["image_scale"] = ["image scale", "image_scale", "artwork scale", "scale"],
        ["image_rotation"] = ["image rotation", "image_rotation", "rotation"],
        ["crop_left"] = ["crop left", "image_crop_left", "crop_left"],
        ["crop_top"] = ["crop top", "image_crop_top", "crop_top"],
        ["crop_right"] = ["crop right", "image_crop_right", "crop_right"],
        ["crop_bottom"] = ["crop bottom", "image_crop_bottom", "crop_bottom"],
        ["image_layer"] = ["image layer", "image_layer", "layer"],
        ["thumbnail_background"] = ["thumbnail background", "thumbnail_background", "background", "icon background", "transparent background"],
    };

    public static async Task<CsvImportResult> ImportAsync(
        string path,
        CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var fullPath = Path.GetFullPath(path);
        if (!File.Exists(fullPath))
            throw new FileNotFoundException("CSV file not found.", fullPath);

        var rows = ParseRows(fullPath);
        if (rows.Count == 0)
            throw new InvalidDataException("The CSV file is empty.");

        var warnings = new List<string>();
        var header = rows[0];
        var mapping = BuildMapping(header);
        var hasRecognizedHeader = mapping.Count >= 2 || mapping.ContainsKey("image") || mapping.ContainsKey("title");

        if (rows.SelectMany(row => row).Any(cell => cell.Any(character =>
                char.IsControl(character) && character is not ('\t' or '\r' or '\n'))))
            throw new InvalidDataException("The selected file is not readable CSV text.");
        if (!hasRecognizedHeader && !rows.Any(row => row.Length > 1))
            throw new InvalidDataException("The selected file has no CSV columns. Use comma, semicolon or tab-separated data, or a recognized header such as Title.");

        var dataStart = hasRecognizedHeader ? 1 : 0;
        if (!hasRecognizedHeader)
        {
            warnings.Add("No familiar header row was found; columns were read as Title, Value, Description, Image, Badge Header.");
            mapping = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
            {
                ["title"] = 0,
                ["value"] = 1,
                ["description"] = 2,
                ["image"] = 3,
                ["badge_header"] = 4,
                ["badge_value"] = 1,
                ["badge_unit"] = -1,
            };
        }

        var durationIndex = FindHeaderIndex(
            header,
            "duration", "video duration", "target duration", "length", "video length");
        double? durationSeconds = null;
        var durationRows = new List<(int RowNumber, string Value)>();

        var cards = new List<ComparisonCard>();
        var csvDirectory = Path.GetDirectoryName(fullPath) ?? Environment.CurrentDirectory;

        for (var rowIndex = dataStart; rowIndex < rows.Count; rowIndex++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var row = rows[rowIndex];
            if (row.All(string.IsNullOrWhiteSpace)) continue;

            if (durationIndex >= 0 && durationIndex < row.Length && !string.IsNullOrWhiteSpace(row[durationIndex]))
                durationRows.Add((rowIndex + 1, row[durationIndex].Trim()));

            string Cell(string role)
                => mapping.TryGetValue(role, out var index) && index >= 0 && index < row.Length
                    ? (row[index] ?? string.Empty).Trim()
                    : string.Empty;

            var title = Cell("title");
            var legacyValue = Cell("value");
            var badgeValue = Cell("badge_value");
            var badgeHeader = Cell("badge_header");
            var badgeUnit = Cell("badge_unit");
            var description = Cell("description");
            if (string.IsNullOrWhiteSpace(badgeValue))
                badgeValue = legacyValue;
            var image = ResolveImageCell(Cell("image"), csvDirectory);
            if (WebImageSource.IsRemoteSource(image) && !WebImageSource.IsAllowedWebSource(image))
            {
                warnings.Add($"CSV row {rowIndex + 1}: image URL rejected. Use a public HTTP(S) image or image-page URL.");
                image = string.Empty;
            }

            var card = new ComparisonCard
            {
                Title = string.IsNullOrWhiteSpace(title) ? $"Card {cards.Count + 1}" : title,
                Value = string.Join(' ', new[] { badgeValue, badgeUnit }.Where(x => !string.IsNullOrWhiteSpace(x))),
                BadgeHeader = badgeHeader,
                BadgeValue = badgeValue,
                BadgeUnit = badgeUnit,
                Description = description,
                ImagePath = image,
                ImageX = ParseDouble(Cell("image_x"), 0),
                ImageY = ParseDouble(Cell("image_y"), 0),
                ImageScale = ImageTransformScale.Normalize(ParseDouble(Cell("image_scale"), 1)),
                ImageRotation = Math.Clamp(ParseDouble(Cell("image_rotation"), 0), -360, 360),
                ImageCropLeft = Math.Clamp(ParseDouble(Cell("crop_left"), 0), 0, .95),
                ImageCropTop = Math.Clamp(ParseDouble(Cell("crop_top"), 0), 0, .95),
                ImageCropRight = Math.Clamp(ParseDouble(Cell("crop_right"), 0), 0, .95),
                ImageCropBottom = Math.Clamp(ParseDouble(Cell("crop_bottom"), 0), 0, .95),
                ImageLayer = Cell("image_layer").Equals("front", StringComparison.OrdinalIgnoreCase) ? "front" : "behind",
                ThumbnailBackgroundColor = Cell("thumbnail_background"),
            };
            cards.Add(card);
        }

        if (cards.Count == 0)
            throw new InvalidDataException("The CSV file has no data rows.");

        var remoteSources = cards
            .Select((card, index) => (card, index))
            .Where(x => WebImageSource.IsRemoteSource(x.card.ImagePath))
            .GroupBy(x => WebImageSource.NormalizeSource(x.card.ImagePath), StringComparer.Ordinal)
            .Select(group => (Source: group.Key, Rows: group.Select(x => x.index + 1).ToArray()))
            .ToArray();

        if (remoteSources.Length > 0)
        {
            var tasks = remoteSources.Select(async entry =>
            {
                try
                {
                    await WebImageSource.ResolveToLocalFileAsync(entry.Source, cancellationToken).ConfigureAwait(false);
                    return (entry.Source, entry.Rows, Error: (string?)null);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    return (entry.Source, entry.Rows, Error: ex.Message);
                }
            });

            foreach (var result in await Task.WhenAll(tasks).ConfigureAwait(false))
            {
                if (!string.IsNullOrWhiteSpace(result.Error))
                    warnings.Add($"Web image for CSV row(s) {string.Join(", ", result.Rows)} could not be loaded: {result.Error}");
            }
        }

        if (durationRows.Count > 0)
        {
            foreach (var entry in durationRows)
            {
                try
                {
                    var parsed = ParseDuration(entry.Value);
                    durationSeconds ??= parsed;
                    if (durationSeconds.HasValue && Math.Abs(durationSeconds.Value - parsed) > 0.001)
                        warnings.Add($"CSV row {entry.RowNumber}: Duration {entry.Value} differs from the first Duration value; using the first value.");
                }
                catch (FormatException ex)
                {
                    warnings.Add($"CSV row {entry.RowNumber}: invalid Duration '{entry.Value}' ({ex.Message}); the imported cards were kept but project timing was not changed.");
                }
            }
        }

        return new CsvImportResult
        {
            Cards = cards,
            Warnings = warnings,
            DurationSeconds = durationSeconds,
        };
    }

    private static List<string[]> ParseRows(string path)
    {
        var delimiter = DetectDelimiter(path);
        using var parser = new TextFieldParser(path)
        {
            TextFieldType = FieldType.Delimited,
            HasFieldsEnclosedInQuotes = true,
            TrimWhiteSpace = false,
        };
        parser.SetDelimiters(delimiter);

        var rows = new List<string[]>();
        while (!parser.EndOfData)
        {
            try
            {
                var fields = parser.ReadFields();
                if (fields is not null)
                    rows.Add(fields);
            }
            catch (MalformedLineException ex)
            {
                throw new InvalidDataException($"Malformed CSV near line {ex.Message}.", ex);
            }
        }
        return rows;
    }

    private static string DetectDelimiter(string path)
    {
        using var reader = new StreamReader(path, detectEncodingFromByteOrderMarks: true);
        string? line;
        do { line = reader.ReadLine(); } while (line is not null && string.IsNullOrWhiteSpace(line));
        line ??= string.Empty;
        var candidates = new[] { ",", ";", "\t" };
        return candidates
            .OrderByDescending(candidate => CountOutsideQuotes(line, candidate[0]))
            .First();
    }

    private static int CountOutsideQuotes(string line, char delimiter)
    {
        var quoted = false;
        var count = 0;
        for (var index = 0; index < line.Length; index++)
        {
            if (line[index] == '"')
            {
                if (quoted && index + 1 < line.Length && line[index + 1] == '"')
                {
                    index++;
                    continue;
                }
                quoted = !quoted;
            }
            else if (!quoted && line[index] == delimiter)
            {
                count++;
            }
        }
        return count;
    }

    private static Dictionary<string, int> BuildMapping(IReadOnlyList<string> headers)
    {
        var normalized = headers.Select(NormalizeHeader).ToArray();
        var result = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var (role, aliases) in Aliases)
        {
            for (var index = 0; index < normalized.Length; index++)
            {
                if (aliases.Any(alias => NormalizeHeader(alias) == normalized[index]))
                {
                    result[role] = index;
                    break;
                }
            }
        }
        return result;
    }

    private static string NormalizeHeader(string? value)
        => string.Join(' ',
            (value ?? string.Empty)
                .Trim()
                .ToLowerInvariant()
                .Replace('_', ' ')
                .Replace('-', ' ')
                .Split(' ', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries));

    private static string ResolveImageCell(string value, string csvDirectory)
    {
        value = WebImageSource.NormalizeSource(value);
        if (string.IsNullOrWhiteSpace(value) || WebImageSource.IsRemoteSource(value))
            return value;

        try
        {
            if (Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.IsFile)
                return uri.LocalPath;

            var candidate = Path.IsPathRooted(value)
                ? value
                : Path.Combine(csvDirectory, value);
            return File.Exists(candidate) ? Path.GetFullPath(candidate) : value;
        }
        catch
        {
            return value;
        }
    }

    private static int FindHeaderIndex(IReadOnlyList<string> headers, params string[] aliases)
    {
        for (var index = 0; index < headers.Count; index++)
        {
            var normalized = NormalizeHeader(headers[index]);
            if (aliases.Any(alias => NormalizeHeader(alias) == normalized))
                return index;
        }
        return -1;
    }

    private static double ParseDuration(string value)
    {
        var text = value.Trim();
        if (double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds) &&
            double.IsFinite(seconds) && seconds >= 1)
            return seconds;

        var parts = text.Split(':');
        if (parts.Length is not (2 or 3) || parts.Any(part => !int.TryParse(part, NumberStyles.Integer, CultureInfo.InvariantCulture, out _)))
            throw new FormatException("use seconds, MM:SS, or HH:MM:SS");

        var numbers = parts.Select(part => int.Parse(part, CultureInfo.InvariantCulture)).ToArray();
        var minutes = parts.Length == 2 ? numbers[0] : numbers[1];
        var secs = numbers[^1];
        var hours = parts.Length == 3 ? numbers[0] : 0;

        if (secs is < 0 or > 59 || minutes is < 0 or > 59 || hours < 0)
            throw new FormatException("minutes and seconds must be below 60");

        var total = hours * 3600d + minutes * 60d + secs;
        if (!double.IsFinite(total) || total < 1)
            throw new FormatException("duration must be at least one second");
        return total;
    }

    private static double ParseDouble(string value, double fallback)
    {
        if (string.IsNullOrWhiteSpace(value)) return fallback;
        if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var invariant) &&
            double.IsFinite(invariant))
            return invariant;
        if (double.TryParse(value, NumberStyles.Float, CultureInfo.CurrentCulture, out var current) &&
            double.IsFinite(current))
            return current;
        return fallback;
    }
}
