from __future__ import annotations

from PySide6.QtCore import QTimer

from .direct_transform import TransformPreviewWidget
from .interaction_runtime import InteractionMainWindow


class LiveTransformPreviewWidget(TransformPreviewWidget):
    """Transform overlay whose selected content redraws while the pointer moves."""

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._live_redraw = QTimer(self)
        self._live_redraw.setSingleShot(True)
        self._live_redraw.setInterval(16)
        self._live_redraw.timeout.connect(self._emit_live_transform)

    def _emit_live_transform(self) -> None:
        if self._drag_mode and self._transform_box is not None:
            self.transform_changed.emit(
                self._transform_card,
                self._transform_role,
                self._transform_box,
            )

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        was_dragging = bool(self._drag_mode)
        super().mouseMoveEvent(event)
        if was_dragging and self._drag_mode and self._transform_box is not None:
            # Coalesce rapid pointer events into approximately one render per display frame.
            if not self._live_redraw.isActive():
                self._live_redraw.start()

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        # Flush the last pointer position before the normal final commit.
        if self._live_redraw.isActive():
            self._live_redraw.stop()
            self._emit_live_transform()
        super().mouseReleaseEvent(event)


class LiveTransformMainWindow(InteractionMainWindow):
    """Final interaction window with real-time move and resize feedback."""

    def _replace_preview_widget(self) -> None:
        old = self.preview
        replacement = LiveTransformPreviewWidget()
        replacement.field_clicked.connect(self._preview_field_clicked)
        replacement.inline_committed.connect(self._commit_direct_edit)
        replacement.inline_canceled.connect(self.update_preview)
        replacement.transform_requested.connect(self._transform_requested)
        replacement.transform_changed.connect(self._transform_changed)

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
        self.statusBar().showMessage(
            "Ready · live text/image transforms · CSV/XLSX import · right-click objects"
        )
