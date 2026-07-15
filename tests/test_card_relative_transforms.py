import unittest

from comparison_studio.card_relative_transform import CardRelativeRenderer
from comparison_studio.data import CardData, ProjectSettings
from comparison_studio.renderer import BACKGROUND


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
        renderer = CardRelativeRenderer(
            transforms={(1, "image"): (0.10, 0.10, 0.80, 0.80)}
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

    def test_first_frame_does_not_show_a_transformed_card_object(self) -> None:
        renderer = CardRelativeRenderer(
            transforms={(0, "title"): (0.10, 0.12, 0.80, 0.18)}
        )
        frame = renderer.render(self.cards, 0.0, self.settings, size=(640, 360))
        self.assertEqual(frame.getpixel((80, 80)), BACKGROUND)
        self.assertEqual(frame.getpixel((320, 180)), BACKGROUND)

    def test_every_card_has_a_distinct_live_image_frame(self) -> None:
        renderer = CardRelativeRenderer()
        first = renderer.image_frame_for_card(
            self.cards,
            8.0,
            self.settings,
            0,
        )
        second = renderer.image_frame_for_card(
            self.cards,
            8.0,
            self.settings,
            1,
        )
        self.assertIsNotNone(first)
        self.assertIsNotNone(second)
        self.assertAlmostEqual(second[0] - first[0], 0.25, places=5)
        self.assertAlmostEqual(first[1], second[1], places=5)
        self.assertAlmostEqual(first[2], second[2], places=5)
        self.assertAlmostEqual(first[3], second[3], places=5)

    def test_transformed_image_inherits_only_its_cards_horizontal_scroll(self) -> None:
        renderer = CardRelativeRenderer(
            transforms={(1, "image"): (0.40, 0.10, 0.50, 0.80)}
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

    def test_hit_testing_uses_transformed_image_frame_not_old_image_area(self) -> None:
        renderer = CardRelativeRenderer(
            transforms={(0, "image"): (0.0, 0.0, 0.50, 0.50)}
        )
        self.assertEqual(
            renderer.hit_test(self.cards, 8.0, self.settings, 0.05, 0.72),
            (0, "image"),
        )
        self.assertIsNone(
            renderer.hit_test(self.cards, 8.0, self.settings, 0.18, 0.90)
        )

    def test_render_uses_one_card_group_for_transformed_content(self) -> None:
        class TrackingRenderer(CardRelativeRenderer):
            group_calls = 0

            def _render_transformed_card_group(self, *args, **kwargs):
                self.group_calls += 1
                return super()._render_transformed_card_group(*args, **kwargs)

        renderer = TrackingRenderer(
            transforms={(0, "image"): (0.10, 0.10, 0.80, 0.80)}
        )
        renderer.render(self.cards, 8.0, self.settings, size=(640, 360))
        self.assertEqual(renderer.group_calls, 1)

    def test_image_transform_is_clamped_to_its_own_image_frame(self) -> None:
        renderer = CardRelativeRenderer(
            transforms={(1, "image"): (-0.60, -0.50, 2.40, 2.00)}
        )
        image_frame = renderer.image_frame_for_card(
            self.cards,
            8.0,
            self.settings,
            1,
        )
        region = renderer.editor_region(
            self.cards,
            8.0,
            self.settings,
            1,
            "image",
        )
        self.assertIsNotNone(image_frame)
        self.assertIsNotNone(region)
        for actual, expected in zip(region, image_frame):
            self.assertAlmostEqual(actual, expected, places=5)

    def test_global_drag_is_stored_relative_to_the_owning_image_frame(self) -> None:
        renderer = CardRelativeRenderer()
        stored = renderer.global_to_transform(
            self.cards,
            8.0,
            self.settings,
            2,
            "image",
            (0.0, -0.25, 1.0, 1.5),
        )
        self.assertEqual(stored, (0.0, 0.0, 1.0, 1.0))


if __name__ == "__main__":
    unittest.main()
