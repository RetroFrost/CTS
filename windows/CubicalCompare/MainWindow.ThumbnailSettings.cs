using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.UI;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private int _thumbnailCardCount = 3;

    private int GetThumbnailCardCount() => _thumbnailCardCount == 4 ? 4 : 3;

    private void InitializeThumbnailSettings()
    {
        SetThumbnailCardCountUi(_thumbnailCardCount);
        CardsList.SelectionChanged += ThumbnailSettings_CardsList_SelectionChanged;
        RefreshThumbnailSelectedCardUi();
    }

    private void SetThumbnailCardCountUi(int count)
    {
        _thumbnailCardCount = count == 4 ? 4 : 3;

        if (ThumbnailCardCountComboBox is not null)
            ThumbnailCardCountComboBox.SelectedIndex = _thumbnailCardCount == 4 ? 1 : 0;

        RefreshThumbnailSelectedCardUi();
    }

    private async void ThumbnailCardCount_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ThumbnailCardCountComboBox is null)
            return;

        var selected = ThumbnailCardCountComboBox.SelectedIndex == 1 ? 4 : 3;
        if (_thumbnailCardCount == selected)
            return;

        _thumbnailCardCount = selected;
        ThumbnailStatusText.Text = $"Thumbnail layout · {selected} cards";
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        await RenderThumbnailPreviewOnlyAsync();
    }

    private async void PickThumbnailBackground_Click(object sender, RoutedEventArgs e)
    {
        var card = CardsList.SelectedItem as ProjectCardViewModel;
        if (card is null)
        {
            await ShowErrorAsync("Select a card first", "Select the card whose transparent/backgroundless artwork needs a thumbnail background.");
            return;
        }

        var current = ParseHexColor(card.ThumbnailBackgroundColor, Color.FromArgb(255, 5, 7, 14));
        var picker = new ColorPicker
        {
            Color = current,
            IsMoreButtonVisible = true,
            IsCompact = false,
            IsAlphaEnabled = false,
            IsHexInputVisible = true,
            IsColorSliderVisible = true,
            IsColorChannelTextInputVisible = true,
            IsSpectrumVisible = true,
        };

        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = $"Thumbnail background · {card.Title}",
            Content = picker,
            PrimaryButtonText = "Apply",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            return;

        card.ThumbnailBackgroundColor = ToHexColor(picker.Color);
        RefreshThumbnailSelectedCardUi();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        await RenderThumbnailPreviewOnlyAsync();
    }

    private async Task RenderThumbnailPreviewOnlyAsync()
    {
        var revision = Interlocked.Increment(ref _thumbnailRevision);
        await RefreshThumbnailAsync(BuildProject(), revision, delayed: false);
    }

    private void ThumbnailSettings_CardsList_SelectionChanged(object sender, SelectionChangedEventArgs e)
        => RefreshThumbnailSelectedCardUi();

    private void RefreshThumbnailSelectedCardUi()
    {
        var card = CardsList?.SelectedItem as ProjectCardViewModel;
        if (ThumbnailSelectedCardText is null)
            return;

        if (card is null)
        {
            ThumbnailSelectedCardText.Text = "No card selected";
            return;
        }

        ThumbnailSelectedCardText.Text =
            $"{card.Title} · background {card.ThumbnailBackgroundColor} · transparent artwork uses this fill";
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
