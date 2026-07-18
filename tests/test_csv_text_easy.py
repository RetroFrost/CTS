import os
import unittest
from unittest.mock import patch

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication, QDialog, QPushButton

from comparison_studio.csv_text_easy import CsvTextDialog, CsvTextEasyMainWindow
from comparison_studio.data import SpreadsheetData


class CsvTextEasyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_first_setup_step_is_csv_text(self) -> None:
        window = CsvTextEasyMainWindow()
        try:
            window.resize(1200, 720)
            window.show()
            self.app.processEvents()

            self.assertEqual(window._wizard_step, 0)
            self.assertEqual(window.wizard_heading.text(), "CSV text")
            self.assertEqual(window.insert_data_button.text(), "PASTE CSV TEXT")
            self.assertIn("comma-separated", window.wizard_detail.text())
            self.assertIn("CSV text", window.wizard_trail.text())
            self.assertEqual(
                window.preview._empty_message,
                "Paste CSV text to create your first comparison",
            )
        finally:
            window.close()

    def test_csv_dialog_parses_comma_separated_cards_without_file_action(self) -> None:
        dialog = CsvTextDialog(
            "Badge Value,Badge Label,Title,Description,Artwork\n"
            "84,PERCENT,Example,Optional description,\n"
        )
        try:
            self.assertEqual(dialog.heading.text(), "PASTE CSV TEXT")
            self.assertTrue(dialog.use_data.isEnabled())
            self.assertEqual(dialog.use_data.text(), "Create 1 card")
            self.assertFalse(
                any(
                    button.isVisible() and "Import CSV" in button.text()
                    for button in dialog.findChildren(QPushButton)
                )
            )
            dialog._accept_pasted()
            self.assertIsNotNone(dialog.selected_data)
            self.assertEqual(dialog.selected_data.row_count, 1)
        finally:
            dialog.close()

    def test_accepting_csv_text_advances_to_style(self) -> None:
        window = CsvTextEasyMainWindow()
        fake = type(
            "FakeCsvDialog",
            (),
            {
                "selected_data": SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Description", "Artwork"],
                    [["84", "PERCENT", "Example", "", ""]],
                ),
                "warnings": [],
                "exec": lambda self: QDialog.DialogCode.Accepted,
            },
        )()
        try:
            with patch("comparison_studio.csv_text_easy.CsvTextDialog", return_value=fake):
                window._choose_spreadsheet_file()
            self.assertEqual(window.table.rowCount(), 1)
            self.assertEqual(window._wizard_step, 1)
            self.assertEqual(window.wizard_heading.text(), "Style")
        finally:
            window.close()


if __name__ == "__main__":
    unittest.main()
