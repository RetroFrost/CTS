import tempfile
import unittest
from pathlib import Path

from PIL import Image

from comparison_studio.strip_splitter import analyze_strip, split_to_directory


class StripSplitterTests(unittest.TestCase):
    def test_horizontal_divider_detection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            colors = [(180, 40, 40), (40, 180, 40), (40, 40, 180), (180, 180, 40)]
            card_width, height, divider = 80, 100, 2
            strip = Image.new("RGB", (card_width * 4 + divider * 3, height))
            x = 0
            for index, color in enumerate(colors):
                strip.paste(Image.new("RGB", (card_width, height), color), (x, 0))
                x += card_width
                if index < len(colors) - 1:
                    strip.paste(Image.new("RGB", (divider, height), (3, 3, 3)), (x, 0))
                    x += divider
            source = tmp_path / "strip.png"
            strip.save(source)

            analysis = analyze_strip(source, expected_count=4)
            self.assertEqual(analysis.orientation, "horizontal")
            self.assertEqual(analysis.count, 4)
            self.assertTrue(analysis.matches_expected)

            outputs = split_to_directory(analysis, tmp_path / "cuts")
            self.assertEqual(len(outputs), 4)
            with Image.open(outputs[0]) as cut:
                self.assertEqual(cut.size, (card_width, height))

    def test_real_divider_color_beats_uniform_artwork_bands(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            count, card_width, height, divider = 8, 80, 100, 2
            strip = Image.new("RGB", (card_width * count + divider * (count - 1), height))
            x = 0
            for index in range(count):
                # A false 5px full-height band exists inside every image.
                strip.paste(Image.new("RGB", (card_width, height), (22 + index, 31, 47)), (x, 0))
                strip.paste(Image.new("RGB", (5, height), (4, 9, 18)), (x + 58, 0))
                if index < count - 1:
                    strip.paste(Image.new("RGB", (divider, height), (0, 0, 0)), (x + card_width, 0))
                x += card_width + (divider if index < count - 1 else 0)
            source = tmp_path / "false-bands.png"
            strip.save(source)

            analysis = analyze_strip(source, expected_count=count)
            self.assertEqual(analysis.orientation, "horizontal")
            self.assertEqual(analysis.count, count)
            self.assertEqual([item.thickness for item in analysis.dividers], [2] * (count - 1))


if __name__ == "__main__":
    unittest.main()
