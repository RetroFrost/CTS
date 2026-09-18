using System.Text.Json;

namespace CubicalCompare.Core.Project;

public static class AppDataPaths
{
    public static string RootDirectory { get; } = Ensure(Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RetroFrost",
        "CubicalCompare"));

    public static string FontsDirectory { get; } = Ensure(Path.Combine(RootDirectory, "Fonts"));
    public static string PreferencesFile { get; } = Path.Combine(RootDirectory, "preferences.json");

    private static string Ensure(string path)
    {
        Directory.CreateDirectory(path);
        return path;
    }
}

public static class AppPreferences
{
    private static readonly object Gate = new();
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static int GetInt(string key, int fallback)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        lock (Gate)
        {
            try
            {
                var values = Load();
                return values.TryGetValue(key, out var node) && node.ValueKind == JsonValueKind.Number && node.TryGetInt32(out var value)
                    ? value
                    : fallback;
            }
            catch
            {
                return fallback;
            }
        }
    }

    public static string GetString(string key, string fallback)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        lock (Gate)
        {
            try
            {
                var values = Load();
                return values.TryGetValue(key, out var node) && node.ValueKind == JsonValueKind.String
                    ? node.GetString() ?? fallback
                    : fallback;
            }
            catch
            {
                return fallback;
            }
        }
    }

    public static bool GetBool(string key, bool fallback)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        lock (Gate)
        {
            try
            {
                var values = Load();
                return values.TryGetValue(key, out var node) && node.ValueKind is JsonValueKind.True or JsonValueKind.False
                    ? node.GetBoolean()
                    : fallback;
            }
            catch
            {
                return fallback;
            }
        }
    }

    public static void SetInts(params (string Key, int Value)[] values)
    {
        lock (Gate)
        {
            var current = Load();
            foreach (var (key, value) in values)
                current[key] = JsonSerializer.SerializeToElement(value);
            Save(current);
        }
    }

    public static void SetString(string key, string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        lock (Gate)
        {
            var current = Load();
            current[key] = JsonSerializer.SerializeToElement(value ?? string.Empty);
            Save(current);
        }
    }

    public static void SetBool(string key, bool value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(key);
        lock (Gate)
        {
            var current = Load();
            current[key] = JsonSerializer.SerializeToElement(value);
            Save(current);
        }
    }

    private static Dictionary<string, JsonElement> Load()
    {
        if (!File.Exists(AppDataPaths.PreferencesFile))
            return new Dictionary<string, JsonElement>(StringComparer.Ordinal);

        var text = File.ReadAllText(AppDataPaths.PreferencesFile);
        return JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(text)
            ?? new Dictionary<string, JsonElement>(StringComparer.Ordinal);
    }

    private static void Save(Dictionary<string, JsonElement> values)
    {
        Directory.CreateDirectory(AppDataPaths.RootDirectory);
        var temp = AppDataPaths.PreferencesFile + ".tmp";
        File.WriteAllText(temp, JsonSerializer.Serialize(values, JsonOptions));
        File.Move(temp, AppDataPaths.PreferencesFile, overwrite: true);
    }
}
