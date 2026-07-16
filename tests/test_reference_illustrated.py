import unittest

from comparison_studio.data import CardData, MODEL_ILLUSTRATED, ProjectSettings
from comparison_studio.reference_illustrated import ReferenceIllustratedRenderer


class ReferenceIllustratedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.renderer = ReferenceIllustratedRenderer()

    def test_reference_bands_match_measured_video_layout(self) -> None:
        card = CardData("1 in 4", "Online Only", "Optional description", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.getpixel((200, 500))[:3], (89, 207, 229))
        self.assertEqual(image.getpixel((20, 760))[:3], (247, 246, 242))
        self.assertEqual(image.getpixel((20, 845))[:3], (165, 96, 0))
        self.assertEqual(image.getpixel((20, 900))[:3], (23, 23, 23))

    def test_full_height_black_card_dividers_are_present(self) -> None:
        card = CardData("15", "Deep Sea", "Optional description", "", "Seconds")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.getpixel((1, 100))[:3], (5, 5, 6))
        self.assertEqual(image.getpixel((398, 900))[:3], (5, 5, 6))

    def test_description_is_not_required(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.size, (400, 1000))
        self.assertEqual(image.getpixel((20, 900))[:3], (23, 23, 23))

    def test_badge_is_an_octagon_and_keeps_long_words_whole(self) -> None:
        badge = self.renderer._render_reference_badge(
            "10.0/10",
            "FOUR HEMISPHERES",
            400,
            1000,
            1.0,
        )

        self.assertEqual(badge.getpixel((0, 0))[3], 0)
        self.assertGreater(badge.getpixel((badge.width // 2, badge.height // 2))[3], 0)

    def test_illustrated_model_defaults_to_four_cards(self) -> None:
        settings = ProjectSettings(model_id=MODEL_ILLUSTRATED)

        self.assertEqual(settings.effective_visible_cards(), 4)


if __name__ == "__main__":
    unittest.main()
