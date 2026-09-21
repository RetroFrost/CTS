using Microsoft.UI;
using CubicalCompare.Core.Project;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using SkiaSharp;
using Windows.Foundation;
using Windows.System;

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

    private enum InlinePreviewTextField
    {
        None,
        Title,
        Description,
        BadgeHeader,
        Value,
    }

    private sealed record PreviewTextHit(
        int CardIndex,
        InlinePreviewTextField Field,
        CubicalCompare.Core.Renderer.PreviewTextRegion Region);

    private const double ReliablePreviewWidth = 960.0;
    private const double ReliablePreviewHeight = 540.0;

    private Canvas? _reliablePreviewCanvas;
    private Rectangle? _reliablePreviewSlot;
    private Grid? _reliablePreviewAdorner;
    private Image? _reliablePreviewArtworkGhost;
    private Border? _reliablePreviewHint;
    private Button? _reliablePreviewApplyAllButton;
    private TextBox? _inlinePreviewTextEditor;
    private bool _inlinePreviewTextLoading;
    private int _inlinePreviewTextCardIndex = -1;
    private InlinePreviewTextField _inlinePreviewTextField;
    private string _inlinePreviewTextOriginal = string.Empty;

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
    private bool _previewInteractionRenderRunning;
    private bool _previewInteractionRenderPending;
    private int _immersivePreviewMutationDepth;
    private bool _suppressCardSelectionPreviewSeek;

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
            Opacity = 0,
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
        Canvas.SetZIndex(_reliablePreviewAdorner, 180);

        _reliablePreviewApplyAllButton = new Button
        {
            Content = "Apply all",
            Visibility = Visibility.Collapsed,
            Padding = new Thickness(12, 6, 12, 6),
            Background = new SolidColorBrush(ColorHelper.FromArgb(238, 20, 20, 20)),
            Foreground = new SolidColorBrush(Colors.White),
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(150, 255, 255, 255)),
            BorderThickness = new Thickness(1),
        };
        _reliablePreviewApplyAllButton.Click += ReliablePreviewApplyAll_Click;
        canvas.Children.Add(_reliablePreviewApplyAllButton);
        Canvas.SetZIndex(_reliablePreviewApplyAllButton, 240);

        var transparentTextBrush = new SolidColorBrush(Colors.Transparent);
        _inlinePreviewTextEditor = new TextBox
        {
            Visibility = Visibility.Collapsed,
            Padding = new Thickness(0),
            MinWidth = 0,
            MinHeight = 0,
            BorderThickness = new Thickness(0),
            BorderBrush = transparentTextBrush,
            Background = transparentTextBrush,
            SelectionHighlightColor = new SolidColorBrush(ColorHelper.FromArgb(90, 0, 120, 215)),
            TextWrapping = TextWrapping.Wrap,
            UseSystemFocusVisuals = false,
        };
        // WinUI TextBox templates use state resources for hover/focus. Override all of
        // them so direct text editing remains visually part of the rendered preview.
        _inlinePreviewTextEditor.Resources["TextControlBackground"] = transparentTextBrush;
        _inlinePreviewTextEditor.Resources["TextControlBackgroundPointerOver"] = transparentTextBrush;
        _inlinePreviewTextEditor.Resources["TextControlBackgroundFocused"] = transparentTextBrush;
        _inlinePreviewTextEditor.Resources["TextControlBorderBrush"] = transparentTextBrush;
        _inlinePreviewTextEditor.Resources["TextControlBorderBrushPointerOver"] = transparentTextBrush;
        _inlinePreviewTextEditor.Resources["TextControlBorderBrushFocused"] = transparentTextBrush;
        _inlinePreviewTextEditor.TextChanged += InlinePreviewTextEditor_TextChanged;
        _inlinePreviewTextEditor.LostFocus += InlinePreviewTextEditor_LostFocus;
        _inlinePreviewTextEditor.KeyDown += InlinePreviewTextEditor_KeyDown;
        canvas.Children.Add(_inlinePreviewTextEditor);
        Canvas.SetZIndex(_inlinePreviewTextEditor, 320);

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
                Text = "Click artwork to move · corners resize · ↻ rotates · click visible text to edit it",
                FontSize = 12,
                Foreground = new SolidColorBrush(Colors.White),
            },
            IsHitTestVisible = false,
        };
        Canvas.SetLeft(_reliablePreviewHint, 12);
        Canvas.SetTop(_reliablePreviewHint, ReliablePreviewHeight - 42);
        canvas.Children.Add(_reliablePreviewHint);
        Canvas.SetZIndex(_reliablePreviewHint, 220);

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
            CloseInlinePreviewTextEditor();
            if (_reliablePreviewActive)
                RefreshReliablePreviewTransformOverlay();
        };

        TimelineStatusText.Text = _legacyRenderer is null
            ? TimelineStatusText.Text
            : "Click artwork or visible text directly in the preview";
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

        if (IsPreviewEditorChild(e.OriginalSource as DependencyObject))
            return;

        var point = e.GetCurrentPoint(_reliablePreviewCanvas);

        if (point.Properties.IsRightButtonPressed)
        {
            CloseInlinePreviewTextEditor();
            var artworkCard = ReliableHitTestPreviewArtwork(point.Position);
            if (artworkCard >= 0)
            {
                SelectPreviewCard(artworkCard);
                ActivateReliablePreviewTransform(artworkCard);
            }
            else
            {
                DeactivateReliablePreviewTransform();
                TimelineStatusText.Text = "Direct preview editing closed";
            }

            e.Handled = true;
            return;
        }

        if (!point.Properties.IsLeftButtonPressed)
            return;

        if (_reliablePreviewActive &&
            _reliablePreviewCardIndex >= 0 &&
            _reliablePreviewCardIndex < Cards.Count)
        {
            var handleMode = ReliablePreviewHandleModeAt(point.Position);
            if (handleMode != ReliablePreviewDragMode.None)
            {
                CloseInlinePreviewTextEditor();
                BeginReliablePreviewDrag(e, point.Position, _reliablePreviewCardIndex, handleMode);
                return;
            }
        }

        var textHit = ReliableHitTestPreviewText(point.Position);
        if (textHit is not null)
        {
            StopPreviewPlayback();
            DeactivateReliablePreviewTransform();
            SelectPreviewCard(textHit.CardIndex);
            OpenInlinePreviewTextEditor(textHit);
            e.Handled = true;
            return;
        }

        var imageCard = ReliableHitTestPreviewArtwork(point.Position);
        if (imageCard >= 0)
        {
            StopPreviewPlayback();
            CloseInlinePreviewTextEditor();
            SelectPreviewCard(imageCard);
            ActivateReliablePreviewTransform(imageCard);
            BeginReliablePreviewDrag(e, point.Position, imageCard, ReliablePreviewDragMode.Move);
            return;
        }

        CloseInlinePreviewTextEditor();
        if (_reliablePreviewActive)
            DeactivateReliablePreviewTransform();
    }

    private ReliablePreviewDragMode ReliablePreviewHandleModeAt(Point point)
    {
        if (_reliablePreviewAdorner is null ||
            _reliablePreviewAdorner.Visibility != Visibility.Visible ||
            _reliablePreviewAdorner.Width <= 0 ||
            _reliablePreviewAdorner.Height <= 0)
            return ReliablePreviewDragMode.None;

        var left = Canvas.GetLeft(_reliablePreviewAdorner);
        var top = Canvas.GetTop(_reliablePreviewAdorner);
        if (double.IsNaN(left) || double.IsNaN(top))
            return ReliablePreviewDragMode.None;

        var width = _reliablePreviewAdorner.Width;
        var height = _reliablePreviewAdorner.Height;
        var centerX = left + width / 2.0;
        var centerY = top + height / 2.0;

        // The entire adorner rotates with the artwork. Transform the pointer back
        // into the unrotated adorner coordinate space before testing the handles.
        var angle = _reliablePreviewAdorner.RenderTransform is RotateTransform rotate
            ? rotate.Angle
            : 0.0;
        var radians = -angle * Math.PI / 180.0;
        var dx = point.X - centerX;
        var dy = point.Y - centerY;
        var localX = dx * Math.Cos(radians) - dy * Math.Sin(radians) + width / 2.0;
        var localY = dx * Math.Sin(radians) + dy * Math.Cos(radians) + height / 2.0;

        static bool Near(double x, double y, double targetX, double targetY, double radius)
        {
            var hx = x - targetX;
            var hy = y - targetY;
            return hx * hx + hy * hy <= radius * radius;
        }

        // Use the actual small handle footprints. Never infer resize mode from
        // RoutedEventArgs.OriginalSource: WinUI documents that OriginalSource can
        // be a control-template part, which made ordinary artwork clicks inherit
        // a resize-handle route in practice.
        const double resizeRadius = 18.0;
        if (Near(localX, localY, 0, 0, resizeRadius) ||
            Near(localX, localY, width, 0, resizeRadius) ||
            Near(localX, localY, 0, height, resizeRadius) ||
            Near(localX, localY, width, height, resizeRadius))
            return ReliablePreviewDragMode.Scale;

        // Rotate handle is a 36 px circle centered 36 px below the top edge.
        if (Near(localX, localY, width / 2.0, 36.0, 20.0))
            return ReliablePreviewDragMode.Rotate;

        return ReliablePreviewDragMode.None;
    }

    private void BeginReliablePreviewDrag(PointerRoutedEventArgs e, Point point, int cardIndex, ReliablePreviewDragMode mode)
    {
        if (_reliablePreviewCanvas is null || cardIndex < 0 || cardIndex >= Cards.Count)
            return;

        var card = Cards[cardIndex];
        if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
            return;

        _reliablePreviewDragMode = mode;
        _reliablePreviewPointerId = e.Pointer.PointerId;
        _reliablePreviewPointerStart = point;
        _reliableStartX = card.ImageX;
        _reliableStartY = card.ImageY;
        _reliableStartScale = card.ImageScale;
        _reliableStartRotation = card.ImageRotation;
        _reliableWorkX = _reliableStartX;
        _reliableWorkY = _reliableStartY;
        _reliableWorkScale = _reliableStartScale;
        _reliableWorkRotation = _reliableStartRotation;

        var center = ReliablePreviewImageCenter(cardIndex, _reliableWorkX, _reliableWorkY);
        _reliableStartDistance = Math.Max(1, ReliableDistance(point, center));
        _reliableRotationOffset = _reliableStartRotation - ReliableAngle(point, center);

        _reliablePreviewCanvas.CapturePointer(e.Pointer);
        e.Handled = true;
    }

    private void ReliablePreviewTransform_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_reliablePreviewDragMode == ReliablePreviewDragMode.None ||
            _reliablePreviewCanvas is null ||
            e.Pointer.PointerId != _reliablePreviewPointerId ||
            _reliablePreviewCardIndex < 0 ||
            _reliablePreviewCardIndex >= Cards.Count)
            return;

        var point = e.GetCurrentPoint(_reliablePreviewCanvas).Position;
        var (sx, sy) = ReliablePreviewScale();
        var geometry = ReliablePreviewGeometry(_reliablePreviewCardIndex);
        if (geometry is null)
            return;

        var g = geometry.Value;
        var center = ReliablePreviewImageCenter(_reliablePreviewCardIndex, _reliableStartX, _reliableStartY);

        switch (_reliablePreviewDragMode)
        {
            case ReliablePreviewDragMode.Move:
                var refDx = (point.X - _reliablePreviewPointerStart.X) / sx;
                var refDy = (point.Y - _reliablePreviewPointerStart.Y) / sy;
                _reliableWorkX = Math.Clamp(
                    _reliableStartX + refDx / Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleX)),
                    -4000,
                    4000);
                _reliableWorkY = Math.Clamp(
                    _reliableStartY + refDy / Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleY)),
                    -4000,
                    4000);
                break;
            case ReliablePreviewDragMode.Scale:
                _reliableWorkScale = Math.Clamp(
                    _reliableStartScale * ReliableDistance(point, center) / _reliableStartDistance,
                    0.05,
                    12.0);
                break;
            case ReliablePreviewDragMode.Rotate:
                _reliableWorkRotation = NormalizeReliablePreviewDegrees(
                    ReliableAngle(point, center) + _reliableRotationOffset);
                break;
        }

        var card = Cards[_reliablePreviewCardIndex];
        card.ImageX = _reliableWorkX;
        card.ImageY = _reliableWorkY;
        card.ImageScale = _reliableWorkScale;
        card.ImageRotation = _reliableWorkRotation;

        RefreshArtworkManipulator();
        SyncPrecisionTransformBoxes(card);
        RefreshReliablePreviewTransformOverlay(useWorkingValues: true);
        ScheduleWorkspaceSave();
        QueuePreviewInteractionRender();
        e.Handled = true;
    }

    private async void ReliablePreviewTransform_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_reliablePreviewDragMode == ReliablePreviewDragMode.None || e.Pointer.PointerId != _reliablePreviewPointerId)
            return;

        // Mark the drag complete BEFORE releasing capture. WinUI can raise
        // PointerCaptureLost synchronously from ReleasePointerCapture; the old
        // order treated a normal mouse-up as a cancellation and restored the
        // starting transform.
        _reliablePreviewDragMode = ReliablePreviewDragMode.None;
        _reliablePreviewPointerId = 0;
        _reliablePreviewCanvas?.ReleasePointerCapture(e.Pointer);

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
            _previewInteractionRenderPending = true;
            await RenderCurrentFrameAsync();
            RefreshReliablePreviewTransformOverlay();
            TimelineStatusText.Text = $"Artwork selected · {card.Title} · drag, resize or rotate directly on the frame";
        }

        e.Handled = true;
    }

    private void ReliablePreviewTransform_PointerCanceled(object sender, PointerRoutedEventArgs e) => CancelReliablePreviewDrag();
    private void ReliablePreviewTransform_PointerCaptureLost(object sender, PointerRoutedEventArgs e) => CancelReliablePreviewDrag();

    private void CancelReliablePreviewDrag()
    {
        if (_reliablePreviewDragMode == ReliablePreviewDragMode.None)
            return;

        if (_reliablePreviewCardIndex >= 0 && _reliablePreviewCardIndex < Cards.Count)
        {
            var card = Cards[_reliablePreviewCardIndex];
            card.ImageX = _reliableStartX;
            card.ImageY = _reliableStartY;
            card.ImageScale = _reliableStartScale;
            card.ImageRotation = _reliableStartRotation;
            SyncPrecisionTransformBoxes(card);
            RefreshArtworkManipulator();
            QueuePreviewInteractionRender();
        }

        _reliablePreviewDragMode = ReliablePreviewDragMode.None;
        _reliablePreviewPointerId = 0;
        _reliableWorkX = _reliableStartX;
        _reliableWorkY = _reliableStartY;
        _reliableWorkScale = _reliableStartScale;
        _reliableWorkRotation = _reliableStartRotation;
        RefreshReliablePreviewTransformOverlay();
    }

    private void SelectPreviewCard(int cardIndex)
    {
        if (cardIndex < 0 || cardIndex >= Cards.Count)
            return;

        // Selecting a card from the rendered frame is an editor-selection action,
        // not a timeline seek. The list's ordinary selection handler intentionally
        // seeks to a representative frame, which is useful when clicking the list
        // but disastrous when clicking a card that is already visible at the
        // current timeline position.
        _suppressCardSelectionPreviewSeek = true;
        try
        {
            CardsList.SelectedIndex = cardIndex;
            CardsList.ScrollIntoView(Cards[cardIndex]);
        }
        finally
        {
            _suppressCardSelectionPreviewSeek = false;
        }
    }

    private void ActivateReliablePreviewTransform(int cardIndex)
    {
        _reliablePreviewActive = true;
        _reliablePreviewCardIndex = cardIndex;
        if (_reliablePreviewHint is not null)
            _reliablePreviewHint.Visibility = Visibility.Collapsed;
        if (_reliablePreviewApplyAllButton is not null)
        {
            _reliablePreviewApplyAllButton.Visibility = Visibility.Visible;
            _reliablePreviewApplyAllButton.IsEnabled = Cards.Count > 1;
        }
        RefreshReliablePreviewTransformOverlay();
        TimelineStatusText.Text = $"Artwork selected · {Cards[cardIndex].Title}";
    }

    private void DeactivateReliablePreviewTransform()
    {
        _reliablePreviewActive = false;
        _reliablePreviewCardIndex = -1;
        _reliablePreviewDragMode = ReliablePreviewDragMode.None;
        if (_reliablePreviewSlot is not null) _reliablePreviewSlot.Visibility = Visibility.Collapsed;
        if (_reliablePreviewAdorner is not null) _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
        if (_reliablePreviewHint is not null) _reliablePreviewHint.Visibility = Visibility.Collapsed;
        if (_reliablePreviewApplyAllButton is not null) _reliablePreviewApplyAllButton.Visibility = Visibility.Collapsed;
    }

    private int ReliableHitTestPreviewArtwork(Point point)
    {
        var renderer = _legacyRenderer;
        if (renderer is null || Cards.Count == 0)
            return -1;

        var project = BuildProject();
        var frame = (int)Math.Round(ProjectFrameSlider.Value);
        var (sx, sy) = ReliablePreviewScale();
        var referencePoint = new Point(point.X / sx, point.Y / sy);

        var candidates = Enumerable.Range(0, Cards.Count)
            .Select(index => renderer.TryGetPreviewCardGeometry(project, frame, index, out var geometry)
                ? (Index: index, Geometry: (CubicalCompare.Core.Renderer.PreviewCardGeometry?)geometry)
                : (Index: index, Geometry: (CubicalCompare.Core.Renderer.PreviewCardGeometry?)null))
            .Where(item => item.Geometry is not null)
            .OrderBy(item => Math.Abs(referencePoint.X - (item.Geometry!.Value.ArtworkX + item.Geometry.Value.ArtworkWidth / 2.0)));

        foreach (var item in candidates)
        {
            var g = item.Geometry!.Value;
            var card = Cards[item.Index];
            if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
                continue;

            if (referencePoint.X < g.ArtworkX ||
                referencePoint.X > g.ArtworkX + g.ArtworkWidth ||
                referencePoint.Y < g.ArtworkY ||
                referencePoint.Y > g.ArtworkY + g.ArtworkHeight)
                continue;

            try
            {
                using var bitmap = SKBitmap.Decode(card.ImagePath);
                if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0)
                    continue;

                var cropWidth = bitmap.Width * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropLeft, 0, .95) - Math.Clamp(card.ImageCropRight, 0, .95));
                var cropHeight = bitmap.Height * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropTop, 0, .95) - Math.Clamp(card.ImageCropBottom, 0, .95));

                var localScaleX = Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleX));
                var localScaleY = Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleY));
                var imageBase = ReliableImageBase(g);
                var localSlotWidth = imageBase.Width / localScaleX;
                var localSlotHeight = imageBase.Height / localScaleY;
                var baseScale = g.ArtworkCover
                    ? Math.Max(localSlotWidth / cropWidth, localSlotHeight / cropHeight)
                    : Math.Min(localSlotWidth / cropWidth, localSlotHeight / cropHeight);

                var imageScale = Math.Clamp(card.ImageScale, .05, 12);
                var halfWidth = cropWidth * baseScale * imageScale * localScaleX / 2.0;
                var halfHeight = cropHeight * baseScale * imageScale * localScaleY / 2.0;
                var centerX = imageBase.X + imageBase.Width / 2.0 + card.ImageX * g.ImageCoordinateScaleX;
                var centerY = imageBase.Y + imageBase.Height / 2.0 + card.ImageY * g.ImageCoordinateScaleY;

                var dx = referencePoint.X - centerX;
                var dy = referencePoint.Y - centerY;
                var radians = -card.ImageRotation * Math.PI / 180.0;
                var localX = dx * Math.Cos(radians) - dy * Math.Sin(radians);
                var localY = dx * Math.Sin(radians) + dy * Math.Cos(radians);
                if (Math.Abs(localX) <= halfWidth && Math.Abs(localY) <= halfHeight)
                    return item.Index;
            }
            catch (Exception ex)
            {
                App.WriteLog("Could not hit-test immersive preview artwork", ex);
            }
        }

        return -1;
    }

    private PreviewTextHit? ReliableHitTestPreviewText(Point point)
    {
        var renderer = _legacyRenderer;
        if (renderer is null || Cards.Count == 0)
            return null;

        var project = BuildProject();
        var frame = (int)Math.Round(ProjectFrameSlider.Value);
        var (sx, sy) = ReliablePreviewScale();
        var x = point.X / sx;
        var y = point.Y / sy;

        foreach (var region in renderer.PreviewTextRegions(project, frame)
                     .OrderBy(region => region.Width * region.Height))
        {
            if (region.CardIndex < 0 || region.CardIndex >= Cards.Count)
                continue;

            var field = ParseInlinePreviewField(region.Field);
            if (field == InlinePreviewTextField.None)
                continue;

            var bounds = new Rect(region.X, region.Y, region.Width, region.Height);
            if (ContainsRotated(bounds, region.Rotation, x, y))
                return new PreviewTextHit(region.CardIndex, field, region);
        }

        return null;
    }


    private static Rect ReliableDescriptionTextBounds(
        string text,
        double bodyLeft,
        double descriptionTop,
        double bodyWidth,
        double availableHeight,
        double preferredTextSize)
    {
        // The renderer paints description text at the top of its available area.
        // Do not treat the entire remaining card body as text: that made a huge
        // invisible editor hit target which stole clicks from transformed artwork.
        var leftInset = 17.0;
        var topInset = 8.0;
        var rightInset = 17.0;
        var bottomInset = 8.0;
        var textWidth = Math.Max(1.0, bodyWidth - leftInset - rightInset);
        var textHeightLimit = Math.Max(1.0, availableHeight - topInset - bottomInset);

        using var paint = new SKPaint
        {
            IsAntialias = true,
            TextSize = (float)Math.Max(12.0, preferredTextSize),
            Typeface = SKTypeface.Default,
        };

        var fontSize = (float)Math.Max(12.0, preferredTextSize);
        List<string> lines = [];
        while (fontSize >= 12)
        {
            paint.TextSize = fontSize;
            lines = ReliableWrapPreviewText(text, paint, (float)textWidth);
            if (lines.Count <= 5 && lines.Count * fontSize * 1.15f <= textHeightLimit)
                break;
            fontSize -= 1;
        }

        if (lines.Count == 0)
            lines.Add(text);

        var visibleLines = lines.Take(5).ToArray();
        var measuredWidth = visibleLines.Length == 0
            ? textWidth
            : Math.Min(textWidth, Math.Max(1.0, visibleLines.Max(line => (double)paint.MeasureText(line))));

        // Keep a small click/edit padding around the actual glyph lines only.
        var horizontalPadding = 10.0;
        var verticalPadding = 6.0;
        var hitWidth = Math.Min(textWidth, measuredWidth + horizontalPadding * 2);
        var hitHeight = Math.Min(
            textHeightLimit,
            Math.Max(fontSize * 1.35, visibleLines.Length * fontSize * 1.15 + verticalPadding * 2));

        return new Rect(
            bodyLeft + leftInset,
            descriptionTop + topInset,
            Math.Max(1, hitWidth),
            Math.Max(1, hitHeight));
    }

    private static List<string> ReliableWrapPreviewText(string text, SKPaint paint, float width)
    {
        var output = new List<string>();
        foreach (var paragraph in text.Replace("\r", "").Split('\n'))
        {
            var current = "";
            foreach (var word in paragraph.Split(' ', StringSplitOptions.RemoveEmptyEntries))
            {
                var candidate = current.Length == 0 ? word : current + " " + word;
                if (paint.MeasureText(candidate) <= width || current.Length == 0)
                {
                    current = candidate;
                }
                else
                {
                    output.Add(current);
                    current = word;
                }
            }

            if (current.Length > 0)
                output.Add(current);
        }

        return output;
    }

    private static bool Contains(Rect rect, double x, double y) =>
        x >= rect.X && x <= rect.X + rect.Width && y >= rect.Y && y <= rect.Y + rect.Height;

    private static bool ContainsRotated(Rect rect, double rotation, double x, double y)
    {
        if (Math.Abs(rotation) <= .001)
            return Contains(rect, x, y);

        var cx = rect.X + rect.Width / 2;
        var cy = rect.Y + rect.Height / 2;
        var radians = -rotation * Math.PI / 180.0;
        var dx = x - cx;
        var dy = y - cy;
        var localX = dx * Math.Cos(radians) - dy * Math.Sin(radians) + cx;
        var localY = dx * Math.Sin(radians) + dy * Math.Cos(radians) + cy;
        return Contains(rect, localX, localY);
    }

    private CubicalCompare.Core.Renderer.PreviewCardGeometry? ReliablePreviewGeometry(int cardIndex)
    {
        var renderer = _legacyRenderer;
        if (renderer is null)
            return null;

        var project = BuildProject();
        var frame = (int)Math.Round(ProjectFrameSlider.Value);
        return renderer.TryGetPreviewCardGeometry(project, frame, cardIndex, out var geometry)
            ? geometry
            : null;
    }

    private static (double X, double Y, double Width, double Height) ReliableImageBase(
        CubicalCompare.Core.Renderer.PreviewCardGeometry geometry)
    {
        var x = double.IsNaN(geometry.ImageBaseX) ? geometry.ArtworkX : geometry.ImageBaseX;
        var y = double.IsNaN(geometry.ImageBaseY) ? geometry.ArtworkY : geometry.ImageBaseY;
        var width = double.IsNaN(geometry.ImageBaseWidth) || geometry.ImageBaseWidth <= 0
            ? geometry.ArtworkWidth
            : geometry.ImageBaseWidth;
        var height = double.IsNaN(geometry.ImageBaseHeight) || geometry.ImageBaseHeight <= 0
            ? geometry.ArtworkHeight
            : geometry.ImageBaseHeight;
        return (x, y, width, height);
    }

    private void RefreshReliablePreviewTransformOverlay(bool useWorkingValues = false)
    {
        if (!_reliablePreviewActive ||
            _reliablePreviewSlot is null ||
            _reliablePreviewAdorner is null ||
            _reliablePreviewCardIndex < 0 ||
            _reliablePreviewCardIndex >= Cards.Count)
            return;

        var geometry = ReliablePreviewGeometry(_reliablePreviewCardIndex);
        if (_legacyRenderer is null || geometry is null)
        {
            _reliablePreviewSlot.Visibility = Visibility.Collapsed;
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
            if (_reliablePreviewApplyAllButton is not null)
                _reliablePreviewApplyAllButton.Visibility = Visibility.Collapsed;
            TimelineStatusText.Text = "Selected card is not visible at this frame";
            return;
        }

        var g = geometry.Value;
        var (sx, sy) = ReliablePreviewScale();
        var card = Cards[_reliablePreviewCardIndex];

        _reliablePreviewSlot.Visibility = Visibility.Visible;
        _reliablePreviewSlot.Width = g.ArtworkWidth * sx;
        _reliablePreviewSlot.Height = g.ArtworkHeight * sy;
        Canvas.SetLeft(_reliablePreviewSlot, g.ArtworkX * sx);
        Canvas.SetTop(_reliablePreviewSlot, g.ArtworkY * sy);

        if (_reliablePreviewApplyAllButton is not null)
        {
            _reliablePreviewApplyAllButton.Visibility = Visibility.Visible;
            _reliablePreviewApplyAllButton.IsEnabled = Cards.Count > 1;
            Canvas.SetLeft(_reliablePreviewApplyAllButton, Math.Clamp((g.ArtworkX + g.ArtworkWidth) * sx - 88, 8, ReliablePreviewWidth - 96));
            Canvas.SetTop(_reliablePreviewApplyAllButton, Math.Clamp(g.ArtworkY * sy + 8, 8, ReliablePreviewHeight - 42));
        }

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
            var coordX = Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleX));
            var coordY = Math.Max(.0001, Math.Abs(g.ImageCoordinateScaleY));
            var imageBase = ReliableImageBase(g);
            var localSlotWidth = imageBase.Width / coordX;
            var localSlotHeight = imageBase.Height / coordY;
            var baseScale = g.ArtworkCover
                ? Math.Max(localSlotWidth / cropWidth, localSlotHeight / cropHeight)
                : Math.Min(localSlotWidth / cropWidth, localSlotHeight / cropHeight);
            var effective = baseScale * Math.Clamp(imageScale, .05, 12);

            var width = Math.Max(1, cropWidth * effective * coordX * sx);
            var height = Math.Max(1, cropHeight * effective * coordY * sy);
            var centerX = (imageBase.X + imageBase.Width / 2 + imageX * g.ImageCoordinateScaleX) * sx;
            var centerY = (imageBase.Y + imageBase.Height / 2 + imageY * g.ImageCoordinateScaleY) * sy;

            // Border/handles only. The rendered frame remains fully visible beneath them.
            _reliablePreviewArtworkGhost!.Source = null;
            _reliablePreviewAdorner.Visibility = Visibility.Visible;
            _reliablePreviewAdorner.Width = width;
            _reliablePreviewAdorner.Height = height;
            Canvas.SetLeft(_reliablePreviewAdorner, centerX - width / 2);
            Canvas.SetTop(_reliablePreviewAdorner, centerY - height / 2);
            _reliablePreviewAdorner.RenderTransform = new RotateTransform { Angle = imageRotation };
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not draw immersive preview transform handles", ex);
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
        }
    }

    private async void ReliablePreviewApplyAll_Click(object sender, RoutedEventArgs e)
    {
        if (_reliablePreviewCardIndex < 0 || _reliablePreviewCardIndex >= Cards.Count)
            return;

        await ApplyArtworkTransformToAllAsync(Cards[_reliablePreviewCardIndex]);
        RefreshReliablePreviewTransformOverlay();
    }

    private void OpenInlinePreviewTextEditor(PreviewTextHit hit)
    {
        if (_inlinePreviewTextEditor is null ||
            _legacyRenderer is null ||
            hit.CardIndex < 0 ||
            hit.CardIndex >= Cards.Count)
            return;

        var card = Cards[hit.CardIndex];
        var region = hit.Region;
        var (sx, sy) = ReliablePreviewScale();
        var bounds = new Rect(
            region.X * sx,
            region.Y * sy,
            Math.Max(1, region.Width * sx),
            Math.Max(1, region.Height * sy));

        _inlinePreviewTextCardIndex = hit.CardIndex;
        _inlinePreviewTextField = hit.Field;
        _inlinePreviewTextOriginal = GetInlinePreviewFieldValue(card, hit.Field);

        _inlinePreviewTextLoading = true;
        _inlinePreviewTextEditor.Text = _inlinePreviewTextOriginal;
        _inlinePreviewTextEditor.AcceptsReturn = hit.Field == InlinePreviewTextField.Description;
        _inlinePreviewTextEditor.TextWrapping = region.Wrap ? TextWrapping.Wrap : TextWrapping.NoWrap;
        _inlinePreviewTextEditor.HorizontalTextAlignment = region.HorizontalAlignment switch
        {
            "left" => TextAlignment.Left,
            "right" => TextAlignment.Right,
            _ => TextAlignment.Center,
        };
        _inlinePreviewTextEditor.VerticalContentAlignment = region.VerticalAlignment switch
        {
            "top" => VerticalAlignment.Top,
            "bottom" => VerticalAlignment.Bottom,
            _ => VerticalAlignment.Center,
        };
        _inlinePreviewTextEditor.FontSize = Math.Max(8, region.FontSize * sy);
        _inlinePreviewTextEditor.FontWeight = new global::Windows.UI.Text.FontWeight
        {
            Weight = region.Bold ? (ushort)700 : (ushort)400,
        };
        if (!string.IsNullOrWhiteSpace(RenderFontSelection.CurrentFamily))
            _inlinePreviewTextEditor.FontFamily = new FontFamily(RenderFontSelection.CurrentFamily);

        _inlinePreviewTextEditor.Foreground = new SolidColorBrush(ArgbColor(region.Argb));
        _inlinePreviewTextEditor.Background = new SolidColorBrush(Colors.Transparent);
        _inlinePreviewTextEditor.BorderBrush = new SolidColorBrush(Colors.Transparent);
        _inlinePreviewTextEditor.BorderThickness = new Thickness(0);
        _inlinePreviewTextEditor.Padding = new Thickness(0);
        _inlinePreviewTextEditor.Margin = new Thickness(0);
        _inlinePreviewTextEditor.Width = Math.Max(18, bounds.Width);
        _inlinePreviewTextEditor.Height = Math.Max(18, bounds.Height);
        _inlinePreviewTextEditor.RenderTransformOrigin = new Point(.5, .5);
        _inlinePreviewTextEditor.RenderTransform = Math.Abs(region.Rotation) > .001
            ? new RotateTransform { Angle = region.Rotation }
            : null;

        Canvas.SetLeft(_inlinePreviewTextEditor, Math.Clamp(bounds.X, 0, ReliablePreviewWidth - _inlinePreviewTextEditor.Width));
        Canvas.SetTop(_inlinePreviewTextEditor, Math.Clamp(bounds.Y, 0, ReliablePreviewHeight - _inlinePreviewTextEditor.Height));
        _inlinePreviewTextEditor.Visibility = Visibility.Visible;
        _inlinePreviewTextLoading = false;

        // Re-render once with an invisible placeholder in this exact field so the
        // caret/edit text replaces, rather than doubles over, the rendered glyphs.
        QueuePreviewInteractionRender();

        _inlinePreviewTextEditor.Focus(FocusState.Programmatic);
        _inlinePreviewTextEditor.SelectionStart = _inlinePreviewTextEditor.Text.Length;
        _inlinePreviewTextEditor.SelectionLength = 0;
        TimelineStatusText.Text = $"Editing {InlinePreviewFieldLabel(hit.Field)} directly on the rendered frame · Esc cancels";
    }

    private void InlinePreviewTextEditor_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_inlinePreviewTextLoading ||
            _inlinePreviewTextCardIndex < 0 ||
            _inlinePreviewTextCardIndex >= Cards.Count)
            return;

        _immersivePreviewMutationDepth++;
        try
        {
            SetInlinePreviewFieldValue(
                Cards[_inlinePreviewTextCardIndex],
                _inlinePreviewTextField,
                _inlinePreviewTextEditor?.Text ?? string.Empty);
        }
        finally
        {
            _immersivePreviewMutationDepth--;
        }

        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        QueuePreviewInteractionRender();
    }

    private void InlinePreviewTextEditor_LostFocus(object sender, RoutedEventArgs e)
    {
        if (_inlinePreviewTextEditor?.Visibility == Visibility.Visible)
            CloseInlinePreviewTextEditor();
    }

    private void InlinePreviewTextEditor_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (_inlinePreviewTextEditor is null)
            return;

        if (e.Key == VirtualKey.Escape)
        {
            if (_inlinePreviewTextCardIndex >= 0 && _inlinePreviewTextCardIndex < Cards.Count)
            {
                _inlinePreviewTextLoading = true;
                SetInlinePreviewFieldValue(Cards[_inlinePreviewTextCardIndex], _inlinePreviewTextField, _inlinePreviewTextOriginal);
                _inlinePreviewTextEditor.Text = _inlinePreviewTextOriginal;
                _inlinePreviewTextLoading = false;
                ScheduleWorkspaceSave();
                _ = RenderCurrentFrameAsync();
            }

            CloseInlinePreviewTextEditor();
            e.Handled = true;
            return;
        }

        if (e.Key == VirtualKey.Enter && _inlinePreviewTextField != InlinePreviewTextField.Description)
        {
            CloseInlinePreviewTextEditor();
            e.Handled = true;
        }
    }

    private void CloseInlinePreviewTextEditor()
    {
        var wasVisible = _inlinePreviewTextEditor?.Visibility == Visibility.Visible;
        if (_inlinePreviewTextEditor is not null)
        {
            _inlinePreviewTextEditor.Visibility = Visibility.Collapsed;
            _inlinePreviewTextEditor.RenderTransform = null;
        }

        _inlinePreviewTextCardIndex = -1;
        _inlinePreviewTextField = InlinePreviewTextField.None;
        _inlinePreviewTextOriginal = string.Empty;

        if (wasVisible)
            QueuePreviewInteractionRender();
    }

    private static string GetInlinePreviewFieldValue(ProjectCardViewModel card, InlinePreviewTextField field) => field switch
    {
        InlinePreviewTextField.Title => card.Title,
        InlinePreviewTextField.Description => card.Description,
        InlinePreviewTextField.BadgeHeader => card.BadgeHeader,
        InlinePreviewTextField.Value => card.Value,
        _ => string.Empty,
    };

    private static void SetInlinePreviewFieldValue(ProjectCardViewModel card, InlinePreviewTextField field, string value)
    {
        switch (field)
        {
            case InlinePreviewTextField.Title:
                card.Title = value;
                break;
            case InlinePreviewTextField.Description:
                card.Description = value;
                break;
            case InlinePreviewTextField.BadgeHeader:
                card.BadgeHeader = value;
                break;
            case InlinePreviewTextField.Value:
                card.Value = value;
                break;
        }
    }

    private static string InlinePreviewFieldLabel(InlinePreviewTextField field) => field switch
    {
        InlinePreviewTextField.Title => "title",
        InlinePreviewTextField.Description => "description",
        InlinePreviewTextField.BadgeHeader => "badge header",
        InlinePreviewTextField.Value => "badge value",
        _ => "text",
    };

    private static InlinePreviewTextField ParseInlinePreviewField(string field) => field switch
    {
        "title" => InlinePreviewTextField.Title,
        "description" => InlinePreviewTextField.Description,
        "badgeHeader" => InlinePreviewTextField.BadgeHeader,
        "value" => InlinePreviewTextField.Value,
        _ => InlinePreviewTextField.None,
    };

    private static global::Windows.UI.Color ArgbColor(uint argb) => ColorHelper.FromArgb(
        (byte)(argb >> 24),
        (byte)(argb >> 16),
        (byte)(argb >> 8),
        (byte)argb);

    private ComparisonProject PrepareProjectForImmersivePreviewRender(ComparisonProject project)
    {
        if (_inlinePreviewTextEditor?.Visibility != Visibility.Visible ||
            _inlinePreviewTextCardIndex < 0 ||
            _inlinePreviewTextCardIndex >= project.Cards.Count)
            return project;

        // U+200B is intentionally non-whitespace to renderer layout checks, but has
        // no visible glyph. It preserves title/description/badge geometry while the
        // transparent TextBox supplies the live editable text over the same field.
        const string invisibleLayoutText = "\u200B";
        var card = project.Cards[_inlinePreviewTextCardIndex];
        switch (_inlinePreviewTextField)
        {
            case InlinePreviewTextField.Title:
                card.Title = invisibleLayoutText;
                break;
            case InlinePreviewTextField.Description:
                card.Description = invisibleLayoutText;
                break;
            case InlinePreviewTextField.BadgeHeader:
                card.BadgeHeader = invisibleLayoutText;
                break;
            case InlinePreviewTextField.Value:
                // Keep both primary and unit jsparse fields present while rendering
                // neither glyph. One zero-width token would make SmartBadge's unit
                // fallback render "People"; two invisible tokens suppress both.
                card.Value = invisibleLayoutText + " " + invisibleLayoutText;
                break;
        }

        return project;
    }

    private bool IsPreviewEditorChild(DependencyObject? source)
    {
        if (source is null)
            return false;

        return (_inlinePreviewTextEditor is not null && IsDescendantOrSelf(source, _inlinePreviewTextEditor)) ||
               (_reliablePreviewApplyAllButton is not null && IsDescendantOrSelf(source, _reliablePreviewApplyAllButton));
    }

    private static bool IsDescendantOrSelf(DependencyObject source, DependencyObject target)
    {
        DependencyObject? current = source;
        while (current is not null)
        {
            if (ReferenceEquals(current, target))
                return true;
            current = VisualTreeHelper.GetParent(current);
        }
        return false;
    }

    private static string? FindPreviewTag(DependencyObject? source)
    {
        DependencyObject? current = source;
        while (current is not null)
        {
            if (current is FrameworkElement element && element.Tag is string tag && tag.StartsWith("reliable-", StringComparison.Ordinal))
                return tag;
            current = VisualTreeHelper.GetParent(current);
        }
        return null;
    }

    private Point ReliablePreviewImageCenter(int cardIndex, double imageX, double imageY)
    {
        var geometry = ReliablePreviewGeometry(cardIndex);
        if (geometry is null)
            return new Point(ReliablePreviewWidth / 2, ReliablePreviewHeight / 2);

        var g = geometry.Value;
        var imageBase = ReliableImageBase(g);
        var (sx, sy) = ReliablePreviewScale();
        return new Point(
            (imageBase.X + imageBase.Width / 2 + imageX * g.ImageCoordinateScaleX) * sx,
            (imageBase.Y + imageBase.Height / 2 + imageY * g.ImageCoordinateScaleY) * sy);
    }

    private (double X, double Y) ReliablePreviewScale()
    {
        var renderer = _legacyRenderer;
        return renderer is null
            ? (0.5, 0.5)
            : (ReliablePreviewWidth / Math.Max(1.0, renderer.ReferenceWidth), ReliablePreviewHeight / Math.Max(1.0, renderer.ReferenceHeight));
    }

    private void QueuePreviewInteractionRender()
    {
        _previewInteractionRenderPending = true;
        if (_previewInteractionRenderRunning)
            return;

        _previewInteractionRenderRunning = true;
        DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                while (_previewInteractionRenderPending)
                {
                    _previewInteractionRenderPending = false;
                    await RenderCurrentFrameAsync();
                }
            }
            finally
            {
                _previewInteractionRenderRunning = false;
                if (_previewInteractionRenderPending)
                    QueuePreviewInteractionRender();
            }
        });
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
