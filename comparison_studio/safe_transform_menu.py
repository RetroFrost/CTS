from __future__ import annotations

from PySide6.QtGui import QCursor
from PySide6.QtWidgets import QMenu

from .screen_locked_transform import ScreenLockedMainWindow


class SafeTransformMainWindow(ScreenLockedMainWindow):
    """Screen-locked transforms with an unambiguous object context menu."""

    def _transform_requested(self, normalized_x: float, normalized_y: float) -> None:
        self.pause_playback()
        settings = self.project_settings()
        cards = self.cards()
        hit = self.renderer.hit_test(
            cards,
            self.position_seconds,
            settings,
            normalized_x,
            normalized_y,
        )
        if hit is None:
            self._clear_transform_selection()
            self.statusBar().showMessage(
                "No text or image object is visible at that point",
                3000,
            )
            return

        card_index, role = hit
        current_screen = self.renderer.editor_region(
            cards,
            self.position_seconds,
            settings,
            card_index,
            role,
        )
        if current_screen is None:
            return

        menu = QMenu(self)
        transform = menu.addAction(
            "Transform image" if role == "image" else "Transform text box"
        )
        edit = menu.addAction("Replace image…" if role == "image" else "Edit text")
        menu.addSeparator()
        deselect = menu.addAction("Deselect object — keep position")
        menu.addSeparator()
        reset = menu.addAction("Remove transform — return to moving card")
        reset.setToolTip(
            "Deletes the custom position and size. The object will follow its card again."
        )

        selected = menu.exec(QCursor.pos())

        if selected is transform:
            self.preview.begin_transform(card_index, role, current_screen)
            self.statusBar().showMessage(
                "Drag inside to move · drag a corner to resize · deselect keeps this fixed position",
                7000,
            )
        elif selected is edit:
            if role == "image":
                header = self.field_mapping().get(role, "")
                if header in self.table.headers():
                    self._choose_image_for_row(
                        card_index,
                        self.table.headers().index(header),
                    )
            else:
                self._preview_field_clicked(normalized_x, normalized_y)
        elif selected is deselect:
            self._clear_transform_selection()
            self.statusBar().showMessage(
                "Object deselected · its fixed position and size were preserved",
                4000,
            )
        elif selected is reset:
            self._transform_reset(card_index, role)
            self.statusBar().showMessage(
                "Transform removed · the object has returned to its moving card",
                4500,
            )
