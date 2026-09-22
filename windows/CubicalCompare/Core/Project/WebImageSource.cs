using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace CubicalCompare.Core.Project;

/// <summary>
/// Resolves local image paths, direct HTTP(S) image URLs and ordinary web pages
/// (for example Flaticon icon pages) into a reusable local image file.
/// </summary>
public static class WebImageSource
{
    private const long MaxImageBytes = 40L * 1024 * 1024;
    private const long MaxHtmlBytes = 5L * 1024 * 1024;
    private const int MaxRedirects = 8;
    private static readonly TimeSpan CacheLifetime = TimeSpan.FromDays(30);

    private static readonly HttpClient Client = CreateHttpClient();
    private static readonly ConcurrentDictionary<string, Lazy<Task<string>>> InFlight =
        new(StringComparer.Ordinal);

    public static string CacheDirectory { get; } = EnsureDirectory(
        Path.Combine(AppDataPaths.RootDirectory, "WebImageCache"));

    public static string NormalizeSource(string? source)
    {
        var value = (source ?? string.Empty).Trim();
        if (value.Length == 0) return string.Empty;

        var markdown = Regex.Match(
            value,
            @"^!?\[[^\]]*\]\((?<url>https?://.+?)\)$",
            RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        if (markdown.Success)
            value = markdown.Groups["url"].Value.Trim();

        if (value.Length >= 2 &&
            ((value[0] == '"' && value[^1] == '"') || (value[0] == '\'' && value[^1] == '\'')))
            value = value[1..^1].Trim();

        if (value.StartsWith('<') && value.EndsWith('>'))
            value = value[1..^1].Trim();

        value = value.Replace(@"\&", "&", StringComparison.Ordinal);
        if (value.StartsWith("//", StringComparison.Ordinal))
            value = "https:" + value;
        else if (value.StartsWith("www.", StringComparison.OrdinalIgnoreCase))
            value = "https://" + value;

        return value;
    }

    public static bool IsRemoteSource(string? source)
    {
        var value = NormalizeSource(source);
        return Uri.TryCreate(value, UriKind.Absolute, out var uri)
            && uri.Scheme is "http" or "https";
    }

    public static string? TryGetCachedLocalPath(string? source)
    {
        var value = NormalizeSource(source);
        if (!IsRemoteSource(value)) return ResolveLocalPath(value);

        var prefix = CacheKey(value) + ".";
        try
        {
            foreach (var path in Directory.EnumerateFiles(CacheDirectory, prefix + "*", SearchOption.TopDirectoryOnly))
            {
                var info = new FileInfo(path);
                if (!info.Exists || info.Length <= 0) continue;
                if (DateTime.UtcNow - info.LastWriteTimeUtc <= CacheLifetime)
                    return info.FullName;
                TryDelete(path);
            }
        }
        catch
        {
            // Cache misses are harmless; the caller can download again.
        }

        return null;
    }

    public static string? ResolveToLocalFile(string? source, CancellationToken cancellationToken = default)
    {
        var value = NormalizeSource(source);
        if (value.Length == 0) return null;
        if (!IsRemoteSource(value)) return ResolveLocalPath(value);
        return ResolveToLocalFileAsync(value, cancellationToken).GetAwaiter().GetResult();
    }

    public static async Task<string?> ResolveToLocalFileAsync(
        string? source,
        CancellationToken cancellationToken = default)
    {
        var value = NormalizeSource(source);
        if (value.Length == 0) return null;
        if (!IsRemoteSource(value)) return ResolveLocalPath(value);

        var cached = TryGetCachedLocalPath(value);
        if (!string.IsNullOrWhiteSpace(cached)) return cached;

        var lazy = InFlight.GetOrAdd(
            value,
            static key => new Lazy<Task<string>>(
                () => DownloadAndCacheAsync(key, CancellationToken.None),
                LazyThreadSafetyMode.ExecutionAndPublication));

        try
        {
            return await lazy.Value.WaitAsync(cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            if (lazy.IsValueCreated && lazy.Value.IsCompleted &&
                InFlight.TryGetValue(value, out var current) &&
                ReferenceEquals(current, lazy))
                InFlight.TryRemove(value, out _);
        }
    }

    private static HttpClient CreateHttpClient()
    {
        var handler = new SocketsHttpHandler
        {
            AllowAutoRedirect = false,
            AutomaticDecompression = DecompressionMethods.All,
            ConnectTimeout = TimeSpan.FromSeconds(12),
            PooledConnectionLifetime = TimeSpan.FromMinutes(10),
            PooledConnectionIdleTimeout = TimeSpan.FromMinutes(2),
            MaxConnectionsPerServer = 8,
        };

        var client = new HttpClient(handler)
        {
            Timeout = Timeout.InfiniteTimeSpan,
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "Chrome/153.0 Safari/537.36 CubicalCompare/4.2");
        client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("en-US,en;q=0.9");
        return client;
    }

    private static async Task<string> DownloadAndCacheAsync(string originalSource, CancellationToken cancellationToken)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        var token = timeout.Token;

        var sourceUri = new Uri(originalSource, UriKind.Absolute);
        var (response, finalUri) = await SendAsync(
            sourceUri,
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,text/html;q=0.9,*/*;q=0.5",
            null,
            token).ConfigureAwait(false);

        using (response)
        {
            response.EnsureSuccessStatusCode();
            var mediaType = response.Content.Headers.ContentType?.MediaType?.Trim().ToLowerInvariant() ?? string.Empty;

            if (mediaType.StartsWith("image/", StringComparison.Ordinal))
            {
                var bytes = await ReadLimitedAsync(response.Content, MaxImageBytes, token).ConfigureAwait(false);
                return SaveImage(originalSource, bytes, mediaType, finalUri);
            }

            if (mediaType.Contains("html", StringComparison.Ordinal) ||
                mediaType.StartsWith("text/", StringComparison.Ordinal))
            {
                var htmlBytes = await ReadLimitedAsync(response.Content, MaxHtmlBytes, token).ConfigureAwait(false);
                var html = DecodeText(htmlBytes, response.Content.Headers.ContentType?.CharSet);
                foreach (var candidate in ExtractImageCandidates(html, finalUri).Take(16))
                {
                    try
                    {
                        var local = await DownloadCandidateAsync(originalSource, candidate, finalUri, token).ConfigureAwait(false);
                        if (!string.IsNullOrWhiteSpace(local)) return local;
                    }
                    catch (OperationCanceledException) when (!token.IsCancellationRequested)
                    {
                    }
                    catch (HttpRequestException)
                    {
                    }
                    catch (InvalidDataException)
                    {
                    }
                }

                throw new InvalidDataException(
                    $"The web page did not expose a usable preview image: {finalUri}");
            }

            var unknownBytes = await ReadLimitedAsync(response.Content, MaxImageBytes, token).ConfigureAwait(false);
            var extension = DetectExtension(unknownBytes, mediaType, finalUri);
            if (extension is null)
                throw new InvalidDataException($"The URL did not return an image: {finalUri}");
            return SaveImage(originalSource, unknownBytes, mediaType, finalUri);
        }
    }

    private static async Task<string?> DownloadCandidateAsync(
        string originalSource,
        Uri candidate,
        Uri referer,
        CancellationToken cancellationToken)
    {
        var (response, finalUri) = await SendAsync(
            candidate,
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.4",
            referer,
            cancellationToken).ConfigureAwait(false);

        using (response)
        {
            response.EnsureSuccessStatusCode();
            var mediaType = response.Content.Headers.ContentType?.MediaType?.Trim().ToLowerInvariant() ?? string.Empty;

            if (mediaType.Contains("html", StringComparison.Ordinal))
                return null;

            var bytes = await ReadLimitedAsync(response.Content, MaxImageBytes, cancellationToken).ConfigureAwait(false);
            if (DetectExtension(bytes, mediaType, finalUri) is null)
                return null;
            return SaveImage(originalSource, bytes, mediaType, finalUri);
        }
    }

    private static async Task<(HttpResponseMessage Response, Uri FinalUri)> SendAsync(
        Uri source,
        string accept,
        Uri? referer,
        CancellationToken cancellationToken)
    {
        var current = source;
        for (var redirect = 0; redirect <= MaxRedirects; redirect++)
        {
            await EnsurePublicHttpUriAsync(current, cancellationToken).ConfigureAwait(false);

            using var request = new HttpRequestMessage(HttpMethod.Get, current);
            request.Headers.Accept.Clear();
            foreach (var part in accept.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                if (MediaTypeWithQualityHeaderValue.TryParse(part, out var parsed))
                    request.Headers.Accept.Add(parsed);
            }
            if (referer is not null)
                request.Headers.Referrer = referer;

            var response = await Client.SendAsync(
                request,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken).ConfigureAwait(false);

            if ((int)response.StatusCode is >= 300 and <= 399 && response.Headers.Location is { } location)
            {
                if (redirect == MaxRedirects)
                {
                    response.Dispose();
                    throw new HttpRequestException("Too many redirects while loading web artwork.");
                }

                var next = location.IsAbsoluteUri ? location : new Uri(current, location);
                response.Dispose();
                current = next;
                continue;
            }

            return (response, current);
        }

        throw new HttpRequestException("Too many redirects while loading web artwork.");
    }

    private static async Task EnsurePublicHttpUriAsync(Uri uri, CancellationToken cancellationToken)
    {
        if (uri.Scheme is not ("http" or "https"))
            throw new InvalidDataException("Only HTTP and HTTPS image URLs are supported.");

        if (string.IsNullOrWhiteSpace(uri.DnsSafeHost))
            throw new InvalidDataException("The image URL has no host.");

        IPAddress[] addresses;
        if (IPAddress.TryParse(uri.DnsSafeHost, out var literal))
            addresses = [literal];
        else
            addresses = await Dns.GetHostAddressesAsync(uri.DnsSafeHost).WaitAsync(cancellationToken).ConfigureAwait(false);

        if (addresses.Length == 0 || addresses.Any(IsPrivateOrLocalAddress))
            throw new InvalidDataException("Private, loopback and local-network image URLs are not allowed.");
    }

    private static bool IsPrivateOrLocalAddress(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;

        if (address.AddressFamily == AddressFamily.InterNetwork)
        {
            var bytes = address.GetAddressBytes();
            return bytes[0] == 0
                || bytes[0] == 10
                || bytes[0] == 127
                || (bytes[0] == 100 && bytes[1] is >= 64 and <= 127)
                || (bytes[0] == 169 && bytes[1] == 254)
                || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
                || (bytes[0] == 192 && bytes[1] == 168)
                || bytes[0] >= 224;
        }

        if (address.AddressFamily == AddressFamily.InterNetworkV6)
        {
            if (address.IsIPv6LinkLocal || address.IsIPv6Multicast || address.IsIPv6SiteLocal)
                return true;
            var bytes = address.GetAddressBytes();
            return (bytes[0] & 0xFE) == 0xFC;
        }

        return true;
    }

    private static IEnumerable<Uri> ExtractImageCandidates(string html, Uri pageUri)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var candidates = new List<string>();

        foreach (Match tag in Regex.Matches(html, @"<meta\b[^>]*>", RegexOptions.IgnoreCase | RegexOptions.Singleline))
        {
            var attrs = ParseAttributes(tag.Value);
            if (!attrs.TryGetValue("content", out var content) || string.IsNullOrWhiteSpace(content))
                continue;

            attrs.TryGetValue("property", out var property);
            attrs.TryGetValue("name", out var name);
            var key = (property ?? name ?? string.Empty).Trim().ToLowerInvariant();
            if (key is "og:image" or "og:image:url" or "og:image:secure_url" or
                "twitter:image" or "twitter:image:src")
                candidates.Add(content);
        }

        foreach (Match tag in Regex.Matches(html, @"<link\b[^>]*>", RegexOptions.IgnoreCase | RegexOptions.Singleline))
        {
            var attrs = ParseAttributes(tag.Value);
            if (!attrs.TryGetValue("href", out var href) || string.IsNullOrWhiteSpace(href))
                continue;
            var rel = attrs.GetValueOrDefault("rel", string.Empty);
            if (rel.Contains("image_src", StringComparison.OrdinalIgnoreCase) ||
                (rel.Contains("preload", StringComparison.OrdinalIgnoreCase) &&
                 attrs.GetValueOrDefault("as", string.Empty).Equals("image", StringComparison.OrdinalIgnoreCase)))
                candidates.Add(href);
        }

        foreach (Match script in Regex.Matches(
                     html,
                     @"<script\b[^>]*type\s*=\s*[""']application/ld\+json[""'][^>]*>(?<json>.*?)</script>",
                     RegexOptions.IgnoreCase | RegexOptions.Singleline))
        {
            foreach (var value in ExtractJsonImageValues(script.Groups["json"].Value))
                candidates.Add(value);
        }

        var flaticon = TryFlaticonCdnCandidate(pageUri);
        if (flaticon is not null)
            candidates.Add(flaticon.AbsoluteUri);

        foreach (var raw in candidates)
        {
            var decoded = WebUtility.HtmlDecode(raw).Trim().Trim('"', '\'');
            if (decoded.StartsWith("//", StringComparison.Ordinal))
                decoded = pageUri.Scheme + ":" + decoded;
            if (!Uri.TryCreate(pageUri, decoded, out var candidate)) continue;
            if (candidate.Scheme is not ("http" or "https")) continue;
            if (seen.Add(candidate.AbsoluteUri))
                yield return candidate;
        }
    }

    private static Dictionary<string, string> ParseAttributes(string tag)
    {
        var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (Match match in Regex.Matches(
                     tag,
                     @"(?<name>[A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?<q>[""'])(?<value>.*?)\k<q>",
                     RegexOptions.Singleline | RegexOptions.CultureInvariant))
        {
            values[match.Groups["name"].Value] = WebUtility.HtmlDecode(match.Groups["value"].Value);
        }
        return values;
    }

    private static IReadOnlyList<string> ExtractJsonImageValues(string json)
    {
        try
        {
            using var document = JsonDocument.Parse(json);
            return EnumerateJsonImageValues(document.RootElement).ToArray();
        }
        catch
        {
            return Array.Empty<string>();
        }
    }

    private static IEnumerable<string> EnumerateJsonImageValues(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            foreach (var property in element.EnumerateObject())
            {
                if (property.Name.Equals("image", StringComparison.OrdinalIgnoreCase) ||
                    property.Name.Equals("thumbnailUrl", StringComparison.OrdinalIgnoreCase) ||
                    property.Name.Equals("contentUrl", StringComparison.OrdinalIgnoreCase))
                {
                    if (property.Value.ValueKind == JsonValueKind.String &&
                        property.Value.GetString() is { Length: > 0 } value)
                        yield return value;
                    else if (property.Value.ValueKind == JsonValueKind.Array)
                        foreach (var item in property.Value.EnumerateArray())
                            if (item.ValueKind == JsonValueKind.String && item.GetString() is { Length: > 0 } arrayValue)
                                yield return arrayValue;
                }

                foreach (var nested in EnumerateJsonImageValues(property.Value))
                    yield return nested;
            }
        }
        else if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var item in element.EnumerateArray())
                foreach (var nested in EnumerateJsonImageValues(item))
                    yield return nested;
        }
    }

    private static Uri? TryFlaticonCdnCandidate(Uri pageUri)
    {
        if (!pageUri.Host.EndsWith("flaticon.com", StringComparison.OrdinalIgnoreCase))
            return null;

        var match = Regex.Match(pageUri.AbsolutePath, @"_(?<id>\d+)(?:/|$)", RegexOptions.CultureInvariant);
        if (!match.Success || !long.TryParse(match.Groups["id"].Value, out var id) || id <= 0)
            return null;

        var bucket = id / 1000;
        return new Uri($"https://cdn-icons-png.flaticon.com/512/{bucket}/{id}.png");
    }

    private static async Task<byte[]> ReadLimitedAsync(
        HttpContent content,
        long maximumBytes,
        CancellationToken cancellationToken)
    {
        var declared = content.Headers.ContentLength;
        if (declared.HasValue && declared.Value > maximumBytes)
            throw new InvalidDataException($"Remote image exceeds the {maximumBytes / (1024 * 1024)} MiB limit.");

        await using var stream = await content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var output = new MemoryStream();
        var buffer = new byte[64 * 1024];
        long total = 0;
        while (true)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(), cancellationToken).ConfigureAwait(false);
            if (read == 0) break;
            total += read;
            if (total > maximumBytes)
                throw new InvalidDataException($"Remote image exceeds the {maximumBytes / (1024 * 1024)} MiB limit.");
            output.Write(buffer, 0, read);
        }
        return output.ToArray();
    }

    private static string DecodeText(byte[] bytes, string? charset)
    {
        if (!string.IsNullOrWhiteSpace(charset))
        {
            try { return Encoding.GetEncoding(charset.Trim('"', '\'')).GetString(bytes); }
            catch { }
        }
        return Encoding.UTF8.GetString(bytes);
    }

    private static string SaveImage(string originalSource, byte[] bytes, string mediaType, Uri finalUri)
    {
        if (bytes.Length == 0)
            throw new InvalidDataException("The remote image is empty.");

        var extension = DetectExtension(bytes, mediaType, finalUri)
            ?? throw new InvalidDataException($"The URL did not return a supported image: {finalUri}");

        Directory.CreateDirectory(CacheDirectory);
        var key = CacheKey(originalSource);
        foreach (var stale in Directory.EnumerateFiles(CacheDirectory, key + ".*", SearchOption.TopDirectoryOnly))
            TryDelete(stale);

        var finalPath = Path.Combine(CacheDirectory, key + extension);
        var tempPath = finalPath + ".tmp-" + Guid.NewGuid().ToString("N");
        File.WriteAllBytes(tempPath, bytes);
        File.Move(tempPath, finalPath, overwrite: true);
        File.SetLastWriteTimeUtc(finalPath, DateTime.UtcNow);
        return finalPath;
    }

    private static string? DetectExtension(byte[] bytes, string mediaType, Uri uri)
    {
        if (bytes.Length >= 8 &&
            bytes[0] == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47)
            return ".png";
        if (bytes.Length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF)
            return ".jpg";
        if (bytes.Length >= 12 &&
            Encoding.ASCII.GetString(bytes, 0, 4) == "RIFF" &&
            Encoding.ASCII.GetString(bytes, 8, 4) == "WEBP")
            return ".webp";
        if (bytes.Length >= 6 &&
            (Encoding.ASCII.GetString(bytes, 0, 6) is "GIF87a" or "GIF89a"))
            return ".gif";
        if (bytes.Length >= 2 && bytes[0] == 0x42 && bytes[1] == 0x4D)
            return ".bmp";
        if (bytes.Length >= 4 && bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 1 && bytes[3] == 0)
            return ".ico";

        var prefix = Encoding.UTF8.GetString(bytes, 0, Math.Min(bytes.Length, 1024)).TrimStart('\uFEFF', ' ', '\r', '\n', '\t');
        if (prefix.StartsWith("<svg", StringComparison.OrdinalIgnoreCase) ||
            (prefix.StartsWith("<?xml", StringComparison.OrdinalIgnoreCase) &&
             prefix.Contains("<svg", StringComparison.OrdinalIgnoreCase)))
            return ".svg";

        return mediaType switch
        {
            "image/png" => ".png",
            "image/jpeg" or "image/jpg" => ".jpg",
            "image/webp" => ".webp",
            "image/gif" => ".gif",
            "image/bmp" => ".bmp",
            "image/x-icon" or "image/vnd.microsoft.icon" => ".ico",
            "image/svg+xml" => ".svg",
            _ => Path.GetExtension(uri.AbsolutePath).ToLowerInvariant() switch
            {
                ".png" => ".png",
                ".jpg" or ".jpeg" => ".jpg",
                ".webp" => ".webp",
                ".gif" => ".gif",
                ".bmp" => ".bmp",
                ".ico" => ".ico",
                ".svg" => ".svg",
                _ => null,
            },
        };
    }

    private static string CacheKey(string source)
        => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(NormalizeSource(source)))).ToLowerInvariant();

    private static string? ResolveLocalPath(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        try
        {
            if (Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.IsFile)
                value = uri.LocalPath;
            var full = Path.GetFullPath(Environment.ExpandEnvironmentVariables(value));
            return File.Exists(full) ? full : null;
        }
        catch
        {
            return null;
        }
    }

    private static string EnsureDirectory(string path)
    {
        Directory.CreateDirectory(path);
        return path;
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); }
        catch { }
    }
}
