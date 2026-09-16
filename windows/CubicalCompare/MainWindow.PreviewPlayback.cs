using System.Diagnostics;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private DispatcherTimer? _previewPlaybackTimer;
    private Button? _previewPlayButton;
    private bool _previewPlaying;
    private int _previewPlaybackStartFrame;
    private readonly Stopwatch _previewPlaybackClock = new();

    internal void InitializePreviewPlayback()
    {
        if (_previewPlayButton is not null)
            return;

        if (ProjectFrameSlider.Parent is not Grid timelineGrid)
        {
            RootNavigation.Loaded += PreviewPlayback_RootNavigationLoaded;
            return;
        }

        RootNavigation.Loaded -= PreviewPlayback_RootNavigationLoaded;

        _previewPlayButton = new Button
        {
            Width = 34,
            Height = 34,
            Padding = new Thickness(0),
            Margin = new Thickness(0, 0, 8, 0),
            HorizontalAlignment = HorizontalAlignment.Left,
            VerticalAlignment = VerticalAlignment.Center,
            Content = new SymbolIcon(Symbol.Play),
        };
        ToolTipService.SetToolTip(_previewPlayButton, "Play preview");
        _previewPlayButton.Click += PreviewPlayButton_Click;

        timelineGrid.Children.Add(_previewPlayButton);
        Grid.SetColumn(_previewPlayButton, 0);
        Canvas.SetZIndex(_previewPlayButton, 20);
        FrameCounterText.Margin = new Thickness(44, 0, 0, 0);

        _previewPlaybackTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(16),
        };
        _previewPlaybackTimer.Tick += PreviewPlaybackTimer_Tick;

        Closed += (_, _) => StopPreviewPlayback();
    }

    private void PreviewPlayback_RootNavigationLoaded(object sender, RoutedEventArgs e)
    {
        RootNavigation.Loaded -= PreviewPlayback_RootNavigationLoaded;
        InitializePreviewPlayback();
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
            return;
        }

        if (ProjectFrameSlider.Value >= ProjectFrameSlider.Maximum)
            ProjectFrameSlider.Value = 0;

        _previewPlaybackStartFrame = (int)Math.Round(ProjectFrameSlider.Value);
        _previewPlaybackClock.Restart();
        _previewPlaying = true;
        _previewPlaybackTimer?.Start();
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
            ProjectFrameSlider.Value = lastFrame;
            StopPreviewPlayback();
            TimelineStatusText.Text = "Preview finished";
            return;
        }

        if ((int)Math.Round(ProjectFrameSlider.Value) != targetFrame)
            ProjectFrameSlider.Value = targetFrame;
    }

    private void StopPreviewPlayback()
    {
        if (!_previewPlaying && _previewPlaybackTimer?.IsEnabled != true)
            return;

        _previewPlaying = false;
        _previewPlaybackTimer?.Stop();
        _previewPlaybackClock.Stop();
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
