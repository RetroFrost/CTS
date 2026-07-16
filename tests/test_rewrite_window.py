import os
import tempfile
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtWidgets import QApplication

from comparison_studio.rewrite.practical_workspace import PracticalWorkspaceWindow


class RewriteWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_practical_workspace_keeps_mobile_quick_workflow(self) -> None:
        window = PracticalWorkspaceWindow()
        try:
            self.assertIn("1.0.0-alpha1", window.windowTitle())
            self.assertEqual(window.table.columnCount(), 5)
            self.assertEqual(window.table.rowCount(), 4)
            self.assertEqual(window.model_combo.count(), 3)
            self.assertFalse(window.preview._pixmap.isNull())
            self.assertEqual(window.tabs.tabText(0), "Edit")
            self.assertEqual(window.tabs.tabText(1), "Data")
            self.assertEqual(window.tabs.tabText(2), "Style")
            self.assertEqual(window.tabs.tabText(3), "Audio")
            self.assertEqual(window.tabs.tabText(4), "Export")
            self.assertEqual(len(window._card_buttons), 4)
            self.assertFalse(hasattr(window, "sequence_view"))

            window.table.selectRow(0)
            window.quick_title.setText("Fast desktop editing")
            window._commit_quick_editor()
            self.assertEqual(window.table.item(0, 2).text(), "Fast desktop editing")
            self.assertEqual(window.project.cards[0].title, "Fast desktop editing")

            with tempfile.TemporaryDirectory() as directory:
                artwork = os.path.join(directory, "artwork.png")
                Image.new("RGB", (32, 32), (20, 90, 180)).save(artwork)
                QApplication.clipboard().setText(artwork)
                window.paste_selected_artwork()
                self.assertEqual(window.table.item(0, 4).text(), artwork)
        finally:
            window.project.dirty = False
            window.close()


if __name__ == "__main__":
    unittest.main()
