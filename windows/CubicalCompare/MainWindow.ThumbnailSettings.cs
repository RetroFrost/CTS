using CubicalCompare.Core.Thumbnail;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.UI;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private enum ThumbnailSelectionPart { None, Artwork, Card }

    private int _thumbnailCardCount = 3;
    private readonly HashSet<string> _thumbnailSelectedCardIds = new(StringComparer.Ordinal);
    private string? _thumbnailSelectedCardId;
    private ThumbnailSelectionPart _thumbnailSelectionPart;

    private int GetThumbnailCardCount() => _thumbnailCardCount == 4 ? 4 : 3;

    private void InitializeThumbnailSettings()
    {
        SetThumbnailCardCountUi(_thumbnailCardCount);
        CardsList.SelectionChanged += ThumbnailSettings_CardsList_SelectionChanged;
        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
    }

    private void SetThumbnailCardCountUi(int count)
    {
        _thumbnailCardCount = count == 4 ? 4 : 3;
        PruneThumbnailSelection();
        if (ThumbnailCardCountComboBox is not null)
            ThumbnailCardCountComboBox.SelectedIndex = _thumbnailCardCount == 4 ? 1 : 0;
        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
    }

    private async void ThumbnailCardCount_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ThumbnailCardCountComboBox is null) return;

        var selected = ThumbnailCardCountComboBox.SelectedIndex == 1 ? 4 : 3;
        if (_thumbnailCardCount == selected) return;

        _thumbnailCardCount = selected;
        PruneThumbnailSelection();
        ThumbnailStatusText.Text = $"Thumbnail layout · {selected} cards";
        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        await RenderThumbnailPreviewOnlyAsync();
    }

    private void UseAutomaticThumbnailCards_Click(object sender, RoutedEventArgs e)
    {
        _thumbnailSelectedCardIds.Clear();
        _thumbnailSelectedCardId = null;
        _thumbnailSelectionPart = ThumbnailSelectionPart.None;
        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
        ThumbnailStatusText.Text = "Thumbnail cards · automatic representative selection";
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        _ = RenderThumbnailPreviewOnlyAsync();
    }

    private void ThumbnailCardPicker_Changed(object sender, RoutedEventArgs e)
    {
        if (sender is not CheckBox check || check.Tag is not string id || string.IsNullOrWhiteSpace(id))
            return;

        if (check.IsChecked == true)
        {
            if (_thumbnailSelectedCardIds.Count >= GetThumbnailCardCount() &&
                !_thumbnailSelectedCardIds.Contains(id))
            {
                check.IsChecked = false;
                return;
            }

            _thumbnailSelectedCardIds.Add(id);
        }
        else
        {
            _thumbnailSelectedCardIds.Remove(id);
        }

        _thumbnailSelectedCardId = _thumbnailSelectedCardIds.Contains(id)
            ? id
            : _thumbnailSelectedCardIds.FirstOrDefault();
        _thumbnailSelectionPart = _thumbnailSelectedCardId is null
            ? ThumbnailSelectionPart.None
            : ThumbnailSelectionPart.Card;

        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        _ = RenderThumbnailPreviewOnlyAsync();
    }

    private void RefreshThumbnailCardPickerUi()
    {
        if (ThumbnailCardPickerPanel is null)
            return;

        PruneThumbnailSelection();
        ThumbnailCardPickerPanel.Children.Clear();

        foreach (var card in Cards)
        {
            var check = new CheckBox
            {
                Content = $"{Cards.IndexOf(card) + 1}. {card.Title}",
                Tag = card.Id,
                IsChecked = _thumbnailSelectedCardIds.Contains(card.Id),
                Padding = new Thickness(5, 2, 5, 2),
                MinWidth = 120,
            };
            check.Checked += ThumbnailCardPicker_Changed;
            check.Unchecked += ThumbnailCardPicker_Changed;
            ThumbnailCardPickerPanel.Children.Add(check);
        }

        ThumbnailPickerModeText.Text = _thumbnailSelectedCardIds.Count > 0
            ? $"Manual · {_thumbnailSelectedCardIds.Count}/{GetThumbnailCardCount()} selected"
            : "Automatic · representative cards";
    }

    private void PruneThumbnailSelection()
    {
        var valid = Cards.Select(card => card.Id).ToHashSet(StringComparer.Ordinal);
        _thumbnailSelectedCardIds.RemoveWhere(id => !valid.Contains(id));

        while (_thumbnailSelectedCardIds.Count > GetThumbnailCardCount())
            _thumbnailSelectedCardIds.Remove(_thumbnailSelectedCardIds.Last());

        if (_thumbnailSelectedCardId is not null &&
            !_thumbnailSelectedCardIds.Contains(_thumbnailSelectedCardId))
            _thumbnailSelectedCardId = _thumbnailSelectedCardIds.FirstOrDefault();

        if (_thumbnailSelectedCardIds.Count == 0)
        {
            _thumbnailSelectedCardId = null;
            if (_thumbnailSelectionPart == ThumbnailSelectionPart.Card)
                _thumbnailSelectionPart = ThumbnailSelectionPart.None;
        }
    }

    private void ApplyThumbnailSelection(IEnumerable<string>? ids)
    {
        _thumbnailSelectedCardIds.Clear();

        if (ids is not null)
        {
            foreach (var id in ids
                .Where(x => !string.IsNullOrWhiteSpace(x))
                .Select(x => x.Trim())
                .Distinct(StringComparer.Ordinal)
                .Take(4))
                _thumbnailSelectedCardIds.Add(id);
        }

        PruneThumbnailSelection();
        _thumbnailSelectedCardId = _thumbnailSelectedCardIds.FirstOrDefault();
        _thumbnailSelectionPart = _thumbnailSelectedCardId is null
            ? ThumbnailSelectionPart.None
            : ThumbnailSelectionPart.Card;

        RefreshThumbnailCardPickerUi();
        RefreshThumbnailInteractionOverlay();
    }

    private void ThumbnailInteractionCanvas_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (e.OriginalSource is FrameworkElement element &&
            !ReferenceEquals(element, ThumbnailInteractionCanvas))
            return;

        ClearThumbnailElementSelection();
        e.Handled = true;
    }

    private void ThumbnailHotspot_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (sender is not FrameworkElement element || element.Tag is not string tag)
            return;

        var parts = tag.Split('|', 2);
        if (parts.Length != 2 || !int.TryParse(parts[0], out var slot))
            return;

        var indices = AutoThumbnailGenerator.PickCards(BuildProject());
        if (slot < 0 || slot >= indices.Count)
            return;

        var card = Cards[indices[slot]];
        CardsList.SelectedItem = card;
        _thumbnailSelectedCardId = card.Id;
        _thumbnailSelectionPart = parts[1].Equals("artwork", StringComparison.Ordinal)
            ? ThumbnailSelectionPart.Artwork
            : ThumbnailSelectionPart.Card;

        RefreshThumbnailSelectedCardUi();
        RefreshThumbnailInteractionOverlay();
        e.Handled = true;
    }

    private void ClearThumbnailElementSelection()
    {
        _thumbnailSelectionPart = ThumbnailSelectionPart.None;
        _thumbnailSelectedCardId = null;
        RefreshThumbnailInteractionOverlay();
    }

    private async void ThumbnailReplaceArtworkByUrl_Click(object sender, RoutedEventArgs e)
    {
        var card = ResolveThumbnailSelectedCard();
        if (card is null) return;

        CardsList.SelectedItem = card;
        SetArtworkUrl_Click(sender, e);
        _thumbnailSelectedCardId = card.Id;
        _thumbnailSelectionPart = ThumbnailSelectionPart.Artwork;
        RefreshThumbnailInteractionOverlay();
    }

    private async void ThumbnailCardBackground_Click(object sender, RoutedEventArgs e)
    {
        var card = ResolveThumbnailSelectedCard();
        if (card is not null)
            await PickThumbnailColorAsync(card, accent: false);
    }

    private async void ThumbnailCardAccent_Click(object sender, RoutedEventArgs e)
    {
        var card = ResolveThumbnailSelectedCard();
        if (card is not null)
            await PickThumbnailColorAsync(card, accent: true);
    }

    private ProjectCardViewModel? ResolveThumbnailSelectedCard()
    {
        if (!string.IsNullOrWhiteSpace(_thumbnailSelectedCardId))
        {
            var selected = Cards.FirstOrDefault(card =>
                string.Equals(card.Id, _thumbnailSelectedCardId, StringComparison.Ordinal));
            if (selected is not null)
                return selected;
        }

        return CardsList.SelectedItem as ProjectCardViewModel;
    }

    private async Task PickThumbnailColorAsync(ProjectCardViewModel card, bool accent)
    {
        var current = ParseHexColor(
            accent ? card.ThumbnailAccentColor : card.ThumbnailBackgroundColor,
            accent
                ? Color.FromArgb(255, 255, 15, 22)
                : Color.FromArgb(255, 5, 7, 14));

        var picker = new ColorPicker
        {
            Color = current,
            IsMoreButtonVisible = true,
            IsAlphaEnabled = false,
            IsHexInputVisible = true,
            IsColorSliderVisible = true,
            IsColorChannelTextInputVisible = true,
        };

        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = accent ? $"Badge color · {card.Title}" : $"Artwork background · {card.Title}",
            Content = picker,
            PrimaryButtonText = "Apply",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            return;

        if (accent)
            card.ThumbnailAccentColor = ToHexColor(picker.Color);
        else
            card.ThumbnailBackgroundColor = ToHexColor(picker.Color);

        _thumbnailSelectedCardId = card.Id;
        _thumbnailSelectionPart = ThumbnailSelectionPart.Card;
        RefreshThumbnailSelectedCardUi();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        await RenderThumbnailPreviewOnlyAsync();
    }

    private async void PickThumbnailBackground_Click(object sender, RoutedEventArgs e)
    {
        var card = ResolveThumbnailSelectedCard();
        if (card is null)
        {
            await ShowErrorAsync(
                "Select a card first",
                "Click a badge in the thumbnail or select a card in the Workspace.");
            return;
        }

        await PickThumbnailColorAsync(card, accent: false);
    }

    private async Task RenderThumbnailPreviewOnlyAsync()
    {
        var revision = Interlocked.Increment(ref _thumbnailRevision);
        await RefreshThumbnailAsync(BuildProject(), revision, delayed: false);
    }

    private void ThumbnailSettings_CardsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        RefreshThumbnailSelectedCardUi();

        if (_thumbnailSelectionPart == ThumbnailSelectionPart.None)
            return;

        if (CardsList.SelectedItem is ProjectCardViewModel card)
            _thumbnailSelectedCardId = card.Id;

        RefreshThumbnailInteractionOverlay();
    }

    private void RefreshThumbnailSelectedCardUi()
    {
        var card = ResolveThumbnailSelectedCard();
        if (ThumbnailSelectedCardText is null)
            return;

        ThumbnailSelectedCardText.Text = card is null
            ? "No thumbnail element selected"
            : _thumbnailSelectionPart switch
            {
                ThumbnailSelectionPart.Artwork => $"Artwork selected · {card.Title}",
                ThumbnailSelectionPart.Card => $"Card selected · {card.Title} · background {card.ThumbnailBackgroundColor} · badge {card.ThumbnailAccentColor}",
                _ => $"{card.Title} · background {card.ThumbnailBackgroundColor} · badge {card.ThumbnailAccentColor}",
            };
    }

    private void RefreshThumbnailInteractionOverlay()
    {
        if (ThumbnailInteractionCanvas is null)
            return;

        ThumbnailInteractionCanvas.Children.Clear();

        var indices = AutoThumbnailGenerator.PickCards(BuildProject());
        var count = Math.Max(1, indices.Count);

        for (var slot = 0; slot < indices.Count; slot++)
        {
            var left = slot * AutoThumbnailGenerator.Width / (double)count;
            var right = (slot + 1) * AutoThumbnailGenerator.Width / (double)count;

            var artwork = new Rectangle
            {
                Width = Math.Max(1, right - left),
                Height = AutoThumbnailGenerator.Height - 360,
                Fill = new SolidColorBrush(Color.FromArgb(1, 255, 255, 255)),
                Tag = $"{slot}|artwork",
            };
            Canvas.SetLeft(artwork, left);
            Canvas.SetTop(artwork, 360);
            artwork.PointerPressed += ThumbnailHotspot_PointerPressed;
            ThumbnailInteractionCanvas.Children.Add(artwork);

            var badgeRadius = Math.Min(125d, (right - left) * .29d);
            var badge = new Ellipse
            {
                Width = badgeRadius * 2.15,
                Height = badgeRadius * 2.15 * .88,
                Fill = new SolidColorBrush(Color.FromArgb(1, 255, 255, 255)),
                Tag = $"{slot}|card",
            };
            Canvas.SetLeft(badge, (left + right) / 2d - badge.Width / 2d);
            Canvas.SetTop(badge, 145d - badge.Height / 2d);
            badge.PointerPressed += ThumbnailHotspot_PointerPressed;
            ThumbnailInteractionCanvas.Children.Add(badge);
        }

        ThumbnailArtworkActions.Visibility = _thumbnailSelectionPart == ThumbnailSelectionPart.Artwork
            ? Visibility.Visible
            : Visibility.Collapsed;

        ThumbnailCardActions.Visibility = _thumbnailSelectionPart == ThumbnailSelectionPart.Card
            ? Visibility.Visible
            : Visibility.Collapsed;

        RefreshThumbnailSelectedCardUi();
    }

    private static Color ParseHexColor(string? value, Color fallback)
    {
        var normalized = (value ?? string.Empty).Trim();
        if (normalized.Length == 7 &&
            normalized[0] == '#' &&
            byte.TryParse(normalized.AsSpan(1, 2), System.Globalization.NumberStyles.HexNumber, null, out var r) &&
            byte.TryParse(normalized.AsSpan(3, 2), System.Globalization.NumberStyles.HexNumber, null, out var g) &&
            byte.TryParse(normalized.AsSpan(5, 2), System.Globalization.NumberStyles.HexNumber, null, out var b))
            return Color.FromArgb(255, r, g, b);

        return fallback;
    }

    private static string ToHexColor(Color color) => $"#{color.R:X2}{color.G:X2}{color.B:X2}";
}
