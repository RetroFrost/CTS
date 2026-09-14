using System.IO.Compression;
using System.Text.Json;
using SkiaSharp;

namespace CubicalCompare.Core.MegaPack;

public static class Zipack2Importer
{
    private const int MaxEntries = 4096;
    private const long MaxEntryBytes = 256L * 1024 * 1024;
    private const long MaxExpandedBytes = 2L * 1024 * 1024 * 1024;
    private const int MaxSheets = 128;
    private const int MaxCards = 10_000;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
        AllowTrailingCommas = true,
    };

    public static async Task<Zipack2ImportResult> ImportAsync(string path, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        if (!File.Exists(path)) throw new FileNotFoundException("MegaPack Zipack2 file not found.", path);

        await using var input = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 128 * 1024, useAsync: true);
        using var archive = new ZipArchive(input, ZipArchiveMode.Read, leaveOpen: false);
        ValidateArchive(archive);

        var manifest = await ReadManifestAsync(archive, cancellationToken);
        if (!string.Equals(manifest.Format, "cubical.megapack.zipack2", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException($"Unsupported Zipack2 format '{manifest.Format}'.");
        if (manifest.Version != 2)
            throw new InvalidDataException($"Unsupported Zipack2 version {manifest.Version}.");
        if (manifest.Cards.Count > MaxCards)
            throw new InvalidDataException($"Zipack2 contains more than {MaxCards:N0} card data rows.");

        var definitions = ResolveSheetDefinitions(archive, manifest);
        if (definitions.Count == 0)
            throw new InvalidDataException("This Zipack2 pack does not contain any contact sheets.");
        if (definitions.Count > MaxSheets)
            throw new InvalidDataException($"Zipack2 pack contains too many contact sheets ({definitions.Count}).");

        ValidateSheetDefinitions(definitions);

        var extractionRoot = Path.Combine(
            Path.GetTempPath(),
            "CubicalCompare",
            "Zipack2",
            Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(extractionRoot);

        try
        {
            var allCards = new List<DetectedZipack2Card>();
            var sheetResults = new List<Zipack2SheetResult>();
            var globalIndex = 0;
            var processedSheetIndex = 0;

            foreach (var definition in definitions.OrderBy(x => x.Order).ThenBy(x => x.Path, StringComparer.OrdinalIgnoreCase))
            {
                cancellationToken.ThrowIfCancellationRequested();
                var entry = FindEntry(archive, definition.Path)
                    ?? throw new InvalidDataException($"Contact sheet '{definition.Path}' was not found in the Zipack2 pack.");

                await using var entryStream = entry.Open();
                await using var bounded = await ReadEntryAsync(entryStream, entry.Length, cancellationToken);
                using var bitmap = SKBitmap.Decode(bounded)
                    ?? throw new InvalidDataException($"Contact sheet '{definition.Path}' is not a supported image.");

                var regions = definition.Regions.Count > 0
                    ? ContactSheetDetector.ValidatePredefinedRegions(bitmap, definition.Regions, definition.Separator)
                    : ContactSheetDetector.Detect(bitmap, definition.Separator);

                if (regions.Count == 0)
                    throw new InvalidDataException($"No cards were detected on contact sheet '{definition.Path}'. Check its yellow outlines or predefined regions.");

                if (allCards.Count + regions.Count > MaxCards)
                    throw new InvalidDataException($"Zipack2 detection would produce more than {MaxCards:N0} cards.");

                // The manifest's Order is presentation metadata, not a safe filesystem key. Two sheets
                // are allowed to share an order value, so use the actual processing index to keep every
                // extracted card in its own directory and prevent silent image overwrites.
                var sheetDirectory = Path.Combine(extractionRoot, $"sheet-{processedSheetIndex++:D3}");
                Directory.CreateDirectory(sheetDirectory);
                var sheetCards = new List<DetectedZipack2Card>(regions.Count);

                for (var localIndex = 0; localIndex < regions.Count; localIndex++)
                {
                    cancellationToken.ThrowIfCancellationRequested();

                    var region = regions[localIndex];
                    var extractedPath = Path.Combine(sheetDirectory, $"card-{localIndex + 1:D4}.png");
                    ExtractCard(bitmap, region, extractedPath);

                    var confidence = ComputeConfidence(region, definition);
                    var detected = new DetectedZipack2Card
                    {
                        SheetPath = definition.Path,
                        SheetOrder = definition.Order,
                        LocalIndex = localIndex,
                        GlobalIndex = globalIndex++,
                        Bounds = region,
                        ExtractedPath = extractedPath,
                        Confidence = confidence,
                    };
                    sheetCards.Add(detected);
                    allCards.Add(detected);
                }

                sheetResults.Add(new Zipack2SheetResult
                {
                    Path = definition.Path,
                    Order = definition.Order,
                    Width = bitmap.Width,
                    Height = bitmap.Height,
                    Cards = sheetCards,
                });
            }

            for (var index = 0; index < Math.Min(allCards.Count, manifest.Cards.Count); index++)
                allCards[index].Data = manifest.Cards[index].Normalize(index);

            return new Zipack2ImportResult
            {
                Name = string.IsNullOrWhiteSpace(manifest.Name) ? Path.GetFileNameWithoutExtension(path) : manifest.Name,
                SourcePath = path,
                ExtractionDirectory = extractionRoot,
                Sheets = sheetResults,
                Cards = allCards,
                ShowBadges = manifest.ShowBadges,
                CreditsEnabled = manifest.CreditsEnabled,
                DurationSeconds = double.IsFinite(manifest.DurationSeconds) && manifest.DurationSeconds > 0 ? manifest.DurationSeconds : 0,
            };
        }
        catch
        {
            // A failed or cancelled import must not leave hundreds of extracted PNGs behind in %TEMP%.
            TryDeleteDirectory(extractionRoot);
            throw;
        }
    }

    private static void ExtractCard(SKBitmap source, PixelRect region, string destinationPath)
    {
        using var card = new SKBitmap(region.Width, region.Height, source.ColorType, source.AlphaType);
        var subset = new SKRectI(region.X, region.Y, region.Right, region.Bottom);
        if (!source.ExtractSubset(card, subset))
            throw new InvalidDataException($"Could not extract contact-sheet region {region.X},{region.Y} {region.Width}×{region.Height}.");

        using var image = SKImage.FromBitmap(card);
        using var data = image.Encode(SKEncodedImageFormat.Png, 100)
            ?? throw new InvalidDataException("Could not encode a detected card as PNG.");
        using var output = new FileStream(destinationPath, FileMode.Create, FileAccess.Write, FileShare.None);
        data.SaveTo(output);
    }

    private static void ValidateArchive(ZipArchive archive)
    {
        if (archive.Entries.Count > MaxEntries)
            throw new InvalidDataException($"Zipack2 contains too many entries ({archive.Entries.Count}).");

        long expanded = 0;
        foreach (var entry in archive.Entries)
        {
            if (entry.Length > MaxEntryBytes)
                throw new InvalidDataException($"Zipack2 entry '{entry.FullName}' exceeds the {MaxEntryBytes / (1024 * 1024)} MiB limit.");
            checked { expanded += entry.Length; }
            if (expanded > MaxExpandedBytes)
                throw new InvalidDataException("Zipack2 expanded size exceeds the safety limit.");
            ValidateEntryName(entry.FullName);
        }
    }

    private static async Task<Zipack2Manifest> ReadManifestAsync(ZipArchive archive, CancellationToken cancellationToken)
    {
        var entry = archive.Entries.FirstOrDefault(x =>
            x.FullName.Equals("manifest.json", StringComparison.OrdinalIgnoreCase)
            || x.FullName.Equals("megapack.json", StringComparison.OrdinalIgnoreCase));

        if (entry is null)
        {
            return new Zipack2Manifest
            {
                Name = "MegaPack Zipack2",
                ContactSheets = [],
            };
        }

        await using var source = entry.Open();
        await using var bounded = await ReadEntryAsync(source, entry.Length, cancellationToken);
        var manifest = await JsonSerializer.DeserializeAsync<Zipack2Manifest>(bounded, JsonOptions, cancellationToken);
        return manifest ?? throw new InvalidDataException("Zipack2 manifest is empty or malformed.");
    }

    private static List<Zipack2ContactSheetDefinition> ResolveSheetDefinitions(ZipArchive archive, Zipack2Manifest manifest)
    {
        if (manifest.ContactSheets.Count > 0)
        {
            return manifest.ContactSheets
                .Where(x => !string.IsNullOrWhiteSpace(x.Path))
                .Select((sheet, index) => new Zipack2ContactSheetDefinition
                {
                    Path = NormalizeEntryName(sheet.Path),
                    Order = sheet.Order == 0 && index > 0 ? index : sheet.Order,
                    ExpectedCardWidth = sheet.ExpectedCardWidth,
                    ExpectedCardHeight = sheet.ExpectedCardHeight,
                    Separator = sheet.Separator,
                    Regions = sheet.Regions,
                })
                .ToList();
        }

        return archive.Entries
            .Where(IsContactSheetEntry)
            .OrderBy(x => x.FullName, StringComparer.OrdinalIgnoreCase)
            .Select((entry, index) => new Zipack2ContactSheetDefinition
            {
                Path = NormalizeEntryName(entry.FullName),
                Order = index,
            })
            .ToList();
    }

    private static void ValidateSheetDefinitions(IReadOnlyList<Zipack2ContactSheetDefinition> definitions)
    {
        var seenPaths = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var definition in definitions)
        {
            if (string.IsNullOrWhiteSpace(definition.Path))
                throw new InvalidDataException("A Zipack2 contact-sheet definition has an empty path.");

            ValidateEntryName(definition.Path);
            var normalized = NormalizeEntryName(definition.Path);
            if (!seenPaths.Add(normalized))
                throw new InvalidDataException($"Zipack2 manifest references contact sheet '{normalized}' more than once.");
        }
    }

    private static bool IsContactSheetEntry(ZipArchiveEntry entry)
    {
        if (string.IsNullOrWhiteSpace(entry.Name)) return false;
        var extension = Path.GetExtension(entry.Name).ToLowerInvariant();
        if (extension is not (".png" or ".jpg" or ".jpeg" or ".webp" or ".bmp")) return false;
        var normalized = NormalizeEntryName(entry.FullName);
        return normalized.StartsWith("artwork/", StringComparison.OrdinalIgnoreCase)
            && (normalized.Contains("contact-sheet", StringComparison.OrdinalIgnoreCase)
                || normalized.Contains("contactsheet", StringComparison.OrdinalIgnoreCase)
                || normalized.Contains("sheet-", StringComparison.OrdinalIgnoreCase));
    }

    private static ZipArchiveEntry? FindEntry(ZipArchive archive, string path)
    {
        var normalized = NormalizeEntryName(path);
        return archive.Entries.FirstOrDefault(x =>
            NormalizeEntryName(x.FullName).Equals(normalized, StringComparison.OrdinalIgnoreCase));
    }

    private static string NormalizeEntryName(string value) => value.Replace('\\', '/').TrimStart('/');

    private static void ValidateEntryName(string value)
    {
        var normalized = NormalizeEntryName(value);
        if (string.IsNullOrEmpty(normalized)) return;
        if (normalized.StartsWith("../", StringComparison.Ordinal)
            || normalized.Contains("/../", StringComparison.Ordinal)
            || Path.IsPathRooted(normalized))
            throw new InvalidDataException($"Unsafe Zipack2 entry path '{value}'.");
    }

    private static async Task<MemoryStream> ReadEntryAsync(Stream source, long declaredLength, CancellationToken cancellationToken)
    {
        if (declaredLength > MaxEntryBytes)
            throw new InvalidDataException("Zipack2 entry exceeds the per-file size limit.");

        var output = new MemoryStream(declaredLength is > 0 and <= int.MaxValue ? (int)declaredLength : 0);
        var buffer = new byte[128 * 1024];
        long total = 0;
        while (true)
        {
            var read = await source.ReadAsync(buffer.AsMemory(), cancellationToken);
            if (read == 0) break;
            total += read;
            if (total > MaxEntryBytes)
            {
                await output.DisposeAsync();
                throw new InvalidDataException("Zipack2 entry exceeds the per-file size limit while reading.");
            }
            await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
        }
        output.Position = 0;
        return output;
    }

    private static double ComputeConfidence(PixelRect region, Zipack2ContactSheetDefinition definition)
    {
        if (definition.Regions.Count > 0) return 1.0;
        if (definition.ExpectedCardWidth is null || definition.ExpectedCardHeight is null) return 0.92;

        var widthError = Math.Abs(region.Width - definition.ExpectedCardWidth.Value) / (double)Math.Max(1, definition.ExpectedCardWidth.Value);
        var heightError = Math.Abs(region.Height - definition.ExpectedCardHeight.Value) / (double)Math.Max(1, definition.ExpectedCardHeight.Value);
        return Math.Clamp(1.0 - ((widthError + heightError) * 0.5), 0.0, 1.0);
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch
        {
            // Best-effort cleanup only. The original import exception is more important.
        }
    }
}
