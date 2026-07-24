from __future__ import annotations

import unittest

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication

from comparison_studio.data import SpreadsheetData
from comparison_studio.desktop_v2 import DESKTOP_STYLE, DesktopMainWindow


class DesktopV2Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])
        cls.app.setStyle("Fusion")
        cls.app.setStyleSheet(DESKTOP_STYLE)

    def test_desktop_workspace_keeps_setup_and_preview_visible(self) -> None:
        window = DesktopMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            self.app.processEvents()

            self.assertEqual(window.content_splitter.orientation(), Qt.Orientation.Horizontal)
            self.assertEqual(window.workspace_splitter.orientation(), Qt.Orientation.Vertical)
            self.assertTrue(window.workflow_panel.isVisible())
            self.assertTrue(window.monitor_panel.isVisible())
            self.assertTrue(window.fix_panel.isHidden())
            self.assertEqual(window.insert_data_button.text(), "PASTE CSV TEXT")
            self.assertIn("ANDROID SYNC", window.easy_style_button.text())
            self.assertEqual(window.subtitle_label.text(), "DESKTOP WORKSPACE")
            self.assertIn("Desktop", window.windowTitle())
        finally:
            window.close()

    def test_detailed_editor_opens_below_preview_without_hiding_workflow(self) -> None:
        window = DesktopMainWindow()
        try:
            window.resize(1366, 768)
            window.show()
            window._apply_inserted_data(
                SpreadsheetData(
                    ["Badge Value", "Badge Label", "Title", "Description", "Artwork"],
                    [["84", "PERCENT", "Example", "Example description", ""]],
                )
            )
            window._set_wizard_step(4)
            window.fix_button.setChecked(True)
            self.app.processEvents()

            self.assertTrue(window.workflow_panel.isVisible())
            self.assertTrue(window.monitor_panel.isVisible())
            self.assertTrue(window.fix_panel.isVisible())
            self.assertEqual(window.subtitle_label.text(), "MANUAL EDITING")
            self.assertIn("Close detailed editor", window.fix_button.text())

            window.fix_button.setChecked(False)
            self.app.processEvents()
            self.assertTrue(window.fix_panel.isHidden())
            self.assertEqual(window.subtitle_label.text(), "DESKTOP WORKSPACE")
        finally:
            window.close()


if __name__ == "__main__":
    unittest.main()
