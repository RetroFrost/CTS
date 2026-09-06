using SkiaSharp;
using System.Globalization;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Xml.Linq;

namespace CubicalCompare.Windows;

/// <summary>Native, sandboxed importers shared with the Windows editor. Mirrors the Android limits and field aliases.</summary>
public static class NativeImporters
{
    private const long MaxPackBytes = 1_073_741_824L;
    private const long MaxExtractedBytes = 536_870_912L;
    private const long MaxEntryBytes = 67_108_864L;
    private const long MaxManifestBytes = 4_194_304L;
    private const int MaxEntries = 1_000;
    private const int MaxCards = 500;

    private static readonly Dictionary<string, HashSet<string>> Aliases = new(StringComparer.Ordinal)
    {
        ["title"] = new(StringComparer.Ordinal) { "title", "name", "label", "item", "topic", "age", "card" },
        ["value"] = new(StringComparer.Ordinal) { "value", "amount", "score", "number", "rank", "percentage", "percent" },
        ["badge_header"] = new(StringComparer.Ordinal) { "badge_header", "badgeheader", "header" },
        ["description"] = new(StringComparer.Ordinal) { "description", "desc", "details", "detail", "explanation", "subtitle", "text" },
        ["image"] = new(StringComparer.Ordinal) { "image", "img", "picture", "photo", "icon", "image_url", "image path", "image_path", "url" },
    };

    public static StudioProject ImportData(StudioProject project, string sourcePath)
    {
        var source = new FileInfo(sourcePath);
        if (!source.Exists) throw new FileNotFoundException("The selected data file could not be opened.", sourcePath);
        List<List<string>> rows = source.Extension.ToLowerInvariant() switch
        {
            ".csv" or ".txt" or ".tsv" => ParseDelimited(File.ReadAllText(source.FullName, Encoding.UTF8).TrimStart('\uFEFF')),
            ".xlsx" or ".xlsm" => ParseXlsx(source.FullName),
            _ => throw new InvalidDataException("Cubical Compare supports CSV, TSV, XLSX and XLSM files."),
        };
        var cards = RowsToCards(rows, source.DirectoryName);
        if (cards.Count == 0) throw new InvalidDataException("No cards were found in the selected file.");
        project.Name = Path.GetFileNameWithoutExtension(source.Name).Replace('_', ' ').Trim() is { Length: > 0 } name ? name : project.Name;
        project.Cards = cards;
        return project;
    }

    public static StudioProject ImportMegaPack(string sourcePath, string assetsDirectory)
    {
        var source = new FileInfo(sourcePath);
        if (!source.Exists) throw new FileNotFoundException("The selected MegaPack could not be opened.", sourcePath);
        if (source.Length > MaxPackBytes) throw new InvalidDataException("MegaPack is larger than the supported size limit.");
        var assets = new DirectoryInfo(assetsDirectory);
        if (assets.Exists && assets.EnumerateFileSystemInfos().Any()) throw new InvalidDataException("MegaPack destination is not empty.");
        assets.Create();

        try
        {
            using var zip = ZipFile.OpenRead(source.FullName);
            if (zip.Entries.Count > MaxEntries) throw new InvalidDataException("This MegaPack contains too many files.");
            var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
            long declaredSize = 0;
            foreach (var entry in zip.Entries)
            {
                if (string.IsNullOrEmpty(entry.Name)) continue;
                var safe = SafeEntry(entry.FullName);
                if (!entries.TryAdd(safe, entry)) throw new InvalidDataException($"MegaPack contains duplicate file '{safe}'.");
                if (entry.Length > MaxEntryBytes) throw new InvalidDataException($"MegaPack file '{safe}' is too large.");
                declaredSize += Math.Max(0, entry.Length);
                if (declaredSize > MaxExtractedBytes) throw new InvalidDataException("MegaPack expands beyond the supported size limit.");
            }

            if (!entries.TryGetValue("megapack.json", out var manifestEntry)) throw new InvalidDataException("MegaPack is missing megapack.json.");
            if (manifestEntry.Length > MaxManifestBytes) throw new InvalidDataException("MegaPack manifest is too large.");
            var manifestBytes = ReadEntry(manifestEntry, MaxManifestBytes);
            using var document = JsonDocument.Parse(Encoding.UTF8.GetString(manifestBytes).TrimStart('\uFEFF'));
            var manifest = document.RootElement;
            var version = manifest.Int("version", 2);
            if (version is < 1 or > 2) throw new InvalidDataException($"MegaPack version {version} is not supported.");
            if (!manifest.TryGetProperty("cards", out var cardArray) || cardArray.ValueKind != JsonValueKind.Array) throw new InvalidDataException("MegaPack manifest has no cards array.");
            if (cardArray.GetArrayLength() is < 1 or > MaxCards) throw new InvalidDataException($"MegaPack must contain between 1 and {MaxCards} cards.");

            long actualBytes = manifestBytes.Length;
            byte[]? Bytes(string reference)
            {
                if (string.IsNullOrWhiteSpace(reference)) return null;
                var safe = SafeEntry(reference);
                if (!entries.TryGetValue(safe, out var entry)) throw new InvalidDataException($"MegaPack file '{safe}' was not found.");
                var data = ReadEntry(entry, MaxEntryBytes);
                actualBytes += data.Length;
                if (actualBytes > MaxExtractedBytes) throw new InvalidDataException("MegaPack expands beyond the supported size limit.");
                return data;
            }

            var cards = new List<StudioCard>(cardArray.GetArrayLength());
            var cardIndex = 0;
            foreach (var item in cardArray.EnumerateArray())
            {
                var title = FirstString(item, "title", "name");
                var description = FirstString(item, "description", "details");
                var header = FirstString(item, "badge_header", "badgeHeader", "header");
                var primary = FirstString(item, "badge_primary", "badgePrimary", "value");
                var secondary = FirstString(item, "badge_secondary", "badgeSecondary", "label", "unit");
                var value = string.Join(' ', new[] { primary, secondary }.Where(x => !string.IsNullOrWhiteSpace(x)));
                var legacy = FirstString(item, "image", "artwork");
                var backgroundRef = FirstString(item, "background", "background_image", "backdrop");
                var subjectRef = FirstString(item, "subject", "foreground", "subject_image");
                if (string.IsNullOrWhiteSpace(subjectRef)) subjectRef = legacy;
                var background = Bytes(backgroundRef);
                var subject = Bytes(subjectRef);
                var imagePath = "";
                if (background != null || subject != null)
                {
                    using var artwork = ComposeArtwork(
                        background, subject,
                        Finite(item, "crop_focus_x", .5, 0, 1),
                        Finite(item, "crop_focus_y", .5, 0, 1),
                        Finite(item, "crop_zoom", 1, 1, 3));
                    imagePath = Path.Combine(assets.FullName, $"card-{cardIndex + 1:000}.png");
                    using var output = File.Create(imagePath);
                    artwork.Encode(output, SKEncodedImageFormat.Png, 100);
                }
                cards.Add(new StudioCard
                {
                    Id = Guid.NewGuid().ToString("N"),
                    Title = title,
                    Value = value,
                    BadgeHeader = header,
                    Description = description,
                    Image = imagePath,
                    ImageX = Finite(item, "image_x", 0, -4000, 4000),
                    ImageY = Finite(item, "image_y", 0, -4000, 4000),
                    ImageScale = Finite(item, "image_scale", 1, .05, 12),
                    ImageRotation = Finite(item, "image_rotation", 0, -360, 360),
                    ImageCropLeft = Finite(item, "image_crop_left", 0, 0, .95),
                    ImageCropTop = Finite(item, "image_crop_top", 0, 0, .95),
                    ImageCropRight = Finite(item, "image_crop_right", 0, 0, .95),
                    ImageCropBottom = Finite(item, "image_crop_bottom", 0, 0, .95),
                    ImageLayer = FirstString(item, "image_layer", "imageLayer", "layer").Equals("front", StringComparison.OrdinalIgnoreCase) ? "front" : "behind",
                });
                cardIndex++;
            }

            var soundtrackPath = "";
            var soundtrackVolume = 1f;
            var soundtrackLoop = true;
            if (manifest.TryGetProperty("soundtrack", out var soundtrack) && soundtrack.ValueKind is not JsonValueKind.Null and not JsonValueKind.Undefined)
            {
                string soundtrackRef;
                if (soundtrack.ValueKind == JsonValueKind.Object)
                {
                    soundtrackVolume = (float)Math.Clamp(Finite(soundtrack, "volume", 1), 0, 1);
                    soundtrackLoop = soundtrack.Bool("loop", true);
                    soundtrackRef = FirstString(soundtrack, "file", "path", "audio");
                }
                else soundtrackRef = soundtrack.ToString().Trim();
                if (!string.IsNullOrWhiteSpace(soundtrackRef))
                {
                    var safe = SafeEntry(soundtrackRef);
                    if (!entries.TryGetValue(safe, out var entry)) throw new InvalidDataException($"MegaPack file '{safe}' was not found.");
                    var suffix = Path.GetExtension(safe).TrimStart('.').ToLowerInvariant();
                    if (suffix.Length is < 1 or > 8 || suffix.Any(c => !char.IsLetterOrDigit(c))) suffix = "bin";
                    soundtrackPath = Path.Combine(assets.FullName, "soundtrack." + suffix);
                    using var input = entry.Open();
                    using var output = File.Create(soundtrackPath);
                    CopyLimited(input, output, MaxEntryBytes);
                }
            }

            var duration = ManifestDuration(manifest);
            var hasBadges = cards.Any(x => !string.IsNullOrWhiteSpace(x.Value) || !string.IsNullOrWhiteSpace(x.BadgeHeader));
            return new StudioProject
            {
                Name = FirstString(manifest, "name", "title") is { Length: > 0 } n ? n : Path.GetFileNameWithoutExtension(source.Name),
                Cards = cards,
                Width = 1920,
                Height = 1080,
                Fps = 60,
                ShowBadges = manifest.Bool("show_badges", true) || hasBadges,
                CreditsEnabled = manifest.Bool("credits_enabled", true),
                Soundtrack = soundtrackPath,
                SoundtrackVolume = soundtrackVolume,
                SoundtrackLoop = soundtrackLoop,
                AutoLength = duration <= 0,
                CustomLengthSeconds = duration > 0 ? duration : 90,
            };
        }
        catch
        {
            try { if (Directory.Exists(assets.FullName)) Directory.Delete(assets.FullName, true); } catch { }
            throw;
        }
    }

    private static List<StudioCard> RowsToCards(List<List<string>> input, string? assetBase)
    {
        var rows = input.Where(row => row.Any(cell => !string.IsNullOrWhiteSpace(cell))).ToList();
        if (rows.Count == 0) return [];
        var headerMap = MapHeaders(rows[0]);
        var nonEmpty = rows[0].Select(Normalize).Where(x => x.Length > 0).ToList();
        var hasHeader = headerMap.Count >= 2 || (headerMap.Count == 1 && nonEmpty.Count == 1 && new[] { "title", "value", "description", "image", "image_url", "image_path" }.Contains(nonEmpty[0], StringComparer.Ordinal));
        var data = hasHeader ? rows.Skip(1) : rows;
        var cards = new List<StudioCard>();
        foreach (var row in data)
        {
            if (row.All(string.IsNullOrWhiteSpace)) continue;
            var mapped = new Dictionary<string, string>(StringComparer.Ordinal);
            if (hasHeader)
                foreach (var pair in headerMap) mapped[pair.Value] = pair.Key < row.Count ? row[pair.Key].Trim() : "";
            else
            {
                mapped["title"] = row.ElementAtOrDefault(0)?.Trim() ?? "";
                mapped["value"] = row.ElementAtOrDefault(1)?.Trim() ?? "";
                mapped["description"] = row.ElementAtOrDefault(2)?.Trim() ?? "";
                mapped["image"] = row.ElementAtOrDefault(3)?.Trim() ?? "";
            }
            var image = mapped.GetValueOrDefault("image", "");
            if (!string.IsNullOrWhiteSpace(image) && !Uri.TryCreate(image, UriKind.Absolute, out var uri))
            {
                if (!Path.IsPathRooted(image) && !string.IsNullOrWhiteSpace(assetBase)) image = Path.GetFullPath(Path.Combine(assetBase, image));
            }
            else if (Uri.TryCreate(image, UriKind.Absolute, out var remote) && remote.Scheme is "http" or "https")
            {
                // Remote artwork remains a reference. The renderer deliberately never downloads it implicitly.
            }
            cards.Add(new StudioCard
            {
                Title = mapped.GetValueOrDefault("title", ""),
                Value = mapped.GetValueOrDefault("value", ""),
                BadgeHeader = mapped.GetValueOrDefault("badge_header", ""),
                Description = mapped.GetValueOrDefault("description", ""),
                Image = image,
            });
            if (cards.Count > 10_000) throw new InvalidDataException("The data file contains too many cards.");
        }
        return cards;
    }

    private static Dictionary<int, string> MapHeaders(IReadOnlyList<string> row)
    {
        var result = new Dictionary<int, string>();
        var used = new HashSet<string>(StringComparer.Ordinal);
        for (var i = 0; i < row.Count; i++)
        {
            var value = Normalize(row[i]);
            foreach (var pair in Aliases)
                if (!used.Contains(pair.Key) && pair.Value.Contains(value)) { result[i] = pair.Key; used.Add(pair.Key); break; }
        }
        return result;
    }

    private static string Normalize(string value) => value.Trim().ToLowerInvariant().Replace('-', '_');

    private static List<List<string>> ParseDelimited(string text)
    {
        var firstLine = text.Split(new[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
        var delimiter = new[] { ',', '\t', ';', '|' }.OrderByDescending(c => firstLine.Count(x => x == c)).First();
        var rows = new List<List<string>>();
        var row = new List<string>();
        var cell = new StringBuilder();
        var quoted = false;
        for (var i = 0; i < text.Length; i++)
        {
            var ch = text[i];
            if (ch == '"' && quoted && i + 1 < text.Length && text[i + 1] == '"') { cell.Append('"'); i++; }
            else if (ch == '"') quoted = !quoted;
            else if (ch == delimiter && !quoted) { row.Add(cell.ToString()); cell.Clear(); }
            else if ((ch == '\r' || ch == '\n') && !quoted)
            {
                if (ch == '\r' && i + 1 < text.Length && text[i + 1] == '\n') i++;
                row.Add(cell.ToString()); cell.Clear(); rows.Add([.. row]); row.Clear();
                if (rows.Count > 20_000) throw new InvalidDataException("The data file contains too many rows.");
            }
            else cell.Append(ch);
        }
        if (cell.Length > 0 || row.Count > 0) { row.Add(cell.ToString()); rows.Add(row); }
        if (quoted) throw new InvalidDataException("The delimited file ends inside a quoted field.");
        return rows;
    }

    private static List<List<string>> ParseXlsx(string source)
    {
        using var zip = ZipFile.OpenRead(source);
        var shared = new List<string>();
        var sharedEntry = zip.GetEntry("xl/sharedStrings.xml");
        if (sharedEntry != null)
        {
            using var input = sharedEntry.Open();
            var doc = XDocument.Load(input, LoadOptions.None);
            shared = doc.Descendants().Where(x => x.Name.LocalName == "si")
                .Select(si => string.Concat(si.Descendants().Where(x => x.Name.LocalName == "t").Select(x => x.Value))).ToList();
        }
        var sheetPath = ResolveFirstSheet(zip);
        var sheet = zip.GetEntry(sheetPath) ?? throw new InvalidDataException("The workbook has no readable worksheet.");
        using var stream = sheet.Open();
        var xml = XDocument.Load(stream, LoadOptions.None);
        var rows = new List<List<string>>();
        foreach (var rowElement in xml.Descendants().Where(x => x.Name.LocalName == "row"))
        {
            var row = new List<string>();
            foreach (var cell in rowElement.Elements().Where(x => x.Name.LocalName == "c"))
            {
                var reference = cell.Attribute("r")?.Value ?? "A1";
                var column = ColumnIndex(reference);
                while (row.Count <= column) row.Add("");
                var type = cell.Attribute("t")?.Value ?? "";
                var raw = cell.Elements().FirstOrDefault(x => x.Name.LocalName == "v")?.Value
                    ?? string.Concat(cell.Descendants().Where(x => x.Name.LocalName == "t").Select(x => x.Value));
                row[column] = type switch
                {
                    "s" when int.TryParse(raw, out var index) && index >= 0 && index < shared.Count => shared[index],
                    "b" => raw == "1" ? "True" : "False",
                    _ => raw,
                };
            }
            rows.Add(row);
            if (rows.Count > 20_000) throw new InvalidDataException("The workbook contains too many rows.");
        }
        return rows;
    }

    private static string ResolveFirstSheet(ZipArchive zip)
    {
        var workbookEntry = zip.GetEntry("xl/workbook.xml");
        if (workbookEntry == null) return "xl/worksheets/sheet1.xml";
        string? relationId;
        using (var stream = workbookEntry.Open())
        {
            var workbook = XDocument.Load(stream);
            relationId = workbook.Descendants().FirstOrDefault(x => x.Name.LocalName == "sheet")?.Attributes().FirstOrDefault(x => x.Name.LocalName == "id")?.Value;
        }
        if (string.IsNullOrWhiteSpace(relationId)) return "xl/worksheets/sheet1.xml";
        var relsEntry = zip.GetEntry("xl/_rels/workbook.xml.rels");
        if (relsEntry == null) return "xl/worksheets/sheet1.xml";
        using var relStream = relsEntry.Open();
        var rels = XDocument.Load(relStream);
        var target = rels.Descendants().FirstOrDefault(x => x.Name.LocalName == "Relationship" && (string?)x.Attribute("Id") == relationId)?.Attribute("Target")?.Value;
        if (string.IsNullOrWhiteSpace(target)) return "xl/worksheets/sheet1.xml";
        if (target.StartsWith('/')) return target.TrimStart('/');
        return ("xl/" + target.TrimStart('.', '/')).Replace("xl/xl/", "xl/", StringComparison.OrdinalIgnoreCase);
    }

    private static int ColumnIndex(string reference)
    {
        var result = 0;
        foreach (var ch in reference)
        {
            if (!char.IsLetter(ch)) break;
            result = checked(result * 26 + (char.ToUpperInvariant(ch) - 'A' + 1));
            if (result > 16_384) throw new InvalidDataException("Workbook column is outside the supported range.");
        }
        return Math.Max(0, result - 1);
    }

    private static string SafeEntry(string value)
    {
        var normalized = value.Trim().Replace('\\', '/');
        if (normalized.StartsWith("./", StringComparison.Ordinal)) normalized = normalized[2..];
        if (string.IsNullOrWhiteSpace(normalized) || normalized.StartsWith('/') || normalized.Contains(':') || normalized.Split('/').Any(x => string.IsNullOrWhiteSpace(x) || x is "." or ".."))
            throw new InvalidDataException("MegaPack contains an unsafe file path.");
        return normalized;
    }

    private static byte[] ReadEntry(ZipArchiveEntry entry, long limit)
    {
        using var input = entry.Open(); using var output = new MemoryStream(); CopyLimited(input, output, limit); return output.ToArray();
    }

    private static void CopyLimited(Stream input, Stream output, long limit)
    {
        var buffer = new byte[64 * 1024]; long total = 0;
        while (true)
        {
            var read = input.Read(buffer, 0, buffer.Length); if (read <= 0) break;
            total += read; if (total > limit) throw new InvalidDataException("Archive entry is larger than the supported size limit.");
            output.Write(buffer, 0, read);
        }
    }

    private static SKBitmap ComposeArtwork(byte[]? backgroundBytes, byte[]? subjectBytes, double focusX, double focusY, double zoom)
    {
        using var background = backgroundBytes == null ? null : DecodeImage(backgroundBytes);
        using var subject = subjectBytes == null ? null : DecodeImage(subjectBytes);
        if (background == null && subject == null) throw new InvalidDataException("MegaPack card has no artwork.");
        var basis = background ?? subject!;
        var output = new SKBitmap(new SKImageInfo(basis.Width, basis.Height, SKColorType.Bgra8888, SKAlphaType.Premul));
        using var canvas = new SKCanvas(output);
        canvas.Clear(SKColors.Transparent);
        using var paint = new SKPaint { IsAntialias = true, FilterQuality = SKFilterQuality.High };
        if (background != null) canvas.DrawBitmap(background, new SKRect(0, 0, output.Width, output.Height), paint);
        if (subject != null)
        {
            if (background == null) canvas.DrawBitmap(subject, new SKRect(0, 0, output.Width, output.Height), paint);
            else if (HasTransparentPixels(subject)) DrawContainedSubject(canvas, subject, output.Width, output.Height, focusX, focusY, zoom, paint);
            else DrawCentreCrop(canvas, subject, output.Width, output.Height, focusX, focusY, zoom, paint);
        }
        canvas.Flush();
        return output;
    }

    private static SKBitmap DecodeImage(byte[] bytes)
    {
        using var codec = SKCodec.Create(new SKMemoryStream(bytes)) ?? throw new InvalidDataException("MegaPack contains an unsupported image.");
        var info = codec.Info;
        if (info.Width <= 0 || info.Height <= 0 || (long)info.Width * info.Height > 64_000_000L) throw new InvalidDataException("MegaPack image dimensions are not supported.");
        var bitmap = SKBitmap.Decode(bytes) ?? throw new InvalidDataException("MegaPack contains an unsupported image.");
        return bitmap;
    }

    private static bool HasTransparentPixels(SKBitmap bitmap)
    {
        if (bitmap.AlphaType == SKAlphaType.Opaque) return false;
        var stepX = Math.Max(1, bitmap.Width / 48); var stepY = Math.Max(1, bitmap.Height / 48);
        for (var y = 0; y < bitmap.Height; y += stepY)
            for (var x = 0; x < bitmap.Width; x += stepX)
                if (bitmap.GetPixel(x, y).Alpha < 245) return true;
        return bitmap.GetPixel(bitmap.Width - 1, bitmap.Height - 1).Alpha < 245;
    }

    private static void DrawContainedSubject(SKCanvas canvas, SKBitmap source, int width, int height, double focusX, double focusY, double zoom, SKPaint paint)
    {
        var fit = Math.Min(width * .80 / Math.Max(1, source.Width), height * .72 / Math.Max(1, source.Height));
        var scale = fit * Math.Clamp(zoom, .5, 3);
        var dw = source.Width * scale; var dh = source.Height * scale;
        var cx = width * (.35 + Math.Clamp(focusX, 0, 1) * .30); var cy = height * (.35 + Math.Clamp(focusY, 0, 1) * .30);
        canvas.DrawBitmap(source, new SKRect((float)(cx - dw / 2), (float)(cy - dh / 2), (float)(cx + dw / 2), (float)(cy + dh / 2)), paint);
    }

    private static void DrawCentreCrop(SKCanvas canvas, SKBitmap source, int width, int height, double focusX, double focusY, double zoom, SKPaint paint)
    {
        var destinationAspect = width / (double)Math.Max(1, height); var sourceAspect = source.Width / (double)Math.Max(1, source.Height);
        double baseWidth, baseHeight;
        if (sourceAspect >= destinationAspect) { baseHeight = source.Height; baseWidth = baseHeight * destinationAspect; }
        else { baseWidth = source.Width; baseHeight = baseWidth / destinationAspect; }
        var cropWidth = Math.Max(1, baseWidth / zoom); var cropHeight = Math.Max(1, baseHeight / zoom);
        var left = Math.Clamp(source.Width * focusX - cropWidth / 2, 0, Math.Max(0, source.Width - cropWidth));
        var top = Math.Clamp(source.Height * focusY - cropHeight / 2, 0, Math.Max(0, source.Height - cropHeight));
        var src = new SKRect((float)left, (float)top, (float)Math.Min(source.Width, left + cropWidth), (float)Math.Min(source.Height, top + cropHeight));
        canvas.DrawBitmap(source, src, new SKRect(0, 0, width, height), paint);
    }

    private static string FirstString(JsonElement element, params string[] keys)
    {
        if (element.ValueKind != JsonValueKind.Object) return "";
        foreach (var key in keys)
            if (element.TryGetProperty(key, out var value) && value.ValueKind is not JsonValueKind.Null and not JsonValueKind.Undefined)
            {
                var text = value.ToString().Trim(); if (text.Length > 0) return text;
            }
        return "";
    }

    private static double Finite(JsonElement element, string key, double fallback, double min = double.NegativeInfinity, double max = double.PositiveInfinity)
    {
        if (!element.TryGetProperty(key, out var value)) return fallback;
        double result = value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var n) ? n : double.TryParse(value.ToString(), NumberStyles.Float, CultureInfo.InvariantCulture, out var p) ? p : fallback;
        if (!double.IsFinite(result)) result = fallback;
        return Math.Clamp(result, min, max);
    }

    private static double ManifestDuration(JsonElement manifest)
    {
        foreach (var key in new[] { "duration_seconds", "video_duration_seconds", "duration" })
        {
            var value = Finite(manifest, key, 0); if (value > 0) return value;
        }
        if (manifest.TryGetProperty("source", out var source) && source.ValueKind == JsonValueKind.Object)
        {
            var value = Finite(source, "duration_seconds", 0); if (value > 0) return value;
        }
        return 0;
    }
}