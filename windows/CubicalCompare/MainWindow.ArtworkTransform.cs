using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using SkiaSharp;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private const double ArtworkSlotWidth = 471.0;
    private const double ArtworkSlotHeight = 872.0;
    private int _artworkTransformBatchDepth;

    private static string? TryGetCachedArtworkPath(string? source)
    {
        if (string.IsNullOrWhiteSpace(source))
            return null;

        var resolved = WebImageSource.TryGetCachedLocalPath(source);
        return !string.IsNullOrWhiteSpace(resolved) && File.Exists(resolved)
            ? resolved
            : null;
    }

    private async void ArtworkTransform_ValueChanged(NumberBox sender, NumberBoxValueChangedEventArgs args)
    {
        if (double.IsNaN(args.NewValue))
        {
            sender.Value = args.OldValue;
            return;
        }

        if (_artworkTransformBatchDepth > 0 || sender.DataContext is not ProjectCardViewModel)
            return;

        await ApplyArtworkTransformAsync();
    }

    private async void ArtworkTransform_Fit_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel card)
            return;

        if (string.IsNullOrWhiteSpace(card.ImagePath))
        {
            TimelineStatusText.Text = "Choose artwork before fitting it.";
            return;
        }

        try
        {
            if (WebImageSource.IsRemoteSource(card.ImagePath))
                TimelineStatusText.Text = "Resolving web artwork for transform…";

            var resolvedArtwork = await WebImageSource.ResolveToLocalFileAsync(card.ImagePath);
            if (string.IsNullOrWhiteSpace(resolvedArtwork) || !File.Exists(resolvedArtwork))
            {
                TimelineStatusText.Text = "Artwork could not be resolved.";
                return;
            }

            using var bitmap = SKBitmap.Decode(resolvedArtwork);
            if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0)
            {
                TimelineStatusText.Text = "Artwork dimensions could not be read.";
                return;
            }

            var coverScale = Math.Max(ArtworkSlotWidth / bitmap.Width, ArtworkSlotHeight / bitmap.Height);
            var containScale = Math.Min(ArtworkSlotWidth / bitmap.Width, ArtworkSlotHeight / bitmap.Height);
            var rendererScale = coverScale > 0 ? containScale / coverScale : 1.0;

            BeginArtworkTransformBatch();
            try
            {
                ImageXBox.Value = 0;
                ImageYBox.Value = 0;
                ImageScaleBox.Value = Math.Clamp(rendererScale, 0.05, 12.0);
                ImageRotationBox.Value = 0;
                ClearCropBoxes();
            }
            finally
            {
                EndArtworkTransformBatch();
            }

            TimelineStatusText.Text = $"Artwork fitted inside {ArtworkSlotWidth:0}×{ArtworkSlotHeight:0}.";
            await ApplyArtworkTransformAsync();
        }
        catch (Exception ex)
        {
            App.WriteLog("Artwork fit failed", ex);
            TimelineStatusText.Text = $"Could not fit artwork: {ex.Message}";
        }
    }

    private async void ArtworkTransform_Fill_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel)
            return;

        BeginArtworkTransformBatch();
        try
        {
            ImageXBox.Value = 0;
            ImageYBox.Value = 0;
            ImageScaleBox.Value = 1;
            ImageRotationBox.Value = 0;
            ClearCropBoxes();
        }
        finally
        {
            EndArtworkTransformBatch();
        }

        TimelineStatusText.Text = "Artwork fills the 471×872 slot.";
        await ApplyArtworkTransformAsync();
    }

    private async void ArtworkTransform_Center_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel)
            return;

        BeginArtworkTransformBatch();
        try
        {
            ImageXBox.Value = 0;
            ImageYBox.Value = 0;
        }
        finally
        {
            EndArtworkTransformBatch();
        }

        TimelineStatusText.Text = "Artwork centered.";
        await ApplyArtworkTransformAsync();
    }

    private async void ArtworkTransform_Reset_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel)
            return;

        BeginArtworkTransformBatch();
        try
        {
            ImageXBox.Value = 0;
            ImageYBox.Value = 0;
            ImageScaleBox.Value = 1;
            ImageRotationBox.Value = 0;
            ClearCropBoxes();
        }
        finally
        {
            EndArtworkTransformBatch();
        }

        TimelineStatusText.Text = "Artwork transform reset.";
        await ApplyArtworkTransformAsync();
    }

    private async void ArtworkTransform_ApplyAll_Click(object sender, RoutedEventArgs e)
    {
        if (CardsList.SelectedItem is not ProjectCardViewModel source)
            return;

        await ApplyArtworkTransformToAllAsync(source);
    }

    private async Task ApplyArtworkTransformToAllAsync(ProjectCardViewModel source)
    {
        if (Cards.Count <= 1)
        {
            TimelineStatusText.Text = "There are no other images to update.";
            return;
        }

        foreach (var card in Cards)
        {
            if (ReferenceEquals(card, source))
                continue;

            card.ImageX = source.ImageX;
            card.ImageY = source.ImageY;
            card.ImageScale = source.ImageScale;
            card.ImageRotation = source.ImageRotation;
            card.ImageCropLeft = source.ImageCropLeft;
            card.ImageCropTop = source.ImageCropTop;
            card.ImageCropRight = source.ImageCropRight;
            card.ImageCropBottom = source.ImageCropBottom;
            card.ImageLayer = source.ImageLayer;
        }

        TimelineStatusText.Text = $"Applied the selected image transform to all {Cards.Count} images.";
        await ApplyArtworkTransformAsync();
    }

    private void BeginArtworkTransformBatch() => _artworkTransformBatchDepth++;

    private void EndArtworkTransformBatch()
    {
        if (_artworkTransformBatchDepth > 0)
            _artworkTransformBatchDepth--;
    }

    private void ClearCropBoxes()
    {
        ImageCropLeftBox.Value = 0;
        ImageCropTopBox.Value = 0;
        ImageCropRightBox.Value = 0;
        ImageCropBottomBox.Value = 0;
    }

    private async Task ApplyArtworkTransformAsync()
    {
        // Transform properties are already part of ComparisonProject and Zipack2. These explicit
        // editor controls update those properties through TwoWay bindings; the work here makes the
        // change immediately visible and durable even though the legacy view-model transform fields
        // predate INotifyPropertyChanged-backed setters.
        RefreshArtworkManipulator();
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();

        if (_legacyRenderer is null)
            return;

        RefreshTimelineRange();
        await RenderCurrentFrameAsync();
    }
}
