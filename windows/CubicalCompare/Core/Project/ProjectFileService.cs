using System.Text.Json;

namespace CubicalCompare.Core.Project;

public static class ProjectFileService
{
    public const string Extension = ".ccproject";
    public const int CurrentFormatVersion = 1;
    private const long MaxProjectBytes = 32L * 1024 * 1024;
    private const int MaxCards = 10_000;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        PropertyNameCaseInsensitive = true,
    };

    public static async Task SaveAsync(ComparisonProject project, string path, CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(project);
        ValidateAndNormalize(project);

        var document = new ProjectDocument
        {
            Format = "CubicalCompareProject",
            Version = CurrentFormatVersion,
            Project = project,
        };

        var directory = Path.GetDirectoryName(path);
        if (!string.IsNullOrWhiteSpace(directory)) Directory.CreateDirectory(directory);

        var temporaryPath = path + ".tmp-" + Guid.NewGuid().ToString("N");
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

            if (new FileInfo(temporaryPath).Length > MaxProjectBytes)
                throw new InvalidDataException("The project is too large to save safely.");

            File.Move(temporaryPath, path, overwrite: true);
        }
        finally
        {
            TryDelete(temporaryPath);
        }
    }

    public static async Task<ComparisonProject> LoadAsync(string path, CancellationToken cancellationToken = default)
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

    public static void ValidateAndNormalize(ComparisonProject project)
    {
        project.Name = string.IsNullOrWhiteSpace(project.Name) ? "Untitled comparison" : project.Name.Trim();
        if (project.Name.Length > 200) project.Name = project.Name[..200];

        if (project.Width is < 320 or > 16_384) throw new InvalidDataException("Project width is outside the supported range.");
        if (project.Height is < 240 or > 16_384) throw new InvalidDataException("Project height is outside the supported range.");
        if (project.Fps is < 1 or > 240) throw new InvalidDataException("Project frame rate is outside the supported range.");
        if (!double.IsFinite(project.CustomLengthSeconds) || project.CustomLengthSeconds is < 0 or > 86_400)
            throw new InvalidDataException("Project duration is invalid.");

        project.RenderFontFamily ??= "Nexa";
        project.RenderFontFile ??= string.Empty;
        project.Cards ??= [];
        if (project.Cards.Count == 0) throw new InvalidDataException("A project must contain at least one card.");
        if (project.Cards.Count > MaxCards) throw new InvalidDataException($"Projects are limited to {MaxCards:N0} cards.");

        var ids = new HashSet<string>(StringComparer.Ordinal);
        foreach (var card in project.Cards)
        {
            card.Id = string.IsNullOrWhiteSpace(card.Id) ? Guid.NewGuid().ToString("N") : card.Id.Trim();
            if (!ids.Add(card.Id)) card.Id = Guid.NewGuid().ToString("N");
            card.Title ??= "Untitled";
            card.Value ??= string.Empty;
            card.BadgeHeader ??= string.Empty;
            card.Description ??= string.Empty;
            card.ImagePath ??= string.Empty;
            card.ImageLayer = string.IsNullOrWhiteSpace(card.ImageLayer) ? "behind" : card.ImageLayer.Trim();

            if (!double.IsFinite(card.ImageX) || !double.IsFinite(card.ImageY) ||
                !double.IsFinite(card.ImageScale) || !double.IsFinite(card.ImageRotation) ||
                !double.IsFinite(card.ImageCropLeft) || !double.IsFinite(card.ImageCropTop) ||
                !double.IsFinite(card.ImageCropRight) || !double.IsFinite(card.ImageCropBottom))
                throw new InvalidDataException($"Card '{card.Title}' contains an invalid image transform.");

            if (card.ImageScale <= 0 || card.ImageScale > 100) card.ImageScale = 1;
            card.ImageCropLeft = Math.Clamp(card.ImageCropLeft, 0, 1);
            card.ImageCropTop = Math.Clamp(card.ImageCropTop, 0, 1);
            card.ImageCropRight = Math.Clamp(card.ImageCropRight, 0, 1);
            card.ImageCropBottom = Math.Clamp(card.ImageCropBottom, 0, 1);
        }
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
