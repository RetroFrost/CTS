using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private void RendererPage_Loaded(object sender, RoutedEventArgs e)
    {
        RefreshInternalCodeOverrideStatus();
    }

    private async void ReplaceInternalCode_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".cs"]);
        if (file is null) return;

        var supported = string.Join(", ", InternalCodeOverrideManager.SupportedFiles);
        var confirmation = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "Replace internal code?",
            Content =
                $"Cubical Compare will compile and run {file.Name} inside the app. " +
                "C# overrides have the same permissions as Cubical Compare and are not sandboxed.\n\n" +
                $"Hot-swappable files in this build: {supported}.",
            PrimaryButtonText = "Compile & replace",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await confirmation.ShowAsync() != ContentDialogResult.Primary) return;

        ReplaceInternalCodeButton.IsEnabled = false;
        RemoveInternalCodeOverrideButton.IsEnabled = false;
        InternalCodeOverrideStatusText.Text = $"Compiling {file.Name}…";

        try
        {
            var result = await Task.Run(() => InternalCodeOverrideManager.Install(file.Path));
            InternalCodeOverrideStatusText.Text =
                $"Active · {result.FriendlyName} · saved for future launches" +
                (result.Warnings.Count > 0 ? $" · {result.Warnings.Count} compiler warning(s)" : "");
            TimelineStatusText.Text = $"Internal code override active · {result.FriendlyName}";

            await RenderCurrentFrameAsync();

            if (result.Warnings.Count > 0)
            {
                var warningDialog = new ContentDialog
                {
                    XamlRoot = RootNavigation.XamlRoot,
                    Title = "Override installed with warnings",
                    Content = string.Join(Environment.NewLine, result.Warnings.Take(12)),
                    CloseButtonText = "Close",
                    DefaultButton = ContentDialogButton.Close,
                };
                await warningDialog.ShowAsync();
            }
        }
        catch (Exception ex)
        {
            App.WriteLog($"Could not install internal code override from {file.Path}", ex);
            RefreshInternalCodeOverrideStatus();
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
        try
        {
            var removed = InternalCodeOverrideManager.RemoveAll();
            InternalCodeOverrideStatusText.Text = removed
                ? "Built-in renderer code restored."
                : "No internal code override was installed.";
            TimelineStatusText.Text = removed
                ? "Internal code overrides removed."
                : TimelineStatusText.Text;

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
        var statuses = InternalCodeOverrideManager.Status();
        var failures = statuses.Where(status => !string.IsNullOrWhiteSpace(status.Error)).ToArray();
        if (failures.Length > 0)
        {
            InternalCodeOverrideStatusText.Text = string.Join(
                " · ",
                failures.Select(status => $"{status.FriendlyName}: {status.Error}"));
            return;
        }

        var active = statuses.Where(status => status.Active).ToArray();
        if (active.Length > 0)
        {
            InternalCodeOverrideStatusText.Text =
                "Active · " + string.Join(", ", active.Select(status => status.FriendlyName));
            return;
        }

        var installed = statuses.Where(status => status.Installed).ToArray();
        if (installed.Length > 0)
        {
            InternalCodeOverrideStatusText.Text =
                "Installed · loads automatically when its renderer is used · " +
                string.Join(", ", installed.Select(status => status.FriendlyName));
            return;
        }

        InternalCodeOverrideStatusText.Text =
            "Using built-in code · supported: " +
            string.Join(", ", InternalCodeOverrideManager.SupportedFiles);
    }
}
