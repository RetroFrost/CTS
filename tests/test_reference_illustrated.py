import unittest

from comparison_studio.data import CardData
from comparison_studio.reference_illustrated import ReferenceIllustratedRenderer


class ReferenceIllustratedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.renderer = ReferenceIllustratedRenderer()

    def test_reference_bands_match_expected_vertical_layout(self) -> None:
        card = CardData("1 in 4", "Online Only", "Optional description", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        # Artwork, white title strip, and dark description strip.
        self.assertEqual(image.getpixel((200, 500))[:3], (70, 204, 226))
        self.assertEqual(image.getpixel((20, 650))[:3], (247, 246, 242))
        self.assertEqual(image.getpixel((20, 900))[:3], (22, 22, 22))

    def test_description_is_not_required(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.size, (400, 1000))
        self.assertEqual(image.getpixel((20, 900))[:3], (22, 22, 22))


if __name__ == "__main__":
    unittest.main()
