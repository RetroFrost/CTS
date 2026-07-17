from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from PySide6.QtWidgets import QApplication, QLineEdit

from comparison_studio.data import MODEL_ILLUSTRATED, AudioTrack
from comparison_studio.easy_timing import EasyTimingMixin
from comparison_studio.easy_ui import EASY_STYLE, EasyMainWindow, load_table_file


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
            self.assertIn("INSERT DATA", window.insert_data_button.text())
            self.assertTrue(window.fix_panel.isHidden())
            self.assertEqual(window.table.rowCount(), 0)
            self.assertEqual(window.soundtrack_table.rowCount(), 0)
            self.assertIs(window.export_button, window.easy_export_button)
            self.assertIsInstance(window.project_settings(), EasyTimingMixin)
            self.assertIsNotNone(window.preview.parentWidget())
            self.assertTrue(window.preview.isVisible())
            self.assertGreater(window.preview.width(), 0)
            self.assertGreater(window.preview.height(), 0)
            self.assertIsInstance(window.preview._editor, QLineEdit)
            self.assertEqual(
                window.preview._empty_message,
                "Click Insert Data to create your first comparison",
            )
            self.assertIn("open Fix Something", window.monitor_hint.text())
            self.assertEqual(window.fix_button.height(), window.easy_music_button.height())

            window.fix_button.setChecked(True)
            self.assertFalse(window.fix_panel.isHidden())
            self.assertTrue(window.android_sheet.isHidden())
            self.assertEqual(window.subtitle_label.text(), "FIX")
            self.assertIn("Click a field", window.monitor_hint.text())
            window.fix_button.setChecked(False)
            self.assertTrue(window.fix_panel.isHidden())
            self.assertFalse(window.android_sheet.isHidden())
            self.assertEqual(window.subtitle_label.text(), "CREATE")
        finally:
            window.close()

    def test_fix_mode_uses_the_editor_instead_of_overlapping_on_short_windows(self) -> None:
        window = EasyMainWindow()
        try:
            window.resize(900, 560)
            window.show()
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
            long_name = "very_long_soundtrack_name_" * 12 + ".mp3"
            window.soundtrack_table.set_tracks([AudioTrack(path=f"/tmp/{long_name}")])
            self.app.processEvents()

            self.assertLess(window.minimumSizeHint().width(), 900)
            self.assertLess(len(window.easy_music_button.text()), len(long_name))
            self.assertEqual(window.easy_music_button.toolTip(), long_name)
            self.assertGreaterEqual(window.easy_export_button.width(), 80)
            self.assertGreaterEqual(window.fix_button.width(), 80)
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
