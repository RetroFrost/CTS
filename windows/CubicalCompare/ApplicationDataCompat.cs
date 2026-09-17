using CubicalCompare.Core.Project;
using Microsoft.Win32;

namespace CubicalCompare;

/// <summary>
/// Package-identity-free compatibility surface for the handful of editor files that
/// historically used Windows.Storage.ApplicationData.Current. Keeping the familiar
/// Current.LocalSettings / Current.LocalFolder shape avoids package-only runtime calls
/// while the app transitions to unpackaged deployment.
/// </summary>
internal static class ApplicationData
{
    public static CompatApplicationData Current { get; } = new();
}

internal sealed class CompatApplicationData
{
    public CompatLocalSettings LocalSettings { get; } = new();
    public CompatLocalFolder LocalFolder { get; } = new();
}

internal sealed class CompatLocalFolder
{
    public string Path => AppDataPaths.RootDirectory;
}

internal sealed class CompatLocalSettings
{
    public CompatSettingsValues Values { get; } = new();
}

internal sealed class CompatSettingsValues
{
    private const string RegistryPath = @"Software\RetroFrost\CubicalCompare";

    public bool TryGetValue(string key, out object? value)
    {
        using var registry = Registry.CurrentUser.OpenSubKey(RegistryPath, writable: false);
        value = registry?.GetValue(key, null, RegistryValueOptions.DoNotExpandEnvironmentNames);
        return value is not null;
    }

    public object? this[string key]
    {
        get
        {
            TryGetValue(key, out var value);
            return value;
        }
        set
        {
            using var registry = Registry.CurrentUser.CreateSubKey(RegistryPath, writable: true)
                ?? throw new IOException("Could not open Cubical Compare settings storage.");

            if (value is null)
            {
                registry.DeleteValue(key, throwOnMissingValue: false);
                return;
            }

            var kind = value switch
            {
                int => RegistryValueKind.DWord,
                long => RegistryValueKind.QWord,
                string => RegistryValueKind.String,
                string[] => RegistryValueKind.MultiString,
                byte[] => RegistryValueKind.Binary,
                bool => RegistryValueKind.DWord,
                _ => RegistryValueKind.String,
            };
            var stored = value is bool flag ? (flag ? 1 : 0) : value;
            registry.SetValue(key, stored, kind);
        }
    }
}
