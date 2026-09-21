using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private DispatcherTimer? _previewPlaybackTimer;
    private Button? _previewPlayButton;
    private bool _previewPlaying;
    private bool _previewPlaybackSliderUpdate;
    private bool _previewPlaybackRenderLoopRunning;
    private int _previewPlaybackRequestedFrame = -1;
    private int _previewPlaybackStartFrame;
    private readonly Stopwatch _previewPlaybackClock = new();

    internal void InitializePreviewPlayback()
    {
        if (_previewPlayButton is not null)
            return;

        _previewPlayButton = PreviewPlayButton;
        _previewPlayButton.Click += PreviewPlayButton_Click;
        ToolTipService.SetToolTip(_previewPlayButton, "Play preview");

        // This timer advances the clock only. Rendering is deliberately single-flight:
        // a 60 FPS timer must never enqueue 60 expensive renderer tasks per second.
        _previewPlaybackTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(16),
        };
        _previewPlaybackTimer.Tick += PreviewPlaybackTimer_Tick;

        Closed += (_, _) => StopPreviewPlayback();
        RefreshPreviewPlayButton();
    }

    private void PreviewPlayButton_Click(object sender, RoutedEventArgs e)
    {
        if (_legacyRenderer is null || ProjectFrameSlider.Maximum <= 0)
        {
            TimelineStatusText.Text = "Load a renderer before playing the preview.";
            return;
        }

        if (_previewPlaying)
        {
            StopPreviewPlayback();
            TimelineStatusText.Text = "Preview paused";
            return;
        }

        if (ProjectFrameSlider.Value >= ProjectFrameSlider.Maximum)
            SetPreviewPlaybackSliderValue(0);

        _previewPlaybackStartFrame = (int)Math.Round(ProjectFrameSlider.Value);
        _previewPlaybackRequestedFrame = -1;
        _previewPlaybackClock.Restart();
        _previewPlaying = true;
        _previewPlaybackTimer?.Start();
        QueuePreviewPlaybackRender(_previewPlaybackStartFrame);
        RefreshPreviewPlayButton();
        TimelineStatusText.Text = "Preview playing";
    }

    private void PreviewPlaybackTimer_Tick(object? sender, object e)
    {
        var renderer = _legacyRenderer;
        if (!_previewPlaying || renderer is null)
        {
            StopPreviewPlayback();
            return;
        }

        var fps = Math.Max(1, renderer.ReferenceFps);
        var elapsedFrames = (int)Math.Floor(_previewPlaybackClock.Elapsed.TotalSeconds * fps);
        var targetFrame = _previewPlaybackStartFrame + elapsedFrames;
        var lastFrame = (int)Math.Round(ProjectFrameSlider.Maximum);

        if (targetFrame >= lastFrame)
        {
            SetPreviewPlaybackSliderValue(lastFrame);
            StopPreviewPlayback();
            QueuePreviewPlaybackRender(lastFrame);
            TimelineStatusText.Text = "Preview finished";
            return;
        }

        if ((int)Math.Round(ProjectFrameSlider.Value) != targetFrame)
            SetPreviewPlaybackSliderValue(targetFrame);

        QueuePreviewPlaybackRender(targetFrame);
    }

    private void SetPreviewPlaybackSliderValue(int frame)
    {
        _previewPlaybackSliderUpdate = true;
        try
        {
            ProjectFrameSlider.Value = Math.Clamp(frame, 0, (int)Math.Round(ProjectFrameSlider.Maximum));
        }
        finally
        {
            _previewPlaybackSliderUpdate = false;
        }
    }

    private void QueuePreviewPlaybackRender(int frame)
    {
        _previewPlaybackRequestedFrame = frame;

        if (_previewPlaybackRenderLoopRunning)
            return;

        _previewPlaybackRenderLoopRunning = true;
        _ = RunPreviewPlaybackRenderLoopAsync();
    }

    private async Task RunPreviewPlaybackRenderLoopAsync()
    {
        try
        {
            while (_previewPlaybackRequestedFrame >= 0)
            {
                // Consume only the newest requested frame. If rendering takes longer
                // than one source frame, intermediate timer ticks are intentionally
                // skipped instead of piling up Task.Run renderer jobs.
                var frame = _previewPlaybackRequestedFrame;
                _previewPlaybackRequestedFrame = -1;
                await RenderFrameAsync(frame);
            }
        }
        catch (Exception ex)
        {
            App.WriteLog("Preview playback render failed", ex);
            TimelineStatusText.Text = $"Preview error: {ex.Message}";
            StopPreviewPlayback();
        }
        finally
        {
            _previewPlaybackRenderLoopRunning = false;

            // A timer tick can arrive while the final await is resuming. Start one
            // more pass if that happened; still only one render loop may run.
            if (_previewPlaybackRequestedFrame >= 0)
                QueuePreviewPlaybackRender(_previewPlaybackRequestedFrame);
        }
    }

    private void StopPreviewPlayback()
    {
        _previewPlaying = false;
        _previewPlaybackTimer?.Stop();
        _previewPlaybackClock.Stop();
        _previewPlaybackRequestedFrame = -1;
        RefreshPreviewPlayButton();
    }

    private void RefreshPreviewPlayButton()
    {
        if (_previewPlayButton is null)
            return;

        _previewPlayButton.Content = new SymbolIcon(_previewPlaying ? Symbol.Pause : Symbol.Play);
        ToolTipService.SetToolTip(_previewPlayButton, _previewPlaying ? "Pause preview" : "Play preview");
    }
}
