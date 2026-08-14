from __future__ import annotations

import unittest

from ccengine.exact_reference_frames import continuous_card_x, relationships_last_card_x
from ccengine.model_registry import MODEL_TYPES_OF_RELATIONSHIPS, MODEL_WHAT_MALES_LEARN
from ccengine.reference_motion import continuous_shift


class ExactReferenceFramesTest(unittest.TestCase):
    def test_males_uses_measured_source_coordinates(self) -> None:
        expected = {
            528: 1.5,
            535: -6.5,
            620: -288.0,
            648: -349.0,
            660: -376.0,
            672: -402.0,
            16_335: -35_212.5,
        }
        for frame, x in expected.items():
            with self.subTest(frame=frame):
                self.assertEqual(x, continuous_card_x(MODEL_WHAT_MALES_LEARN, frame, 0))

    def test_males_final_conveyor_state_is_held_during_outro(self) -> None:
        final_x = continuous_card_x(MODEL_WHAT_MALES_LEARN, 16_335, 77)
        self.assertEqual(final_x, continuous_card_x(MODEL_WHAT_MALES_LEARN, 16_740, 77))

    def test_relationships_uses_measured_conveyor_and_final_card_tracks(self) -> None:
        self.assertEqual(-0.5, continuous_card_x(MODEL_TYPES_OF_RELATIONSHIPS, 896, 0))
        self.assertEqual(-184.5, continuous_card_x(MODEL_TYPES_OF_RELATIONSHIPS, 1_000, 0))
        self.assertEqual(1_031.0, relationships_last_card_x(10_670))
        self.assertEqual(343.0, relationships_last_card_x(10_738))
        self.assertEqual(781.0, relationships_last_card_x(11_129))

    def test_compatibility_shift_resolves_from_the_exact_card_position(self) -> None:
        self.assertAlmostEqual(376.0 / 477.0, continuous_shift(MODEL_WHAT_MALES_LEARN, 660))


if __name__ == "__main__":
    unittest.main()
