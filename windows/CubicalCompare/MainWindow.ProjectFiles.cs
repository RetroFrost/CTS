using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private string? _activeProjectPath;
    private string _projectDisplayName = "Untitled comparison";

    private void NewProjectWithFileState_Click(object sender, RoutedEventArgs e)
    {
        _activeProjectPath = null;
        _projectDisplayName = "Untitled comparison";
        UpdateProjectIdentityUi();
        NewProject_Click(sender, e);
        TimelineStatusText.Text = "New project";
    }

    private async void OpenProject_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".ccproject"]);
        if (file is null) return;

        try
        {
            TimelineStatusText.Text = $"Opening {file.Name}…";
            var project = await ProjectFileService.LoadAsync(file.Path);
            ApplyProjectFile(project);
            _activeProjectPath = file.Path;
            _projectDisplayName = project.Name;
            UpdateProjectIdentityUi();
            TimelineStatusText.Text = $"Opened {file.Name} · {Cards.Count} card{(Cards.Count == 1 ? "" : "s")}";
            ScheduleThumbnailRefresh();
            ScheduleWorkspaceSave();
            await RenderCurrentFrameAsync();
        }
        catch (Exception ex)
        {
            App.WriteLog($"Project open failed: {file.Path}", ex);
            TimelineStatusText.Text = "Could not open project.";
            await ShowErrorAsync("Could not open project", ex.Message);
        }
    }

    private async void SaveProject_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_activeProjectPath))
        {
            await SaveProjectAsAsync();
            return;
        }

        await SaveProjectToPathAsync(_activeProjectPath);
    }

    private async void SaveProjectAs_Click(object sender, RoutedEventArgs e) => await SaveProjectAsAsync();

    private async Task SaveProjectAsAsync()
    {
        var file = await PickSaveFileAsync(
            "Cubical Compare project",
            ".ccproject",
            SafeFileStem(_projectDisplayName));
        if (file is null) return;

        var proposedName = string.Equals(_projectDisplayName, "Untitled comparison", StringComparison.Ordinal)
            ? Path.GetFileNameWithoutExtension(file.Name)
            : _projectDisplayName;

        if (!await SaveProjectToPathAsync(file.Path, proposedName))
            return;

        _activeProjectPath = file.Path;
        _projectDisplayName = proposedName;
        UpdateProjectIdentityUi();
    }

    private async Task<bool> SaveProjectToPathAsync(string path, string? projectName = null)
    {
        try
        {
            TimelineStatusText.Text = $"Saving {Path.GetFileName(path)}…";
            var project = BuildProject();
            project.Name = string.IsNullOrWhiteSpace(projectName) ? _projectDisplayName : projectName;
            project.SoundtrackPath = _soundtrackPath;
            project.SoundtrackVolume = _soundtrackVolume;
            project.SoundtrackLoop = _soundtrackLoop;
            await ProjectFileService.SaveAsync(project, path);
            TimelineStatusText.Text = $"Saved {Path.GetFileName(path)}";
            return true;
        }
        catch (Exception ex)
        {
            App.WriteLog($"Project save failed: {path}", ex);
            TimelineStatusText.Text = "Could not save project.";
            await ShowErrorAsync("Could not save project", ex.Message);
            return false;
        }
    }

    private void ApplyProjectFile(ComparisonProject project)
    {
        ProjectFileService.ValidateAndNormalize(project);

        _restoringWorkspace = true;
        try
        {
            ClearProjectCards();
            _pendingZipack2 = null;
            _projectShowBadges = project.ShowBadges;
            _projectCreditsEnabled = project.CreditsEnabled;
            _projectDurationSeconds = project.AutoLength ? 0 : Math.Max(0, project.CustomLengthSeconds);
            _soundtrackPath = project.SoundtrackPath;
            _soundtrackVolume = project.SoundtrackVolume;
            _soundtrackLoop = project.SoundtrackLoop;
            RenderFontSelection.ApplyProjectFont(project.RenderFontFamily, project.RenderFontFile);

            foreach (var card in project.Cards)
            {
                AddProjectCard(new ProjectCardViewModel
                {
                    Id = card.Id,
                    Title = card.Title,
                    Value = card.Value,
                    BadgeHeader = card.BadgeHeader,
                    Description = card.Description,
                    ImagePath = card.ImagePath,
                    ImageX = card.ImageX,
                    ImageY = card.ImageY,
                    ImageScale = card.ImageScale,
                    ImageRotation = card.ImageRotation,
                    ImageCropLeft = card.ImageCropLeft,
                    ImageCropTop = card.ImageCropTop,
                    ImageCropRight = card.ImageCropRight,
                    ImageCropBottom = card.ImageCropBottom,
                    ImageLayer = card.ImageLayer,
                });
            }

            CardsList.SelectedIndex = 0;
            RootNavigation.SelectedItem = RootNavigation.MenuItems[0];
            RefreshTimelineRange();
            RefreshSoundtrackUi();
            RefreshRenderFontUi();
        }
        finally
        {
            _restoringWorkspace = false;
        }
    }

    private void UpdateProjectIdentityUi()
    {
        ProjectNameText.Text = _projectDisplayName;
        Title = $"{_projectDisplayName} — Cubical Compare";
    }
}
