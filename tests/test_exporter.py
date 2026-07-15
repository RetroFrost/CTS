import unittest

from PIL import Image

from comparison_studio.data import FriendlyError
from comparison_studio.exporter import _rgb24_frame_bytes


class ExportFrameFormatTests(unittest.TestCase):
    def test_rgba_frame_is_serialized_as_exact_rgb24(self) -> None:
        image = Image.new("RGBA", (2, 2), (10, 20, 30, 40))

        payload = _rgb24_frame_bytes(image, 2, 2)

        self.assertEqual(len(payload), 2 * 2 * 3)
        self.assertEqual(payload, bytes([10, 20, 30] * 4))

    def test_unexpected_frame_size_is_rejected_before_ffmpeg(self) -> None:
        image = Image.new("RGB", (3, 2), (1, 2, 3))

        with self.assertRaises(FriendlyError):
            _rgb24_frame_bytes(image, 2, 2)


if __name__ == "__main__":
    unittest.main()
