import tempfile
import unittest
from pathlib import Path

from PIL import Image

from comparison_studio.rewrite.model import Card, MODEL_ILLUSTRATED, Project
from comparison_studio.rewrite.render import BACKGROUND, Renderer, render_badge


class RewriteRendererTests(unittest.TestCase):
    def test_illustrated_card_uses_measured_bands(self) -> None:
        renderer = Renderer()
        card = Card("10.0/10", "FOUR HEMISPHERES", "Kiribati", "Description", "")
        image = renderer._render_illustrated(card, 400, 1000, 1.0)
        self.assertEqual(image.getpixel((20, 300))[:3], (89, 207, 229))
        self.assertEqual(image.getpixel((20, 760))[:3], (247, 246, 242))
        self.assertEqual(image.getpixel((20, 845))[:3], (165, 96, 0))
        self.assertEqual(image.getpixel((20, 900))[:3], (23, 23, 23))
        self.assertEqual(image.getpixel((1, 500))[:3], (5, 5, 7))

    def test_reference_badge_is_a_stop_sign_octagon(self) -> None:
        badge = render_badge("10.0/10", "FOUR HEMISPHERES", 400, 1000, 1.0)
        self.assertEqual(badge.getpixel((0, 0))[3], 0)
        self.assertGreater(badge.getpixel((badge.width // 2, badge.height // 2))[3], 0)
        self.assertEqual(badge.getpixel((badge.width // 2, 0))[3], 0)

    def test_badge_size_is_independent_of_text_length(self) -> None:
        short = render_badge("1", "A", 400, 1000, 1.0)
        long = render_badge("10.0/10", "FOUR HEMISPHERES", 400, 1000, 1.0)
        self.assertEqual(short.size, long.size)

    def test_transparent_lineal_artwork_is_contained(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "icon.png"
            icon = Image.new("RGBA", (400, 100), (0, 0, 0, 0))
            for x in range(100, 300):
                for y in range(20, 80):
                    icon.putpixel((x, y), (255, 0, 0, 255))
            icon.save(path)
            renderer = Renderer()
            card = Card("1", "ICON", "Example", "Description", str(path))
            image = renderer._render_illustrated(card, 400, 1000, 1.0)
            self.assertEqual(image.getpixel((20, 300))[:3], (89, 207, 229))
            self.assertNotEqual(image.getpixel((200, 365))[:3], (89, 207, 229))

    def test_four_cards_fill_the_rendered_viewport(self) -> None:
        cards = [Card(str(index), "LABEL", f"Card {index}", "Description", "") for index in range(4)]
        project = Project(cards=cards, model_id=MODEL_ILLUSTRATED)
        image = Renderer().render(project, 8.0, (640, 360))
        self.assertEqual(image.size, (640, 360))
        self.assertNotEqual(image.getpixel((80, 330)), BACKGROUND)
        self.assertNotEqual(image.getpixel((560, 330)), BACKGROUND)


if __name__ == "__main__":
    unittest.main()
