import json
import tempfile
import unittest
from pathlib import Path

from comparison_studio.rewrite.model import (
    MODEL_ILLUSTRATED,
    AudioSettings,
    Card,
    Project,
    load_project,
    parse_table,
    save_project,
)


class RewriteModelTests(unittest.TestCase):
    def test_paste_table_maps_all_illustrated_fields(self) -> None:
        cards = parse_table(
            "Value\tLabel\tTitle\tDescription\tImage\n"
            "10.0/10\tFOUR HEMISPHERES\tKiribati\tPacific islands.\thttps://example.test/a.png\n"
        )
        self.assertEqual(len(cards), 1)
        self.assertEqual(cards[0].value, "10.0/10")
        self.assertEqual(cards[0].label, "FOUR HEMISPHERES")
        self.assertEqual(cards[0].title, "Kiribati")
        self.assertEqual(cards[0].description, "Pacific islands.")
        self.assertEqual(cards[0].image, "https://example.test/a.png")

    def test_rewrite_project_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "project.cts.json"
            project = Project(
                cards=[Card("9.8/10", "NO CAPITAL", "Nauru", "Description", "/tmp/a.png")],
                model_id=MODEL_ILLUSTRATED,
                custom_duration=125.0,
                badge_bounce=False,
                audio=AudioSettings("song.mp3", 0.75, True),
            )
            save_project(project, path)
            loaded = load_project(path)
            self.assertEqual(loaded.model_id, MODEL_ILLUSTRATED)
            self.assertEqual(loaded.cards[0].label, "NO CAPITAL")
            self.assertEqual(loaded.custom_duration, 125.0)
            self.assertFalse(loaded.badge_bounce)
            self.assertEqual(loaded.audio.volume, 0.75)
            self.assertTrue(loaded.audio.loop)

    def test_legacy_v2_project_is_migrated(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.cts.json"
            payload = {
                "version": 2,
                "spreadsheet": {
                    "headers": ["Value", "Label", "Title", "Description", "Image"],
                    "rows": [["1", "ONE", "Title", "Desc", "art.png"]],
                },
                "settings": {
                    "model_id": MODEL_ILLUSTRATED,
                    "width": 1280,
                    "height": 720,
                    "fps": 30,
                    "hexagons_bounce": False,
                },
                "audio_tracks": [{"path": "song.wav", "volume": 0.5, "loop": True}],
            }
            path.write_text(json.dumps(payload), encoding="utf-8")
            loaded = load_project(path)
            self.assertEqual(loaded.cards[0], Card("1", "ONE", "Title", "Desc", "art.png"))
            self.assertEqual((loaded.width, loaded.height), (1280, 720))
            self.assertFalse(loaded.badge_bounce)
            self.assertEqual(loaded.audio.path, "song.wav")


if __name__ == "__main__":
    unittest.main()
