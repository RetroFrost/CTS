from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path

from comparison_studio import shared_contract


ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "shared" / "cts_contract.json"
KOTLIN_PATH = (
    ROOT
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "io"
    / "github"
    / "retrofrost"
    / "cts"
    / "android"
    / "shared"
    / "SharedContract.kt"
)


class SharedContractTest(unittest.TestCase):
    def setUp(self) -> None:
        self.spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))

    def test_desktop_adapter_matches_source_contract(self) -> None:
        model = self.spec["canonical_model"]
        timing = self.spec["timing"]
        self.assertEqual(shared_contract.CONTRACT_VERSION, self.spec["contract_version"])
        self.assertEqual(shared_contract.PROJECT_VERSION, self.spec["project_version"])
        self.assertEqual(shared_contract.MODEL_ID, model["id"])
        self.assertEqual(shared_contract.MODEL_LABEL, model["label"])
        self.assertEqual(shared_contract.VISIBLE_CARDS, model["visible_cards"])
        self.assertAlmostEqual(shared_contract.REVEAL_SECONDS, timing["reveal_seconds"])
        self.assertAlmostEqual(shared_contract.SCROLL_SECONDS, timing["scroll_seconds"])
        self.assertAlmostEqual(
            shared_contract.INTRO_TAIL_HOLD_SECONDS,
            timing["intro_tail_hold_seconds"],
        )

    def test_every_legacy_model_opens_as_canonical_design(self) -> None:
        for model_id in self.spec["canonical_model"]["legacy_ids"]:
            self.assertEqual(shared_contract.normalize_model_id(model_id), shared_contract.MODEL_ID)
        self.assertEqual(shared_contract.normalize_model_id("future_unknown_model"), shared_contract.MODEL_ID)

    def test_android_and_desktop_share_sample_cards(self) -> None:
        kotlin = KOTLIN_PATH.read_text(encoding="utf-8")
        desktop_titles = [card.title for card in shared_contract.SAMPLE_CARDS]
        source_titles = [card["title"] for card in self.spec["sample_cards"]]
        self.assertEqual(desktop_titles, source_titles)
        for title in source_titles:
            self.assertIn(json.dumps(title, ensure_ascii=False), kotlin)

    def test_duration_matches_android_reference_timeline(self) -> None:
        expected = 4 * 2.0 + 0.8 + (10.0 / 3.0) + 2.0 + 0.8
        self.assertAlmostEqual(shared_contract.automatic_duration(5), expected, places=6)
        self.assertEqual(shared_contract.automatic_duration(0), 0.0)
        self.assertAlmostEqual(shared_contract.chosen_duration(5, 7.5), 7.5)
        self.assertAlmostEqual(shared_contract.model_time(5, 3.75, 7.5), expected / 2.0)

    def test_material_curve_and_scroll_shift_are_bounded(self) -> None:
        self.assertAlmostEqual(shared_contract.material_ease(0.0), 0.0, places=4)
        self.assertAlmostEqual(shared_contract.material_ease(1.0), 1.0, places=4)
        midpoint = shared_contract.material_ease(0.5)
        self.assertGreater(midpoint, 0.0)
        self.assertLess(midpoint, 1.0)
        self.assertEqual(shared_contract.placement_shift(-1.0, 4), 0.0)
        self.assertEqual(shared_contract.placement_shift(999.0, 4), 4.0)

    def test_generated_adapters_pass_drift_check(self) -> None:
        result = subprocess.run(
            [sys.executable, "tools/sync_shared_contract.py", "--check"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
