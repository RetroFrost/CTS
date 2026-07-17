from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from PySide6.QtWidgets import QApplication

from comparison_studio.data import MODEL_ILLUSTRATED
from comparison_studio.easy_timing import EasyTimingMixin
from comparison_studio.easy_ui import EasyMainWindow, load_table_file


class EasyWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_easy_window_builds_like_the_android_workflow(self) -> None:
        window = EasyMainWindow()
        try:
            self.assertEqual(window.model_combo.currentData(), MODEL_ILLUSTRATED)
            self.assertIn("INSERT DATA", window.insert_data_button.text())
            self.assertFalse(window.fix_panel.isVisible())
            self.assertEqual(window.table.rowCount(), 0)
            self.assertEqual(window.soundtrack_table.rowCount(), 0)
            self.assertIs(window.export_button, window.easy_export_button)
            self.assertIsInstance(window.project_settings(), EasyTimingMixin)

            window.fix_button.setChecked(True)
            self.assertTrue(window.fix_panel.isVisible())
            self.assertEqual(window.subtitle_label.text(), "FIX")
            window.fix_button.setChecked(False)
            self.assertFalse(window.fix_panel.isVisible())
            self.assertEqual(window.subtitle_label.text(), "CREATE")
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
