import unittest

from comparison_studio.data import CardData
from comparison_studio.reference_illustrated import ReferenceIllustratedRenderer
from comparison_studio.shared_contract import COLORS


def rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


class ReferenceIllustratedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.renderer = ReferenceIllustratedRenderer()

    def test_reference_bands_match_android_vertical_layout(self) -> None:
        card = CardData("1 in 4", "Online Only", "Optional description", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.getpixel((20, 500))[:3], rgb(COLORS["image_top"]))
        self.assertEqual(image.getpixel((20, 850))[:3], rgb(COLORS["title_background"]))
        self.assertEqual(
            image.getpixel((20, 950))[:3],
            rgb(COLORS["description_background"]),
        )

    def test_empty_description_keeps_fixed_android_description_band(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")

        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)

        self.assertEqual(image.size, (400, 1000))
        self.assertEqual(image.getpixel((20, 850))[:3], rgb(COLORS["title_background"]))
        self.assertEqual(
            image.getpixel((20, 950))[:3],
            rgb(COLORS["description_background"]),
        )

    def test_historical_render_methods_resolve_to_the_same_card(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")

        illustrated = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        reference = self.renderer._render_reference_card(card, 400, 1000, 1.0)
        classic = self.renderer._render_classic_card(card, 400, 1000, 1.0)

        self.assertEqual(reference.tobytes(), illustrated.tobytes())
        self.assertEqual(classic.tobytes(), illustrated.tobytes())


if __name__ == "__main__":
    unittest.main()
