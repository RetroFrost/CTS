import os
import tempfile
import unittest

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PIL import Image
from PySide6.QtCore import QPoint, Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QLineEdit

from comparison_studio.rewrite.image_transform import parse_image_reference
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
            self.assertEqual(window.insert_data_button.text(), "＋  CLICK TO INSERT DATA")
            self.assertTrue(window.insert_data_button.isVisibleTo(window))

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

    def test_click_to_insert_data_pastes_table_from_default_screen(self) -> None:
        window = PracticalWorkspaceWindow()
        try:
            QApplication.clipboard().setText(
                "Value\tLabel\tTitle\tDescription\tImage\n"
                "1\tONE\tFirst\tFirst description\t\n"
                "2\tTWO\tSecond\tSecond description\t"
            )
            window.insert_data_button.click()

            self.assertEqual(window.table.rowCount(), 2)
            self.assertEqual(window.table.item(0, 2).text(), "First")
            self.assertEqual(window.table.item(1, 2).text(), "Second")
            self.assertEqual(window.tabs.currentIndex(), 0)
            self.assertEqual(len(window._card_buttons), 2)
        finally:
            QApplication.clipboard().clear()
            window.project.dirty = False
            window.close()

    def test_click_rendered_title_and_type_directly_on_object(self) -> None:
        window = PracticalWorkspaceWindow()
        try:
            window.resize(1200, 720)
            window.table.item(0, 2).setText("Old title")
            window._sync_project_from_table()
            window.current_time = 8.0
            window._refresh_all()
            window.show()
            self.app.processEvents()
            window.preview.repaint()
            self.app.processEvents()

            rect = window.preview.field_rect(0, "title")
            self.assertIsNotNone(rect)
            assert rect is not None
            QTest.mouseClick(window.preview, Qt.MouseButton.LeftButton, pos=rect.center())
            self.app.processEvents()

            editor = window.preview.active_editor
            self.assertIsInstance(editor, QLineEdit)
            assert isinstance(editor, QLineEdit)
            self.assertEqual(window.preview.editing_field, "title")
            self.assertEqual(window.preview.selected_field, "title")
            self.assertEqual(window.preview.selected_field_rect, rect)
            self.assertTrue(rect.intersects(editor.geometry()))
            self.assertEqual(editor.placeholderText(), "Title")

            editor.setText("Typed on the preview")
            QTest.keyClick(editor, Qt.Key.Key_Return)
            self.app.processEvents()

            self.assertEqual(window.table.item(0, 2).text(), "Typed on the preview")
            self.assertEqual(window.project.cards[0].title, "Typed on the preview")
            self.assertIsNone(window.preview.active_editor)
            self.assertEqual(window.preview.selected_field, "title")
            self.assertEqual(window.preview.selected_field_rect, rect)
        finally:
            window.project.dirty = False
            window.close()

    def test_blank_label_identifies_itself_as_optional_in_place(self) -> None:
        window = PracticalWorkspaceWindow()
        try:
            window.resize(1200, 720)
            window.current_time = 8.0
            window._refresh_all()
            window.show()
            self.app.processEvents()
            window.preview.repaint()
            self.app.processEvents()

            rect = window.preview.field_rect(0, "label")
            self.assertIsNotNone(rect)
            assert rect is not None
            QTest.mouseClick(window.preview, Qt.MouseButton.LeftButton, pos=rect.center())
            self.app.processEvents()

            editor = window.preview.active_editor
            self.assertIsInstance(editor, QLineEdit)
            assert isinstance(editor, QLineEdit)
            self.assertEqual(window.preview.selected_field, "label")
            self.assertEqual(editor.placeholderText(), "Label (optional)")
            self.assertTrue(rect.intersects(editor.geometry()))
        finally:
            window.project.dirty = False
            window.close()

    def test_dragging_side_handle_resizes_width_without_resizing_height(self) -> None:
        window = PracticalWorkspaceWindow()
        try:
            with tempfile.TemporaryDirectory() as directory:
                artwork = os.path.join(directory, "artwork.png")
                Image.new("RGBA", (120, 80), (30, 160, 220, 255)).save(artwork)
                window.resize(1200, 720)
                window.table.item(0, 4).setText(artwork)
                window._sync_project_from_table()
                window.current_time = 8.0
                window._refresh_all()
                window.show()
                self.app.processEvents()
                window.preview.repaint()
                self.app.processEvents()

                window.preview.select_image(0)
                self.app.processEvents()
                handle = window.preview.resize_handle_rect("e")
                self.assertIsNotNone(handle)
                assert handle is not None
                start = handle.center()
                finish = start + QPoint(55, 0)
                QTest.mousePress(
                    window.preview,
                    Qt.MouseButton.LeftButton,
                    pos=start,
                )
                QTest.mouseMove(window.preview, finish, delay=10)
                QTest.mouseRelease(
                    window.preview,
                    Qt.MouseButton.LeftButton,
                    pos=finish,
                )
                self.app.processEvents()

                _source, transform = parse_image_reference(window.table.item(0, 4).text())
                self.assertGreater(transform.width_scale, 1.15)
                self.assertAlmostEqual(transform.height_scale, 1.0, places=2)
                self.assertIn("×", window.image_zoom_label.text())
        finally:
            window.project.dirty = False
            window.close()


if __name__ == "__main__":
    unittest.main()
