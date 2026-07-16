import unittest

from comparison_studio.data import MODEL_ILLUSTRATED, MODEL_SCHEMAS, ProjectSettings
from comparison_studio.illustrated_video_profile import install_illustrated_video_profile


install_illustrated_video_profile()


class IllustratedVideoProfileTests(unittest.TestCase):
    def test_thirty_cards_auto_length_matches_reference_scroll_rate(self) -> None:
        settings = ProjectSettings(model_id=MODEL_ILLUSTRATED)

        self.assertAlmostEqual(settings.auto_duration(30), 125.2, places=4)
        self.assertAlmostEqual(settings.seconds_per_card(30), 4.4, places=4)

    def test_custom_length_does_not_retime_reveal_animation(self) -> None:
        automatic = ProjectSettings(model_id=MODEL_ILLUSTRATED)
        longer = ProjectSettings(model_id=MODEL_ILLUSTRATED, custom_duration=180.0)

        self.assertAlmostEqual(automatic.model_time(4.25, 30), 4.25, places=4)
        self.assertAlmostEqual(longer.model_time(4.25, 30), 4.25, places=4)

    def test_longer_video_only_slows_the_scrolling_window(self) -> None:
        automatic = ProjectSettings(model_id=MODEL_ILLUSTRATED)
        longer = ProjectSettings(model_id=MODEL_ILLUSTRATED, custom_duration=180.0)
        sample_time = 18.0

        self.assertLess(longer.model_time(sample_time, 30), automatic.model_time(sample_time, 30))

    def test_description_is_part_of_illustrated_schema(self) -> None:
        roles = [role for _label, role in MODEL_SCHEMAS[MODEL_ILLUSTRATED]]

        self.assertIn("description", roles)


if __name__ == "__main__":
    unittest.main()
