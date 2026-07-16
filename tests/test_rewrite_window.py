import os
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from comparison_studio.rewrite.mobile_convenience import ConvenientPremiereWindow


class RewriteWindowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.app = QApplication.instance() or QApplication([])

    def test_premiere_workspace_keeps_mobile_quick_workflow(self) -> None:
        window = ConvenientPremiereWindow()
        try:
            self.assertIn("1.0.0-alpha1", window.windowTitle())
            self.assertEqual(window.table.columnCount(), 5)
            self.assertEqual(window.table.rowCount(), 4)
            self.assertEqual(window.model_combo.count(), 3)
            self.assertFalse(window.preview._pixmap.isNull())
            self.assertEqual(window.project_tabs.tabText(0), "Project: CTS")
            self.assertEqual(window.tabs.tabText(0), "Edit")
            self.assertEqual(window.tabs.tabText(1), "Style")
            self.assertEqual(window.tabs.tabText(2), "Audio")
            self.assertEqual(window.tabs.tabText(3), "Export")
            self.assertEqual(len(window._card_buttons), 4)
            self.assertGreaterEqual(window.sequence_view.minimumHeight(), 150)

            window.table.selectRow(0)
            window.quick_title.setText("Fast desktop editing")
            window._commit_quick_editor()
            self.assertEqual(window.table.item(0, 2).text(), "Fast desktop editing")
            self.assertEqual(window.project.cards[0].title, "Fast desktop editing")

            QApplication.clipboard().setText("https://example.com/artwork.png")
            window.paste_selected_artwork()
            self.assertEqual(
                window.table.item(0, 4).text(),
                "https://example.com/artwork.png",
            )
        finally:
            window.project.dirty = False
            window.close()


if __name__ == "__main__":
    unittest.main()
