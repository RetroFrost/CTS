using Microsoft.UI.Xaml;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private long _activityWatcherRevision;

    private void ShowActivityWatcher(string title, string detail, double? progress, bool indeterminate = false)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() => ShowActivityWatcher(title, detail, progress, indeterminate));
            return;
        }

        Interlocked.Increment(ref _activityWatcherRevision);
        ActivityWatcherPanel.Visibility = Visibility.Visible;
        ActivityWatcherTitleText.Text = title;
        ActivityWatcherDetailText.Text = detail;
        ActivityWatcherProgressBar.IsIndeterminate = indeterminate || progress is null;
        ActivityWatcherProgressBar.Value = progress is null ? 0 : Math.Clamp(progress.Value, 0, 100);
    }

    private void UpdateActivityWatcher(string title, string detail, double progress)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() => UpdateActivityWatcher(title, detail, progress));
            return;
        }

        ActivityWatcherPanel.Visibility = Visibility.Visible;
        ActivityWatcherTitleText.Text = title;
        ActivityWatcherDetailText.Text = detail;
        ActivityWatcherProgressBar.IsIndeterminate = false;
        ActivityWatcherProgressBar.Value = Math.Clamp(progress, 0, 100);
    }

    private void CompleteActivityWatcher(string title, string detail)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() => CompleteActivityWatcher(title, detail));
            return;
        }

        var revision = Interlocked.Increment(ref _activityWatcherRevision);
        ActivityWatcherPanel.Visibility = Visibility.Visible;
        ActivityWatcherTitleText.Text = title;
        ActivityWatcherDetailText.Text = detail;
        ActivityWatcherProgressBar.IsIndeterminate = false;
        ActivityWatcherProgressBar.Value = 100;
        _ = HideActivityWatcherAfterDelayAsync(revision, TimeSpan.FromSeconds(4));
    }

    private void FailActivityWatcher(string title, string detail)
    {
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() => FailActivityWatcher(title, detail));
            return;
        }

        var revision = Interlocked.Increment(ref _activityWatcherRevision);
        ActivityWatcherPanel.Visibility = Visibility.Visible;
        ActivityWatcherTitleText.Text = title;
        ActivityWatcherDetailText.Text = detail;
        ActivityWatcherProgressBar.IsIndeterminate = false;
        ActivityWatcherProgressBar.Value = 0;
        _ = HideActivityWatcherAfterDelayAsync(revision, TimeSpan.FromSeconds(8));
    }

    private async Task HideActivityWatcherAfterDelayAsync(long revision, TimeSpan delay)
    {
        await Task.Delay(delay);
        if (!DispatcherQueue.HasThreadAccess)
        {
            DispatcherQueue.TryEnqueue(() =>
            {
                if (revision == Interlocked.Read(ref _activityWatcherRevision))
                    ActivityWatcherPanel.Visibility = Visibility.Collapsed;
            });
            return;
        }

        if (revision == Interlocked.Read(ref _activityWatcherRevision))
            ActivityWatcherPanel.Visibility = Visibility.Collapsed;
    }

    private static string FormatByteCount(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        if (bytes < 1024L * 1024) return $"{bytes / 1024d:0.0} KB";
        if (bytes < 1024L * 1024 * 1024) return $"{bytes / (1024d * 1024):0.0} MB";
        return $"{bytes / (1024d * 1024 * 1024):0.00} GB";
    }
}
