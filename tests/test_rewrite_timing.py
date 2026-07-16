import unittest

from comparison_studio.rewrite.model import Card, MODEL_ILLUSTRATED, Project
from comparison_studio.rewrite.timing import Timeline


class RewriteTimingTests(unittest.TestCase):
    def setUp(self) -> None:
        self.cards = [Card(title=f"Card {index + 1}") for index in range(30)]

    def test_illustrated_auto_length_matches_reference_video_rate(self) -> None:
        project = Project(cards=self.cards, model_id=MODEL_ILLUSTRATED)
        timeline = Timeline(project, len(self.cards))
        self.assertEqual(timeline.visible_cards, 4)
        self.assertAlmostEqual(timeline.seconds_per_card, 4.4, places=5)
        self.assertAlmostEqual(timeline.automatic_duration, 125.2, places=5)

    def test_custom_length_does_not_retime_reveals(self) -> None:
        automatic = Timeline(Project(cards=self.cards, model_id=MODEL_ILLUSTRATED), 30)
        longer = Timeline(
            Project(cards=self.cards, model_id=MODEL_ILLUSTRATED, custom_duration=180.0),
            30,
        )
        for sample in (0.0, 1.0, 2.5, 4.25, 7.9):
            self.assertAlmostEqual(automatic.model_time(sample), longer.model_time(sample), places=5)

    def test_custom_length_only_changes_horizontal_scroll_window(self) -> None:
        automatic = Timeline(Project(cards=self.cards, model_id=MODEL_ILLUSTRATED), 30)
        longer = Timeline(
            Project(cards=self.cards, model_id=MODEL_ILLUSTRATED, custom_duration=180.0),
            30,
        )
        self.assertLess(longer.model_time(20.0), automatic.model_time(20.0))
        self.assertGreater(longer.seconds_per_card, automatic.seconds_per_card)
        self.assertAlmostEqual(longer.fade_amount(179.7), automatic.fade_amount(124.9), places=5)

    def test_four_cards_are_placed_across_viewport(self) -> None:
        project = Project(cards=self.cards, model_id=MODEL_ILLUSTRATED)
        placements = Timeline(project, len(self.cards)).placements(8.0, 640.0)
        self.assertEqual([item.index for item in placements], [0, 1, 2, 3])
        self.assertEqual([round(item.x) for item in placements], [0, 160, 320, 480])


if __name__ == "__main__":
    unittest.main()
