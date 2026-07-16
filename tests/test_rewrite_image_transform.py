import tempfile
import unittest
from pathlib import Path

from PIL import Image

from comparison_studio.rewrite.image_transform import (
    ImageTransform,
    TransformRenderer,
    format_image_reference,
    parse_image_reference,
)
from comparison_studio.rewrite.model import Card


class RewriteImageTransformTests(unittest.TestCase):
    def test_transform_round_trip_keeps_source_and_values(self) -> None:
        source = "/tmp/artwork.png"
        expected = ImageTransform(scale=1.75, x=-0.35, y=0.22, mode="fit")
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
                    artwork.putpixel((x, y), (255, 0, 0, 255))
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

            def red_center(image: Image.Image) -> float:
                xs = []
                for y in range(0, 730):
                    for x in range(0, 400):
                        red, green, blue, alpha = image.getpixel((x, y))
                        if red > 220 and green < 60 and blue < 60 and alpha > 0:
                            xs.append(x)
                return sum(xs) / len(xs)

            self.assertGreater(red_center(moved_frame), red_center(centered_frame) + 40)


if __name__ == "__main__":
    unittest.main()
