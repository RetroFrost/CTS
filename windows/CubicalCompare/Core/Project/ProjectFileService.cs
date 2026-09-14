using System.Text.Json;

namespace CubicalCompare.Core.Project;

public static class ProjectFileService
{
    public const string Extension = ".ccproject";
    public const int CurrentFormatVersion = 1;
    private const long MaxProjectBytes = 32L * 1024 * 1024;
    private const int MaxCards = 10_000;
    private const int MaxNameLength = 200;
    private const int MaxCardTitleLength = 1_000;
    private const int MaxCardValueLength = 1_000;
    private const int MaxBadgeHeaderLength = 1_000;
    private const int MaxDescriptionLength = 100_000;
    private const int MaxPathLength = 32_000;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public static async Task SaveAsync(ComparisonProject project, string path, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(project);
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("A project path is required.", nameof(path));

        ValidateAndNormalize(project);

        var document = new ProjectDocument
        {
            Format = "CubicalCompareProject",
            Version = CurrentFormatVersion,
            Project = project,
        };

        var fullPath = Path.GetFullPath(path);
        var directory = Path.GetDirectoryName(fullPath);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);

        var temporaryPath = fullPath + ".tmp-" + Guid.NewGuid().ToString("N");
        var backupPath = fullPath + ".bak";
        try
        {
            await using (var stream = new FileStream(
                temporaryPath,
                FileMode.CreateNew,
                FileAccess.Write,
                FileShare.None,
                64 * 1024,
                FileOptions.Asynchronous | FileOptions.WriteThrough))
            {
                await JsonSerializer.SerializeAsync(stream, document, JsonOptions, cancellationToken);
                await stream.FlushAsync(cancellationToken);
            }

            var stagedLength = new FileInfo(temporaryPath).Length;
            if (stagedLength <= 0)
                throw new IOException("The staged project file is empty.");
            if (stagedLength > MaxProjectBytes)
                throw new InvalidDataException("The project is too large to save safely.");

            // Never destroy the last known-good project while replacing it. File.Replace performs a
            // same-volume transactional replacement on Windows and leaves the previous version at .bak.
            if (File.Exists(fullPath))
            {
                File.Replace(temporaryPath, fullPath, backupPath, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(temporaryPath, fullPath);
            }
        }
        finally
        {
            TryDelete(temporaryPath);
        }
    }

    public static async Task<ComparisonProject> LoadAsync(string path, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("A project path is required.", nameof(path));

        var fullPath = Path.GetFullPath(path);
        try
        {
            return await LoadExactAsync(fullPath, cancellationToken);
        }
        catch (Exception primaryError) when (IsRecoverableLoadFailure(primaryError))
        {
            var backupPath = fullPath + ".bak";
            if (!File.Exists(backupPath)) throw;

            try
            {
                return await LoadExactAsync(backupPath, cancellationToken);
            }
            catch (Exception backupError) when (IsRecoverableLoadFailure(backupError))
            {
                throw new InvalidDataException(
                    "The project and its automatic backup are both unreadable.",
                    new AggregateException(primaryError, backupError));
            }
        }
    }

    public static void ValidateAndNormalize(ComparisonProject project)
    {
        ArgumentNullException.ThrowIfNull(project);

        project.Name = NormalizeText(project.Name, "Untitled comparison", MaxNameLength);

        if (project.Width is < 320 or > 16_384) throw new InvalidDataException("Project width is outside the supported range.");
        if (project.Height is < 240 or > 16_384) throw new InvalidDataException("Project height is outside the supported range.");
        if (project.Fps is < 1 or > 240) throw new InvalidDataException("Project frame rate is outside the supported range.");
        if (!double.IsFinite(project.CustomLengthSeconds) || project.CustomLengthSeconds is < 0 or > 86_400)
            throw new InvalidDataException("Project duration is invalid.");

        project.RenderFontFamily = NormalizeText(project.RenderFontFamily, "Nexa", 256);
        project.RenderFontFile = NormalizeText(project.RenderFontFile, string.Empty, MaxPathLength);
        project.Cards ??= [];
        if (project.Cards.Count == 0) throw new InvalidDataException("A project must contain at least one card.");
        if (project.Cards.Count > MaxCards) throw new InvalidDataException($"Projects are limited to {MaxCards:N0} cards.");

        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var card in project.Cards)
        {
            if (card is null) throw new InvalidDataException("The project contains an empty card entry.");

            card.Id = NormalizeText(card.Id, Guid.NewGuid().ToString("N"), 256);
            if (!ids.Add(card.Id)) card.Id = Guid.NewGuid().ToString("N");
            card.Title = NormalizeText(card.Title, "Untitled", MaxCardTitleLength);
            card.Value = NormalizeText(card.Value, string.Empty, MaxCardValueLength);
            card.BadgeHeader = NormalizeText(card.BadgeHeader, string.Empty, MaxBadgeHeaderLength);
            card.Description = NormalizeText(card.Description, string.Empty, MaxDescriptionLength, trim: false);
            card.ImagePath = NormalizeText(card.ImagePath, string.Empty, MaxPathLength);
            card.ImageLayer = NormalizeText(card.ImageLayer, "behind", 64).ToLowerInvariant();

            if (!double.IsFinite(card.ImageX) || !double.IsFinite(card.ImageY) ||
                !double.IsFinite(card.ImageScale) || !double.IsFinite(card.ImageRotation) ||
                !double.IsFinite(card.ImageCropLeft) || !double.IsFinite(card.ImageCropTop) ||
                !double.IsFinite(card.ImageCropRight) || !double.IsFinite(card.ImageCropBottom))
                throw new InvalidDataException($"Card '{card.Title}' contains an invalid image transform.");

            // Keep transforms bounded before they reach Skia/native code. Extreme but finite values can
            // otherwise create enormous intermediate geometry and turn a malformed project into an OOM.
            card.ImageX = Math.Clamp(card.ImageX, -100_000, 100_000);
            card.ImageY = Math.Clamp(card.ImageY, -100_000, 100_000);
            card.ImageRotation = Math.Clamp(card.ImageRotation, -360_000, 360_000);
            if (card.ImageScale <= 0 || card.ImageScale > 100) card.ImageScale = 1;
            card.ImageCropLeft = Math.Clamp(card.ImageCropLeft, 0, 1);
            card.ImageCropTop = Math.Clamp(card.ImageCropTop, 0, 1);
            card.ImageCropRight = Math.Clamp(card.ImageCropRight, 0, 1);
            card.ImageCropBottom = Math.Clamp(card.ImageCropBottom, 0, 1);

            // A crop that removes the whole image is not meaningful and can lead to zero-size source
            // rectangles in renderers. Repair only the invalid axis while preserving the user's other crop.
            if (card.ImageCropLeft + card.ImageCropRight >= 0.999)
            {
                card.ImageCropLeft = 0;
                card.ImageCropRight = 0;
            }
            if (card.ImageCropTop + card.ImageCropBottom >= 0.999)
            {
                card.ImageCropTop = 0;
                card.ImageCropBottom = 0;
            }
        }
    }

    private static async Task<ComparisonProject> LoadExactAsync(string path, CancellationToken cancellationToken)
    {
        var info = new FileInfo(path);
        if (!info.Exists) throw new FileNotFoundException("The project file no longer exists.", path);
        if (info.Length <= 0) throw new InvalidDataException("The project file is empty.");
        if (info.Length > MaxProjectBytes)
            throw new InvalidDataException($"The project file is unexpectedly large ({info.Length:N0} bytes).");

        ProjectDocument? document;
        try
        {
            await using var stream = new FileStream(
                path,
                FileMode.Open,
                FileAccess.Read,
                FileShare.Read,
                64 * 1024,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            document = await JsonSerializer.DeserializeAsync<ProjectDocument>(stream, JsonOptions, cancellationToken);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException("The file is not a valid Cubical Compare project.", ex);
        }

        if (document is null || !string.Equals(document.Format, "CubicalCompareProject", StringComparison.Ordinal))
            throw new InvalidDataException("The file is not a Cubical Compare project.");
        if (document.Version is < 1 or > CurrentFormatVersion)
            throw new InvalidDataException($"Project format version {document.Version} is not supported by this build.");
        if (document.Project is null)
            throw new InvalidDataException("The project file does not contain project data.");

        ValidateAndNormalize(document.Project);
        return document.Project;
    }

    private static bool IsRecoverableLoadFailure(Exception exception) => exception is
        InvalidDataException or
        IOException or
        UnauthorizedAccessException;

    private static string NormalizeText(string? value, string fallback, int maxLength, bool trim = true)
    {
        var normalized = string.IsNullOrWhiteSpace(value) ? fallback : value;
        if (trim) normalized = normalized.Trim();
        if (normalized.Length > maxLength) normalized = normalized[..maxLength];
        return normalized;
    }

    private static void TryDelete(string path)
    {
        try
        {
            if (File.Exists(path)) File.Delete(path);
        }
        catch
        {
            // Best-effort cleanup for an interrupted save.
        }
    }

    private sealed class ProjectDocument
    {
        public string Format { get; set; } = "CubicalCompareProject";
        public int Version { get; set; } = CurrentFormatVersion;
        public ComparisonProject? Project { get; set; }
    }
}
