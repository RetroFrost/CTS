import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from comparison_studio.rewrite.premiere_workspace import PremiereWorkspaceWindow


class RewriteWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_premiere_workspace_builds_on_clean_engine(self) -> None:
        window = PremiereWorkspaceWindow()
        try:
            self.assertIn("1.0.0-alpha1", window.windowTitle())
            self.assertEqual(window.table.columnCount(), 5)
            self.assertEqual(window.table.rowCount(), 4)
            self.assertEqual(window.model_combo.count(), 3)
            self.assertFalse(window.preview._pixmap.isNull())
            self.assertEqual(window.project_tabs.tabText(0), "Project: CTS")
            self.assertEqual(window.tabs.tabText(0), "Effect Controls")
            self.assertEqual(window.tabs.tabText(1), "Essential Graphics")
            self.assertGreaterEqual(window.sequence_view.minimumHeight(), 150)
        finally:
            window.project.dirty = False
            window.close()


if __name__ == "__main__":
    unittest.main()
