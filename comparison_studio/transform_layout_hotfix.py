from __future__ import annotations

from PySide6.QtWidgets import QLayout

from .direct_transform import DirectTransformMainWindow, TransformPreviewWidget


class TransformLayoutFixedMainWindow(DirectTransformMainWindow):
    """Install the transform overlay inside the existing Program Monitor body."""

    def _replace_preview_widget(self) -> None:
        old = self.preview
        parent = old.parentWidget()
        layout = parent.layout() if parent is not None else None
        if not isinstance(layout, QLayout):
            raise RuntimeError("CTS could not locate the Program Monitor preview layout.")

        replacement = TransformPreviewWidget(parent)
        replacement.field_clicked.connect(self._preview_field_clicked)
        replacement.inline_committed.connect(self._commit_direct_edit)
        replacement.inline_canceled.connect(self.update_preview)
        replacement.transform_requested.connect(self._transform_requested)
        replacement.transform_changed.connect(self._transform_changed)

        index = layout.indexOf(old)
        if index < 0:
            raise RuntimeError("CTS could not replace the Program Monitor preview widget.")

        # Replace only the preview widget in monitor_layout. The playback and sequence
        # controls remain in their original rows beneath it.
        layout.replaceWidget(old, replacement)
        old.hide()
        old.setParent(None)
        old.deleteLater()
        self.preview = replacement
