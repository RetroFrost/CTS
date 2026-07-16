import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from comparison_studio.rewrite.window import MainWindow


class RewriteWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_clean_window_builds_without_legacy_ui(self) -> None:
        window = MainWindow()
        try:
            self.assertIn("1.0.0-alpha1", window.windowTitle())
            self.assertEqual(window.table.columnCount(), 5)
            self.assertEqual(window.table.rowCount(), 4)
            self.assertEqual(window.model_combo.count(), 3)
            self.assertFalse(window.preview._pixmap.isNull())
        finally:
            window.project.dirty = False
            window.close()


if __name__ == "__main__":
    unittest.main()
