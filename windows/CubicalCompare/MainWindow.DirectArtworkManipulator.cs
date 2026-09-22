using CubicalCompare.Core.Project;
using System.ComponentModel;
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
    private enum ArtworkPointerMode
    {
        None,
        Move,
        Scale,
        Rotate,
    }

    private Canvas? _artworkManipulatorCanvas;
    private Grid? _artworkManipulatorAdorner;
    private Image? _artworkManipulatorImage;
    private TextBlock? _artworkManipulatorInfo;
    private ProjectCardViewModel? _artworkManipulatorCard;
    private string _artworkManipulatorImagePath = string.Empty;
    private int _artworkSourceWidth;
    private int _artworkSourceHeight;
    private ArtworkPointerMode _artworkPointerMode;
    private uint _artworkPointerId;
    private Point _artworkPointerStart;
    private double _artworkStartX;
    private double _artworkStartY;
    private double _artworkStartScale;
    private double _artworkStartRotation;
    private double _artworkStartDistance;
    private double _artworkRotationOffset;

    internal void InitializeDirectArtworkManipulator()
    {
        if (_artworkManipulatorCanvas is not null)
            return;

        var transformExpander = FindAncestor<Expander>(ImageScaleBox);
        if (transformExpander?.Parent is not StackPanel inspectorStack)
            return;

        transformExpander.Header = "Transform / resize · precision";

        var canvas = new Canvas
        {
            Width = ArtworkSlotWidth,
            Height = ArtworkSlotHeight,
            Background = new SolidColorBrush(ColorHelper.FromArgb(255, 12, 12, 12)),
            Clip = new RectangleGeometry { Rect = new Rect(0, 0, ArtworkSlotWidth, ArtworkSlotHeight) },
        };
        canvas.PointerPressed += ArtworkManipulator_PointerPressed;
        canvas.PointerMoved += ArtworkManipulator_PointerMoved;
        canvas.PointerReleased += ArtworkManipulator_PointerReleased;
        canvas.PointerCanceled += ArtworkManipulator_PointerCanceled;
        canvas.PointerCaptureLost += ArtworkManipulator_PointerCaptureLost;
        _artworkManipulatorCanvas = canvas;

        // Slot guides make the image editor read like the actual 471×872 artwork area.
        canvas.Children.Add(new Rectangle
        {
            Width = ArtworkSlotWidth,
            Height = ArtworkSlotHeight,
            Fill = new SolidColorBrush(ColorHelper.FromArgb(255, 8, 20, 35)),
            Stroke = new SolidColorBrush(ColorHelper.FromArgb(255, 95, 110, 128)),
            StrokeThickness = 2,
            IsHitTestVisible = false,
        });
        var safeGuide = new Rectangle
        {
            Width = ArtworkSlotWidth - 16,
            Height = ArtworkSlotHeight - 380,
            Stroke = new SolidColorBrush(ColorHelper.FromArgb(115, 255, 255, 255)),
            StrokeThickness = 2,
            StrokeDashArray = [8, 7],
            IsHitTestVisible = false,
        };
        Canvas.SetLeft(safeGuide, 8);
        Canvas.SetTop(safeGuide, 370);
        canvas.Children.Add(safeGuide);

        var adorner = new Grid
        {
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
            RenderTransformOrigin = new Point(0.5, 0.5),
        };
        _artworkManipulatorAdorner = adorner;

        var image = new Image
        {
            Stretch = Stretch.Fill,
            IsHitTestVisible = false,
        };
        _artworkManipulatorImage = image;
        adorner.Children.Add(image);

        adorner.Children.Add(new Border
        {
            BorderBrush = new SolidColorBrush(Colors.White),
            BorderThickness = new Thickness(3),
            Background = new SolidColorBrush(ColorHelper.FromArgb(1, 255, 255, 255)),
        });

        adorner.Children.Add(CreateResizeHandle("resize-nw", HorizontalAlignment.Left, VerticalAlignment.Top, new Thickness(-17, -17, 0, 0)));
        adorner.Children.Add(CreateResizeHandle("resize-ne", HorizontalAlignment.Right, VerticalAlignment.Top, new Thickness(0, -17, -17, 0)));
        adorner.Children.Add(CreateResizeHandle("resize-sw", HorizontalAlignment.Left, VerticalAlignment.Bottom, new Thickness(-17, 0, 0, -17)));
        adorner.Children.Add(CreateResizeHandle("resize-se", HorizontalAlignment.Right, VerticalAlignment.Bottom, new Thickness(0, 0, -17, -17)));

        var rotateHandle = new Border
        {
            Tag = "rotate",
            Width = 44,
            Height = 44,
            CornerRadius = new CornerRadius(22),
            Background = new SolidColorBrush(ColorHelper.FromArgb(245, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 18, 0, 0),
            Child = new TextBlock
            {
                Text = "↻",
                Foreground = new SolidColorBrush(Colors.Black),
                FontSize = 26,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                IsHitTestVisible = false,
            },
        };
        adorner.Children.Add(rotateHandle);
        canvas.Children.Add(adorner);

        var viewbox = new Viewbox
        {
            Stretch = Stretch.Uniform,
            Child = canvas,
        };

        var viewport = new Border
        {
            Height = 350,
            Background = new SolidColorBrush(Colors.Black),
            BorderBrush = new SolidColorBrush(ColorHelper.FromArgb(100, 255, 255, 255)),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(9),
            Padding = new Thickness(6),
            Child = viewbox,
        };

        _artworkManipulatorInfo = new TextBlock
        {
            Text = "Choose artwork to manipulate it directly.",
            FontSize = 11,
            Opacity = 0.65,
            TextWrapping = TextWrapping.Wrap,
        };

        var directPanel = new StackPanel { Spacing = 8, Margin = new Thickness(0, 8, 0, 0) };
        directPanel.Children.Add(new TextBlock
        {
            Text = "Drag the image to move it. Drag any corner handle to resize. Drag ↻ to rotate.",
            FontSize = 11,
            Opacity = 0.7,
            TextWrapping = TextWrapping.Wrap,
        });
        directPanel.Children.Add(viewport);
        directPanel.Children.Add(_artworkManipulatorInfo);

        var directExpander = new Expander
        {
            Header = "Transform / resize · drag",
            IsExpanded = true,
            Content = directPanel,
            Margin = new Thickness(0, 4, 0, 0),
        };

        var precisionIndex = inspectorStack.Children.IndexOf(transformExpander);
        inspectorStack.Children.Insert(Math.Max(0, precisionIndex), directExpander);

        CardsList.SelectionChanged += (_, _) => AttachArtworkManipulatorCard(CardsList.SelectedItem as ProjectCardViewModel);
        AttachArtworkManipulatorCard(CardsList.SelectedItem as ProjectCardViewModel);
    }

    private static Border CreateResizeHandle(string tag, HorizontalAlignment horizontal, VerticalAlignment vertical, Thickness margin)
    {
        return new Border
        {
            Tag = tag,
            Width = 34,
            Height = 34,
            CornerRadius = new CornerRadius(17),
            Background = new SolidColorBrush(ColorHelper.FromArgb(245, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Colors.Black),
            BorderThickness = new Thickness(2),
            HorizontalAlignment = horizontal,
            VerticalAlignment = vertical,
            Margin = margin,
        };
    }

    private static T? FindAncestor<T>(DependencyObject start) where T : DependencyObject
    {
        DependencyObject? current = start;
        while (current is not null)
        {
            if (current is T found)
                return found;
            current = VisualTreeHelper.GetParent(current);
        }
        return null;
    }

    private void AttachArtworkManipulatorCard(ProjectCardViewModel? card)
    {
        if (ReferenceEquals(_artworkManipulatorCard, card))
        {
            RefreshArtworkManipulator();
            return;
        }

        if (_artworkManipulatorCard is not null)
            _artworkManipulatorCard.PropertyChanged -= ArtworkManipulatorCard_PropertyChanged;

        _artworkManipulatorCard = card;
        _artworkManipulatorImagePath = string.Empty;
        _artworkSourceWidth = 0;
        _artworkSourceHeight = 0;

        if (_artworkManipulatorCard is not null)
            _artworkManipulatorCard.PropertyChanged += ArtworkManipulatorCard_PropertyChanged;

        RefreshArtworkManipulator();
    }

    private void ArtworkManipulatorCard_PropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName is nameof(ProjectCardViewModel.ImagePath) or nameof(ProjectCardViewModel.Preview))
        {
            _artworkManipulatorImagePath = string.Empty;
            _artworkSourceWidth = 0;
            _artworkSourceHeight = 0;
        }
        RefreshArtworkManipulator();
    }

    private void RefreshArtworkManipulator()
    {
        var card = CardsList.SelectedItem as ProjectCardViewModel;
        if (card is null || _artworkManipulatorAdorner is null || _artworkManipulatorImage is null)
            return;

        if (!ReferenceEquals(_artworkManipulatorCard, card))
        {
            AttachArtworkManipulatorCard(card);
            return;
        }

        var resolvedArtwork = TryGetCachedArtworkPath(card.ImagePath);
        if (resolvedArtwork is null)
        {
            _artworkManipulatorAdorner.Visibility = Visibility.Collapsed;
            if (_artworkManipulatorInfo is not null)
                _artworkManipulatorInfo.Text = WebImageSource.IsRemoteSource(card.ImagePath)
                    ? "Web artwork is still resolving…"
                    : "Choose artwork to manipulate it directly.";
            return;
        }

        EnsureArtworkSourceDimensions(resolvedArtwork);
        if (_artworkSourceWidth <= 0 || _artworkSourceHeight <= 0)
        {
            _artworkManipulatorAdorner.Visibility = Visibility.Collapsed;
            if (_artworkManipulatorInfo is not null)
                _artworkManipulatorInfo.Text = "Could not read the artwork dimensions.";
            return;
        }

        _artworkManipulatorAdorner.Visibility = Visibility.Visible;
        _artworkManipulatorImage.Source = card.Preview;

        var cropWidth = _artworkSourceWidth * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropLeft, 0, .95) - Math.Clamp(card.ImageCropRight, 0, .95));
        var cropHeight = _artworkSourceHeight * Math.Max(0.01, 1 - Math.Clamp(card.ImageCropTop, 0, .95) - Math.Clamp(card.ImageCropBottom, 0, .95));
        var baseScale = Math.Max(ArtworkSlotWidth / cropWidth, ArtworkSlotHeight / cropHeight);
        var scale = baseScale * Math.Clamp(card.ImageScale, .05, 12);
        var width = Math.Max(1, cropWidth * scale);
        var height = Math.Max(1, cropHeight * scale);
        var centerX = ArtworkSlotWidth / 2 + card.ImageX;
        var centerY = ArtworkSlotHeight / 2 + card.ImageY;

        _artworkManipulatorAdorner.Width = width;
        _artworkManipulatorAdorner.Height = height;
        Canvas.SetLeft(_artworkManipulatorAdorner, centerX - width / 2);
        Canvas.SetTop(_artworkManipulatorAdorner, centerY - height / 2);
        _artworkManipulatorAdorner.RenderTransform = new RotateTransform { Angle = card.ImageRotation };

        SyncPrecisionTransformBoxes(card);

        if (_artworkManipulatorInfo is not null)
            _artworkManipulatorInfo.Text = $"x {card.ImageX:0} · y {card.ImageY:0} · scale {card.ImageScale:0.00}× · rotation {card.ImageRotation:0.#}°";
    }

    private void EnsureArtworkSourceDimensions(string path)
    {
        if (string.Equals(path, _artworkManipulatorImagePath, StringComparison.OrdinalIgnoreCase) && _artworkSourceWidth > 0 && _artworkSourceHeight > 0)
            return;

        _artworkManipulatorImagePath = path;
        _artworkSourceWidth = 0;
        _artworkSourceHeight = 0;
        try
        {
            using var bitmap = SKBitmap.Decode(path);
            if (bitmap is null)
                return;
            _artworkSourceWidth = bitmap.Width;
            _artworkSourceHeight = bitmap.Height;
        }
        catch (Exception ex)
        {
            App.WriteLog("Could not read artwork for direct manipulation", ex);
        }
    }

    private void SyncPrecisionTransformBoxes(ProjectCardViewModel card)
    {
        BeginArtworkTransformBatch();
        try
        {
            ImageXBox.Value = card.ImageX;
            ImageYBox.Value = card.ImageY;
            ImageScaleBox.Value = card.ImageScale;
            ImageRotationBox.Value = card.ImageRotation;
            ImageCropLeftBox.Value = card.ImageCropLeft;
            ImageCropTopBox.Value = card.ImageCropTop;
            ImageCropRightBox.Value = card.ImageCropRight;
            ImageCropBottomBox.Value = card.ImageCropBottom;
        }
        finally
        {
            EndArtworkTransformBatch();
        }
    }

    private void ArtworkManipulator_PointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (_artworkManipulatorCanvas is null || CardsList.SelectedItem is not ProjectCardViewModel card || string.IsNullOrWhiteSpace(card.ImagePath))
            return;

        var point = e.GetCurrentPoint(_artworkManipulatorCanvas).Position;
        var tag = (e.OriginalSource as FrameworkElement)?.Tag as string;
        _artworkPointerMode = tag switch
        {
            "rotate" => ArtworkPointerMode.Rotate,
            "resize-nw" or "resize-ne" or "resize-sw" or "resize-se" => ArtworkPointerMode.Scale,
            _ => ArtworkPointerMode.Move,
        };

        _artworkPointerId = e.Pointer.PointerId;
        _artworkPointerStart = point;
        _artworkStartX = card.ImageX;
        _artworkStartY = card.ImageY;
        _artworkStartScale = card.ImageScale;
        _artworkStartRotation = card.ImageRotation;

        var center = new Point(ArtworkSlotWidth / 2 + card.ImageX, ArtworkSlotHeight / 2 + card.ImageY);
        _artworkStartDistance = Math.Max(1, Distance(point, center));
        var angle = AngleDegrees(point, center);
        _artworkRotationOffset = card.ImageRotation - angle;

        _artworkManipulatorCanvas.CapturePointer(e.Pointer);
        e.Handled = true;
    }

    private void ArtworkManipulator_PointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (_artworkPointerMode == ArtworkPointerMode.None || _artworkManipulatorCanvas is null || e.Pointer.PointerId != _artworkPointerId || CardsList.SelectedItem is not ProjectCardViewModel card)
            return;

        var point = e.GetCurrentPoint(_artworkManipulatorCanvas).Position;
        var center = new Point(ArtworkSlotWidth / 2 + _artworkStartX, ArtworkSlotHeight / 2 + _artworkStartY);

        switch (_artworkPointerMode)
        {
            case ArtworkPointerMode.Move:
                card.ImageX = Math.Clamp(_artworkStartX + point.X - _artworkPointerStart.X, -4000, 4000);
                card.ImageY = Math.Clamp(_artworkStartY + point.Y - _artworkPointerStart.Y, -4000, 4000);
                break;
            case ArtworkPointerMode.Scale:
                card.ImageScale = Math.Clamp(_artworkStartScale * Distance(point, center) / _artworkStartDistance, .05, 12);
                break;
            case ArtworkPointerMode.Rotate:
                card.ImageRotation = NormalizeDegrees(AngleDegrees(point, center) + _artworkRotationOffset);
                break;
        }

        RefreshArtworkManipulator();
        ScheduleWorkspaceSave();
        e.Handled = true;
    }

    private async void ArtworkManipulator_PointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (_artworkManipulatorCanvas is null || e.Pointer.PointerId != _artworkPointerId)
            return;

        _artworkManipulatorCanvas.ReleasePointerCapture(e.Pointer);
        _artworkPointerMode = ArtworkPointerMode.None;
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        await ApplyArtworkTransformAsync();
        e.Handled = true;
    }

    private void ArtworkManipulator_PointerCanceled(object sender, PointerRoutedEventArgs e) => EndArtworkPointerManipulation();

    private void ArtworkManipulator_PointerCaptureLost(object sender, PointerRoutedEventArgs e) => EndArtworkPointerManipulation();

    private void EndArtworkPointerManipulation()
    {
        if (_artworkPointerMode == ArtworkPointerMode.None)
            return;
        _artworkPointerMode = ArtworkPointerMode.None;
        ScheduleThumbnailRefresh();
        ScheduleWorkspaceSave();
        _ = ApplyArtworkTransformAsync();
    }

    private static double Distance(Point a, Point b)
    {
        var dx = a.X - b.X;
        var dy = a.Y - b.Y;
        return Math.Sqrt(dx * dx + dy * dy);
    }

    private static double AngleDegrees(Point point, Point center) => Math.Atan2(point.Y - center.Y, point.X - center.X) * 180.0 / Math.PI;

    private static double NormalizeDegrees(double degrees)
    {
        degrees %= 360;
        if (degrees > 180) degrees -= 360;
        if (degrees < -180) degrees += 360;
        return degrees;
    }
}
