from __future__ import annotations

import unittest

from comparison_studio.data import MODEL_ILLUSTRATED, ProjectSettings
from comparison_studio.easy_timing import timeline_parts, with_easy_timing
from comparison_studio.shared_contract import SCROLL_SECONDS, VISIBLE_CARDS
from comparison_studio.studio_ui import StudioProjectSettings


class EasyTimingTests(unittest.TestCase):
    def test_custom_target_retimes_only_the_scroll_segment(self) -> None:
        base = ProjectSettings(
            custom_duration=30.0,
            model_id=MODEL_ILLUSTRATED,
            visible_cards=3,
        )
        easy = with_easy_timing(base)
        intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(easy, 9)
        chosen_scroll = easy.duration(9) - intro - fixed_tail

        self.assertEqual(easy.duration(9), 30.0)
        self.assertEqual(easy.effective_visible_cards(), VISIBLE_CARDS)
        self.assertAlmostEqual(easy.model_time(3.0, 9), 3.0, places=6)
        self.assertAlmostEqual(
            easy.model_time(intro + chosen_scroll / 2.0, 9),
            intro + automatic_scroll / 2.0,
            places=6,
        )
        self.assertNotEqual(base.model_time(intro + chosen_scroll / 2.0, 9), easy.model_time(intro + chosen_scroll / 2.0, 9))
        self.assertEqual(scroll_steps, 5)

    def test_scroll_cadence_uses_the_remaining_target_time(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=30.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )
        intro, scroll_steps, automatic_scroll, fixed_tail = timeline_parts(settings, 9)
        chosen_scroll = settings.duration(9) - intro - fixed_tail
        expected_seconds_per_card = chosen_scroll / scroll_steps
        expected_speed = automatic_scroll / chosen_scroll

        self.assertAlmostEqual(settings.seconds_per_card(9), expected_seconds_per_card, places=6)
        self.assertAlmostEqual(settings.speed_multiplier(9), expected_speed, places=6)

        during_scroll = 16.6
        self.assertAlmostEqual(
            settings.model_time(during_scroll, 9),
            intro + (during_scroll - intro) * expected_speed,
            places=6,
        )

        after_scroll = intro + chosen_scroll + 1.0
        self.assertAlmostEqual(
            settings.model_time(after_scroll, 9),
            intro + automatic_scroll + 1.0,
            places=6,
        )

    def test_custom_target_is_ignored_when_all_cards_fit(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=100.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )

        self.assertEqual(settings.effective_visible_cards(), VISIBLE_CARDS)
        self.assertAlmostEqual(settings.duration(3), settings.auto_duration(3))
        self.assertEqual(settings.seconds_per_card(3), 0.0)
        self.assertAlmostEqual(settings.model_time(5.0, 3), 5.0, places=6)

    def test_visual_settings_survive_easy_wrapper(self) -> None:
        base = StudioProjectSettings(
            custom_duration=24.0,
            model_id=MODEL_ILLUSTRATED,
            illustrated_background="night",
            image_scale=1.35,
        )
        easy = with_easy_timing(base)

        self.assertEqual(easy.illustrated_background, "night")
        self.assertAlmostEqual(easy.image_scale, 1.35)
        self.assertIsInstance(easy, StudioProjectSettings)
        self.assertIs(easy, with_easy_timing(easy))


if __name__ == "__main__":
    unittest.main()
