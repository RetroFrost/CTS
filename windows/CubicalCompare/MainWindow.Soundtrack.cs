using CubicalCompare.Core.MegaPack;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private readonly List<string> _soundtrackPaths = [];
    private StackPanel? _soundtrackListPanel;
    private TextBlock? _audioImportStatus;
    private global::Windows.Media.Playback.MediaPlayer? _audioAuditionPlayer;
    private bool _audioImportBusy;
    private readonly Dictionary<string, TimeSpan> _soundtrackDurations = new(StringComparer.OrdinalIgnoreCase);
    private string _soundtrackPath = string.Empty;
    private double _soundtrackVolume = 1.0;
    private bool _soundtrackLoop = true;

    private TextBlock? _soundtrackPathText;
    private Slider? _soundtrackVolumeSlider;
    private CheckBox? _soundtrackLoopCheckBox;
    private bool _soundtrackUiUpdating;
    private Zipack2ImportResult? _appliedMegaPackSoundtrack;
    private Grid? _audioPage;
    private TextBlock? _audioPagePathText;
    private TextBlock? _audioRendererAudioText;
    private Slider? _audioPageVolumeSlider;
    private CheckBox? _audioPageLoopCheckBox;

    internal void InitializeSoundtrackEditor()
    {
        if (_soundtrackPathText is not null)
            return;

        var transformExpander = FindAncestor<Expander>(ImageScaleBox);
        if (transformExpander?.Parent is not StackPanel inspectorStack)
            return;

        _soundtrackPathText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            MaxHeight = 42,
            Opacity = 0.6,
        };

        var chooseButton = new Button
        {
            Content = "Add soundtracks…",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };
        chooseButton.Click += ChooseSoundtrack_Click;

        var clearButton = new Button { Content = "Clear" };
        clearButton.Click += ClearSoundtrack_Click;

        var buttonGrid = new Grid { ColumnSpacing = 8 };
        buttonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        buttonGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        buttonGrid.Children.Add(chooseButton);
        Grid.SetColumn(clearButton, 1);
        buttonGrid.Children.Add(clearButton);

        _soundtrackVolumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            StepFrequency = 1,
            Value = 100,
        };
        _soundtrackVolumeSlider.ValueChanged += (_, args) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackVolume = Math.Clamp(args.NewValue / 100.0, 0, 1);
            UpdateAudioAuditionVolume();
            ScheduleWorkspaceSave();
        };

        _soundtrackLoopCheckBox = new CheckBox
        {
            Content = "Repeat playlist to video length",
            IsChecked = true,
        };
        _soundtrackLoopCheckBox.Checked += (_, _) => SetSoundtrackLoopFromUi(true);
        _soundtrackLoopCheckBox.Unchecked += (_, _) => SetSoundtrackLoopFromUi(false);

        var panel = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        panel.Children.Add(_soundtrackPathText);
        panel.Children.Add(buttonGrid);
        panel.Children.Add(new TextBlock { Text = "Volume", FontSize = 11, Opacity = 0.65 });
        panel.Children.Add(_soundtrackVolumeSlider);
        panel.Children.Add(_soundtrackLoopCheckBox);

        var expander = new Expander
        {
            Header = "Soundtrack",
            IsExpanded = false,
            Content = panel,
            Margin = new Thickness(0, 4, 0, 0),
        };

        inspectorStack.Children.Add(expander);
        Cards.CollectionChanged += (_, _) => ApplyPendingMegaPackSoundtrackIfProjectMatches();
        ApplyPendingMegaPackSoundtrackIfProjectMatches();
        RefreshSoundtrackUi();
    }

    internal Grid BuildAudioPage()
    {
        if (_audioPage is not null)
            return _audioPage;

        _audioPagePathText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };
        _audioRendererAudioText = new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        };

        var chooseButton = new Button
        {
            Content = "Add soundtracks…",
            Padding = new Thickness(16, 7, 16, 7),
        };
        chooseButton.Click += ChooseSoundtrack_Click;

        var clearButton = new Button
        {
            Content = "Clear soundtrack",
            Padding = new Thickness(16, 7, 16, 7),
        };
        clearButton.Click += ClearSoundtrack_Click;

        var buttons = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            Spacing = 8,
        };
        buttons.Children.Add(chooseButton);
        buttons.Children.Add(clearButton);

        _audioPageVolumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            StepFrequency = 1,
            Width = 420,
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        _audioPageVolumeSlider.ValueChanged += (_, args) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackVolume = Math.Clamp(args.NewValue / 100.0, 0, 1);
            UpdateAudioAuditionVolume();
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };

        _audioPageLoopCheckBox = new CheckBox
        {
            Content = "Repeat playlist to video length",
        };
        _audioPageLoopCheckBox.Checked += (_, _) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackLoop = true;
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };
        _audioPageLoopCheckBox.Unchecked += (_, _) =>
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackLoop = false;
            RefreshSoundtrackUi();
            ScheduleWorkspaceSave();
        };

        var soundtrackPanel = new StackPanel { Spacing = 10 };
        soundtrackPanel.Children.Add(new TextBlock
        {
            Text = "Soundtrack",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        soundtrackPanel.Children.Add(_audioPagePathText);
        soundtrackPanel.Children.Add(buttons);
        soundtrackPanel.Children.Add(new TextBlock { Text = "Tracks play consecutively in this order. Preview listens to one track; export uses the whole list.", TextWrapping = TextWrapping.Wrap });
        _audioImportStatus = new TextBlock { TextWrapping = TextWrapping.Wrap };
        _soundtrackListPanel = new StackPanel { Spacing = 8 };
        soundtrackPanel.Children.Add(_audioImportStatus);
        soundtrackPanel.Children.Add(_soundtrackListPanel);
        var stop = new Button { Content = "Stop audio preview" };
        stop.Click += (_, _) => StopAudioAudition();
        soundtrackPanel.Children.Add(stop);
        soundtrackPanel.Children.Add(new TextBlock
        {
            Text = "Volume",
            FontSize = 12,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        soundtrackPanel.Children.Add(_audioPageVolumeSlider);
        soundtrackPanel.Children.Add(_audioPageLoopCheckBox);
        _audioFadeOutCheckBox = new CheckBox { Content = "Fade out audio at its ending (2 seconds)", IsChecked = _soundtrackFadeOut };
        void SetFadeOut(bool enabled)
        {
            if (_soundtrackUiUpdating) return;
            _soundtrackFadeOut = enabled;
            UpdateAudioAuditionVolume();
            ScheduleWorkspaceSave();
        }
        _audioFadeOutCheckBox.Checked += (_, _) => SetFadeOut(true);
        _audioFadeOutCheckBox.Unchecked += (_, _) => SetFadeOut(false);
        soundtrackPanel.Children.Add(_audioFadeOutCheckBox);
        soundtrackPanel.Children.Add(new TextBlock { Text = "Applies to playlists and renderer audio, including repeat, video cutoffs and audio that ends early. Short audio uses a shorter fade.", TextWrapping = TextWrapping.Wrap });

        var rendererAudioPanel = new StackPanel { Spacing = 8 };
        rendererAudioPanel.Children.Add(new TextBlock
        {
            Text = "Renderer audio",
            FontSize = 18,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        rendererAudioPanel.Children.Add(_audioRendererAudioText);

        var stack = new StackPanel
        {
            Spacing = 14,
            MaxWidth = 900,
            HorizontalAlignment = HorizontalAlignment.Left,
        };
        stack.Children.Add(new TextBlock
        {
            Text = "Audio",
            FontSize = 30,
            FontWeight = global::Windows.UI.Text.FontWeights.SemiBold,
        });
        stack.Children.Add(new TextBlock
        {
            Text = "Choose multiple soundtracks for export and listen to them here. Renderer-embedded audio is shown separately so it is never hidden inside the inspector.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorTextSecondaryBrush"],
        });
        stack.Children.Add(CreateAudioCard(soundtrackPanel));
        stack.Children.Add(CreateAudioCard(rendererAudioPanel));

        Closed += (_, _) => StopAudioAudition();
        _audioPage = new Grid { Visibility = Visibility.Collapsed };
        _audioPage.Children.Add(new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = stack,
        });

        RefreshSoundtrackUi();
        return _audioPage;
    }

    private static Border CreateAudioCard(UIElement content) => new()
    {
        Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorSurfaceRaisedBrush"],
        BorderBrush = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorBorderBrush"],
        BorderThickness = new Thickness(1),
        CornerRadius = new CornerRadius(14),
        Padding = new Thickness(18),
        Child = content,
    };

    private async void ChooseSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        if (_audioImportBusy) return;
        _audioImportBusy = true;
        try
        {
            var files = await PickFilesAsync(["*"]);
            if (files.Count == 0) { TimelineStatusText.Text = "Audio selection cancelled"; return; }
            TimelineStatusText.Text = "Checking selected audio…";
            var failures = await ImportSoundtracksAsync(files);
            if (failures.Count > 0) await ShowErrorAsync("Some audio files could not be imported", string.Join("\n\n", failures));
        }
        catch (Exception ex)
        {
            TimelineStatusText.Text = "Audio import failed";
            if (_audioImportStatus is not null) _audioImportStatus.Text = ex.Message;
            await ShowErrorAsync("Could not import audio", ex.Message);
        }
        finally { _audioImportBusy = false; }
    }

    internal async Task<List<string>> ImportSoundtracksAsync(IReadOnlyList<global::Windows.Storage.StorageFile> files)
    {
        var failures = new List<string>();
        var added = 0;
        foreach (var file in files)
        {
            try
            {
                if (CurrentSoundtrackPaths().Contains(file.Path, StringComparer.OrdinalIgnoreCase)) continue;
                if (_soundtrackPaths.Count >= 256) throw new InvalidDataException("The playlist is limited to 256 tracks.");
                TimelineStatusText.Text = $"Checking audio · {file.Name}";
                if (_audioImportStatus is not null) _audioImportStatus.Text = TimelineStatusText.Text;
                var track = await ReadSoundtrackAsync(file);
                if (track.OriginalDuration <= TimeSpan.Zero) throw new InvalidDataException("No playable audio was found.");
                if (string.IsNullOrWhiteSpace(file.Path)) throw new InvalidDataException("The selected file has no accessible local path.");
                if (_soundtrackPaths.Count == 0 && !string.IsNullOrWhiteSpace(_soundtrackPath)) _soundtrackPaths.Add(_soundtrackPath);
                _soundtrackDurations[file.Path] = track.OriginalDuration;
                _soundtrackPaths.Add(file.Path);
                added++;
            }
            catch (Exception ex) { failures.Add($"{file.Name}: {ex.Message} (0x{ex.HResult:X8}). Try a locally downloaded MP3, WAV or M4A file supported by Windows."); }
        }
        _soundtrackPath = _soundtrackPaths.FirstOrDefault() ?? _soundtrackPath;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();
        var status = $"Added {added} soundtrack(s) · {failures.Count} failed · {CurrentSoundtrackPaths().Count} in playlist";
        TimelineStatusText.Text = status;
        if (_audioImportStatus is not null) _audioImportStatus.Text = status + "\n" + AudioDurationStatus();
        return failures;
    }

    internal static async Task<global::Windows.Media.Editing.BackgroundAudioTrack> ReadSoundtrackAsync(
        global::Windows.Storage.StorageFile file, CancellationToken cancellationToken = default)
    {
        var operation = global::Windows.Media.Editing.BackgroundAudioTrack.CreateFromFileAsync(file);
        async Task<global::Windows.Media.Editing.BackgroundAudioTrack> DecodeAsync() => await operation;
        using var registration = cancellationToken.Register(() => operation.Cancel());
        try { return await DecodeAsync().WaitAsync(TimeSpan.FromSeconds(30), cancellationToken); }
        catch { operation.Cancel(); throw; }
    }

    private IReadOnlyList<string> CurrentSoundtrackPaths() => _soundtrackPaths.Count > 0
        ? _soundtrackPaths.ToArray() : string.IsNullOrWhiteSpace(_soundtrackPath) ? [] : [_soundtrackPath];

    private void RefreshSoundtrackList()
    {
        if (_soundtrackListPanel is null) return;
        _soundtrackListPanel.Children.Clear();
        var paths = CurrentSoundtrackPaths();
        for (var index = 0; index < paths.Count; index++)
        {
            var rowIndex = index;
            var path = paths[index];
            var row = new StackPanel { Spacing = 6 };
            row.Children.Add(new TextBlock { Text = $"{index + 1}. {Path.GetFileName(path)}{(File.Exists(path) ? "" : " · MISSING")}", TextWrapping = TextWrapping.Wrap });
            var controls = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 6 };
            void Action(string label, Action action, bool enabled = true)
            {
                var button = new Button { Content = label, IsEnabled = enabled };
                button.Click += (_, _) => action();
                controls.Children.Add(button);
            }
            Action("Preview", async () =>
            {
                try
                {
                    var file = await global::Windows.Storage.StorageFile.GetFileFromPathAsync(path);
                    StopAudioAudition();
                    _audioAuditionPlayer = new global::Windows.Media.Playback.MediaPlayer { Volume = _soundtrackVolume };
                    _audioAuditionPlayer.MediaFailed += (_, args) => DispatcherQueue.TryEnqueue(() =>
                    {
                        if (_audioImportStatus is not null) _audioImportStatus.Text = $"Audio preview failed: {args.ErrorMessage}";
                    });
                    _audioAuditionPlayer.PlaybackSession.PlaybackStateChanged += (_, _) => DispatcherQueue.TryEnqueue(() =>
                    {
                        if (_audioAuditionPlayer?.PlaybackSession.PlaybackState == global::Windows.Media.Playback.MediaPlaybackState.Playing) StartAudioAuditionFade();
                        else _audioAuditionFadeTimer?.Stop();
                    });
                    _audioAuditionPlayer.Source = global::Windows.Media.Core.MediaSource.CreateFromStorageFile(file);
                    _audioAuditionPlayer.Play();
                    StartAudioAuditionFade();
                }
                catch (Exception ex) { await ShowErrorAsync("Could not preview audio", ex.Message); }
            });
            Action("Up", () => MoveSoundtrack(rowIndex, -1), index > 0);
            Action("Down", () => MoveSoundtrack(rowIndex, 1), index + 1 < paths.Count);
            Action("Remove", () =>
            {
                var copy = CurrentSoundtrackPaths().ToList(); copy.RemoveAt(rowIndex);
                SetSoundtrackPaths(copy);
            });
            row.Children.Add(controls);
            _soundtrackListPanel.Children.Add(row);
        }
    }

    private void MoveSoundtrack(int index, int delta)
    {
        var paths = CurrentSoundtrackPaths().ToList();
        (paths[index], paths[index + delta]) = (paths[index + delta], paths[index]);
        SetSoundtrackPaths(paths);
    }

    private void SetSoundtrackPaths(IEnumerable<string> paths)
    {
        StopAudioAudition();
        _soundtrackPaths.Clear(); _soundtrackPaths.AddRange(paths);
        _soundtrackPath = _soundtrackPaths.FirstOrDefault() ?? string.Empty;
        RefreshSoundtrackUi(); ScheduleWorkspaceSave();
    }

    private string AudioDurationStatus()
    {
        var paths = CurrentSoundtrackPaths();
        if (paths.Count == 0) return "No project soundtrack. Renderer audio is used when available.";
        if (paths.Any(p => !File.Exists(p))) return "Warning: a soundtrack is missing; export will stop until it is replaced or removed.";
        if (paths.Any(p => !_soundtrackDurations.ContainsKey(p))) return "Audio durations will be checked before export.";
        var total = TimeSpan.FromTicks(paths.Sum(p => _soundtrackDurations[p].Ticks));
        var video = TimeSpan.FromSeconds((_legacyRenderer?.FrameCount(BuildProject()) ?? 1) / (double)Math.Max(1, SelectedExportFps));
        return DescribeAudioCoverage(total, video, _soundtrackLoop);
    }

    internal static string DescribeAudioCoverage(TimeSpan audio, TimeSpan video, bool repeat)
    {
        if (audio > video) return $"Warning: playlist is {audio.TotalSeconds:0.##}s; video is {video.TotalSeconds:0.##}s. Audio is cut at the video end ({(audio-video).TotalSeconds:0.##}s unused).";
        if (audio < video) return repeat
            ? $"Playlist is {audio.TotalSeconds:0.##}s; it repeats to cover the {video.TotalSeconds:0.##}s video and stops at the video end."
            : $"Warning: audio ends {(video-audio).TotalSeconds:0.##}s before the video. The remaining video will be silent.";
        return "Audio and video durations match. Audio stops at the video end.";
    }

    private void RefreshAudioDurationStatus()
    {
        if (_audioImportStatus is not null && !_audioImportBusy) _audioImportStatus.Text = AudioDurationStatus();
    }

    private async Task CheckAudioCoverageBeforeExportAsync(IReadOnlyList<string> paths, TimeSpan video, bool repeat)
    {
        long ticks = 0;
        foreach (var path in paths)
        {
            var file = await global::Windows.Storage.StorageFile.GetFileFromPathAsync(path);
            var audio = await ReadSoundtrackAsync(file);
            if (audio.OriginalDuration <= TimeSpan.Zero) throw new InvalidDataException($"No playable audio: {Path.GetFileName(path)}");
            _soundtrackDurations[path] = audio.OriginalDuration;
            ticks = checked(ticks + audio.OriginalDuration.Ticks);
        }
        var total = TimeSpan.FromTicks(ticks);
        if (_audioImportStatus is not null) _audioImportStatus.Text = DescribeAudioCoverage(total, video, repeat);
        if (total > video || (total < video && !repeat))
        {
            var dialog = new ContentDialog { XamlRoot = RootNavigation.XamlRoot, Title = "Soundtrack duration", Content = DescribeAudioCoverage(total, video, repeat), PrimaryButtonText = "Export anyway", CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close };
            if (await dialog.ShowAsync() != ContentDialogResult.Primary) throw new OperationCanceledException();
        }
    }

    private void ClearSoundtrack_Click(object sender, RoutedEventArgs e)
    {
        _soundtrackPaths.Clear();
        StopAudioAudition();
        _soundtrackPath = string.Empty;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();
        TimelineStatusText.Text = "Soundtrack cleared";
    }

    private void SetSoundtrackLoopFromUi(bool value)
    {
        if (_soundtrackUiUpdating) return;
        _soundtrackLoop = value;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();
    }

    private void ApplyPendingMegaPackSoundtrackIfProjectMatches()
    {
        var pack = _pendingZipack2;
        if (pack is null || ReferenceEquals(pack, _appliedMegaPackSoundtrack) || Cards.Count == 0)
            return;

        if (!Cards.Any(card => IsInsideDirectory(card.ImagePath, pack.ExtractionDirectory)))
            return;

        _appliedMegaPackSoundtrack = pack;
        _soundtrackPaths.Clear();
        if (!string.IsNullOrWhiteSpace(pack.SoundtrackPath)) _soundtrackPaths.Add(pack.SoundtrackPath);
        _soundtrackPath = pack.SoundtrackPath;
        _soundtrackLoop = pack.SoundtrackLoop;
        _soundtrackVolume = double.IsFinite(pack.SoundtrackVolume) ? Math.Clamp(pack.SoundtrackVolume, 0, 1) : 1.0;
        RefreshSoundtrackUi();
        ScheduleWorkspaceSave();

        if (!string.IsNullOrWhiteSpace(_soundtrackPath))
            TimelineStatusText.Text = $"MegaPack soundtrack loaded · {Path.GetFileName(_soundtrackPath)}";
    }

    private static bool IsInsideDirectory(string filePath, string directoryPath)
    {
        if (string.IsNullOrWhiteSpace(filePath) || string.IsNullOrWhiteSpace(directoryPath)) return false;
        try
        {
            var directory = Path.GetFullPath(directoryPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar)
                + Path.DirectorySeparatorChar;
            var file = Path.GetFullPath(filePath);
            return file.StartsWith(directory, StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private void RefreshSoundtrackUi()
    {
        RefreshSoundtrackList();
        RefreshAudioDurationStatus();
        _soundtrackUiUpdating = true;
        try
        {
            if (_soundtrackPathText is not null) _soundtrackPathText.Text = string.IsNullOrWhiteSpace(_soundtrackPath)
                ? "No soundtrack selected. Export will contain video only."
                : File.Exists(_soundtrackPath)
                    ? _soundtrackPath
                    : $"Missing audio file: {_soundtrackPath}";

            if (_soundtrackVolumeSlider is not null)
                _soundtrackVolumeSlider.Value = Math.Clamp(_soundtrackVolume * 100.0, 0, 100);
            if (_soundtrackLoopCheckBox is not null)
                _soundtrackLoopCheckBox.IsChecked = _soundtrackLoop;

            if (_audioPagePathText is not null)
                _audioPagePathText.Text = string.IsNullOrWhiteSpace(_soundtrackPath)
                    ? "No soundtrack selected. Export will contain renderer audio only when the loaded renderer provides it."
                    : File.Exists(_soundtrackPath)
                        ? _soundtrackPath
                        : $"Missing audio file: {_soundtrackPath}";
            if (_audioPageVolumeSlider is not null)
                _audioPageVolumeSlider.Value = Math.Clamp(_soundtrackVolume * 100.0, 0, 100);
            if (_audioFadeOutCheckBox is not null) _audioFadeOutCheckBox.IsChecked = _soundtrackFadeOut;
            if (_audioPageLoopCheckBox is not null)
                _audioPageLoopCheckBox.IsChecked = _soundtrackLoop;

            if (_audioRendererAudioText is not null)
            {
                var embedded = _legacyRenderer?.EmbeddedAudio;
                _audioRendererAudioText.Text = embedded is null
                    ? "The loaded renderer does not provide embedded audio."
                    : $"Embedded renderer audio: {embedded.FileName} · volume {embedded.Volume:P0} · {(embedded.Loop ? "looping" : "one-shot")}. A selected project soundtrack remains independently controllable here.";
            }
        }
        finally
        {
            _soundtrackUiUpdating = false;
        }
    }
}
