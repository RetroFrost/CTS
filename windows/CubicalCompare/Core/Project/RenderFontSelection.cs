namespace CubicalCompare.Core.Project;

public static class RenderFontSelection
{
    private static readonly object Gate = new();
    private static string _currentFamily = "Nexa";
    private static string _currentFile = string.Empty;

    public static event EventHandler? Changed;

    public static string CurrentFamily
    {
        get
        {
            lock (Gate) return _currentFamily;
        }
    }

    public static string CurrentFile
    {
        get
        {
            lock (Gate) return _currentFile;
        }
    }

    public static void Apply(string? family, string? filePath)
    {
        var normalizedFamily = string.IsNullOrWhiteSpace(family) ? "Nexa" : family.Trim();
        var normalizedFile = string.IsNullOrWhiteSpace(filePath) ? string.Empty : filePath.Trim();
        var changed = false;

        lock (Gate)
        {
            if (!string.Equals(_currentFamily, normalizedFamily, StringComparison.Ordinal)
                || !string.Equals(_currentFile, normalizedFile, StringComparison.OrdinalIgnoreCase))
            {
                _currentFamily = normalizedFamily;
                _currentFile = normalizedFile;
                changed = true;
            }
        }

        if (changed) Changed?.Invoke(null, EventArgs.Empty);
    }

    public static void ApplyProjectFont(string? family, string? filePath)
    {
        var usableFile = string.IsNullOrWhiteSpace(filePath) ? string.Empty : filePath.Trim();
        if (usableFile.Length > 0 && !File.Exists(usableFile)) usableFile = string.Empty;
        Apply(family, usableFile);
    }
}
