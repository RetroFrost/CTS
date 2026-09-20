using Microsoft.UI;
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

    private sealed record PreviewTextHit(int CardIndex, InlinePreviewTextField Field, Rect Bounds);

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
        Canvas.SetZIndex(_reliablePreviewAdorner, 180);

        _reliablePreviewApplyAllButton = new Button
        {
            Content = "Apply to all images",
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

        _inlinePreviewTextEditor = new TextBox
        {
            Visibility = Visibility.Collapsed,
            Padding = new Thickness(6, 2, 6, 2),
            BorderThickness = new Thickness(2),
            BorderBrush = new SolidColorBrush(Colors.DeepSkyBlue),
            SelectionHighlightColor = new SolidColorBrush(ColorHelper.FromArgb(160, 0, 120, 215)),
            TextWrapping = TextWrapping.Wrap,
        };
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
        var tag = FindPreviewTag(e.OriginalSource as DependencyObject);

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
            _reliablePreviewCardIndex < Cards.Count &&
            tag is "reliable-rotate" or "reliable-resize-nw" or "reliable-resize-ne" or "reliable-resize-sw" or "reliable-resize-se")
        {
            CloseInlinePreviewTextEditor();
            BeginReliablePreviewDrag(e, point.Position, _reliablePreviewCardIndex, tag == "reliable-rotate" ? ReliablePreviewDragMode.Rotate : ReliablePreviewDragMode.Scale);
            return;
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
            TimelineStatusText.Text = $"Artwork selected · {card.Title} · drag, resize, rotate or apply to all images";
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

    private void SelectPreviewCard(int cardIndex)
    {
        if (cardIndex < 0 || cardIndex >= Cards.Count)
            return;

        CardsList.SelectedIndex = cardIndex;
        CardsList.ScrollIntoView(Cards[cardIndex]);
    }

    private void ActivateReliablePreviewTransform(int cardIndex)
    {
        _reliablePreviewActive = true;
        _reliablePreviewCardIndex = cardIndex;
        if (_reliablePreviewHint is not null)
            _reliablePreviewHint.Visibility = Visibility.Visible;
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

        var (sx, sy) = ReliablePreviewScale();
        var referencePoint = new Point(point.X / sx, point.Y / sy);
        var positions = renderer.VisibleCardSlotXs(BuildProject(), (int)Math.Round(ProjectFrameSlider.Value));

        foreach (var pair in positions.OrderBy(pair => Math.Abs(referencePoint.X - (pair.Value + renderer.BodyInset + renderer.BodyWidth / 2.0))))
        {
            if (pair.Key < 0 || pair.Key >= Cards.Count)
                continue;

            var card = Cards[pair.Key];
            if (string.IsNullOrWhiteSpace(card.ImagePath) || !File.Exists(card.ImagePath))
                continue;

            var bodyLeft = pair.Value + renderer.BodyInset;
            if (referencePoint.X < bodyLeft || referencePoint.X > bodyLeft + renderer.BodyWidth ||
                referencePoint.Y < 0 || referencePoint.Y > renderer.ImageHeight)
                continue;

            try
            {
                using var bitmap = SKBitmap.Decode(card.ImagePath);
                if (bitmap is null || bitmap.Width <= 0 || bitmap.Height <= 0)
                    continue;

                var cropWidth = bitmap.Width * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropLeft, 0, .95) - Math.Clamp(card.ImageCropRight, 0, .95));
                var cropHeight = bitmap.Height * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropTop, 0, .95) - Math.Clamp(card.ImageCropBottom, 0, .95));
                var baseScale = Math.Max(renderer.BodyWidth / cropWidth, renderer.ImageHeight / cropHeight);
                var scale = baseScale * Math.Clamp(card.ImageScale, .05, 12);
                var halfWidth = cropWidth * scale / 2.0;
                var halfHeight = cropHeight * scale / 2.0;
                var centerX = bodyLeft + renderer.BodyWidth / 2.0 + card.ImageX;
                var centerY = renderer.ImageHeight / 2.0 + card.ImageY;

                var dx = referencePoint.X - centerX;
                var dy = referencePoint.Y - centerY;
                var radians = -card.ImageRotation * Math.PI / 180.0;
                var localX = dx * Math.Cos(radians) - dy * Math.Sin(radians);
                var localY = dx * Math.Sin(radians) + dy * Math.Cos(radians);
                if (Math.Abs(localX) <= halfWidth && Math.Abs(localY) <= halfHeight)
                    return pair.Key;
            }
            catch (Exception ex)
            {
                App.WriteLog("Could not hit-test preview artwork", ex);
            }
        }

        return -1;
    }

    private PreviewTextHit? ReliableHitTestPreviewText(Point point)
    {
        var renderer = _legacyRenderer;
        if (renderer is null || Cards.Count == 0)
            return null;

        var (sx, sy) = ReliablePreviewScale();
        var x = point.X / sx;
        var y = point.Y / sy;
        var positions = renderer.VisibleCardSlotXs(BuildProject(), (int)Math.Round(ProjectFrameSlider.Value));

        foreach (var pair in positions.OrderBy(pair => Math.Abs(x - (pair.Value + renderer.BodyInset + renderer.BodyWidth / 2.0))))
        {
            if (pair.Key < 0 || pair.Key >= Cards.Count)
                continue;

            var card = Cards[pair.Key];
            var bodyLeft = pair.Value + renderer.BodyInset;
            var bodyRight = bodyLeft + renderer.BodyWidth;
            if (x < bodyLeft || x > bodyRight)
                continue;

            if (!string.IsNullOrWhiteSpace(card.Title))
            {
                var title = new Rect(bodyLeft, renderer.ImageHeight, renderer.BodyWidth, renderer.TitleHeight);
                if (Contains(title, x, y))
                    return new PreviewTextHit(pair.Key, InlinePreviewTextField.Title, title);
            }

            if (!string.IsNullOrWhiteSpace(card.Description))
            {
                var descriptionTop = renderer.ImageHeight + (string.IsNullOrWhiteSpace(card.Title) ? 0 : renderer.TitleHeight);
                var description = new Rect(bodyLeft, descriptionTop, renderer.BodyWidth, Math.Max(1, renderer.ReferenceHeight - descriptionTop));
                if (Contains(description, x, y))
                    return new PreviewTextHit(pair.Key, InlinePreviewTextField.Description, description);
            }

            if (_projectShowBadges && (!string.IsNullOrWhiteSpace(card.BadgeHeader) || !string.IsNullOrWhiteSpace(card.Value)))
            {
                var badgeSize = 380.0 * Math.Max(0.25, renderer.BadgeScale);
                var badge = new Rect(
                    pair.Value + renderer.BadgeCenterX - badgeSize / 2,
                    renderer.BadgeCenterY - badgeSize / 2,
                    badgeSize,
                    badgeSize);
                if (Contains(badge, x, y))
                {
                    var headerBoundary = renderer.BadgeCenterY - 12;
                    if (!string.IsNullOrWhiteSpace(card.BadgeHeader) && y < headerBoundary)
                    {
                        var header = new Rect(badge.X + 45, badge.Y + 55, Math.Max(1, badge.Width - 90), Math.Max(52, badge.Height * 0.28));
                        return new PreviewTextHit(pair.Key, InlinePreviewTextField.BadgeHeader, header);
                    }

                    if (!string.IsNullOrWhiteSpace(card.Value))
                    {
                        var value = new Rect(badge.X + 45, badge.Y + badge.Height * 0.32, Math.Max(1, badge.Width - 90), Math.Max(80, badge.Height * 0.46));
                        return new PreviewTextHit(pair.Key, InlinePreviewTextField.Value, value);
                    }
                }
            }
        }

        return null;
    }

    private static bool Contains(Rect rect, double x, double y) =>
        x >= rect.X && x <= rect.X + rect.Width && y >= rect.Y && y <= rect.Y + rect.Height;

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
            if (_reliablePreviewApplyAllButton is not null) _reliablePreviewApplyAllButton.Visibility = Visibility.Collapsed;
            return;
        }

        var slotX = ReliableSlotX(_reliablePreviewCardIndex);
        if (slotX is null)
        {
            _reliablePreviewSlot.Visibility = Visibility.Collapsed;
            _reliablePreviewAdorner.Visibility = Visibility.Collapsed;
            if (_reliablePreviewApplyAllButton is not null) _reliablePreviewApplyAllButton.Visibility = Visibility.Collapsed;
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

        if (_reliablePreviewApplyAllButton is not null)
        {
            _reliablePreviewApplyAllButton.Visibility = Visibility.Visible;
            _reliablePreviewApplyAllButton.IsEnabled = Cards.Count > 1;
            Canvas.SetLeft(_reliablePreviewApplyAllButton, Math.Clamp(bodyLeft * sx + 8, 8, ReliablePreviewWidth - 160));
            Canvas.SetTop(_reliablePreviewApplyAllButton, Math.Clamp(renderer.ImageHeight * sy - 43, 8, ReliablePreviewHeight - 48));
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

    private async void ReliablePreviewApplyAll_Click(object sender, RoutedEventArgs e)
    {
        if (_reliablePreviewCardIndex < 0 || _reliablePreviewCardIndex >= Cards.Count)
            return;

        await ApplyArtworkTransformToAllAsync(Cards[_reliablePreviewCardIndex]);
        RefreshReliablePreviewTransformOverlay();
    }

    private void OpenInlinePreviewTextEditor(PreviewTextHit hit)
    {
        if (_inlinePreviewTextEditor is null || _legacyRenderer is null || hit.CardIndex < 0 || hit.CardIndex >= Cards.Count)
            return;

        var card = Cards[hit.CardIndex];
        var (sx, sy) = ReliablePreviewScale();
        var bounds = new Rect(hit.Bounds.X * sx, hit.Bounds.Y * sy, hit.Bounds.Width * sx, hit.Bounds.Height * sy);

        _inlinePreviewTextCardIndex = hit.CardIndex;
        _inlinePreviewTextField = hit.Field;
        _inlinePreviewTextOriginal = GetInlinePreviewFieldValue(card, hit.Field);

        _inlinePreviewTextLoading = true;
        _inlinePreviewTextEditor.Text = _inlinePreviewTextOriginal;
        _inlinePreviewTextEditor.AcceptsReturn = hit.Field == InlinePreviewTextField.Description;
        _inlinePreviewTextEditor.TextWrapping = hit.Field == InlinePreviewTextField.Description ? TextWrapping.Wrap : TextWrapping.NoWrap;
        _inlinePreviewTextEditor.HorizontalTextAlignment = hit.Field == InlinePreviewTextField.Description ? TextAlignment.Left : TextAlignment.Center;
        _inlinePreviewTextEditor.VerticalContentAlignment = hit.Field == InlinePreviewTextField.Description ? VerticalAlignment.Top : VerticalAlignment.Center;
        _inlinePreviewTextEditor.FontSize = hit.Field switch
        {
            InlinePreviewTextField.Title => Math.Max(12, _legacyRenderer.TitleTextSize * sy),
            InlinePreviewTextField.Description => Math.Max(11, _legacyRenderer.DescriptionTextSize * sy),
            InlinePreviewTextField.BadgeHeader => Math.Max(12, 30 * sy),
            InlinePreviewTextField.Value => Math.Max(16, 66 * sy),
            _ => 14,
        };
        _inlinePreviewTextEditor.Foreground = new SolidColorBrush(
            hit.Field == InlinePreviewTextField.Title ? Colors.Black : Colors.White);
        _inlinePreviewTextEditor.Background = new SolidColorBrush(
            hit.Field == InlinePreviewTextField.Title
                ? ColorHelper.FromArgb(238, 255, 255, 255)
                : ColorHelper.FromArgb(220, 20, 24, 30));
        _inlinePreviewTextEditor.Width = Math.Max(70, bounds.Width);
        _inlinePreviewTextEditor.Height = Math.Max(36, bounds.Height);
        Canvas.SetLeft(_inlinePreviewTextEditor, Math.Clamp(bounds.X, 0, ReliablePreviewWidth - _inlinePreviewTextEditor.Width));
        Canvas.SetTop(_inlinePreviewTextEditor, Math.Clamp(bounds.Y, 0, ReliablePreviewHeight - _inlinePreviewTextEditor.Height));
        _inlinePreviewTextEditor.Visibility = Visibility.Visible;
        _inlinePreviewTextLoading = false;

        _inlinePreviewTextEditor.Focus(FocusState.Programmatic);
        _inlinePreviewTextEditor.SelectionStart = _inlinePreviewTextEditor.Text.Length;
        _inlinePreviewTextEditor.SelectionLength = 0;
        TimelineStatusText.Text = $"Editing {InlinePreviewFieldLabel(hit.Field)} directly in preview · Esc cancels";
    }

    private void InlinePreviewTextEditor_TextChanged(object sender, TextChangedEventArgs e)
    {
        if (_inlinePreviewTextLoading || _inlinePreviewTextCardIndex < 0 || _inlinePreviewTextCardIndex >= Cards.Count)
            return;

        var card = Cards[_inlinePreviewTextCardIndex];
        SetInlinePreviewFieldValue(card, _inlinePreviewTextField, _inlinePreviewTextEditor?.Text ?? string.Empty);
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
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
        if (_inlinePreviewTextEditor is not null)
            _inlinePreviewTextEditor.Visibility = Visibility.Collapsed;

        _inlinePreviewTextCardIndex = -1;
        _inlinePreviewTextField = InlinePreviewTextField.None;
        _inlinePreviewTextOriginal = string.Empty;
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
