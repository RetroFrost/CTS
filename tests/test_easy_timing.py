from __future__ import annotations

import unittest

from comparison_studio.data import MODEL_ILLUSTRATED, ProjectSettings
from comparison_studio.easy_timing import with_easy_timing
from comparison_studio.studio_ui import StudioProjectSettings


class EasyTimingTests(unittest.TestCase):
    def test_custom_target_keeps_intro_at_real_time(self) -> None:
        base = ProjectSettings(
            custom_duration=30.0,
            model_id=MODEL_ILLUSTRATED,
            visible_cards=3,
        )
        easy = with_easy_timing(base)

        self.assertEqual(easy.duration(9), 30.0)
        self.assertEqual(easy.model_time(3.0, 9), 3.0)
        self.assertNotEqual(base.model_time(3.0, 9), easy.model_time(3.0, 9))

    def test_only_scroll_segment_is_retimed(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=30.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )

        # Intro is 6 seconds. The six horizontal steps receive 21.2 seconds total,
        # while the final 2.8-second hold/fade remains at normal speed.
        self.assertAlmostEqual(settings.seconds_per_card(9), 21.2 / 6, places=6)
        self.assertAlmostEqual(settings.model_time(16.6, 9), 16.0, places=6)
        self.assertAlmostEqual(settings.model_time(28.2, 9), 27.0, places=6)

    def test_target_is_ignored_when_all_cards_fit(self) -> None:
        settings = with_easy_timing(
            ProjectSettings(
                custom_duration=100.0,
                model_id=MODEL_ILLUSTRATED,
                visible_cards=3,
            )
        )

        self.assertAlmostEqual(settings.duration(3), settings.auto_duration(3))
        self.assertEqual(settings.seconds_per_card(3), 0.0)

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
