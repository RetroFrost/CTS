from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import QEvent, QObject, QPoint, QRect, Qt, QTimer, Signal
from PySide6.QtGui import QKeyEvent
from PySide6.QtWidgets import QLineEdit, QTextEdit, QWidget

from .model import MODEL_COMPACT, MODEL_ILLUSTRATED, MODEL_REFERENCE, Project
from .timing import Timeline
from .window import PreviewCanvas


@dataclass(frozen=True, slots=True)
class FieldHit:
    card_index: int
    field: str
    rect: QRect


def _field_regions(model_id: str) -> tuple[tuple[str, float, float, float, float], ...]:
    """Return normalized editable field rectangles inside one rendered card."""
    if model_id == MODEL_ILLUSTRATED:
        return (
            ("value", 0.14, 0.010, 0.86, 0.150),
            ("label", 0.14, 0.150, 0.86, 0.305),
            ("title", 0.015, 0.730, 0.985, 0.842),
            ("description", 0.020, 0.848, 0.980, 0.995),
        )
    if model_id == MODEL_COMPACT:
        return (
            ("value", 0.16, 0.025, 0.84, 0.145),
            ("label", 0.16, 0.145, 0.84, 0.290),
            ("title", 0.015, 0.390, 0.985, 0.495),
        )
    return (
        ("value", 0.14, 0.035, 0.86, 0.175),
        ("label", 0.14, 0.175, 0.86, 0.345),
        ("title", 0.015, 0.440, 0.985, 0.538),
        ("description", 0.020, 0.538, 0.980, 0.670),
    )


def hit_test_field(
    project: Project,
    output_time: float,
    viewport_width: int,
    viewport_height: int,
    x: float,
    y: float,
) -> FieldHit | None:
    """Resolve a preview coordinate to a card text field.

    The result is expressed in preview-video coordinates, not global widget coordinates.
    """
    if viewport_width <= 0 or viewport_height <= 0:
        return None
    cards = project.content_cards()
    timeline = Timeline(project, len(cards))
    card_width = viewport_width / timeline.visible_cards
    for placement in reversed(timeline.placements(output_time, float(viewport_width))):
        if placement.index >= len(cards):
            continue
        y_offset = round((1.0 - placement.alpha) * viewport_height * 0.018)
        if not (
            placement.x <= x < placement.x + card_width
            and y_offset <= y < y_offset + viewport_height
        ):
            continue
        local_x = (x - placement.x) / max(1.0, card_width)
        local_y = (y - y_offset) / max(1.0, viewport_height)
        for field, left, top, right, bottom in _field_regions(project.model_id):
            if left <= local_x <= right and top <= local_y <= bottom:
                rect = QRect(
                    round(placement.x + left * card_width),
                    round(y_offset + top * viewport_height),
                    max(1, round((right - left) * card_width)),
                    max(1, round((bottom - top) * viewport_height)),
                )
                return FieldHit(placement.index, field, rect)
        return None
    return None


class InlinePreviewCanvas(PreviewCanvas):
    """Program monitor that edits rendered text where the user clicks it."""

    field_committed = Signal(int, str, str)
    field_edit_started = Signal(int, str)

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setToolTip("Click value, label, title, or description text to edit it directly")
        self._active_hit: FieldHit | None = None
        self._original_text = ""
        self._committing = False

        self._line_editor = QLineEdit(self)
        self._line_editor.hide()
        self._line_editor.installEventFilter(self)

        self._text_editor = QTextEdit(self)
        self._text_editor.setAcceptRichText(False)
        self._text_editor.hide()
        self._text_editor.installEventFilter(self)

    @property
    def active_editor(self) -> QLineEdit | QTextEdit | None:
        if self._line_editor.isVisible():
            return self._line_editor
        if self._text_editor.isVisible():
            return self._text_editor
        return None

    @property
    def editing_field(self) -> str | None:
        return self._active_hit.field if self._active_hit is not None else None

    def field_rect(self, card_index: int, field: str) -> QRect | None:
        """Return the current widget rectangle for a visible field."""
        if self._project is None or self._video_rect is None:
            return None
        video_x, video_y, video_width, video_height = self._video_rect
        cards = self._project.content_cards()
        timeline = Timeline(self._project, len(cards))
        card_width = video_width / timeline.visible_cards
        for placement in timeline.placements(self._time, float(video_width)):
            if placement.index != card_index:
                continue
            y_offset = round((1.0 - placement.alpha) * video_height * 0.018)
            for region_field, left, top, right, bottom in _field_regions(self._project.model_id):
                if region_field != field:
                    continue
                return QRect(
                    video_x + round(placement.x + left * card_width),
                    video_y + round(y_offset + top * video_height),
                    max(1, round((right - left) * card_width)),
                    max(1, round((bottom - top) * video_height)),
                )
        return None

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.MouseButton.LeftButton:
            super().mousePressEvent(event)
            return
        if self._project is None or self._video_rect is None:
            super().mousePressEvent(event)
            return

        video_x, video_y, video_width, video_height = self._video_rect
        point = event.position()
        if not (
            video_x <= point.x() <= video_x + video_width
            and video_y <= point.y() <= video_y + video_height
        ):
            self._commit_active_editor()
            super().mousePressEvent(event)
            return

        hit = hit_test_field(
            self._project,
            self._time,
            video_width,
            video_height,
            point.x() - video_x,
            point.y() - video_y,
        )
        if hit is None:
            self._commit_active_editor()
            super().mousePressEvent(event)
            return

        widget_hit = FieldHit(
            hit.card_index,
            hit.field,
            hit.rect.translated(video_x, video_y),
        )
        self.card_clicked.emit(widget_hit.card_index)
        self._begin_edit(widget_hit)
        event.accept()

    def _begin_edit(self, hit: FieldHit) -> None:
        self._commit_active_editor()
        project = self._project
        if project is None:
            return
        cards = project.content_cards()
        if not (0 <= hit.card_index < len(cards)):
            return
        value = str(getattr(cards[hit.card_index], hit.field, ""))
        self._active_hit = hit
        self._original_text = value
        self.field_edit_started.emit(hit.card_index, hit.field)

        editor: QLineEdit | QTextEdit
        if hit.field == "description":
            editor = self._text_editor
            editor.setPlainText(value)
        else:
            editor = self._line_editor
            editor.setText(value)

        editor.setGeometry(self._editor_geometry(hit))
        editor.setStyleSheet(self._editor_style(hit.field))
        editor.show()
        editor.raise_()
        editor.setFocus(Qt.FocusReason.MouseFocusReason)
        if isinstance(editor, QLineEdit):
            editor.selectAll()
        else:
            editor.selectAll()

    def _editor_geometry(self, hit: FieldHit) -> QRect:
        rect = hit.rect.adjusted(2, 2, -2, -2)
        if hit.field in {"value", "label", "title"}:
            height = max(28, rect.height())
            center_y = rect.center().y()
            rect.setTop(center_y - height // 2)
            rect.setHeight(height)
        return rect.intersected(self.rect().adjusted(1, 1, -1, -1))

    @staticmethod
    def _editor_style(field: str) -> str:
        if field == "title":
            return (
                "QLineEdit { background: rgba(248,247,243,245); color:#151515; "
                "border:2px solid #67a8e4; padding:2px 5px; font-weight:700; }"
            )
        if field == "description":
            return (
                "QTextEdit { background: rgba(20,20,20,245); color:#f0f0f0; "
                "border:2px solid #67a8e4; padding:3px; }"
            )
        return (
            "QLineEdit { background: rgba(214,30,43,245); color:white; "
            "border:2px solid #ffd0d3; padding:2px 5px; font-weight:800; "
            "selection-background-color:#315f89; }"
        )

    def eventFilter(self, watched: QObject, event: QEvent) -> bool:  # noqa: N802
        if watched not in {self._line_editor, self._text_editor}:
            return super().eventFilter(watched, event)
        if event.type() == QEvent.Type.KeyPress:
            key_event = event
            assert isinstance(key_event, QKeyEvent)
            if key_event.key() == Qt.Key.Key_Escape:
                self._cancel_active_editor()
                return True
            if watched is self._line_editor and key_event.key() in {
                Qt.Key.Key_Return,
                Qt.Key.Key_Enter,
            }:
                self._commit_active_editor()
                return True
            if (
                watched is self._text_editor
                and key_event.key() in {Qt.Key.Key_Return, Qt.Key.Key_Enter}
                and key_event.modifiers() & Qt.KeyboardModifier.ControlModifier
            ):
                self._commit_active_editor()
                return True
        if event.type() == QEvent.Type.FocusOut:
            QTimer.singleShot(0, self._commit_active_editor)
        return super().eventFilter(watched, event)

    def _editor_text(self) -> str:
        if self._text_editor.isVisible():
            return self._text_editor.toPlainText().strip()
        return self._line_editor.text().strip()

    def _commit_active_editor(self) -> None:
        if self._committing or self._active_hit is None:
            return
        self._committing = True
        try:
            hit = self._active_hit
            value = self._editor_text()
            self._hide_editors()
            self._active_hit = None
            if value != self._original_text:
                self.field_committed.emit(hit.card_index, hit.field, value)
        finally:
            self._committing = False

    def _cancel_active_editor(self) -> None:
        self._hide_editors()
        self._active_hit = None

    def _hide_editors(self) -> None:
        self._line_editor.hide()
        self._text_editor.hide()
        self.setFocus(Qt.FocusReason.OtherFocusReason)
