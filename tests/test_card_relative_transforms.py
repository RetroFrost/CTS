import unittest

from comparison_studio.data import CardData, ProjectSettings
from comparison_studio.reselect_fix import ReselectAwareRenderer


class CardRelativeTransformTests(unittest.TestCase):
    def setUp(self) -> None:
        self.cards = [
            CardData(
                uploaded=str(index + 1),
                title=f"Card {index + 1}",
                description="Description",
                image="",
            )
            for index in range(5)
        ]
        self.settings = ProjectSettings()

    def test_transformed_object_is_hidden_until_its_card_is_revealed(self) -> None:
        renderer = ReselectAwareRenderer(
            transforms={(1, "image"): (0.10, 0.67, 0.80, 0.30)}
        )
        self.assertIsNone(
            renderer.editor_region(
                self.cards,
                0.5,
                self.settings,
                1,
                "image",
            )
        )

    def test_transformed_object_inherits_its_cards_horizontal_scroll(self) -> None:
        renderer = ReselectAwareRenderer(
            transforms={(1, "image"): (0.40, 0.60, 0.50, 0.30)}
        )
        before = renderer.editor_region(
            self.cards,
            8.0,
            self.settings,
            1,
            "image",
        )
        after = renderer.editor_region(
            self.cards,
            8.0 + 10.0 / 3.0,
            self.settings,
            1,
            "image",
        )
        self.assertIsNotNone(before)
        self.assertIsNotNone(after)
        self.assertAlmostEqual(before[0] - after[0], 0.25, places=5)
        self.assertAlmostEqual(before[1], after[1], places=5)
        self.assertAlmostEqual(before[2], after[2], places=5)
        self.assertAlmostEqual(before[3], after[3], places=5)

    def test_hit_testing_uses_moved_position_not_old_image_area(self) -> None:
        renderer = ReselectAwareRenderer(
            transforms={(0, "image"): (0.08, 0.12, 0.72, 0.22)}
        )
        self.assertEqual(
            renderer.hit_test(self.cards, 8.0, self.settings, 0.10, 0.20),
            (0, "image"),
        )
        self.assertIsNone(
            renderer.hit_test(self.cards, 8.0, self.settings, 0.125, 0.82)
        )


if __name__ == "__main__":
    unittest.main()
