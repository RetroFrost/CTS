using CubicalCompare.Core.Project;
using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage;
using Windows.Storage.Pickers;
using WinRT.Interop;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private bool _developerModeUnlocked = true;
    private Border? _developerSettingsCard;
    private TextBlock? _developerUnlockStatus;

    private void RendererPage_Loaded(object sender, RoutedEventArgs e)
    {
        ApplyDeveloperVisibility();
        RefreshInternalCodeOverrideStatus();
    }

    private void ConfigureDeveloperUnlock(
        TextBlock versionText,
        Border developerSettingsCard,
        TextBlock developerUnlockStatus)
    {
        _developerSettingsCard = developerSettingsCard;
        _developerUnlockStatus = developerUnlockStatus;
        _developerModeUnlocked = true;
        ToolTipService.SetToolTip(versionText, "Developer Options are available in Settings and Style & Model.");
        if (_developerUnlockStatus is not null)
            _developerUnlockStatus.Text = "Developer Options are enabled and always visible.";
        ApplyDeveloperVisibility();
    }

    private void ApplyDeveloperVisibility()
    {
        DeveloperCodeOverridePanel.Visibility = Visibility.Visible;
        if (_developerSettingsCard is not null)
            _developerSettingsCard.Visibility = Visibility.Visible;
    }

    private async void ReplaceInternalCode_Click(object sender, RoutedEventArgs e)
    {
        if (!_developerModeUnlocked)
            return;

        var files = await PickFilesAsync([".cs"]);
        if (files.Count == 0) return;

        var names = files.Select(file => file.Name).ToArray();
        var confirmation = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = files.Count == 1 ? "Replace internal code?" : $"Import {files.Count} C# files?",
            Content =
                "Cubical Compare will compile the selected .cs files together as one runtime source bundle. " +
                "Any valid C# filename is accepted; helper files, partial classes and shared code can be imported in bulk. " +
                "Known renderer classes are hot-swapped automatically when present.\n\n" +
                "Imported C# runs with the same permissions as Cubical Compare and is not sandboxed.\n\n" +
                string.Join(Environment.NewLine, names.Take(12)) +
                (names.Length > 12 ? $"{Environment.NewLine}…and {names.Length - 12} more" : string.Empty),
            PrimaryButtonText = "Compile & replace",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await confirmation.ShowAsync() != ContentDialogResult.Primary) return;

        ReplaceInternalCodeButton.IsEnabled = false;
        RemoveInternalCodeOverrideButton.IsEnabled = false;
        InternalCodeOverrideStatusText.Text = $"Compiling {files.Count} source file{(files.Count == 1 ? "" : "s")}…";
        ShowActivityWatcher("Developer compile", $"Compiling {files.Count} C# file{(files.Count == 1 ? "" : "s")}…", null, true);

        try
        {
            var paths = files.Select(file => file.Path).ToArray();
            var result = await Task.Run(() => InternalCodeOverrideManager.InstallMany(paths));
            var targets = result.ActivatedTargets.Count == 0
                ? "source bundle loaded (support code only)"
                : "active · " + string.Join(", ", result.ActivatedTargets);
            InternalCodeOverrideStatusText.Text =
                $"{targets} · {result.Files.Count} source file{(result.Files.Count == 1 ? "" : "s")} saved" +
                (result.Warnings.Count > 0 ? $" · {result.Warnings.Count} compiler warning(s)" : "");
            TimelineStatusText.Text = "Internal C# bundle reloaded.";

            await RenderCurrentFrameAsync();

            if (result.Warnings.Count > 0)
            {
                var warningDialog = new ContentDialog
                {
                    XamlRoot = RootNavigation.XamlRoot,
                    Title = "C# bundle installed with warnings",
                    Content = string.Join(Environment.NewLine, result.Warnings.Take(16)),
                    CloseButtonText = "Close",
                    DefaultButton = ContentDialogButton.Close,
                };
                await warningDialog.ShowAsync();
            }

            CompleteActivityWatcher("Developer compile", $"Loaded {result.Files.Count} C# source file{(result.Files.Count == 1 ? "" : "s")}.");
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not install internal C# source bundle", ex);
            RefreshInternalCodeOverrideStatus();
            FailActivityWatcher("Developer compile failed", ex.Message);
            await ShowErrorAsync("Could not replace internal code", ex.Message);
        }
        finally
        {
            ReplaceInternalCodeButton.IsEnabled = true;
            RemoveInternalCodeOverrideButton.IsEnabled = true;
            RefreshInternalCodeOverrideStatus();
        }
    }

    private async void RemoveInternalCodeOverride_Click(object sender, RoutedEventArgs e)
    {
        if (!_developerModeUnlocked)
            return;

        try
        {
            var removed = InternalCodeOverrideManager.RemoveAll();
            InternalCodeOverrideStatusText.Text = removed
                ? "Built-in renderer code restored."
                : "No internal C# bundle was installed.";
            if (removed) TimelineStatusText.Text = "Internal C# overrides removed.";
            await RenderCurrentFrameAsync();
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not remove internal code override", ex);
            await ShowErrorAsync("Could not restore built-in code", ex.Message);
        }
        finally
        {
            RefreshInternalCodeOverrideStatus();
        }
    }

    private void RefreshInternalCodeOverrideStatus()
    {
        var status = InternalCodeOverrideManager.Status();
        if (!string.IsNullOrWhiteSpace(status.Error))
        {
            InternalCodeOverrideStatusText.Text = $"Stored bundle could not load · {status.Error}";
            return;
        }

        if (status.Loaded)
        {
            var targetText = status.ActivatedTargets.Count == 0
                ? "support-code bundle"
                : string.Join(", ", status.ActivatedTargets);
            InternalCodeOverrideStatusText.Text =
                $"Loaded · {targetText} · {status.Files.Count} source file{(status.Files.Count == 1 ? "" : "s")}";
            return;
        }

        if (status.Installed)
        {
            InternalCodeOverrideStatusText.Text =
                $"Installed · {status.Files.Count} source file{(status.Files.Count == 1 ? "" : "s")} · loads on renderer use";
            return;
        }

        InternalCodeOverrideStatusText.Text =
            "Using built-in code · bulk .cs bundles accepted · hot-swap targets: " +
            string.Join(", ", InternalCodeOverrideManager.HotSwapTargets);
    }

    private async Task<IReadOnlyList<StorageFile>> PickFilesAsync(IReadOnlyList<string> extensions)
    {
        var picker = new FileOpenPicker
        {
            SuggestedStartLocation = PickerLocationId.Downloads,
            ViewMode = PickerViewMode.List,
        };
        foreach (var extension in extensions) picker.FileTypeFilter.Add(extension);

        var hwnd = WindowNative.GetWindowHandle(this);
        InitializeWithWindow.Initialize(picker, hwnd);
        return await picker.PickMultipleFilesAsync();
    }
}
