from __future__ import annotations

import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QObject, Qt, Signal
from PySide6.QtWidgets import QApplication

from comparison_studio.csv_text_easy import (
    CsvTextEasyMainWindow,
    ForegroundExportDialog,
)
from comparison_studio.data import SpreadsheetData


class _FakeExportWorker(QObject):
    stage_changed = Signal(str, str)
    progress_changed = Signal(int, int, float)
    completed = Signal(str)
    failed = Signal(str, str, str)
    canceled = Signal()

    def request_cancel(self) -> None:
        pass

    def start(self) -> None:
        pass


class CsvTextRegressionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def setUp(self) -> None:
        self.window = CsvTextEasyMainWindow()

    def tearDown(self) -> None:
        self.window.close()
        self.app.processEvents()

    def test_deleting_untitled_card_removes_the_selected_row(self) -> None:
        headers = ["Badge Value", "Badge Label", "Title", "Description", "Artwork"]
        self.window.table.set_data(
            SpreadsheetData(
                headers,
                [
                    ["1", "", "", "", ""],
                    ["2", "", "", "", ""],
                    ["3", "", "", "", ""],
                ],
            )
        )
        self.window.table.setCurrentCell(2, 0)
        self.window.table.selectRow(2)

        self.window._delete_selected_cards()

        self.assertEqual(self.window.table.rowCount(), 2)
        self.assertEqual(
            [self.window.table.item(row, 0).text() for row in range(2)],
            ["1", "2"],
        )

    def test_export_dialog_is_foreground_and_renders_live_frame(self) -> None:
        cards = self.window.cards()
        settings = self.window.project_settings()
        worker = _FakeExportWorker()
        dialog = ForegroundExportDialog(worker, cards, settings, self.window)
        dialog.stage_label.setText("Rendering")

        dialog._render_progress_preview(1, 10, 0.0)

        self.assertEqual(dialog.windowModality(), Qt.WindowModality.ApplicationModal)
        self.assertIsNotNone(dialog.export_preview._image)
        dialog.close()


if __name__ == "__main__":
    unittest.main()
