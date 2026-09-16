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
    private enum ReliablePreviewDragMode
    {
        None,
        Move,
        Scale,
        Rotate,
    }

    private const double ReliablePreviewWidth = 960.0;
    private const double ReliablePreviewHeight = 540.0;

    private Canvas? _reliablePreviewCanvas;
    private Rectangle? _reliablePreviewSlot;
    private Grid? _reliablePreviewAdorner;
    private Image? _reliablePreviewArtworkGhost;
    private Border? _reliablePreviewHint;
    private bool _reliablePreviewActive;
    private int _reliablePreviewCardIndex = -1;
    private ReliablePreviewDragMode _reliablePreviewDragMode;
    private uint _reliablePreviewPointerId;
    private Point _reliablePreviewPointerStart;
    private double _reliableStartX;
    private double _reliableStartY;
    private double _reliableStartScale;
    private double _reliableStartRotation;
    private double _reliableWorkX;
    private double _reliableWorkY;
    private double _reliableWorkScale;
    private double _reliableWorkRotation;
    private double _reliableStartDistance;
    private double _reliableRotationOffset;

    internal void InitializeReliablePreviewTransformEditor()
    {
        if (_reliablePreviewCanvas is not null)
            return;

        if (RenderedFrameImage.Parent is not Grid previewGrid)
        {
            RootNavigation.Loaded += ReliablePreviewTransform_RootNavigationLoaded;
            return;
        }

        RootNavigation.Loaded -= ReliablePreviewTransform_RootNavigationLoaded;

        var canvas = new Canvas
        {
            Width = ReliablePreviewWidth,
            Height = ReliablePreviewHeight,
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 0, 0, 0)),
        };
        canvas.PointerPressed += ReliablePreviewTransform_PointerPressed;
        canvas.PointerMoved += ReliablePreviewTransform_PointerMoved;
        canvas.PointerReleased += ReliablePreviewTransform_PointerReleased;
        canvas.PointerCanceled += ReliablePreviewTransform_PointerCanceled;
        canvas.PointerCaptureLost += ReliablePreviewTransform_PointerCaptureLost;
        _reliablePreviewCanvas = canvas;

        _reliablePreviewSlot = new Rectangle
        {
            Visibility = Visibility.Collapsed,
            Stroke = new SolidColorBrush(ColorHelper.FromArgb(255, 255, 212, 0)),
            StrokeThickness = 3,
            StrokeDashArray = [8, 5],
            Fill = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            IsHitTestVisible = false,
        };
        canvas.Children.Add(_reliablePreviewSlot);

        _reliablePreviewAdorner = new Grid
        {
            Visibility = Visibility.Collapsed,
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            RenderTransformOrigin = new Point(0.5, 0.5),
        };

        _reliablePreviewArtworkGhost = new Image
        {
            Stretch = Stretch.Fill,
            Opacity = 0.28,
            IsHitTestVisible = false,
        };
        _reliablePreviewAdorner.Children.Add(_reliablePreviewArtworkGhost);
        _reliablePreviewAdorner.Children.Add(new Border
        {
            BorderBrush = new SolidColorBrush(Colors.White),
            BorderThickness = new Thickness(3),
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            IsHitTestVisible = false,
        });
        _reliablePreviewAdorner.Children.Add(CreateReliablePreviewHandle("reliable-resize-nw", HorizontalAlignment.Left, VerticalAlignment.Top, new Thickness(-12, -12, 0, 0)));
        _reliablePreviewAdorner.Children.Add(CreateReliablePreviewHandle("reliable-resize-ne", HorizontalAlignment.Right, VerticalAlignment.Top, new Thickness(0, -12, -12, 0)));
        _reliablePreviewAdorner.Children.Add(CreateReliablePreviewHandle("reliable-resize-sw", HorizontalAlignment.Left, VerticalAlignment.Bottom, new Thickness(-12, 0, 0, -12)));
        _reliablePreviewAdorner.Children.Add(CreateReliablePreviewHandle("reliable-resize-se", HorizontalAlignment.Right, VerticalAlignment.Bottom, new Thickness(0, 0, -12, -12)));

        var rotate = new Border
        {
            Tag = "reliable-rotate",
            Width = 36,
            Height = 36,
            CornerRadius = new CornerRadius(18),
            Background = new SolidColorBrush(ColorHelper.FromArgb(252, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 18, 0, 0),
            Child = new TextBlock
            {
                Text = "↻",
                Foreground = new SolidColorBrush(Colors.Black),
                FontSize = 22,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                IsHitTestVisible = false,
            },
        };
        _reliablePreviewAdorner.Children.Add(rotate);
        canvas.Children.Add(_reliablePreviewAdorner);

        _reliablePreviewHint = new Border
        {
            Visibility = Visibility.Collapsed,
            Background = new SolidColorBrush(ColorHelper.FromArgb(225, 24, 24, 24)),
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(120, 255, 255, 255)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(10, 6, 10, 6),
            Child = new TextBlock
            {
                Text = "Right-click a visible card · drag to move · corners resize · ↻ rotates",
                FontSize = 12,
                Foreground = new SolidColorBrush(Colors.White),
            },
            IsHitTestVisible = false,
        };
        Canvas.SetLeft(_reliablePreviewHint, 12);
        Canvas.SetTop(_reliablePreviewHint, ReliablePreviewHeight - 42);
        canvas.Children.Add(_reliablePreviewHint);

        previewGrid.Children.Add(canvas);
        Canvas.SetZIndex(canvas, 120);

        CardsList.SelectionChanged += (_, _) =>
        {
            if (!_reliablePreviewActive) return;
            if (CardsList.SelectedIndex < 0) return;
            _reliablePreviewCardIndex = CardsList.SelectedIndex;
            RefreshReliablePreviewTransformOverlay();
        };
        ProjectFrameSlider.ValueChanged += (_, _) =>
        {
            if (_reliablePreviewActive)
                RefreshReliablePreviewTransformOverlay();
        };

        TimelineStatusText.Text = _legacyRenderer is null
            ? TimelineStatusText.Text
            : "Right-click a visible preview card to transform its artwork";
    }

    private void ReliablePreviewTransform_RootNavigationLoaded(object sender, RoutedEventArgs e)
    {
        RootNavigation.Loaded -= ReliablePreviewTransform_RootNavigationLoaded;
        InitializeReliablePreviewTransformEditor();
    }

    private static Border CreateReliablePreviewHandle(string tag, HorizontalAlignment horizontal, VerticalAlignment vertical, Thickness margin)
    {
        return new Border
        {
            Tag = tag,
            Width = 26,
            Height = 26,
            CornerRadius = new CornerRadius(13),
            Background = new SolidColorBrush(ColorHelper.FromArgb(252, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = horizontal,
            VerticalAlignment = vertical,
            Margin = margin,
        };
    }

    private void ReliablePreviewTransform_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_reliablePreviewCanvas is null)
            return;

        var point = e.GetCurrentPoint(_reliablePreviewCanvas);
        if (point.Properties.IsRightButtonPressed)
        {
            var cardIndex = ReliableHitTestPreviewCard(point.Position);
            if (cardIndex < 0)
            {
                DeactivateReliablePreviewTransform();
                TimelineStatusText.Text = "Direct transform closed";
            }
            else
            {
                CardsList.SelectedIndex = cardIndex;
                CardsList.ScrollIntoView(Cards[cardIndex]);
                ActivateReliablePreviewTransform(cardIndex);
            }
            e.Handled = true;
            return;
        }

        if (!_reliablePreviewActive || _reliablePreviewCardIndex < 0 || _reliablePreviewCardIndex >= Cards.Count || !point.Properties.IsLeftButtonPressed)
            return;

        var card = Cards[_reliablePreviewCardIndex];
        if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
            return;

        var tag = (e.OriginalSource as FrameworkElement)?.Tag as string;
        _reliablePreviewDragMode = tag switch
        {
            "reliable-rotate" => ReliablePreviewDragMode.Rotate,
            "reliable-resize-nw" or "reliable-resize-ne" or "reliable-resize-sw" or "reliable-resize-se" => ReliablePreviewDragMode.Scale,
            _ => ReliablePreviewDragMode.Move,
        };

        _reliablePreviewPointerId = e.Pointer.PointerId;
        _reliablePreviewPointerStart = point.Position;
        _reliableStartX = card.ImageX;
        _reliableStartY = card.ImageY;
        _reliableStartScale = card.ImageScale;
        _reliableStartRotation = card.ImageRotation;
        _reliableWorkX = _reliableStartX;
        _reliableWorkY = _reliableStartY;
        _reliableWorkScale = _reliableStartScale;
        _reliableWorkRotation = _reliableStartRotation;

        var center = ReliablePreviewImageCenter(_reliablePreviewCardIndex, _reliableWorkX, _reliableWorkY);
        _reliableStartDistance = Math.Max(1, ReliableDistance(point.Position, center));
        _reliableRotationOffset = _reliableStartRotation - ReliableAngle(point.Position, center);

        _reliablePreviewCanvas.CapturePointer(e.Pointer);
        e.Handled = true;
    }

    private void ReliablePreviewTransform_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_reliablePreviewDragMode == ReliablePreviewDragMode.None || _reliablePreviewCanvas is null || e.Pointer.PointerId != _reliablePreviewPointerId)
            return;

        var point = e.GetCurrentPoint(_reliablePreviewCanvas).Position;
        var (sx, sy) = ReliablePreviewScale();
        var center = ReliablePreviewImageCenter(_reliablePreviewCardIndex, _reliableStartX, _reliableStartY);

        switch (_reliablePreviewDragMode)
        {
            case ReliablePreviewDragMode.Move:
                _reliableWorkX = Math.Clamp(_reliableStartX + (point.X - _reliablePreviewPointerStart.X) / sx, -4000, 4000);
                _reliableWorkY = Math.Clamp(_reliableStartY + (point.Y - _reliablePreviewPointerStart.Y) / sy, -4000, 4000);
                break;
            case ReliablePreviewDragMode.Scale:
                _reliableWorkScale = Math.Clamp(_reliableStartScale * ReliableDistance(point, center) / _reliableStartDistance, 0.05, 12.0);
                break;
            case ReliablePreviewDragMode.Rotate:
                _reliableWorkRotation = NormalizeReliablePreviewDegrees(ReliableAngle(point, center) + _reliableRotationOffset);
                break;
        }

        RefreshReliablePreviewTransformOverlay(useWorkingValues: true);
        e.Handled = true;
    }

    private async void ReliablePreviewTransform_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_reliablePreviewDragMode == ReliablePreviewDragMode.None || e.Pointer.PointerId != _reliablePreviewPointerId)
            return;

        _reliablePreviewCanvas?.ReleasePointerCapture(e.Pointer);
        _reliablePreviewDragMode = ReliablePreviewDragMode.None;

        if (_reliablePreviewCardIndex >= 0 && _reliablePreviewCardIndex < Cards.Count)
        {
            var card = Cards[_reliablePreviewCardIndex];
            card.ImageX = _reliableWorkX;
            card.ImageY = _reliableWorkY;
            card.ImageScale = _reliableWorkScale;
            card.ImageRotation = _reliableWorkRotation;
            RefreshArtworkManipulator();
            SyncPrecisionTransformBoxes(card);
            ScheduleThumbnailRefresh();
            ScheduleWorkspaceSave();
            await RenderCurrentFrameAsync();
            RefreshReliablePreviewTransformOverlay();
            TimelineStatusText.Text = $"Direct transform · {card.Title}";
        }

        e.Handled = true;
    }

    private void ReliablePreviewTransform_PointerCanceled(object sender, PointerRoutedEventArgs e) => CancelReliablePreviewDrag();
    private void ReliablePreviewTransform_PointerCaptureLost(object sender, PointerRoutedEventArgs e) => CancelReliablePreviewDrag();

    private void CancelReliablePreviewDrag()
    {
        _reliablePreviewDragMode = ReliablePreviewDragMode.None;
        _reliableWorkX = _reliableStartX;
        _reliableWorkY = _reliableStartY;
        _reliableWorkScale = _reliableStartScale;
        _reliableWorkRotation = _reliableStartRotation;
        RefreshReliablePreviewTransformOverlay();
    }

    private void ActivateReliablePreviewTransform(int cardIndex)
    {
        _reliablePreviewActive = true;
        _reliablePreviewCardIndex = cardIndex;
        _reliablePreviewHint?.SetValue(UIElement.VisibilityProperty, Visibility.Visible);
        RefreshReliablePreviewTransformOverlay();
        TimelineStatusText.Text = $"Direct transform · {Cards[cardIndex].Title}";
    }

    private void DeactivateReliablePreviewTransform()
    {
        _reliablePreviewActive = false;
        _reliablePreviewCardIndex = -1;
        _reliablePreviewDragMode = ReliablePreviewDragMode.None;
        if (_reliablePreviewSlot is not null) _reliablePreviewSlot.Visibility = Visibility.Collapsed;
        if (_reliablePreviewAdorner is not null) _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
        if (_reliablePreviewHint is not null) _reliablePreviewHint.Visibility = Visibility.Collapsed;
    }

    private int ReliableHitTestPreviewCard(Point point)
    {
        var renderer = _legacyRenderer;
        if (renderer is null || Cards.Count == 0)
            return CardsList.SelectedIndex;

        var (sx, sy) = ReliablePreviewScale();
        var referenceX = point.X / sx;
        var referenceY = point.Y / sy;
        if (referenceY < 0 || referenceY > renderer.ImageHeight)
            return -1;

        var positions = renderer.VisibleCardSlotXs(BuildProject(), (int)Math.Round(ProjectFrameSlider.Value));
        var hits = positions
            .Where(pair => referenceX >= pair.Value + renderer.BodyInset && referenceX <= pair.Value + renderer.BodyInset + renderer.BodyWidth)
            .OrderBy(pair => Math.Abs(referenceX - (pair.Value + renderer.BodyInset + renderer.BodyWidth / 2.0)))
            .ToArray();
        if (hits.Length > 0)
            return hits[0].Key;

        return -1;
    }

    private double? ReliableSlotX(int cardIndex)
    {
        var renderer = _legacyRenderer;
        if (renderer is null)
            return null;

        var positions = renderer.VisibleCardSlotXs(BuildProject(), (int)Math.Round(ProjectFrameSlider.Value));
        return positions.TryGetValue(cardIndex, out var x) ? x : null;
    }

    private void RefreshReliablePreviewTransformOverlay(bool useWorkingValues = false)
    {
        if (!_reliablePreviewActive || _reliablePreviewSlot is null || _reliablePreviewAdorner is null || _reliablePreviewCardIndex < 0 || _reliablePreviewCardIndex >= Cards.Count)
            return;

        var renderer = _legacyRenderer;
        if (renderer is null)
        {
            _reliablePreviewSlot.Visibility = Visibility.Collapsed;
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
            return;
        }

        var slotX = ReliableSlotX(_reliablePreviewCardIndex);
        if (slotX is null)
        {
            _reliablePreviewSlot.Visibility = Visibility.Collapsed;
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
            TimelineStatusText.Text = "Selected card is not visible at this frame";
            return;
        }

        var (sx, sy) = ReliablePreviewScale();
        var bodyLeft = slotX.Value + renderer.BodyInset;
        var card = Cards[_reliablePreviewCardIndex];

        _reliablePreviewSlot.Visibility = Visibility.Visible;
        _reliablePreviewSlot.Width = renderer.BodyWidth * sx;
        _reliablePreviewSlot.Height = renderer.ImageHeight * sy;
        Canvas.SetLeft(_reliablePreviewSlot, bodyLeft * sx);
        Canvas.SetTop(_reliablePreviewSlot, 0);

        if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
        {
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
            return;
        }

        try
        {
            using var bitmap = SKBitmap.Decode(card.ImagePath);
            if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0)
            {
                _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
                return;
            }

            var imageX = useWorkingValues ? _reliableWorkX : card.ImageX;
            var imageY = useWorkingValues ? _reliableWorkY : card.ImageY;
            var imageScale = useWorkingValues ? _reliableWorkScale : card.ImageScale;
            var imageRotation = useWorkingValues ? _reliableWorkRotation : card.ImageRotation;

            var cropWidth = bitmap.Width * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropLeft, 0, .95) - Math.Clamp(card.ImageCropRight, 0, .95));
            var cropHeight = bitmap.Height * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropTop, 0, .95) - Math.Clamp(card.ImageCropBottom, 0, .95));
            var baseScale = Math.Max(renderer.BodyWidth / cropWidth, renderer.ImageHeight / cropHeight);
            var scale = baseScale * Math.Clamp(imageScale, .05, 12);
            var width = Math.Max(1, cropWidth * scale * sx);
            var height = Math.Max(1, cropHeight * scale * sy);
            var centerX = (bodyLeft + renderer.BodyWidth / 2 + imageX) * sx;
            var centerY = (renderer.ImageHeight / 2 + imageY) * sy;

            _reliablePreviewArtworkGhost!.Source = card.Preview;
            _reliablePreviewAdorner.Visibility = Visibility.Visible;
            _reliablePreviewAdorner.Width = width;
            _reliablePreviewAdorner.Height = height;
            Canvas.SetLeft(_reliablePreviewAdorner, centerX - width / 2);
            Canvas.SetTop(_reliablePreviewAdorner, centerY - height / 2);
            _reliablePreviewAdorner.RenderTransform = new RotateTransform { Angle = imageRotation };
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not draw reliable preview transform handles", ex);
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
        }
    }

    private Point ReliablePreviewImageCenter(int cardIndex, double imageX, double imageY)
    {
        var renderer = _legacyRenderer;
        var slotX = ReliableSlotX(cardIndex);
        if (renderer is null || slotX is null)
            return new Point(ReliablePreviewWidth / 2, ReliablePreviewHeight / 2);

        var (sx, sy) = ReliablePreviewScale();
        var bodyLeft = slotX.Value + renderer.BodyInset;
        return new Point(
            (bodyLeft + renderer.BodyWidth / 2 + imageX) * sx,
            (renderer.ImageHeight / 2 + imageY) * sy);
    }

    private (double X, double Y) ReliablePreviewScale()
    {
        var renderer = _legacyRenderer;
        return renderer is null
            ? (0.5, 0.5)
            : (ReliablePreviewWidth / Math.Max(1.0, renderer.ReferenceWidth), ReliablePreviewHeight / Math.Max(1.0, renderer.ReferenceHeight));
    }

    private static double ReliableDistance(Point a, Point b)
    {
        var dx = a.X - b.X;
        var dy = a.Y - b.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }

    private static double ReliableAngle(Point point, Point center) =>
        Math.Atan2(point.Y - center.Y, point.X - center.X) * 180.0 / Math.PI;

    private static double NormalizeReliablePreviewDegrees(double angle)
    {
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;
        return angle;
    }
}
