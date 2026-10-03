using System.Buffers.Binary;
using System.IO.Compression;
using System.Text;
using System.Text.Json;
using Windows.Media.MediaProperties;
using Windows.Storage;
using Windows.Storage.FileProperties;
using Windows.Storage.Pickers;
using WinRT.Interop;
using CubicalCompare.Core.Renderer;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace CubicalCompare;

public sealed partial class MainWindow
{
    private sealed record ReferenceVideoMeasurement(
        string FileName,
        uint Width,
        uint Height,
        double Fps,
        long FrameCount,
        TimeSpan Duration);

    private async void CalibrateReferenceVideo_Click(object sender, RoutedEventArgs e)
    {
        var file = await PickFileAsync([".mp4", ".mov", ".mkv", ".webm", ".avi"]);
        if (file is null) return;

        try
        {
            TimelineStatusText.Text = "Measuring reference video metadata…";
            var measurement = await MeasureReferenceVideoAsync(file);
            await ShowReferenceCalibrationDialogAsync(file, measurement);
        }
        catch (Exception ex)
        {
            App.WriteLog("Reference renderer calibration prototype failed", ex);
            await ShowErrorAsync("Could not measure reference video", ex.Message);
        }
    }

    private static async Task<ReferenceVideoMeasurement> MeasureReferenceVideoAsync(StorageFile file)
    {
        var video = await file.Properties.GetVideoPropertiesAsync();
        var profile = await MediaEncodingProfile.CreateFromFileAsync(file);
        var encoding = profile.Video
            ?? throw new InvalidDataException("The selected file does not expose a video stream.");

        var ratio = encoding.FrameRate;
        var fps = ratio is not null && ratio.Denominator != 0
            ? (double)ratio.Numerator / ratio.Denominator
            : 0;

        var frameCount = fps > 0
            ? Math.Max(1, (long)Math.Round(video.Duration.TotalSeconds * fps))
            : 0;

        return new ReferenceVideoMeasurement(
            file.Name,
            video.Width,
            video.Height,
            fps,
            frameCount,
            video.Duration);
    }

    private async Task ShowReferenceCalibrationDialogAsync(
        StorageFile sourceFile,
        ReferenceVideoMeasurement measurement)
    {
        var width = Math.Max(1, (int)measurement.Width);
        var height = Math.Max(1, (int)measurement.Height);
        var fpsForRenderer = Math.Max(1, (int)Math.Round(measurement.Fps <= 0 ? 60 : measurement.Fps));

        var pitchSeed = width == 1920 ? 480 : Math.Max(1, width / 4);
        var insetSeed = 9;
        var bodyWidthSeed = Math.Max(1, pitchSeed - insetSeed);
        var openingIntervalSeed = Math.Max(1, (int)Math.Round((double)fpsForRenderer * 2));
        var conveyorStepSeed = Math.Max(1, (int)Math.Round((double)fpsForRenderer * 4));
        var imageHeightSeed = Math.Clamp((int)Math.Round(height * (872d / 1080d)), 1, height);
        var titleHeightSeed = Math.Clamp((int)Math.Round(height * (93d / 1080d)), 1, height - imageHeightSeed);

        var cardCount = new NumberBox
        {
            Header = "Canonical cards",
            Value = 4,
            Minimum = 1,
            Maximum = 64,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var pitch = new NumberBox
        {
            Header = "Card pitch (px)",
            Value = pitchSeed,
            Minimum = 1,
            Maximum = 16384,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var inset = new NumberBox
        {
            Header = "Body inset (px)",
            Value = insetSeed,
            Minimum = 0,
            Maximum = 4096,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var bodyWidth = new NumberBox
        {
            Header = "Body width (px)",
            Value = bodyWidthSeed,
            Minimum = 1,
            Maximum = 16384,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var openingInterval = new NumberBox
        {
            Header = "Opening interval (frames)",
            Value = openingIntervalSeed,
            Minimum = 1,
            Maximum = 100000,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var conveyorStep = new NumberBox
        {
            Header = "Conveyor step (frames)",
            Value = conveyorStepSeed,
            Minimum = 1,
            Maximum = 100000,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var imageHeight = new NumberBox
        {
            Header = "Artwork height (px)",
            Value = imageHeightSeed,
            Minimum = 1,
            Maximum = height,
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var titleHeight = new NumberBox
        {
            Header = "Title height (px)",
            Value = titleHeightSeed,
            Minimum = 1,
            Maximum = Math.Max(1, height - 1),
            SmallChange = 1,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Compact,
        };

        var measuredText = new TextBlock
        {
            Text =
                $"Source: {measurement.FileName}\n" +
                $"Measured: {measurement.Width} × {measurement.Height} · " +
                $"{(measurement.Fps > 0 ? measurement.Fps.ToString("0.###", System.Globalization.CultureInfo.InvariantCulture) : "unknown")} FPS · " +
                $"{measurement.Duration:hh\\:mm\\:ss\\.fff}\n" +
                $"Estimated frames: {(measurement.FrameCount > 0 ? measurement.FrameCount.ToString("N0") : "unknown")}",
            TextWrapping = TextWrapping.Wrap,
        };

        var noticeText = new TextBlock
        {
            Text = "Prototype only: metadata is measured from the source file, while layout values are seeded heuristically. It does not yet perform frame-by-frame pixel comparison.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = new Microsoft.UI.Xaml.Media.SolidColorBrush(Microsoft.UI.Colors.DarkOrange),
        };

        var layoutGrid = new Grid { ColumnSpacing = 12, RowSpacing = 8 };
        layoutGrid.ColumnDefinitions.Add(new ColumnDefinition());
        layoutGrid.ColumnDefinitions.Add(new ColumnDefinition());
        layoutGrid.RowDefinitions.Add(new RowDefinition());
        layoutGrid.RowDefinitions.Add(new RowDefinition());
        layoutGrid.RowDefinitions.Add(new RowDefinition());
        layoutGrid.RowDefinitions.Add(new RowDefinition());

        Grid.SetColumn(cardCount, 0);
        Grid.SetRow(cardCount, 0);
        Grid.SetColumn(pitch, 1);
        Grid.SetRow(pitch, 0);
        Grid.SetColumn(inset, 0);
        Grid.SetRow(inset, 1);
        Grid.SetColumn(bodyWidth, 1);
        Grid.SetRow(bodyWidth, 1);
        Grid.SetColumn(openingInterval, 0);
        Grid.SetRow(openingInterval, 2);
        Grid.SetColumn(conveyorStep, 1);
        Grid.SetRow(conveyorStep, 2);
        Grid.SetColumn(imageHeight, 0);
        Grid.SetRow(imageHeight, 3);
        Grid.SetColumn(titleHeight, 1);
        Grid.SetRow(titleHeight, 3);

        layoutGrid.Children.Add(cardCount);
        layoutGrid.Children.Add(pitch);
        layoutGrid.Children.Add(inset);
        layoutGrid.Children.Add(bodyWidth);
        layoutGrid.Children.Add(openingInterval);
        layoutGrid.Children.Add(conveyorStep);
        layoutGrid.Children.Add(imageHeight);
        layoutGrid.Children.Add(titleHeight);

        var stack = new StackPanel
        {
            Spacing = 10,
            Children =
            {
                new TextBlock
                {
                    Text = "Reference-video calibration prototype",
                    FontSize = 20,
                    FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                },
                measuredText,
                noticeText,
                new TextBlock
                {
                    Text = "Layout seed",
                    FontSize = 14,
                    FontWeight = Microsoft.UI.Text.FontWeights.SemiBold,
                    Margin = new Thickness(0, 4, 0, 0),
                },
                layoutGrid,
            },
        };

        var dialog = new ContentDialog
        {
            XamlRoot = RootNavigation.XamlRoot,
            Title = "Calibrate a renderer from a reference video",
            Content = new ScrollViewer
            {
                Content = stack,
                MaxHeight = 620,
                VerticalScrollBarVisibility = Microsoft.UI.Xaml.Controls.ScrollBarVisibility.Auto,
            },
            PrimaryButtonText = "Generate prototype renderer",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            return;

        var requestedCardCount = ClampNumberBoxInt(cardCount, 1, 64);
        var requestedPitch = ClampNumberBoxInt(pitch, 1, 16384);
        var requestedInset = ClampNumberBoxInt(inset, 0, 4096);
        var requestedBodyWidth = ClampNumberBoxInt(bodyWidth, 1, 16384);
        var requestedOpeningInterval = ClampNumberBoxInt(openingInterval, 1, 100000);
        var requestedConveyorStep = ClampNumberBoxInt(conveyorStep, 1, 100000);
        var requestedImageHeight = ClampNumberBoxInt(imageHeight, 1, height);
        var requestedTitleHeight = ClampNumberBoxInt(titleHeight, 1, Math.Max(1, height - requestedImageHeight));

        if (requestedBodyWidth > requestedPitch)
            requestedBodyWidth = requestedPitch;

        var rendererJson = BuildReferenceCalibrationSceneJson(
            sourceFile.Name,
            measurement,
            fpsForRenderer,
            requestedCardCount,
            requestedPitch,
            requestedInset,
            requestedBodyWidth,
            requestedOpeningInterval,
            requestedConveyorStep,
            requestedImageHeight,
            requestedTitleHeight);

        var rendererBytes = PackRendererV3Container(rendererJson);
        var suggestedName = $"{Path.GetFileNameWithoutExtension(sourceFile.Name)}-calibrated.renderer3";

        var picker = new FileSavePicker
        {
            SuggestedStartLocation = PickerLocationId.Downloads,
            SuggestedFileName = suggestedName,
        };
        picker.FileTypeChoices.Add("Cubical Compare Renderer v3", [".renderer3"]);

        var hwnd = WindowNative.GetWindowHandle(this);
        InitializeWithWindow.Initialize(picker, hwnd);

        var destination = await picker.PickSaveFileAsync();
        if (destination is null)
            return;

        await File.WriteAllBytesAsync(destination.Path, rendererBytes);

        try
        {
            TimelineStatusText.Text = "Validating generated renderer…";
            var probe = await RendererPackageProbe.InspectAsync(destination.Path);
            var replacement = LegacyRendererAdapter.Load(destination.Path);

            _legacyRenderer?.Dispose();
            _legacyRenderer = replacement;

            RendererNameText.Text = probe.Name;
            RendererGenerationText.Text = $"Renderer v{(int)probe.Generation} · API {probe.Api}";
            RendererEngineText.Text = $"Engine {probe.Engine}";
            RendererCanvasText.Text = $"Reference {probe.ReferenceWidth}×{probe.ReferenceHeight} · {probe.ReferenceFps} FPS";
            RendererSourceText.Text = probe.SourcePath;
            RendererCompatibilityText.Text =
                "Prototype loaded successfully. Layout seed is metadata-measured + heuristically placed; visual verification is not yet performed.";

            RefreshTimelineRange();
            RefreshSoundtrackUi();

            var initialFrame = replacement.InitialPreviewFrame(BuildProject());
            ProjectFrameSlider.Value = initialFrame;
            TimelineStatusText.Text =
                $"Calibration prototype generated from {sourceFile.Name} · {requestedCardCount} card seed · frame {initialFrame}";

            await RenderCurrentFrameAsync();
        }
        catch (Exception ex)
        {
            App.WriteLog("Generated renderer prototype failed validation", ex);
            await ShowErrorAsync(
                "Prototype renderer was written but could not be loaded",
                ex.Message);
        }
    }

    private static int ClampNumberBoxInt(NumberBox box, int minimum, int maximum)
    {
        if (double.IsNaN(box.Value) || double.IsInfinity(box.Value))
            return minimum;

        return Math.Clamp((int)Math.Round(box.Value), minimum, maximum);
    }

    private static string BuildReferenceCalibrationSceneJson(
        string sourceFileName,
        ReferenceVideoMeasurement measurement,
        int rendererFps,
        int cardCount,
        int slotPitch,
        int bodyInset,
        int bodyWidth,
        int openingInterval,
        int conveyorStep,
        int imageHeight,
        int titleHeight)
    {
        var referenceWidth = Math.Max(1, (int)measurement.Width);
        var referenceHeight = Math.Max(1, (int)measurement.Height);
        var frameCount = Math.Max(1L, measurement.FrameCount);

        var objects = new List<object>();
        var layers = new List<string>();

        for (var index = 0; index < cardCount; index++)
        {
            var left = index * slotPitch + bodyInset;
            var bodyId = $"card.{index}.body";
            var artworkId = $"card.{index}.artwork";
            var titleId = $"card.{index}.title";
            var labelId = $"card.{index}.label";

            objects.Add(new
            {
                id = bodyId,
                kind = "rect",
                frame = 0,
                lifespan = new { start = 0, end = frameCount - 1 },
                resource = "cardBody",
                properties = new { x = left, y = 0, width = bodyWidth, height = referenceHeight }
            });

            objects.Add(new
            {
                id = artworkId,
                kind = "rect",
                frame = 0,
                lifespan = new { start = 0, end = frameCount - 1 },
                resource = "artworkField",
                properties = new { x = left, y = 0, width = bodyWidth, height = imageHeight }
            });

            objects.Add(new
            {
                id = titleId,
                kind = "rect",
                frame = 0,
                lifespan = new { start = 0, end = frameCount - 1 },
                resource = "titleBand",
                properties = new { x = left, y = imageHeight, width = bodyWidth, height = titleHeight }
            });

            objects.Add(new
            {
                id = labelId,
                kind = "text",
                frame = 0,
                lifespan = new { start = 0, end = frameCount - 1 },
                resource = "cardLabel",
                properties = new
                {
                    x = left + Math.Max(12, bodyInset + 6),
                    y = Math.Min(referenceHeight - 18, imageHeight + titleHeight / 2 + 10),
                    text = $"Card {index + 1}",
                }
            });

            layers.Add(bodyId);
            layers.Add(artworkId);
            layers.Add(titleId);
            layers.Add(labelId);
        }

        var footerId = "calibration.footer";
        objects.Add(new
        {
            id = footerId,
            kind = "text",
            frame = 0,
            lifespan = new { start = 0, end = frameCount - 1 },
            resource = "footer",
            properties = new
            {
                x = 18,
                y = Math.Max(20, referenceHeight - 18),
                text = $"SOURCE {measurement.Width}×{measurement.Height} · FPS {measurement.Fps:0.###} · PITCH {slotPitch} · INSET {bodyInset} · BODY {bodyWidth}",
            }
        });
        layers.Add(footerId);

        var root = new
        {
            api = 3,
            id = "reference-calibration-prototype",
            name = "Reference Calibration Prototype",
            author = "Cubical Compare",
            minAppVersion = "4.2.3",
            canvas = new
            {
                width = referenceWidth,
                height = referenceHeight,
                fps = rendererFps
            },
            reference = new
            {
                width = referenceWidth,
                height = referenceHeight,
                fps = rendererFps,
                sourceFps = measurement.Fps,
                frameCount,
                cardCount
            },
            timeline = new
            {
                clock = "absolute",
                frames = frameCount,
                implicitAnimation = false
            },
            background = "#11161C",
            geometry = new
            {
                slotPitch,
                bodyInset,
                bodyWidth,
                imageHeight,
                topFieldHeight = imageHeight,
                titleHeight
            },
            calibration = new
            {
                status = "metadata-measured;layout-seeded",
                sourceFile = sourceFileName,
                sourceWidth = measurement.Width,
                sourceHeight = measurement.Height,
                sourceFps = measurement.Fps,
                sourceDurationMs = (long)Math.Round(measurement.Duration.TotalMilliseconds),
                sourceFrameCount = measurement.FrameCount,
                canonicalCardCount = cardCount,
                openingIntervalFrames = openingInterval,
                conveyorStepFrames = conveyorStep,
                frameVerification = "not-run",
                pixelDifference = "not-measured"
            },
            resources = new Dictionary<string, object>
            {
                ["cardBody"] = new
                {
                    type = "rect",
                    color = "#202A34",
                    radius = 8
                },
                ["artworkField"] = new
                {
                    type = "rect",
                    color = "#2B3A4A"
                },
                ["titleBand"] = new
                {
                    type = "rect",
                    color = "#F2F4F7"
                },
                ["cardLabel"] = new
                {
                    type = "text",
                    color = "#20252B",
                    size = Math.Max(18, Math.Min(32, titleHeight * 0.30)),
                    bold = true
                },
                ["footer"] = new
                {
                    type = "text",
                    color = "#B8C4D1",
                    size = 16
                }
            },
            layers,
            objects
        };

        return JsonSerializer.Serialize(
            root,
            new JsonSerializerOptions { WriteIndented = true });
    }

    private static byte[] PackRendererV3Container(string json)
    {
        var jsonBytes = Encoding.UTF8.GetBytes(json);
        using var compressed = new MemoryStream();
        using (var gzip = new GZipStream(compressed, CompressionLevel.Optimal, leaveOpen: true))
            gzip.Write(jsonBytes, 0, jsonBytes.Length);

        var payload = compressed.ToArray();
        var result = new byte[20 + payload.Length];

        Encoding.ASCII.GetBytes("CCRNDR03").CopyTo(result, 0);
        BinaryPrimitives.WriteInt32BigEndian(result.AsSpan(8, 4), 1);
        BinaryPrimitives.WriteInt32BigEndian(result.AsSpan(12, 4), payload.Length);
        BinaryPrimitives.WriteUInt32BigEndian(result.AsSpan(16, 4), ComputeCrc32(payload));
        Buffer.BlockCopy(payload, 0, result, 20, payload.Length);

        return result;
    }

    private static uint ComputeCrc32(ReadOnlySpan<byte> data)
    {
        var crc = 0xFFFFFFFFu;

        foreach (var value in data)
        {
            crc ^= value;
            for (var bit = 0; bit < 8; bit++)
                crc = (crc & 1) != 0
                    ? (crc >> 1) ^ 0xEDB88320u
                    : crc >> 1;
        }

        return ~crc;
    }
}
