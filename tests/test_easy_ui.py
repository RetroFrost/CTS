from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PySide6.QtWidgets import QApplication, QLineEdit, QPushButton

from comparison_studio.data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    AudioTrack,
    ProjectSettings,
    SpreadsheetData,
    save_project_json,
)
from comparison_studio.easy_timing import EasyTimingMixin
from comparison_studio.easy_ui import (
    EASY_STYLE,
    EasyMainWindow,
    InsertDataDialog,
    StyleDialog,
    _table_as_text,
    load_table_file,
)


class EasyWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])
        cls.app.setStyle("Fusion")
        cls.app.setStyleSheet(EASY_STYLE)

    def test_easy_window_builds_like_the_android_workflow(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            self.app.processEvents()

            self.assertEqual(window.model_combo.currentData(), MODEL_ILLUSTRATED)
            self.assertEqual(window._wizard_step, 0)
            self.assertEqual(window.wizard_stack.currentIndex(), 0)
            self.assertEqual(window.wizard_heading.text(), "Spreadsheet")
            self.assertEqual(window.wizard_progress.text(), "STEP 1 OF 5")
            self.assertEqual(window.insert_data_button.text(), "SELECT SPREADSHEET")
            self.assertTrue(window.insert_data_button.isVisible())
            self.assertFalse(window.easy_style_button.isVisible())
            self.assertFalse(window.easy_music_button.isVisible())
            self.assertFalse(window.easy_timing_button.isVisible())
            self.assertFalse(window.easy_export_button.isVisible())
            self.assertFalse(window.fix_button.isVisible())
            self.assertEqual(window.fix_button.text(), "Manual editor")
            self.assertFalse(window.wizard_back.isEnabled())
            self.assertFalse(window.wizard_next.isEnabled())
            self.assertTrue(window.fix_panel.isHidden())
            self.assertEqual(window.table.rowCount(), 0)
            self.assertEqual(window.soundtrack_table.rowCount(), 0)
            self.assertIs(window.export_button, window.easy_export_button)
            self.assertIsInstance(window.project_settings(), EasyTimingMixin)
            self.assertFalse(window.easy_export_button.isEnabled())
            self.assertFalse(window.easy_timing_button.isEnabled())
            self.assertIn("optional", window.easy_music_button.text().lower())
            self.assertIn("Select a spreadsheet to begin", window.android_duration.text())
            self.assertIsNotNone(window.preview.parentWidget())
            self.assertTrue(window.preview.isVisible())
            self.assertGreater(window.preview.width(), 0)
            self.assertGreater(window.preview.height(), 0)
            self.assertFalse(
                any(
                    button.isVisible() and button.text().endswith("Add card")
                    for button in window.monitor_panel.findChildren(QPushButton)
                )
            )
            self.assertIsInstance(window.preview._editor, QLineEdit)
            self.assertEqual(
                window.preview._empty_message,
                "Select a spreadsheet to create your first comparison",
            )
            self.assertIn("video setup step 1 of 5", window.monitor_hint.text())
            window._set_wizard_step(4)
            self.assertEqual(window._wizard_step, 0)
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Artwork"],
                    [["84", "PERCENT", "Example", ""]],
                )
            )
            window._set_wizard_step(4)
            self.app.processEvents()
            self.assertEqual(window.wizard_heading.text(), "Ready to export")
            self.assertTrue(window.easy_export_button.isVisible())
            self.assertTrue(window.fix_button.isVisible())
            window.fix_button.setChecked(True)
            self.assertFalse(window.fix_panel.isHidden())
            self.assertTrue(window.android_sheet.isHidden())
            self.assertEqual(window.subtitle_label.text(), "MANUAL")
            self.assertIn("Click a field", window.monitor_hint.text())
            window.fix_button.setChecked(False)
            self.assertTrue(window.fix_panel.isHidden())
            self.assertFalse(window.android_sheet.isHidden())
            self.assertEqual(window.subtitle_label.text(), "CREATE")
        finally:
            window.close()

    def test_insert_data_detects_cards_live_and_accepts_with_one_action(self) -> None:
        text = (
            "Badge Value\tBadge Label\tTitle\tArtwork\n"
            "84\tPERCENT\tExample\thttps://example.com/a.png\n"
            "63\tPERCENT\tSecond\thttps://example.com/b.png\n"
        )
        dialog = InsertDataDialog(text)
        try:
            self.assertEqual(dialog.editor.toPlainText(), text)
            self.assertTrue(dialog.use_data.isEnabled())
            self.assertEqual(dialog.use_data.text(), "Create 2 cards")
            self.assertIn("2 cards", dialog.status.text())
            self.assertIn("4 fields", dialog.status.text())

            dialog._accept_pasted()
            self.assertIsNotNone(dialog.selected_data)
            self.assertEqual(dialog.selected_data.row_count, 2)
        finally:
            dialog.close()

    def test_insert_data_waits_for_a_card_row(self) -> None:
        dialog = InsertDataDialog("Badge Value,Title")
        try:
            self.assertFalse(dialog.use_data.isEnabled())
            self.assertIn("add at least one card row", dialog.status.text())
        finally:
            dialog.close()

    def test_existing_data_reopens_as_an_editable_table(self) -> None:
        data = SpreadsheetData(
            ["Badge Value", "Title"],
            [["84", "Example"], ["63", "Second"]],
        )
        text = _table_as_text(data)
        dialog = InsertDataDialog(text, existing=True)
        try:
            self.assertEqual(dialog.heading.text(), "EDIT ALL DATA")
            self.assertEqual(dialog.editor.toPlainText(), text)
            self.assertEqual(dialog.use_data.text(), "Update 2 cards")
            self.assertTrue(dialog.use_data.isEnabled())
        finally:
            dialog.close()

    def test_inserted_cards_open_on_a_visible_ready_to_export_frame(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            data = SpreadsheetData(
                ["Badge Value", "Badge Label", "Title", "Artwork"],
                [
                    ["84", "PERCENT", "Example", ""],
                    ["63", "PERCENT", "Second", ""],
                ],
            )
            window._apply_inserted_data(data)
            self.app.processEvents()

            self.assertEqual(window.table.rowCount(), 2)
            self.assertGreater(window.position_seconds, 0.0)
            self.assertIsNotNone(window.preview._image)
            self.assertTrue(window.easy_export_button.isEnabled())
            self.assertTrue(window.easy_timing_button.isEnabled())
            self.assertIn("Ready to export", window.android_duration.text())
            self.assertEqual(window.easy_timing_button.text(), "SET LENGTH · AUTOMATIC")
            self.assertEqual(window._wizard_step, 1)
            self.assertEqual(window.wizard_heading.text(), "Style")
        finally:
            window.close()

    def test_primary_action_selects_a_spreadsheet_directly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cards.csv"
            path.write_text(
                "Badge Value,Badge Label,Title,Artwork\n"
                "84,PERCENT,Example,\n",
                encoding="utf-8",
            )
            window = EasyMainWindow()
            try:
                window.resize(1366, 768)
                window.show()
                with patch(
                    "comparison_studio.easy_ui.QFileDialog.getOpenFileName",
                    return_value=(str(path), ""),
                ):
                    window._choose_spreadsheet_file()
                self.app.processEvents()

                self.assertEqual(window.table.rowCount(), 1)
                self.assertGreater(window.position_seconds, 0.0)
                self.assertIsNotNone(window.preview._image)
                self.assertTrue(window.easy_export_button.isEnabled())
                self.assertEqual(window._wizard_step, 1)
                self.assertIn("setup step 2", window.statusBar().currentMessage())
            finally:
                window.close()

    def test_style_choice_remaps_cards_and_refreshes_the_preview(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Artwork"],
                    [["84", "PERCENT", "Example", ""]],
                )
            )
            window._apply_style(MODEL_CLASSIC)
            self.app.processEvents()

            self.assertEqual(window.model_combo.currentData(), MODEL_CLASSIC)
            self.assertIn("CLASSIC COMPACT", window.easy_style_button.text())
            self.assertEqual(window._wizard_step, 2)
            self.assertEqual(window.wizard_heading.text(), "Music")
            self.assertGreater(window.position_seconds, 0.0)
            self.assertIsNotNone(window.preview._image)
            self.assertIn("Style selected", window.statusBar().currentMessage())
        finally:
            window.close()

    def test_style_dialog_returns_the_selected_model(self) -> None:
        dialog = StyleDialog(MODEL_ILLUSTRATED)
        try:
            dialog._choose(MODEL_REFERENCE)
            self.assertEqual(dialog.selected_model, MODEL_REFERENCE)
            self.assertEqual(dialog.result(), dialog.DialogCode.Accepted)
        finally:
            dialog.close()

    def test_opened_project_skips_the_black_opening_frame(self) -> None:
        data = SpreadsheetData(
            ["Badge Value", "Badge Label", "Title", "Artwork"],
            [["84", "PERCENT", "Example", ""]],
        )
        settings = ProjectSettings(
            model_id=MODEL_ILLUSTRATED,
            field_mapping={
                "badge_primary": "Badge Value",
                "badge_secondary": "Badge Label",
                "title": "Title",
                "image": "Artwork",
            },
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "example.cts.json"
            save_project_json(path, data, settings, [])
            window = EasyMainWindow()
            try:
                with patch(
                    "comparison_studio.interaction_runtime.QFileDialog.getOpenFileName",
                    return_value=(str(path), ""),
                ):
                    window.open_project()
                self.app.processEvents()

                self.assertEqual(window.table.rowCount(), 1)
                self.assertGreater(window.position_seconds, 0.0)
                self.assertIsNotNone(window.preview._image)
                self.assertEqual(window._wizard_step, 4)
                self.assertEqual(window.wizard_heading.text(), "Ready to export")
            finally:
                window.close()

    def test_setup_wizard_moves_one_step_at_a_time_and_supports_back(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Artwork"],
                    [["84", "PERCENT", "Example", ""]],
                )
            )
            self.assertEqual(window._wizard_step, 1)

            window.wizard_next.click()
            self.assertEqual(window._wizard_step, 2)
            self.assertEqual(window.wizard_next.text(), "Skip ›")
            window.wizard_next.click()
            self.assertEqual(window._wizard_step, 3)
            self.assertEqual(window.wizard_next.text(), "Review ›")
            window.wizard_next.click()
            self.assertEqual(window._wizard_step, 4)
            self.assertFalse(window.wizard_next.isVisible())

            window.wizard_back.click()
            self.assertEqual(window._wizard_step, 3)
            self.assertEqual(window.wizard_heading.text(), "Video length")
        finally:
            window.close()

    def test_fix_mode_uses_the_editor_instead_of_overlapping_on_short_windows(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(900, 560)
            window.show()
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Artwork"],
                    [["84", "PERCENT", "Example", ""]],
                )
            )
            window._set_wizard_step(4)
            window.fix_button.setChecked(True)
            self.app.processEvents()

            self.assertFalse(window.monitor_panel.isVisible())
            self.assertTrue(window.fix_panel.isVisible())
            self.assertFalse(window.cards_helper.isVisible())
            self.assertFalse(window.field_guide.isVisible())
            self.assertGreater(window.table.height(), 70)

            window.fix_button.setChecked(False)
            self.app.processEvents()
            self.assertTrue(window.monitor_panel.isVisible())
            self.assertTrue(window.preview.isVisible())
        finally:
            window.close()

    def test_long_soundtrack_name_does_not_crush_the_action_row(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(900, 560)
            window.show()
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Artwork"],
                    [["84", "PERCENT", "Example", ""]],
                )
            )
            window._set_wizard_step(2)
            long_name = "very_long_soundtrack_name_" * 12 + ".mp3"
            window.soundtrack_table.set_tracks([AudioTrack(path=f"/tmp/{long_name}")])
            self.app.processEvents()

            self.assertLess(window.minimumSizeHint().width(), 900)
            self.assertLess(len(window.easy_music_button.text()), len(long_name))
            self.assertEqual(window.easy_music_button.toolTip(), long_name)
            self.assertEqual(window.wizard_stack.currentIndex(), 2)
            self.assertGreaterEqual(window.wizard_next.width(), 55)
        finally:
            window.close()

    def test_single_import_action_reads_semicolon_csv(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "cards.csv"
            path.write_text(
                "Badge Value;Badge Label;Title;Artwork\n"
                "84;PERCENT;Example;https://example.com/image.png\n",
                encoding="utf-8",
            )
            data, warnings = load_table_file(path)

        self.assertEqual(warnings, [])
        self.assertEqual(data.headers, ["Badge Value", "Badge Label", "Title", "Artwork"])
        self.assertEqual(data.row_count, 1)
        self.assertEqual(data.rows[0][2], "Example")


if __name__ == "__main__":
    unittest.main()
