using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Windows.System;
using Windows.UI.Core;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private bool _editorWorkspaceUxInitialized;
    private bool _previewFocusMode;

    internal void InitializeEditorWorkspaceUx()
    {
        if (_editorWorkspaceUxInitialized) return;
        _editorWorkspaceUxInitialized = true;
        // Built-in Mica follows activation, theme and Windows transparency policy.
        if (Microsoft.UI.Composition.SystemBackdrops.MicaController.IsSupported())
        {
            SystemBackdrop = new Microsoft.UI.Xaml.Media.MicaBackdrop
            {
                Kind = Microsoft.UI.Composition.SystemBackdrops.MicaKind.BaseAlt
            };
            EditorRootGrid.Background = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.Transparent);
        }
        else
        {
            SystemBackdrop = null;
            EditorRootGrid.Background = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources["EditorBackgroundBrush"];
        }
        Cards.CollectionChanged += (_, _) => RefreshEditorCardActions();
        CardsList.SelectionChanged += (_, _) => RefreshEditorCardActions();
        ProjectFrameSlider.ValueChanged += (_, _) => RefreshEditorTimecode();
        var statusToken = TimelineStatusText.RegisterPropertyChangedCallback(TextBlock.TextProperty,
            (_, _) => ToolTipService.SetToolTip(TimelineStatusText, TimelineStatusText.Text));
        var frameToken = FrameCounterText.RegisterPropertyChangedCallback(TextBlock.TextProperty,
            (_, _) => RefreshEditorTimecode());
        Closed += (_, _) =>
        {
            TimelineStatusText.UnregisterPropertyChangedCallback(TextBlock.TextProperty, statusToken);
            FrameCounterText.UnregisterPropertyChangedCallback(TextBlock.TextProperty, frameToken);
        };
        ToolTipService.SetToolTip(PreviewPlayButton, "Play / pause · Space");
        ToolTipService.SetToolTip(ProjectFrameSlider, "Scrub frames · Left / Right · Shift for 10 frames");
        RefreshEditorCardActions();
        RefreshEditorTimecode();
    }

    internal void RunEditorWorkspaceSmoke()
    {
        var source = new ProjectCardViewModel { Title = "UX smoke", BadgeValue = "42", BadgeUnit = "km", ImageX = 123, ImageScale = 1.25 };
        var copy = CloneEditorCard(source);
        if (copy.Id == source.Id || copy.Title != source.Title || copy.BadgeValue != "42" || copy.BadgeUnit != "km" || copy.ImageX != 123 || copy.ImageScale != 1.25)
            throw new InvalidOperationException("Card duplication did not preserve editable fields and independent identity.");
        PreviewFocusButton.IsChecked = true;
        EditorFocus_Click(this, new RoutedEventArgs());
        if (CardInspectorPanel.Visibility != Visibility.Collapsed || CardRailPanel.Visibility != Visibility.Collapsed || InspectorGapColumn.Width.Value != 0)
            throw new InvalidOperationException("Preview focus did not hide the inspector and card rail.");
        PreviewFocusButton.IsChecked = false;
        EditorFocus_Click(this, new RoutedEventArgs());
        if (CardInspectorPanel.Visibility != Visibility.Visible || CardRailPanel.Visibility != Visibility.Visible || InspectorGapColumn.Width.Value != 12)
            throw new InvalidOperationException("Preview focus did not restore the editing workspace.");
        if (((CornerRadius)Application.Current.Resources["OverlayCornerRadius"]).TopLeft != 12)
            throw new InvalidOperationException("Rounded dropdown resources were not loaded.");
        var fixture = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "CubicalCompare-CSV-" + Guid.NewGuid().ToString("N"));
        try
        {
            foreach (var extension in new[] { ".txt", ".data", "" })
            {
                var path = fixture + extension;
                System.IO.File.WriteAllText(path, "Number,Title,Badge Header,Badge Value,Badge Unit,Image,Description\n1,\"Quoted, title\",DISTANCE,42,km,,Description\n");
                var imported = CubicalCompare.Core.Project.CsvImportService.ImportAsync(path).GetAwaiter().GetResult();
                if (imported.Cards.Count != 1 || imported.Cards[0].Title != "Quoted, title" || imported.Cards[0].BadgeValue != "42" || imported.Cards[0].BadgeUnit != "km")
                    throw new InvalidOperationException("CSV content import depends on the file extension or loses quoted fields.");
                System.IO.File.Delete(path);
            }
            System.IO.File.WriteAllText(fixture, "This is plain text without CSV structure.");
            try
            {
                CubicalCompare.Core.Project.CsvImportService.ImportAsync(fixture).GetAwaiter().GetResult();
                throw new InvalidOperationException("Plain text without CSV structure was accepted.");
            }
            catch (System.IO.InvalidDataException) { }
        }
        finally
        {
            foreach (var extension in new[] { ".txt", ".data", "" }) System.IO.File.Delete(fixture + extension);
        }
        var developerItems = RootNavigation.MenuItems.OfType<NavigationViewItem>().Where(item => Equals(item.Tag, "developer")).ToArray();
        if (developerItems.Length != 1) throw new InvalidOperationException("Developer navigation item is missing or duplicated.");
        ShowPage("developer");
        if (DeveloperPage.Visibility != Visibility.Visible || RendererPage.Visibility != Visibility.Collapsed || ProjectPage.Visibility != Visibility.Collapsed)
            throw new InvalidOperationException("Developer tools are not isolated on their own page.");
        // Collapsed ScrollViewer content may not have a materialized visual parent yet.
        // Check the actual content/children ownership rather than template realization timing.
        if (!DeveloperToolsPanel.Children.Contains(DeveloperCodeOverridePanel) ||
            !ReferenceEquals(DeveloperScrollViewer.Content, DeveloperToolsPanel) ||
            !DeveloperPage.Children.Contains(DeveloperScrollViewer))
            throw new InvalidOperationException("Developer overrides do not belong to Developer page.");
        if (ImportAppPatchButton is null || CancelAppPatchButton is null || RestoreAppPatchButton is null)
            throw new InvalidOperationException("Full-app patch controls are missing.");
        ShowPage("project");
        App.WriteLog("Developer tab isolation smoke passed.");
        App.WriteLog("Editor UX interaction smoke passed.");
    }

    private void RefreshEditorCardActions()
    {
        var index = CardsList.SelectedItem is ProjectCardViewModel card ? Cards.IndexOf(card) : -1;
        CardCountText.Text = $"{Cards.Count} {(Cards.Count == 1 ? "card" : "cards")}";
        SelectedCardPositionText.Text = index < 0 ? "Select a card" : $"Card {index + 1} of {Cards.Count}";
        DuplicateCardMenuItem.IsEnabled = index >= 0;
        MoveCardEarlierMenuItem.IsEnabled = index > 0;
        MoveCardLaterMenuItem.IsEnabled = index >= 0 && index < Cards.Count - 1;
    }

    private void RefreshEditorTimecode()
    {
        var fps = Math.Max(1, _legacyRenderer?.ReferenceFps ?? 60);
        var frame = Math.Max(0, (int)Math.Round(ProjectFrameSlider.Value));
        var seconds = frame / fps;
        PreviewTimecodeText.Text = $"{seconds / 3600:00}:{seconds / 60 % 60:00}:{seconds % 60:00}:{frame % fps:00}";
    }

    private void EditorFocus_Click(object sender, RoutedEventArgs e)
    {
        _previewFocusMode = PreviewFocusButton.IsChecked == true;
        PreviewFocusButton.Content = _previewFocusMode ? "Exit focus" : "Focus preview";
        ApplyAdaptiveWorkspaceLayout();
    }

    private static ProjectCardViewModel CloneEditorCard(ProjectCardViewModel source) => new()
    {
        Title = source.Title,
        Description = source.Description,
        BadgeHeader = source.BadgeHeader,
        BadgeValue = source.BadgeValue,
        BadgeUnit = source.BadgeUnit,
        ImagePath = source.ImagePath,
        ImageX = source.ImageX,
        ImageY = source.ImageY,
        ImageScale = source.ImageScale,
        ImageRotation = source.ImageRotation,
        ImageCropLeft = source.ImageCropLeft,
        ImageCropTop = source.ImageCropTop,
        ImageCropRight = source.ImageCropRight,
        ImageCropBottom = source.ImageCropBottom,
        ImageLayer = source.ImageLayer,
        ThumbnailBackgroundColor = source.ThumbnailBackgroundColor,
        ThumbnailAccentColor = source.ThumbnailAccentColor,
    };

    private void EditorDuplicateCard_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel source) return;
        var index = Cards.IndexOf(source);
        var copy = CloneEditorCard(source);
        var wasSuppressed = _suppressCardSelectionPreviewSeek;
        try
        {
            _suppressCardSelectionPreviewSeek = true;
            AddProjectCard(copy);
            Cards.Move(Cards.Count - 1, index + 1);
        }
        finally { _suppressCardSelectionPreviewSeek = wasSuppressed; }
        CardsList.SelectedItem = copy;
        CardsList.ScrollIntoView(copy);
        RefreshEditorCardActions();
        RefreshTimelineRange();
        TimelineStatusText.Text = $"Duplicated card {index + 1}";
        _ = RenderCurrentFrameAsync();
    }

    private void EditorMoveEarlier_Click(object sender, RoutedEventArgs e) => MoveEditorCard(-1);
    private void EditorMoveLater_Click(object sender, RoutedEventArgs e) => MoveEditorCard(1);

    private void MoveEditorCard(int direction)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel card) return;
        var index = Cards.IndexOf(card);
        var destination = index + direction;
        if (index < 0 || destination < 0 || destination >= Cards.Count) return;
        var wasSuppressed = _suppressCardSelectionPreviewSeek;
        try
        {
            _suppressCardSelectionPreviewSeek = true;
            Cards.Move(index, destination);
            CardsList.SelectedItem = card;
        }
        finally { _suppressCardSelectionPreviewSeek = wasSuppressed; }
        CardsList.ScrollIntoView(card);
        RefreshEditorCardActions();
        RefreshTimelineRange();
        TimelineStatusText.Text = $"Moved card to position {destination + 1}";
        _ = RenderCurrentFrameAsync();
    }

    private bool HandleEditorKeyboard(KeyRoutedEventArgs e)
    {
        if (e.Handled || !_editorWorkspaceUxInitialized || ProjectPage.Visibility != Visibility.Visible) return false;
        var origin = e.OriginalSource as DependencyObject;
        // Keep text editing, numeric inputs, menus, navigation and native button keys intact.
        if (origin is not null &&
            (FindAncestor<TextBox>(origin) is not null || FindAncestor<NumberBox>(origin) is not null ||
             FindAncestor<ComboBox>(origin) is not null || FindAncestor<ButtonBase>(origin) is not null ||
             FindAncestor<NavigationViewItem>(origin) is not null)) return false;
        bool Down(VirtualKey key) => (InputKeyboardSource.GetKeyStateForCurrentThread(key) & CoreVirtualKeyStates.Down) != 0;
        var control = Down(VirtualKey.Control);
        var shift = Down(VirtualKey.Shift);
        if (Down(VirtualKey.Menu)) return false;
        if (control && shift && e.Key == VirtualKey.F)
        {
            PreviewFocusButton.IsChecked = !_previewFocusMode;
            EditorFocus_Click(this, new RoutedEventArgs());
        }
        else if (control && !shift && e.Key == VirtualKey.D) EditorDuplicateCard_Click(this, new RoutedEventArgs());
        else if (control && shift && (e.Key is VirtualKey.Left or VirtualKey.Right)) MoveEditorCard(e.Key == VirtualKey.Left ? -1 : 1);
        else if (!control && e.Key == VirtualKey.Space) PreviewPlayButton_Click(this, new RoutedEventArgs());
        else if (!control && (e.Key is VirtualKey.Left or VirtualKey.Right) && _legacyRenderer is not null)
        {
            StopPreviewPlayback();
            var step = (shift ? 10 : 1) * (e.Key == VirtualKey.Left ? -1 : 1);
            ProjectFrameSlider.Value = Math.Clamp(ProjectFrameSlider.Value + step, 0, ProjectFrameSlider.Maximum);
        }
        else return false;
        e.Handled = true;
        return true;
    }
}
