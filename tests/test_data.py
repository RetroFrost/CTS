import unittest
import json
import tempfile
from pathlib import Path

from comparison_studio.data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    MODEL_SCHEMAS,
    AudioTrack,
    ProjectSettings,
    SpreadsheetData,
    cards_from_matrix,
    guess_field_mapping,
    load_project_document,
    parse_clipboard_data,
    parse_clipboard_table,
    parse_duration,
    resolve_cards,
    save_project_json,
)


class DataTests(unittest.TestCase):
    def test_clipboard_with_headers(self) -> None:
        cards = parse_clipboard_table(
            "Date\tTitle\tDescription\tImage\n"
            "2005-04-23\tFirst\tDescription one\t/a.png\n"
            "2005-04-24\tSecond\tDescription two\t/b.png\n"
        )
        self.assertEqual(len(cards), 2)
        self.assertEqual(cards[0].uploaded, "2005-04-23")
        self.assertEqual(cards[1].title, "Second")

    def test_header_aliases_and_blank_rows(self) -> None:
        cards = cards_from_matrix(
            [
                ["Upload Date", "Name", "Summary", "Thumbnail"],
                ["2005", "Example", "Text", "image.png"],
                [None, None, None, None],
            ]
        )
        self.assertEqual(len(cards), 1)
        self.assertEqual(cards[0].image, "image.png")

    def test_duration_parsing(self) -> None:
        self.assertEqual(parse_duration("90"), 90)
        self.assertEqual(parse_duration("01:30"), 90)
        self.assertEqual(parse_duration("01:01:30"), 3690)

    def test_custom_duration_changes_speed(self) -> None:
        settings = ProjectSettings()
        automatic = settings.auto_duration(10)
        custom = ProjectSettings(custom_duration=automatic / 2)
        self.assertEqual(custom.speed_multiplier(10), 2)
        self.assertLess(custom.seconds_per_card(10), settings.seconds_per_card(10))

    def test_arbitrary_columns_have_no_required_schema(self) -> None:
        data = parse_clipboard_data("Creature\tPower\tEra\tNotes\nDragon\t9000\tMythic\tFlying\n")
        self.assertEqual(data.headers, ["Creature", "Power", "Era", "Notes"])
        cards = resolve_cards(data, {"title": "Creature", "badge_primary": "Power"})
        self.assertEqual(len(cards), 1)
        self.assertEqual(cards[0].title, "Dragon")
        self.assertEqual(cards[0].uploaded, "9000")
        self.assertEqual(cards[0].description, "")
        self.assertEqual(cards[0].image, "")

    def test_blank_rows_can_be_styled_cards(self) -> None:
        cards = resolve_cards(SpreadsheetData(["Anything"], [[""], [""]]), {})
        self.assertEqual(len(cards), 2)
        self.assertTrue(all(card.is_blank() for card in cards))

    def test_model_native_visible_count(self) -> None:
        settings = ProjectSettings(model_id=MODEL_ILLUSTRATED)
        self.assertEqual(settings.effective_visible_cards(), 3)
        settings.visible_cards = 6
        self.assertEqual(settings.effective_visible_cards(), 6)

    def test_each_model_defines_the_fields_its_layout_uses(self) -> None:
        reference = {role: header for header, role in MODEL_SCHEMAS[MODEL_REFERENCE]}
        illustrated = {role: header for header, role in MODEL_SCHEMAS[MODEL_ILLUSTRATED]}
        classic = {role: header for header, role in MODEL_SCHEMAS[MODEL_CLASSIC]}
        self.assertEqual(reference["description"], "Description")
        self.assertNotIn("description", illustrated)
        self.assertNotIn("description", classic)
        self.assertEqual(illustrated["image"], "Artwork")
        self.assertEqual(classic["badge_secondary"], "Unit")

    def test_v2_project_round_trip(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "project.cts.json"
            data = SpreadsheetData(["Whatever", "Picture"], [["A", "/tmp/a.png"]])
            settings = ProjectSettings(
                model_id=MODEL_ILLUSTRATED,
                field_mapping={"title": "Whatever", "image": "Picture"},
            )
            tracks = [AudioTrack(path="song.mp3", volume=0.75, loop=True)]
            save_project_json(path, data, settings, tracks)
            document = load_project_document(path)
            self.assertEqual(document.data.headers, data.headers)
            self.assertEqual(document.settings.model_id, MODEL_ILLUSTRATED)
            self.assertEqual(document.settings.field_mapping["title"], "Whatever")
            self.assertEqual(document.audio_tracks[0].volume, 0.75)
            self.assertTrue(document.audio_tracks[0].loop)


if __name__ == "__main__":
    unittest.main()
