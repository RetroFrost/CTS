from __future__ import annotations

import unittest

from comparison_studio.data import MODEL_ILLUSTRATED, ProjectSettings
from comparison_studio.easy_timing import with_easy_timing
from comparison_studio.shared_contract import SCROLL_SECONDS, VISIBLE_CARDS
from comparison_studio.studio_ui import StudioProjectSettings


class EasyTimingTests(unittest.TestCase):
    def test_custom_target_scales_the_complete_animation(self) -> None:
        base = ProjectSettings(
            custom_duration=30.0,
            model_id=MODEL_ILLUSTRATED,
            visible_cards=3,
        )
        easy = with_easy_timing(base)
        automatic = easy.auto_duration(9)
        expected_speed = automatic / 30.0

        self.assertEqual(easy.duration(9), 30.0)
        self.assertEqual(easy.effective_visible_cards(), VISIBLE_CARDS)
        self.assertAlmostEqual(easy.model_time(3.0, 9), 3.0 * expected_speed, places=6)
        self.assertNotEqual(base.model_time(3.0, 9), easy.model_time(3.0, 9))

    def test_scroll_cadence_scales_with_android_speed(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=30.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )
        expected_speed = settings.auto_duration(9) / settings.duration(9)

        self.assertAlmostEqual(
            settings.seconds_per_card(9),
            SCROLL_SECONDS / expected_speed,
            places=6,
        )
        self.assertAlmostEqual(
            settings.model_time(16.6, 9),
            16.6 * expected_speed,
            places=6,
        )
        self.assertAlmostEqual(
            settings.model_time(28.2, 9),
            28.2 * expected_speed,
            places=6,
        )

    def test_custom_target_is_honored_when_all_cards_fit(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=100.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )

        self.assertEqual(settings.effective_visible_cards(), VISIBLE_CARDS)
        self.assertAlmostEqual(settings.duration(3), 100.0)
        self.assertEqual(settings.seconds_per_card(3), 0.0)
        self.assertAlmostEqual(
            settings.model_time(50.0, 3),
            settings.auto_duration(3) / 2.0,
            places=6,
        )

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
