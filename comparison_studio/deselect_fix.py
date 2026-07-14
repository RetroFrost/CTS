from __future__ import annotations

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QCursor, QKeySequence, QShortcut
from PySide6.QtWidgets import QMenu

from .live_transform import LiveTransformPreviewWidget
from .optional_hexagons import OptionalHexagonMainWindow


class DeselectablePreviewWidget(LiveTransformPreviewWidget):
    """Live transform preview with predictable click-away deselection."""

    selection_cleared = Signal()

    def _clear_selection(self) -> None:
        if not self.is_transforming:
            return
        self.clear_transform()
        self.selection_cleared.emit()

    def mousePressEvent(self, event) -> None:  # noqa: N802
        point = event.position().toPoint()

        if self.is_transforming:
            box = self._screen_box()
            on_handle = any(handle.contains(point) for handle in self._handle_rects().values())
            inside_box = box is not None and box.contains(point)

            # A normal click anywhere outside the selected object is an explicit
            # deselect. Consume the first click so it does not unexpectedly edit
            # or select another field at the same time.
            if event.button() == Qt.MouseButton.LeftButton and not (inside_box or on_handle):
                self._clear_selection()
                event.accept()
                return

            # The black monitor margin is not a model object, so right-clicking it
            # should also drop the selection immediately.
            if (
                event.button() == Qt.MouseButton.RightButton
                and (self._video_rect is None or not self._video_rect.contains(point))
            ):
                self._clear_selection()
                event.accept()
                return

        super().mousePressEvent(event)


class DeselectFixedMainWindow(OptionalHexagonMainWindow):
    """CTS window where transform selections can always be dismissed."""

    def _replace_preview_widget(self) -> None:
        old = self.preview
        replacement = DeselectablePreviewWidget()
        replacement.field_clicked.connect(self._preview_field_clicked)
        replacement.inline_committed.connect(self._commit_direct_edit)
        replacement.inline_canceled.connect(self.update_preview)
        replacement.transform_requested.connect(self._transform_requested)
        replacement.transform_changed.connect(self._transform_changed)
        replacement.selection_cleared.connect(self._selection_was_cleared)

        parent = old.parentWidget()
        layout = parent.layout() if parent is not None else None
        if layout is None:
            raise RuntimeError("Program Monitor preview has no parent layout")
        index = layout.indexOf(old)
        if index < 0:
            raise RuntimeError("Program Monitor preview is missing from its parent layout")
        layout.replaceWidget(old, replacement)
        old.setParent(None)
        old.deleteLater()
        self.preview = replacement

    def __init__(self) -> None:
        super().__init__()

        # The old Escape handler lived in PreviewWidget.keyPressEvent, but the
        # spreadsheet or another control usually owns keyboard focus. A window
        # shortcut works regardless of which panel was clicked last.
        self._deselect_shortcut = QShortcut(QKeySequence(Qt.Key.Key_Escape), self)
        self._deselect_shortcut.setContext(Qt.ShortcutContext.WindowShortcut)
        self._deselect_shortcut.activated.connect(self._clear_transform_selection)

        self.statusBar().showMessage(
            "Ready · Esc or click outside deselects · live transforms · optional hexagons"
        )

    def _selection_was_cleared(self) -> None:
        self.statusBar().showMessage("Object deselected", 2200)

    def _clear_transform_selection(self) -> None:
        if getattr(self, "preview", None) is None or not self.preview.is_transforming:
            return
        self.preview.clear_transform()
        self.statusBar().showMessage("Object deselected", 2200)

    def _transform_requested(self, normalized_x: float, normalized_y: float) -> None:
        self.pause_playback()
        settings = self.project_settings()
        hit = self.renderer.hit_test(
            self.cards(),
            self.position_seconds,
            settings,
            normalized_x,
            normalized_y,
        )
        if hit is None:
            self._clear_transform_selection()
            self.statusBar().showMessage("No text or image object is visible at that point", 3000)
            return

        card_index, role = hit
        default_region = self.renderer.editor_region(
            self.cards(), self.position_seconds, settings, card_index, role
        )
        if default_region is None:
            return
        current = self.transform_overrides.get((card_index, role), default_region)

        menu = QMenu(self)
        transform = menu.addAction("Transform image" if role == "image" else "Transform text box")
        edit = menu.addAction("Replace image…" if role == "image" else "Edit text")
        reset = menu.addAction("Reset position and size")
        menu.addSeparator()
        deselect = menu.addAction("Deselect object")
        selected = menu.exec(QCursor.pos())

        if selected is transform:
            self.preview.begin_transform(card_index, role, current)
            self.statusBar().showMessage(
                "Drag inside to move · drag a corner to resize · click outside or press Esc to deselect",
                7000,
            )
        elif selected is edit:
            if role == "image":
                header = self.field_mapping().get(role, "")
                if header in self.table.headers():
                    self._choose_image_for_row(card_index, self.table.headers().index(header))
            else:
                self._preview_field_clicked(normalized_x, normalized_y)
        elif selected is reset:
            self._transform_reset(card_index, role)
        elif selected is deselect:
            self._clear_transform_selection()
