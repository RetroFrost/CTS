using CubicalCompare.Core.Project;
using CubicalCompare.Core.Project.Ai;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Security.Credentials;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const string GeminiCredentialResource = "CubicalCompare.V5.Gemini";
    private readonly List<ProjectArtworkSource> _aiArtworkSources = [];
    private CancellationTokenSource? _aiBuildCancellation;
    private ComparisonProject? _aiUndoProject;
    private string? _sessionGeminiKey;

    private async void AiBuild_Click(object sender, RoutedEventArgs e)
    {
        if (_aiBuildCancellation is not null)
        {
            _aiBuildCancellation.Cancel();
            return;
        }

        var topicBox = new TextBox
        {
            Header = "Comparison topic",
            PlaceholderText = "What if I had a headache every day?",
            AcceptsReturn = true,
            TextWrapping = TextWrapping.Wrap,
            MinHeight = 70,
            MaxLength = 1000,
        };
        var cardCountBox = new NumberBox
        {
            Header = "Number of cards",
            Minimum = 1,
            Maximum = GeminiComparisonService.MaximumCards,
            Value = 50,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline,
        };
        var keyBox = new PasswordBox
        {
            Header = "Google Gemini API key",
            PlaceholderText = "Paste your Google AI Studio key",
            Password = _sessionGeminiKey ?? LoadGeminiKey() ?? "",
        };
        var panel = new StackPanel { Spacing = 12 };
        panel.Children.Add(topicBox);
        panel.Children.Add(cardCountBox);
        panel.Children.Add(keyBox);
        panel.Children.Add(new TextBlock
        {
            Text = "Cubical Compare automatically selects a Google Search-compatible " +
                   "model, writes real project cards, and searches for Flaticon free-icon pages. " +
                   "This replaces the current cards. Save your work first. " +
                   "Gemini searches may use paid API quota.",
            TextWrapping = TextWrapping.Wrap,
            Opacity = 0.72,
        });
        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "AI Project Builder",
            Content = new ScrollViewer { Content = panel, MaxHeight = 480 },
            PrimaryButtonText = "Build comparison",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;

        var topic = topicBox.Text.Trim();
        var key = keyBox.Password.Trim();
        if (topic.Length == 0 || key.Length == 0 ||
            !double.IsFinite(cardCountBox.Value) ||
            cardCountBox.Value != Math.Truncate(cardCountBox.Value) ||
            cardCountBox.Value < 1 ||
            cardCountBox.Value > GeminiComparisonService.MaximumCards)
        {
            await ShowErrorAsync("Missing AI settings",
                "Enter a topic, a Gemini API key, and a whole number of cards from 1 to 100.");
            return;
        }

        _sessionGeminiKey = key;
        SaveGeminiKey(key);
        var cardCount = (int)cardCountBox.Value;
        using var cancellation = new CancellationTokenSource();
        _aiBuildCancellation = cancellation;
        AiBuildButton.Content = "Cancel AI";
        AiBuildRing.Visibility = Visibility.Visible;
        AiBuildRing.IsActive = true;
        var progress = new Progress<string>(message =>
        {
            if (_aiBuildCancellation == cancellation)
                TimelineStatusText.Text = message;
        });

        try
        {
            var provider = new GeminiComparisonService();
            var model = await provider.SelectGroundedModelAsync(key, progress, cancellation.Token);
            var drafts = await provider.GenerateCardsAsync(
                key, model, topic, cardCount, progress, cancellation.Token);
            cancellation.Token.ThrowIfCancellationRequested();

            // Keep a complete in-memory snapshot before replacing the current cards.
            _aiUndoProject = BuildProject();
            _aiUndoProject.SoundtrackPaths = CurrentSoundtrackPaths().ToList();
            _aiUndoProject.SoundtrackPath = _soundtrackPath;
            _aiUndoProject.SoundtrackVolume = _soundtrackVolume;
            _aiUndoProject.SoundtrackLoop = _soundtrackLoop;
            _aiUndoProject.SoundtrackFadeOut = _soundtrackFadeOut;
            AiUndoButton.Visibility = Visibility.Visible;

            ClearProjectCards();
            _aiArtworkSources.Clear();
            var generated = new List<ProjectCardViewModel>(drafts.Count);
            foreach (var draft in drafts)
            {
                var card = new ProjectCardViewModel
                {
                    Title = draft.Title,
                    BadgeValue = draft.BadgeValue,
                    BadgeUnit = draft.BadgeUnit,
                    Description = draft.Description,
                };
                generated.Add(card);
                AddProjectCard(card);
            }

            _projectDisplayName = topic.Length > 200 ? topic[..200] : topic;
            UpdateProjectIdentityUi();
            CardsList.SelectedIndex = 0;
            RefreshTimelineRange();
            ScheduleWorkspaceSave();
            await RenderCurrentFrameAsync();

            var assigned = 0;
            for (var start = 0; start < drafts.Count; start += 5)
            {
                cancellation.Token.ThrowIfCancellationRequested();
                var batch = drafts.Skip(start).Take(5).ToArray();
                var pages = await provider.FindIconPagesAsync(
                    key, model, batch, progress, cancellation.Token);
                foreach (var (index, pageUrl) in pages)
                {
                    cancellation.Token.ThrowIfCancellationRequested();
                    var card = generated[start + index];
                    if (!Cards.Contains(card)) continue;
                    try
                    {
                        // The existing downloader checks public destinations and
                        // caches Flaticon page artwork before it reaches the editor.
                        var local = await WebImageSource.ResolveToLocalFileAsync(
                            pageUrl, cancellation.Token);
                        if (string.IsNullOrWhiteSpace(local) || !File.Exists(local))
                            continue;

                        card.ImagePath = pageUrl;
                        _aiArtworkSources.Add(new ProjectArtworkSource
                        {
                            CardId = card.Id,
                            PageUrl = pageUrl,
                            LicenseStatus = "NeedsReview",
                        });
                        assigned++;
                    }
                    catch (OperationCanceledException) { throw; }
                    catch (Exception ex)
                    {
                        App.WriteLog($"AI artwork resolution failed for card {start + index + 1}", ex);
                    }
                }

                TimelineStatusText.Text =
                    $"AI artwork: {Math.Min(start + batch.Length, drafts.Count)}/{drafts.Count} checked · {assigned} assigned";
                ScheduleWorkspaceSave();
            }

            RefreshTimelineRange();
            await RenderCurrentFrameAsync();
            ScheduleWorkspaceSave();
            if (!string.IsNullOrWhiteSpace(_activeProjectPath))
                await SaveProjectToPathAsync(_activeProjectPath);

            TimelineStatusText.Text =
                $"AI created {drafts.Count} cards and assigned {assigned} icons. Review Flaticon licenses and credits before publishing.";
        }
        catch (OperationCanceledException)
        {
            ScheduleWorkspaceSave();
            TimelineStatusText.Text = "AI cancelled. Any cards already created remain editable; Undo AI restores the previous project.";
        }
        catch (Exception ex)
        {
            App.WriteLog("AI Project Builder failed", ex);
            TimelineStatusText.Text = "AI build failed.";
            await ShowErrorAsync("AI Project Builder", ex.Message);
        }
        finally
        {
            _aiBuildCancellation = null;
            AiBuildButton.Content = "AI Build";
            AiBuildRing.IsActive = false;
            AiBuildRing.Visibility = Visibility.Collapsed;
        }
    }

    private async void UndoAiBuild_Click(object sender, RoutedEventArgs e)
    {
        if (_aiBuildCancellation is not null || _aiUndoProject is null) return;
        var previous = _aiUndoProject;
        _aiUndoProject = null;
        AiUndoButton.Visibility = Visibility.Collapsed;
        ApplyProjectFile(previous);
        ScheduleWorkspaceSave();
        if (!string.IsNullOrWhiteSpace(_activeProjectPath))
            await SaveProjectToPathAsync(_activeProjectPath);
        TimelineStatusText.Text = "Restored the project from before AI Build.";
    }

    private static string? LoadGeminiKey()
    {
        try
        {
            var credential = new PasswordVault().Retrieve(GeminiCredentialResource, "Gemini");
            credential.RetrievePassword();
            return credential.Password;
        }
        catch
        {
            return null; // Credential Locker may be unavailable on some PCs.
        }
    }

    private static void SaveGeminiKey(string key)
    {
        try
        {
            var vault = new PasswordVault();
            try { vault.Remove(vault.Retrieve(GeminiCredentialResource, "Gemini")); }
            catch { /* First use: no previous key. */ }
            vault.Add(new PasswordCredential(GeminiCredentialResource, "Gemini", key));
        }
        catch
        {
            // Never fall back to plaintext configuration or project storage.
        }
    }
}
