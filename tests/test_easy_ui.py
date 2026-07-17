from __future__ import annotations

import unittest

from PySide6.QtWidgets import QApplication

from comparison_studio.data import MODEL_ILLUSTRATED
from comparison_studio.easy_timing import EasyTimingMixin
from comparison_studio.easy_ui import EasyMainWindow


class EasyWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_easy_window_builds_with_simple_defaults(self) -> None:
        window = EasyMainWindow()
        try:
            self.assertEqual(window.model_combo.currentData(), MODEL_ILLUSTRATED)
            self.assertEqual(window.insert_data_button.text(), "＋  CLICK TO INSERT DATA")
            self.assertFalse(window.tabs.isTabVisible(1))
            self.assertFalse(window.tabs.isTabVisible(2))
            self.assertEqual(window.soundtrack_table.rowCount(), 0)
            self.assertIsInstance(window.project_settings(), EasyTimingMixin)
        finally:
            window.close()


if __name__ == "__main__":
    unittest.main()
