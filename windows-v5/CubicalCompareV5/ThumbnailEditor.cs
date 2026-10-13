using Microsoft.Win32;
using SaveFileDialog = Microsoft.Win32.SaveFileDialog;
using Point = System.Windows.Point;
using MouseEventArgs = System.Windows.Input.MouseEventArgs;
using MouseButtonEventArgs = System.Windows.Input.MouseButtonEventArgs;
using MouseButtonState = System.Windows.Input.MouseButtonState;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Ink;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using Image = System.Windows.Controls.Image;
using Brush = System.Windows.Media.Brush;

namespace CubicalCompareV5;

// Independent thumbnail transforms deliberately use the same controls and WPF
// composition as video-card transforms. Exporting either surface captures the
// actual transformed visual, not its unmodified source bitmap.
public partial class MainWindow
{
    private void MainMode_Click(object sender, RoutedEventArgs e) => SetMode(false);
    private void ThumbnailMode_Click(object sender, RoutedEventArgs e) => SetMode(true);

    private void SetMode(bool thumbnail)
    {
        if (_dragging)
        {
            _dragging = false;
            ArtworkImage.ReleaseMouseCapture();
            ThumbnailArtworkImage.ReleaseMouseCapture();
        }
        _thumbnailMode = thumbnail;
        Stage.Visibility = thumbnail ? Visibility.Collapsed : Visibility.Visible;
        ThumbnailStage.Visibility = thumbnail ? Visibility.Visible : Visibility.Collapsed;
        Sketch.EditingMode = InkCanvasEditingMode.None;
        ThumbnailSketch.EditingMode = InkCanvasEditingMode.None;
        Sketch.IsHitTestVisible = false;
        ThumbnailSketch.IsHitTestVisible = false;
        _drawing = false;
        DrawButton.Background = (Brush)Resources["FieldBrush"];
        EraseButton.Background = (Brush)Resources["FieldBrush"];
        MainModeButton.Background = thumbnail ? (Brush)Resources["FieldBrush"] : BrushOf("#166B78");
        ThumbnailModeButton.Background = thumbnail ? BrushOf("#166B78") : (Brush)Resources["FieldBrush"];
        CopyMainTransformButton.Visibility = thumbnail ? Visibility.Visible : Visibility.Collapsed;
        ThumbnailTransformHint.Visibility = thumbnail ? Visibility.Visible : Visibility.Collapsed;
        ArtworkLabel.Text = thumbnail ? "Thumbnail artwork" : T("art");
        ArtworkButton.Content = thumbnail ? "Change thumbnail artwork..." : T("artimport");
        RemoveArtworkButton.Content = thumbnail ? "Use main image" : T("artremove");
        TransformHeading.Text = thumbnail ? "Thumbnail image transform" : "Image transform";
        PreviewHint.Text = thumbnail
            ? "Thumbnail: 1920 × 1080 · Thumbnail-only transforms affect PNG output"
            : "Canvas: 1920 × 1080 · Drag an image or draw over artwork";
        UpdateTransformControls();
    }

    private ArtworkTransformState? ActiveTransform
    {
        get
        {
            if (_active is null) return null;
            if (_thumbnailMode) return _active.ThumbnailTransform ??= new ArtworkTransformState();
            return _active.ImageTransform ??= new ArtworkTransformState();
        }
    }

    private void UpdateTransformControls()
    {
        var t = ActiveTransform;
        if (t is null) return;
        bool before = _syncing;
        _syncing = true;
        try
        {
            ScaleSlider.Value = Math.Clamp(t.Scale, ScaleSlider.Minimum, ScaleSlider.Maximum);
            OffsetXSlider.Value = Math.Clamp(t.X, OffsetXSlider.Minimum, OffsetXSlider.Maximum);
            OffsetYSlider.Value = Math.Clamp(t.Y, OffsetYSlider.Minimum, OffsetYSlider.Maximum);
            RotationSlider.Value = Math.Clamp(t.Rotation, RotationSlider.Minimum, RotationSlider.Maximum);
            FlipXCheck.IsChecked = t.FlipHorizontal;
            FlipYCheck.IsChecked = t.FlipVertical;
            ScaleReadout.Text = $"{t.Scale * 100:0}%";
            OffsetXReadout.Text = $"{t.X:0} px";
            OffsetYReadout.Text = $"{t.Y:0} px";
            RotationReadout.Text = $"{t.Rotation:0}°";
        }
        finally { _syncing = before; }
    }

    private void Transform_Changed(object sender, RoutedPropertyChangedEventArgs<double> e) => CommitTransformControls();
    private void TransformFlip_Changed(object sender, RoutedEventArgs e) => CommitTransformControls();

    private void CommitTransformControls()
    {
        if (_syncing || _active is null) return;
        var t = ActiveTransform!;
        t.Scale = ScaleSlider.Value;
        t.X = OffsetXSlider.Value;
        t.Y = OffsetYSlider.Value;
        t.Rotation = RotationSlider.Value;
        t.FlipHorizontal = FlipXCheck.IsChecked == true;
        t.FlipVertical = FlipYCheck.IsChecked == true;
        UpdateTransformControls();
        ApplyTransforms(_active);
    }

    private void ResetTransform_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        if (_thumbnailMode) _active.ThumbnailTransform = new ArtworkTransformState();
        else _active.ImageTransform = new ArtworkTransformState();
        UpdateTransformControls();
        ApplyTransforms(_active);
    }

    private void CopyMainTransform_Click(object sender, RoutedEventArgs e)
    {
        if (_active is null) return;
        _active.ThumbnailTransform = (_active.ImageTransform ?? new ArtworkTransformState()).Clone();
        SetMode(true);
        ApplyTransforms(_active);
    }

    private void Artwork_MouseDown(object sender, MouseButtonEventArgs e)
    {
        if (_active is null || _drawing || sender is not Image img) return;
        var t = ActiveTransform!;
        _dragStartX = t.X;
        _dragStartY = t.Y;
        _dragOrigin = e.GetPosition(_thumbnailMode ? ThumbnailStage : Stage);
        _dragging = true;
        img.CaptureMouse();
        e.Handled = true;
    }

    private void Artwork_MouseMove(object sender, MouseEventArgs e)
    {
        if (!_dragging || _active is null) return;
        if (e.LeftButton != MouseButtonState.Pressed)
        {
            _dragging = false;
            if (sender is Image img) img.ReleaseMouseCapture();
            return;
        }
        var position = e.GetPosition(_thumbnailMode ? ThumbnailStage : Stage);
        var t = ActiveTransform!;
        t.X = Math.Clamp(_dragStartX + position.X - _dragOrigin.X, OffsetXSlider.Minimum, OffsetXSlider.Maximum);
        t.Y = Math.Clamp(_dragStartY + position.Y - _dragOrigin.Y, OffsetYSlider.Minimum, OffsetYSlider.Maximum);
        UpdateTransformControls();
        ApplyTransforms(_active);
        e.Handled = true;
    }

    private void Artwork_MouseUp(object sender, MouseButtonEventArgs e)
    {
        _dragging = false;
        if (sender is Image img) img.ReleaseMouseCapture();
    }

    private void Artwork_LostCapture(object sender, MouseEventArgs e) => _dragging = false;

    // The exact WPF visual used in preview is rendered to PNG. No separate
    // untransformed bitmap codepath exists for thumbnail exports.
    private static byte[] EncodeStagePng(Grid stage)
    {
        stage.UpdateLayout();
        var bitmap = new RenderTargetBitmap(1920, 1080, 192, 192, PixelFormats.Pbgra32);
        bitmap.Render(stage);
        bitmap.Freeze();
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var result = new MemoryStream();
        encoder.Save(result);
        return result.ToArray();
    }

    private void ExportThumbnail_Click(object sender, RoutedEventArgs e)
    {
        if (_exporting || _active is null) return;
        PersistInk();
        var dialog = new SaveFileDialog
        {
            Filter = "PNG image|*.png",
            DefaultExt = ".png",
            AddExtension = true,
            FileName = "CubicalCompare-Thumbnail.png"
        };
        if (dialog.ShowDialog(this) != true) return;
        bool before = _thumbnailMode;
        try
        {
            SetMode(true);
            UpdatePreview(_active);
            File.WriteAllBytes(dialog.FileName, EncodeStagePng(ThumbnailStage));
            StatusLabel.Text = "Thumbnail saved: " + Path.GetFileName(dialog.FileName);
        }
        catch (Exception ex) { ShowError(ex); }
        finally { SetMode(before); }
    }
    // GitHub Actions exercises actual WPF composition, not only compile-time syntax.
    // The regression proves thumbnail-only transforms change exported PNG pixels,
    // leave the video frame untouched, and survive project serialization.
    internal void RunThumbnailRegressionTest()
    {
        if (_active is null) throw new InvalidOperationException("No selected test card.");
        var card = _active;
        var artwork = new DrawingVisual();
        using (var context = artwork.RenderOpen())
        {
            context.DrawRectangle(System.Windows.Media.Brushes.OrangeRed, null, new Rect(0, 0, 80, 80));
            context.DrawRectangle(System.Windows.Media.Brushes.White, null, new Rect(0, 0, 36, 80));
            context.DrawEllipse(System.Windows.Media.Brushes.Navy, null, new Point(56, 22), 14, 14);
        }
        var testImage = new RenderTargetBitmap(80, 80, 96, 96, PixelFormats.Pbgra32);
        testImage.Render(artwork);
        var encoder = new PngBitmapEncoder();
        encoder.Frames.Add(BitmapFrame.Create(testImage));
        using var stream = new MemoryStream();
        encoder.Save(stream);
        card.ArtworkBase64 = Convert.ToBase64String(stream.ToArray());
        card.ThumbnailArtworkBase64 = "";
        card.ImageTransform = new ArtworkTransformState();
        card.ThumbnailTransform = new ArtworkTransformState();

        SetMode(false);
        UpdatePreview(card);
        var originalVideo = EncodeStagePng(Stage);
        SetMode(true);
        UpdatePreview(card);
        var originalThumbnail = EncodeStagePng(ThumbnailStage);

        card.ThumbnailTransform = new ArtworkTransformState
        {
            Scale = 1.55, X = -110, Y = 65, Rotation = 23,
            FlipHorizontal = true, FlipVertical = false
        };
        UpdatePreview(card);
        UpdateTransformControls();
        var changedThumbnail = EncodeStagePng(ThumbnailStage);
        if (originalThumbnail.AsSpan().SequenceEqual(changedThumbnail))
            throw new InvalidOperationException("Thumbnail PNG ignored the image transform.");

        SetMode(false);
        UpdatePreview(card);
        var unchangedVideo = EncodeStagePng(Stage);
        if (!originalVideo.AsSpan().SequenceEqual(unchangedVideo))
            throw new InvalidOperationException("Thumbnail-only transform changed the video image.");

        var project = new ComparisonProject();
        project.Cards.Add(card);
        string json = System.Text.Json.JsonSerializer.Serialize(project);
        var restored = System.Text.Json.JsonSerializer.Deserialize<ComparisonProject>(json)
            ?? throw new InvalidOperationException("Cannot deserialize saved project.");
        var check = restored.Cards[0].ThumbnailTransform;
        if (Math.Abs(check.Scale - 1.55) > 0.001 ||
            Math.Abs(check.X + 110) > 0.001 ||
            Math.Abs(check.Y - 65) > 0.001 ||
            Math.Abs(check.Rotation - 23) > 0.001 ||
            !check.FlipHorizontal)
            throw new InvalidOperationException("Thumbnail transform was not saved correctly.");

        SetMode(true);
        UpdatePreview(card);
        var savedThumbnail = EncodeStagePng(ThumbnailStage);
        if (!changedThumbnail.AsSpan().SequenceEqual(savedThumbnail))
            throw new InvalidOperationException("Thumbnail transform changed after switching editor modes.");
    }

}
