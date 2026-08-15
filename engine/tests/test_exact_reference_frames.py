from __future__ import annotations

import unittest

from ccengine.exact_reference_frames import continuous_card_x, males_fade_alpha
from ccengine.model_registry import MODEL_WHAT_MALES_LEARN
from ccengine.reference_motion import continuous_shift


class ExactReferenceFramesTest(unittest.TestCase):
    def test_males_uses_measured_source_coordinates(self) -> None:
        expected = {528: 9.5, 535: 1.5, 620: -280.0, 648: -341.0, 660: -368.0, 672: -394.0}
        for frame, x in expected.items():
            with self.subTest(frame=frame):
                self.assertEqual(x, continuous_card_x(MODEL_WHAT_MALES_LEARN, frame, 0))

    def test_final_conveyor_state_is_held_during_outro(self) -> None:
        final_x = continuous_card_x(MODEL_WHAT_MALES_LEARN, 11_841, 56)
        self.assertEqual(final_x, continuous_card_x(MODEL_WHAT_MALES_LEARN, 12_266, 56))

    def test_compatibility_shift_resolves_from_exact_position(self) -> None:
        self.assertAlmostEqual(368.0 / 476.0, continuous_shift(MODEL_WHAT_MALES_LEARN, 660))

    def test_measured_fade_and_black_boundary(self) -> None:
        self.assertEqual(1.0, males_fade_alpha(12_179))
        self.assertGreater(males_fade_alpha(12_180), 0.0)
        self.assertEqual(0.0, males_fade_alpha(12_258))
        self.assertEqual(0.0, males_fade_alpha(12_259))


if __name__ == "__main__":
    unittest.main()
