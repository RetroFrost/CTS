import tempfile
import unittest
from pathlib import Path

from PIL import Image
from PySide6.QtCore import QPoint, QRect

from comparison_studio.rewrite.image_transform import (
    ImageTransform,
    TransformRenderer,
    format_image_reference,
    parse_image_reference,
    resize_transform,
)
from comparison_studio.rewrite.model import Card


class RewriteImageTransformTests(unittest.TestCase):
    def test_transform_round_trip_keeps_source_and_values(self) -> None:
        source = "/tmp/artwork.png"
        expected = ImageTransform(
            scale=1.75,
            x=-0.35,
            y=0.22,
            mode="fit",
            width_scale=1.60,
            height_scale=0.72,
        )
        encoded = format_image_reference(source, expected)
        decoded_source, decoded = parse_image_reference(encoded)
        self.assertEqual(decoded_source, source)
        self.assertEqual(decoded, expected)

    def test_default_transform_does_not_modify_plain_image_reference(self) -> None:
        source = "https://example.com/artwork.png"
        self.assertEqual(format_image_reference(source, ImageTransform()), source)

    def test_horizontal_transform_moves_artwork_in_renderer(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artwork.png"
            artwork = Image.new("RGBA", (80, 80), (0, 0, 0, 0))
            for x in range(10, 30):
                for y in range(20, 60):
                    artwork.putpixel((x, y), (255, 0, 255, 255))
            artwork.save(path)

            renderer = TransformRenderer()
            centered = Card(image=format_image_reference(str(path), ImageTransform(mode="fit")))
            moved = Card(
                image=format_image_reference(
                    str(path),
                    ImageTransform(scale=1.0, x=0.70, y=0.0, mode="fit"),
                )
            )
            centered_frame = renderer._render_illustrated(centered, 400, 1000, 1.0)
            moved_frame = renderer._render_illustrated(moved, 400, 1000, 1.0)

            def marker_center(image: Image.Image) -> float:
                xs = []
                for y in range(0, 730):
                    for x in range(0, 400):
                        red, green, blue, alpha = image.getpixel((x, y))
                        if red > 220 and green < 40 and blue > 220 and alpha > 0:
                            xs.append(x)
                self.assertTrue(xs)
                return sum(xs) / len(xs)

            self.assertGreater(marker_center(moved_frame), marker_center(centered_frame) + 40)

    def test_renderer_resizes_width_and_height_independently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artwork.png"
            artwork = Image.new("RGBA", (80, 80), (0, 0, 0, 0))
            for x in range(20, 60):
                for y in range(20, 60):
                    artwork.putpixel((x, y), (255, 0, 255, 255))
            artwork.save(path)

            renderer = TransformRenderer()
            normal = Card(image=format_image_reference(str(path), ImageTransform(mode="fit")))
            free = Card(
                image=format_image_reference(
                    str(path),
                    ImageTransform(
                        mode="fit",
                        width_scale=1.8,
                        height_scale=0.5,
                    ),
                )
            )
            normal_frame = renderer._render_illustrated(normal, 400, 1000, 1.0)
            free_frame = renderer._render_illustrated(free, 400, 1000, 1.0)

            def marker_size(image: Image.Image) -> tuple[int, int]:
                points = []
                for y in range(0, 730):
                    for x in range(0, 400):
                        red, green, blue, alpha = image.getpixel((x, y))
                        if red > 220 and green < 40 and blue > 220 and alpha > 0:
                            points.append((x, y))
                self.assertTrue(points)
                xs = [point[0] for point in points]
                ys = [point[1] for point in points]
                return max(xs) - min(xs) + 1, max(ys) - min(ys) + 1

            normal_width, normal_height = marker_size(normal_frame)
            free_width, free_height = marker_size(free_frame)
            self.assertGreater(free_width, normal_width * 1.45)
            self.assertLess(free_height, normal_height * 0.70)

    def test_side_handle_changes_only_one_axis(self) -> None:
        original = ImageTransform(width_scale=1.0, height_scale=1.0)
        resized = resize_transform(
            original,
            QRect(100, 100, 200, 120),
            QRect(50, 50, 400, 300),
            "e",
            QPoint(80, 60),
        )
        self.assertGreater(resized.width_scale, 1.35)
        self.assertAlmostEqual(resized.height_scale, 1.0, places=4)
        self.assertGreater(resized.x, 0.0)
        self.assertAlmostEqual(resized.y, 0.0, places=4)


if __name__ == "__main__":
    unittest.main()
