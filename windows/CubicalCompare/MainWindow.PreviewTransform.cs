using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using SkiaSharp;
using Windows.Foundation;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private enum PreviewTransformMode
    {
        None,
        Move,
        Scale,
        Rotate,
    }

    private const double PreviewCanvasWidth = 960.0;
    private const double PreviewCanvasHeight = 540.0;
    private const double PreviewSlotPitch = 476.0;
    private const double PreviewBodyInset = 9.0;
    private const double PreviewBodyWidth = 471.0;
    private const double PreviewImageHeight = 872.0;

    private Canvas? _previewTransformCanvas;
    private Rectangle? _previewArtworkSlotFrame;
    private Grid? _previewTransformAdorner;
    private TextBlock? _previewTransformHint;
    private bool _previewTransformActive;
    private int _previewTransformCardIndex = -1;
    private PreviewTransformMode _previewTransformMode;
    private uint _previewTransformPointerId;
    private Point _previewTransformPointerStart;
    private double _previewStartX;
    private double _previewStartY;
    private double _previewStartScale;
    private double _previewStartRotation;
    private double _previewWorkingX;
    private double _previewWorkingY;
    private double _previewWorkingScale;
    private double _previewWorkingRotation;
    private double _previewStartDistance;
    private double _previewRotationOffset;

    internal void InitializePreviewTransformEditor()
    {
        if (_previewTransformCanvas is not null)
            return;

        if (RenderedFrameImage.Parent is not Grid previewGrid)
        {
            RootNavigation.Loaded += PreviewTransform_RootNavigationLoaded;
            return;
        }

        RootNavigation.Loaded -= PreviewTransform_RootNavigationLoaded;

        var canvas = new Canvas
        {
            Width = PreviewCanvasWidth,
            Height = PreviewCanvasHeight,
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 0, 0, 0)),
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
        };
        canvas.PointerPressed += PreviewTransform_PointerPressed;
        canvas.PointerMoved += PreviewTransform_PointerMoved;
        canvas.PointerReleased += PreviewTransform_PointerReleased;
        canvas.PointerCanceled += PreviewTransform_PointerCanceled;
        canvas.PointerCaptureLost += PreviewTransform_PointerCaptureLost;
        _previewTransformCanvas = canvas;

        var slotFrame = new Rectangle
        {
            Visibility = Visibility.Collapsed,
            Stroke = new SolidColorBrush(ColorHelper.FromArgb(230, 255, 230, 0)),
            StrokeThickness = 3,
            StrokeDashArray = [8, 5],
            Fill = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            IsHitTestVisible = false,
        };
        _previewArtworkSlotFrame = slotFrame;
        canvas.Children.Add(slotFrame);

        var adorner = new Grid
        {
            Visibility = Visibility.Collapsed,
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            RenderTransformOrigin = new Point(0.5, 0.5),
        };
        _previewTransformAdorner = adorner;
        adorner.Children.Add(new Border
        {
            BorderBrush = new SolidColorBrush(Colors.White),
            BorderThickness = new Thickness(3),
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            IsHitTestVisible = false,
        });
        adorner.Children.Add(CreatePreviewHandle("preview-resize-nw", HorizontalAlignment.Left, VerticalAlignment.Top, new Thickness(-11, -11, 0, 0)));
        adorner.Children.Add(CreatePreviewHandle("preview-resize-ne", HorizontalAlignment.Right, VerticalAlignment.Top, new Thickness(0, -11, -11, 0)));
        adorner.Children.Add(CreatePreviewHandle("preview-resize-sw", HorizontalAlignment.Left, VerticalAlignment.Bottom, new Thickness(-11, 0, 0, -11)));
        adorner.Children.Add(CreatePreviewHandle("preview-resize-se", HorizontalAlignment.Right, VerticalAlignment.Bottom, new Thickness(0, 0, -11, -11)));

        var rotate = new Border
        {
            Tag = "preview-rotate",
            Width = 34,
            Height = 34,
            CornerRadius = new CornerRadius(17),
            Background = new SolidColorBrush(ColorHelper.FromArgb(250, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 16, 0, 0),
            Child = new TextBlock
            {
                Text = "↻",
                Foreground = new SolidColorBrush(Colors.Black),
                FontSize = 21,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                IsHitTestVisible = false,
            },
        };
        adorner.Children.Add(rotate);
        canvas.Children.Add(adorner);

        var hintBorder = new Border
        {
            Background = new SolidColorBrush(ColorHelper.FromArgb(220, 20, 20, 20)),
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(120, 255, 255, 255)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(10, 6, 10, 6),
            IsHitTestVisible = false,
            Visibility = Visibility.Collapsed,
        };
        _previewTransformHint = new TextBlock
        {
            Text = "Right-click a card to transform its artwork directly",
            FontSize = 12,
            Foreground = new SolidColorBrush(Colors.White),
        };
        hintBorder.Child = _previewTransformHint;
        Canvas.SetLeft(hintBorder, 12);
        Canvas.SetTop(hintBorder, PreviewCanvasHeight - 42);
        canvas.Children.Add(hintBorder);
        canvas.Tag = hintBorder;

        previewGrid.Children.Add(canvas);
        Canvas.SetZIndex(canvas, 100);

        CardsList.SelectionChanged += (_, _) =>
        {
            if (!_previewTransformActive) return;
            _previewTransformCardIndex = CardsList.SelectedIndex;
            RefreshPreviewTransformOverlay();
        };
        ProjectFrameSlider.ValueChanged += (_, _) =>
        {
            if (_previewTransformActive) RefreshPreviewTransformOverlay();
        };

        TimelineStatusText.Text = _legacyRenderer is null
            ? TimelineStatusText.Text
            : "Right-click a preview card to transform its artwork directly";
    }

    private void PreviewTransform_RootNavigationLoaded(object sender, RoutedEventArgs e)
    {
        RootNavigation.Loaded -= PreviewTransform_RootNavigationLoaded;
        InitializePreviewTransformEditor();
    }

    private static Border CreatePreviewHandle(string tag, HorizontalAlignment horizontal, VerticalAlignment vertical, Thickness margin)
    {
        return new Border
        {
            Tag = tag,
            Width = 24,
            Height = 24,
            CornerRadius = new CornerRadius(12),
            Background = new SolidColorBrush(ColorHelper.FromArgb(250, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = horizontal,
            VerticalAlignment = vertical,
            Margin = margin,
        };
    }

    private void PreviewTransform_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_previewTransformCanvas is null)
            return;

        var point = e.GetCurrentPoint(_previewTransformCanvas);
        if (point.Properties.IsRightButtonPressed)
        {
            var cardIndex = HitTestPreviewCard(point.Position);
            if (cardIndex < 0)
            {
                DeactivatePreviewTransform();
                TimelineStatusText.Text = "Direct transform closed";
            }
            else
            {
                CardsList.SelectedIndex = cardIndex;
                CardsList.ScrollIntoView(Cards[cardIndex]);
                ActivatePreviewTransform(cardIndex);
            }
            e.Handled = true;
            return;
        }

        if (!_previewTransformActive || _previewTransformCardIndex < 0 || _previewTransformCardIndex >= Cards.Count || !point.Properties.IsLeftButtonPressed)
            return;

        var card = Cards[_previewTransformCardIndex];
        if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
            return;

        var tag = (e.OriginalSource as FrameworkElement)?.Tag as string;
        _previewTransformMode = tag switch
        {
            "preview-rotate" => PreviewTransformMode.Rotate,
            "preview-resize-nw" or "preview-resize-ne" or "preview-resize-sw" or "preview-resize-se" => PreviewTransformMode.Scale,
            _ => PreviewTransformMode.Move,
        };

        _previewTransformPointerId = e.Pointer.PointerId;
        _previewTransformPointerStart = point.Position;
        _previewStartX = card.ImageX;
        _previewStartY = card.ImageY;
        _previewStartScale = card.ImageScale;
        _previewStartRotation = card.ImageRotation;
        _previewWorkingX = _previewStartX;
        _previewWorkingY = _previewStartY;
        _previewWorkingScale = _previewStartScale;
        _previewWorkingRotation = _previewStartRotation;

        var center = PreviewImageCenter(_previewTransformCardIndex, _previewWorkingX, _previewWorkingY);
        _previewStartDistance = Math.Max(1, DistancePreview(point.Position, center));
        _previewRotationOffset = _previewStartRotation - AnglePreview(point.Position, center);

        _previewTransformCanvas.CapturePointer(e.Pointer);
        e.Handled = true;
    }

    private void PreviewTransform_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_previewTransformMode == PreviewTransformMode.None || _previewTransformCanvas is null || e.Pointer.PointerId != _previewTransformPointerId)
            return;

        var point = e.GetCurrentPoint(_previewTransformCanvas).Position;
        var renderer = _legacyRenderer;
        var sx = renderer is null ? 0.5 : PreviewCanvasWidth / Math.Max(1.0, renderer.ReferenceWidth);
        var sy = renderer is null ? 0.5 : PreviewCanvasHeight / Math.Max(1.0, renderer.ReferenceHeight);
        var center = PreviewImageCenter(_previewTransformCardIndex, _previewStartX, _previewStartY);

        switch (_previewTransformMode)
        {
            case PreviewTransformMode.Move:
                _previewWorkingX = Math.Clamp(_previewStartX + (point.X - _previewTransformPointerStart.X) / sx, -4000, 4000);
                _previewWorkingY = Math.Clamp(_previewStartY + (point.Y - _previewTransformPointerStart.Y) / sy, -4000, 4000);
                break;
            case PreviewTransformMode.Scale:
                _previewWorkingScale = Math.Clamp(_previewStartScale * DistancePreview(point, center) / _previewStartDistance, 0.05, 12.0);
                break;
            case PreviewTransformMode.Rotate:
                _previewWorkingRotation = NormalizePreviewDegrees(AnglePreview(point, center) + _previewRotationOffset);
                break;
        }

        RefreshPreviewTransformOverlay(useWorkingValues: true);
        e.Handled = true;
    }

    private async void PreviewTransform_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_previewTransformMode == PreviewTransformMode.None || e.Pointer.PointerId != _previewTransformPointerId)
            return;

        _previewTransformCanvas?.ReleasePointerCapture(e.Pointer);
        _previewTransformMode = PreviewTransformMode.None;

        if (_previewTransformCardIndex >= 0 && _previewTransformCardIndex < Cards.Count)
        {
            var card = Cards[_previewTransformCardIndex];
            card.ImageX = _previewWorkingX;
            card.ImageY = _previewWorkingY;
            card.ImageScale = _previewWorkingScale;
            card.ImageRotation = _previewWorkingRotation;
            RefreshArtworkManipulator();
            SyncPrecisionTransformBoxes(card);
            ScheduleThumbnailRefresh();
            ScheduleWorkspaceSave();
            await RenderCurrentFrameAsync();
            RefreshPreviewTransformOverlay();
            TimelineStatusText.Text = $"Direct transform · {card.Title} · right-click another card to switch";
        }

        e.Handled = true;
    }

    private void PreviewTransform_PointerCanceled(object sender, PointerRoutedEventArgs e) => CancelPreviewTransformDrag();
    private void PreviewTransform_PointerCaptureLost(object sender, PointerRoutedEventArgs e) => CancelPreviewTransformDrag();

    private void CancelPreviewTransformDrag()
    {
        _previewTransformMode = PreviewTransformMode.None;
        _previewWorkingX = _previewStartX;
        _previewWorkingY = _previewStartY;
        _previewWorkingScale = _previewStartScale;
        _previewWorkingRotation = _previewStartRotation;
        RefreshPreviewTransformOverlay();
    }

    private void ActivatePreviewTransform(int cardIndex)
    {
        _previewTransformActive = true;
        _previewTransformCardIndex = cardIndex;
        RefreshPreviewTransformOverlay();
        if (_previewTransformCanvas?.Tag is Border hintBorder)
            hintBorder.Visibility = Visibility.Visible;
        if (_previewTransformHint is not null)
            _previewTransformHint.Text = "Drag artwork to move · corner handles resize · ↻ rotates · right-click blank to exit";
        TimelineStatusText.Text = $"Direct transform · {Cards[cardIndex].Title}";
    }

    private void DeactivatePreviewTransform()
    {
        _previewTransformActive = false;
        _previewTransformCardIndex = -1;
        _previewTransformMode = PreviewTransformMode.None;
        if (_previewArtworkSlotFrame is not null) _previewArtworkSlotFrame.Visibility = Visibility.Collapsed;
        if (_previewTransformAdorner is not null) _previewTransformAdorner.Visibility = Visibility.Collapsed;
        if (_previewTransformCanvas?.Tag is Border hintBorder) hintBorder.Visibility = Visibility.Collapsed;
    }

    private int HitTestPreviewCard(Point point)
    {
        if (_legacyRenderer is null || Cards.Count == 0)
            return CardsList.SelectedIndex;

        var sx = PreviewCanvasWidth / Math.Max(1.0, _legacyRenderer.ReferenceWidth);
        var sy = PreviewCanvasHeight / Math.Max(1.0, _legacyRenderer.ReferenceHeight);
        var referenceX = point.X / sx;
        var referenceY = point.Y / sy;
        if (referenceY < 0 || referenceY > PreviewImageHeight)
            return -1;

        for (var index = 0; index < Cards.Count; index++)
        {
            var slotX = EstimatePreviewSlotX(index);
            var left = slotX + PreviewBodyInset;
            if (referenceX >= left && referenceX <= left + PreviewBodyWidth)
                return index;
        }
        return -1;
    }

    private double EstimatePreviewSlotX(int cardIndex)
    {
        var renderer = _legacyRenderer;
        if (renderer is null)
            return cardIndex * PreviewSlotPitch;

        var project = BuildProject();
        var frame = (int)Math.Round(ProjectFrameSlider.Value);

        if (renderer.Engine.Equals("ribbon-exact", StringComparison.OrdinalIgnoreCase))
        {
            if (Cards.Count <= 4)
                return cardIndex * PreviewSlotPitch;

            var frame4 = renderer.PreviewFrameForCard(project, 4);
            var frame5 = Cards.Count > 5 ? renderer.PreviewFrameForCard(project, 5) : frame4 + 120;
            var step = Math.Max(1, frame5 - frame4);
            var continuousStart = frame4 - step / 2.0;
            if (frame < continuousStart)
                return cardIndex * PreviewSlotPitch;

            var anchor = Math.Clamp(4 + (int)Math.Round((frame - frame4) / (double)step), 4, Cards.Count - 1);
            var anchorFrame = renderer.PreviewFrameForCard(project, anchor);
            var anchorX = 3.5 * PreviewSlotPitch - ((frame - anchorFrame) / (double)step) * PreviewSlotPitch;
            return anchorX + (cardIndex - anchor) * PreviewSlotPitch;
        }

        if (renderer.Engine.Equals("native-standard", StringComparison.OrdinalIgnoreCase) && Cards.Count > 1)
        {
            var frame0 = renderer.PreviewFrameForCard(project, 0);
            var frame1 = renderer.PreviewFrameForCard(project, 1);
            var step = Math.Max(1, frame1 - frame0);
            return cardIndex * PreviewSlotPitch - frame / (double)step * PreviewSlotPitch;
        }

        var selected = CardsList.SelectedIndex >= 0 ? CardsList.SelectedIndex : cardIndex;
        return (renderer.ReferenceWidth / 2.0 - PreviewBodyWidth / 2.0 - PreviewBodyInset) + (cardIndex - selected) * PreviewSlotPitch;
    }

    private void RefreshPreviewTransformOverlay(bool useWorkingValues = false)
    {
        if (!_previewTransformActive || _previewArtworkSlotFrame is null || _previewTransformAdorner is null || _previewTransformCardIndex < 0 || _previewTransformCardIndex >= Cards.Count)
            return;

        var renderer = _legacyRenderer;
        var sx = renderer is null ? 0.5 : PreviewCanvasWidth / Math.Max(1.0, renderer.ReferenceWidth);
        var sy = renderer is null ? 0.5 : PreviewCanvasHeight / Math.Max(1.0, renderer.ReferenceHeight);
        var card = Cards[_previewTransformCardIndex];
        var slotX = EstimatePreviewSlotX(_previewTransformCardIndex);
        var bodyLeft = slotX + PreviewBodyInset;

        if (bodyLeft + PreviewBodyWidth < 0 || bodyLeft > (renderer?.ReferenceWidth ?? 1920))
        {
            _previewArtworkSlotFrame.Visibility = Visibility.Collapsed;
            _previewTransformAdorner.Visibility = Visibility.Collapsed;
            return;
        }

        _previewArtworkSlotFrame.Visibility = Visibility.Visible;
        _previewArtworkSlotFrame.Width = PreviewBodyWidth * sx;
        _previewArtworkSlotFrame.Height = PreviewImageHeight * sy;
        Canvas.SetLeft(_previewArtworkSlotFrame, bodyLeft * sx);
        Canvas.SetTop(_previewArtworkSlotFrame, 0);

        if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
        {
            _previewTransformAdorner.Visibility = Visibility.Collapsed;
            return;
        }

        try
        {
            using var bitmap = SKBitmap.Decode(card.ImagePath);
            if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0)
            {
                _previewTransformAdorner.Visibility = Visibility.Collapsed;
                return;
            }

            var imageX = useWorkingValues ? _previewWorkingX : card.ImageX;
            var imageY = useWorkingValues ? _previewWorkingY : card.ImageY;
            var imageScale = useWorkingValues ? _previewWorkingScale : card.ImageScale;
            var imageRotation = useWorkingValues ? _previewWorkingRotation : card.ImageRotation;

            var cropWidth = bitmap.Width * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropLeft, 0, .95) - Math.Clamp(card.ImageCropRight, 0, .95));
            var cropHeight = bitmap.Height * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropTop, 0, .95) - Math.Clamp(card.ImageCropBottom, 0, .95));
            var baseScale = Math.Max(PreviewBodyWidth / cropWidth, PreviewImageHeight / cropHeight);
            var scale = baseScale * Math.Clamp(imageScale, .05, 12);
            var width = Math.Max(1, cropWidth * scale * sx);
            var height = Math.Max(1, cropHeight * scale * sy);
            var centerX = (bodyLeft + PreviewBodyWidth / 2 + imageX) * sx;
            var centerY = (PreviewImageHeight / 2 + imageY) * sy;

            _previewTransformAdorner.Visibility = Visibility.Visible;
            _previewTransformAdorner.Width = width;
            _previewTransformAdorner.Height = height;
            Canvas.SetLeft(_previewTransformAdorner, centerX - width / 2);
            Canvas.SetTop(_previewTransformAdorner, centerY - height / 2);
            _previewTransformAdorner.RenderTransform = new RotateTransform { Angle = imageRotation };
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not draw main-preview transform handles", ex);
            _previewTransformAdorner.Visibility = Visibility.Collapsed;
        }
    }

    private Point PreviewImageCenter(int cardIndex, double imageX, double imageY)
    {
        var renderer = _legacyRenderer;
        var sx = renderer is null ? 0.5 : PreviewCanvasWidth / Math.Max(1.0, renderer.ReferenceWidth);
        var sy = renderer is null ? 0.5 : PreviewCanvasHeight / Math.Max(1.0, renderer.ReferenceHeight);
        var bodyLeft = EstimatePreviewSlotX(cardIndex) + PreviewBodyInset;
        return new Point(
            (bodyLeft + PreviewBodyWidth / 2 + imageX) * sx,
            (PreviewImageHeight / 2 + imageY) * sy);
    }

    private static double DistancePreview(Point a, Point b)
    {
        var dx = a.X - b.X;
        var dy = a.Y - b.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }

    private static double AnglePreview(Point point, Point center) =>
        Math.Atan2(point.Y - center.Y, point.X - center.X) * 180.0 / Math.PI;

    private static double NormalizePreviewDegrees(double angle)
    {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
