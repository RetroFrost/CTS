using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using System.Text.Json;

namespace CubicalCompare.Core.Renderer;

public enum RendererGeneration
{
    Unknown = 0,
    V2 = 2,
    V3 = 3,
    V4 = 4,
}

public sealed record RendererPackageInfo(
    RendererGeneration Generation,
    string Id,
    string Name,
    string Engine,
    int Api,
    int ReferenceWidth,
    int ReferenceHeight,
    int ReferenceFps,
    bool IsPackage,
    string SourcePath);

public static class RendererPackageProbe
{
    private const int MaxRendererBytes = 128 * 1024 * 1024;
    private const int MaxJsonBytes = 64 * 1024 * 1024;

    public static async Task<RendererPackageInfo> InspectAsync(string path, CancellationToken cancellationToken = default)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(path);
        var info = new FileInfo(path);
        if (!info.Exists) throw new FileNotFoundException("Renderer package not found.", path);
        if (info.Length > MaxRendererBytes) throw new InvalidDataException("Renderer package exceeds 128 MiB.");

        var bytes = await File.ReadAllBytesAsync(path, cancellationToken);
        if (bytes.Length >= 8)
        {
            var magic = Encoding.ASCII.GetString(bytes, 0, 8);
            if (magic == "CCRNDR03") return ReadContainer(bytes, path, RendererGeneration.V3, "CCRNDR03", isPackage: false);
            if (magic == "CCRNDR04") return ReadContainer(bytes, path, RendererGeneration.V4, "CCRNDR04", isPackage: false);
            if (magic == "CCRNDR01") return ReadLegacy(bytes, path);
        }

        if (bytes.Length >= 2 && bytes[0] == (byte)'P' && bytes[1] == (byte)'K')
            return ReadZip(bytes, path);

        throw new InvalidDataException("Not a supported Cubical Compare renderer package.");
    }

    private static RendererPackageInfo ReadLegacy(byte[] bytes, string path)
    {
        using var json = ReadContainerJson(bytes, "CCRNDR01", "legacy renderer");
        var root = json.RootElement;
        var formatVersion = GetInt(root, "formatVersion", 1);
        var api = GetInt(root, "rendererApi", formatVersion >= 2 ? 2 : 1);
        if (api != 2)
            throw new InvalidDataException($"Renderer API {api} is legacy and is not part of the Cubical Compare 4 compatibility target.");

        return BuildInfo(root, path, RendererGeneration.V2, api, false);
    }

    private static RendererPackageInfo ReadContainer(byte[] bytes, string path, RendererGeneration generation, string magic, bool isPackage)
    {
        using var json = ReadContainerJson(bytes, magic, $"Renderer v{(int)generation}");
        var root = json.RootElement;
        var api = GetInt(root, "api", GetInt(root, "rendererApi", (int)generation));
        if (api != (int)generation)
            throw new InvalidDataException($"Renderer declares API {api}, but its container is Renderer v{(int)generation}.");
        return BuildInfo(root, path, generation, api, isPackage);
    }

    private static RendererPackageInfo ReadZip(byte[] bytes, string path)
    {
        using var memory = new MemoryStream(bytes, writable: false);
        using var zip = new ZipArchive(memory, ZipArchiveMode.Read, leaveOpen: false);
        if (zip.Entries.Count > 2048) throw new InvalidDataException("Renderer package contains too many entries.");

        var entry = zip.Entries.FirstOrDefault(x => x.FullName.EndsWith(".renderer4", StringComparison.OrdinalIgnoreCase))
            ?? zip.Entries.FirstOrDefault(x => x.FullName.EndsWith(".renderer3", StringComparison.OrdinalIgnoreCase))
            ?? zip.Entries.FirstOrDefault(x => x.FullName.EndsWith(".renderer", StringComparison.OrdinalIgnoreCase));
        if (entry is null) throw new InvalidDataException("Renderer ZIP contains no renderer scene file.");
        if (entry.Length > MaxRendererBytes) throw new InvalidDataException("Renderer scene exceeds 128 MiB.");

        using var stream = entry.Open();
        using var output = new MemoryStream();
        CopyLimited(stream, output, MaxRendererBytes);
        var scene = output.ToArray();

        if (scene.Length >= 8)
        {
            var magic = Encoding.ASCII.GetString(scene, 0, 8);
            if (magic == "CCRNDR04") return ReadContainer(scene, path, RendererGeneration.V4, "CCRNDR04", isPackage: true);
            if (magic == "CCRNDR03") return ReadContainer(scene, path, RendererGeneration.V3, "CCRNDR03", isPackage: true);
            if (magic == "CCRNDR01")
            {
                var legacy = ReadLegacy(scene, path);
                return legacy with { IsPackage = true };
            }
        }

        throw new InvalidDataException("Renderer ZIP scene has an unsupported container header.");
    }

    private static RendererPackageInfo BuildInfo(
        JsonElement root,
        string path,
        RendererGeneration generation,
        int api,
        bool isPackage)
    {
        var canvas = root.TryGetProperty("canvas", out var canvasElement) && canvasElement.ValueKind == JsonValueKind.Object
            ? canvasElement
            : default;
        var reference = root.TryGetProperty("reference", out var referenceElement) && referenceElement.ValueKind == JsonValueKind.Object
            ? referenceElement
            : default;

        var id = GetString(root, "id", $"renderer-v{(int)generation}");
        var name = GetString(root, "name", id);
        var engine = GetString(root, "engine", generation switch
        {
            RendererGeneration.V3 => "scene-v3",
            RendererGeneration.V4 => "scene-v4",
            _ => "legacy-v2",
        });
        var width = GetInt(canvas, "width", GetInt(reference, "width", GetInt(root, "referenceWidth", 1920)));
        var height = GetInt(canvas, "height", GetInt(reference, "height", GetInt(root, "referenceHeight", 1080)));
        var fps = GetInt(canvas, "fps", GetInt(reference, "fps", GetInt(root, "referenceFps", 60)));

        return new RendererPackageInfo(generation, id, name, engine, api, width, height, fps, isPackage, path);
    }

    private static JsonDocument ReadContainerJson(byte[] bytes, string expectedMagic, string label)
    {
        if (bytes.Length < 20) throw new InvalidDataException($"Not a Cubical Compare {label} file.");
        if (Encoding.ASCII.GetString(bytes, 0, 8) != expectedMagic) throw new InvalidDataException($"Not a Cubical Compare {label} file.");
        var containerVersion = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(8, 4));
        if (containerVersion != 1) throw new InvalidDataException($"Unsupported {label} container version {containerVersion}.");
        var length = BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(12, 4));
        var expectedCrc = unchecked((uint)BinaryPrimitives.ReadInt32BigEndian(bytes.AsSpan(16, 4)));
        if (length <= 0 || length > MaxRendererBytes - 20 || bytes.Length != 20 + length)
            throw new InvalidDataException($"Invalid {label} payload length.");

        var payload = bytes.AsSpan(20, length).ToArray();
        if (Crc32.Compute(payload) != expectedCrc) throw new InvalidDataException($"{label} checksum failed.");

        using var compressed = new MemoryStream(payload, writable: false);
        using var gzip = new GZipStream(compressed, CompressionMode.Decompress);
        using var json = new MemoryStream();
        CopyLimited(gzip, json, MaxJsonBytes);
        return JsonDocument.Parse(json.ToArray());
    }

    private static void CopyLimited(Stream input, Stream output, int maxBytes)
    {
        var buffer = new byte[32 * 1024];
        var total = 0;
        while (true)
        {
            var read = input.Read(buffer, 0, buffer.Length);
            if (read <= 0) break;
            total += read;
            if (total > maxBytes) throw new InvalidDataException("Renderer payload exceeds its safety limit.");
            output.Write(buffer, 0, read);
        }
    }

    private static int GetInt(JsonElement element, string property, int fallback)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(property, out var value)) return fallback;
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var result) ? result : fallback;
    }

    private static string GetString(JsonElement element, string property, string fallback)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(property, out var value)) return fallback;
        return value.ValueKind == JsonValueKind.String ? value.GetString() ?? fallback : fallback;
    }

    private static class Crc32
    {
        private static readonly uint[] Table = BuildTable();

        public static uint Compute(ReadOnlySpan<byte> data)
        {
            var crc = 0xFFFFFFFFu;
            foreach (var value in data) crc = Table[(crc ^ value) & 0xFF] ^ (crc >> 8);
            return crc ^ 0xFFFFFFFFu;
        }

        private static uint[] BuildTable()
        {
            var table = new uint[256];
            for (uint i = 0; i < table.Length; i++)
            {
                var c = i;
                for (var bit = 0; bit < 8; bit++) c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
                table[i] = c;
            }
            return table;
        }
    }
}
