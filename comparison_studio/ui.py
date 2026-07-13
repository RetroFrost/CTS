from __future__ import annotations

import csv
import io
import shutil
import tempfile
import time
from pathlib import Path

from PIL.ImageQt import ImageQt
from PySide6.QtCore import QEvent, QSignalBlocker, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QCursor, QImage, QKeySequence, QPainter, QPixmap
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QDoubleSpinBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QGridLayout,
    QGroupBox,
    QHBoxLayout,
    QHeaderView,
    QInputDialog,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMenu,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSizePolicy,
    QSlider,
    QSpinBox,
    QSplitter,
    QTabWidget,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from .data import (
    FIELD_ROLES,
    MODEL_CLASSIC,
    MODEL_DEFAULT_VISIBLE,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    MODEL_SCHEMAS,
    REFERENCE_REVEAL_SECONDS,
    REFERENCE_SCROLL_SECONDS,
    AudioTrack,
    CardData,
    FriendlyError,
    ProjectSettings,
    SpreadsheetData,
    format_duration,
    guess_field_mapping,
    load_project_document,
    load_xlsx_table,
    parse_clipboard_data,
    parse_duration,
    resolve_cards,
    save_project_json,
)
from .exporter import ExportWorker
from .renderer import (
    AssetCache,
    TimelineRenderer,
    is_remote_image_source,
    normalize_image_source,
)
from .strip_splitter import (
    SplitAnalysis,
    analyze_strip,
    equal_slice_analysis,
    preview_overlay,
    split_to_directory,
)


APP_STYLE = """
QWidget { background:#0b0f17; color:#eef2f7; font-family:"Inter","Noto Sans",sans-serif; font-size:13px; }
QMainWindow { background:#080b12; }
QFrame#topBar { background:#111824; border:1px solid #202b3b; border-radius:14px; }
QLabel#appMark { background:#6d55f7; color:white; border-radius:10px; padding:8px 10px; font-size:15px; font-weight:900; }
QLabel#eyebrow { color:#8e9aac; font-size:11px; font-weight:800; }
QLabel#muted { color:#8995a6; }
QFrame#panel { background:#111722; border:1px solid #202b3a; border-radius:14px; }
QFrame#previewFrame { background:#05060f; border:1px solid #2a374a; border-radius:11px; }
QFrame#controlBar,QFrame#settingsBar { background:#0c121c; border:1px solid #202b3b; border-radius:10px; }
QPushButton { background:#192332; border:1px solid #2b3a50; border-radius:8px; padding:8px 12px; font-weight:650; }
QPushButton:hover { background:#223148; border-color:#516b91; }
QPushButton:pressed { background:#16202d; }
QPushButton:disabled { color:#6f7a88; background:#151b24; border-color:#222a35; }
QPushButton#toolbar { background:transparent; border-color:#2a3749; }
QPushButton#primary { background:#6d55f7; border-color:#8d7bff; color:white; padding-left:16px; padding-right:16px; }
QPushButton#primary:hover { background:#7b65ff; border-color:#a194ff; }
QPushButton#danger { color:#ff9eaa; }
QLineEdit,QTableWidget,QComboBox,QSpinBox,QDoubleSpinBox { background:#090e16; border:1px solid #293648; border-radius:8px; selection-background-color:#5f4bd2; selection-color:white; }
QLineEdit,QComboBox,QSpinBox,QDoubleSpinBox { padding:6px 8px; }
QTableWidget { gridline-color:#202a38; }
QHeaderView::section { background:#192231; color:#dce5ef; border:0; border-right:1px solid #2a3749; border-bottom:1px solid #2a3749; padding:8px; font-weight:700; }
QTabWidget::pane { border:0; }
QTabBar::tab { background:#0d131d; color:#96a3b4; padding:11px 18px; border:1px solid #222e3e; border-bottom:0; }
QTabBar::tab:first { border-top-left-radius:8px; }
QTabBar::tab:last { border-top-right-radius:8px; }
QTabBar::tab:selected { background:#202a3a; color:#fff; border-top:2px solid #806cff; }
QGroupBox { border:1px solid #273447; border-radius:8px; margin-top:12px; padding-top:12px; font-weight:700; }
QSlider::groove:horizontal { height:5px; background:#263244; border-radius:2px; }
QSlider::handle:horizontal { background:#806cff; width:16px; margin:-6px 0; border-radius:8px; }
QSlider::sub-page:horizontal { background:#6d55f7; border-radius:2px; }
QProgressBar { background:#151d29; border:1px solid #2d3a4d; border-radius:7px; text-align:center; min-height:20px; }
QProgressBar::chunk { background:#6d55f7; border-radius:6px; }
QCheckBox { spacing:8px; }
QCheckBox::indicator { width:18px; height:18px; }
QSplitter::handle { background:#151e2b; width:6px; margin:8px 2px; border-radius:3px; }
QStatusBar { background:#0c111a; color:#93a0b2; border-top:1px solid #1d2735; }
"""


MODEL_INFO = {
    MODEL_REFERENCE: (
        "Reference Detail",
        "Black badge stage, white title bar, muted description panel, and inset image. Faithful to the supplied video.",
    ),
    MODEL_ILLUSTRATED: (
        "Illustrated Cards",
        "Three wide image-first cards with a top red badge and a white title strip at the bottom.",
    ),
    MODEL_CLASSIC: (
        "Classic Compact",
        "Dense four-card comparison: dark badge stage, white title band, and full-width image below.",
    ),
}

MODEL_FIELD_HELP = {
    MODEL_REFERENCE: {
        "Badge Date / Value": "Big text inside the red badge. A full date is split into date and year automatically.",
        "Title": "Main card name shown in the white strip.",
        "Description": "Smaller explanation shown in the muted panel above the image.",
        "Image": "Picture inside the lower framed image area. Use a path, URL, embedded XLSX image, or the image picker.",
    },
    MODEL_ILLUSTRATED: {
        "Badge Value": "Big number or text inside the red badge, such as 20, 12%, or ?.",
        "Badge Label": "Small text below the badge value, such as AGE, KNEW THIS, or MINUTES.",
        "Title": "Card name shown in the white strip along the bottom.",
        "Artwork": "The large illustration or picture filling the card behind the badge.",
    },
    MODEL_CLASSIC: {
        "Value": "Big number or text inside the red badge, such as 84 or 3,540.",
        "Unit": "Small text below the value, such as METER, PROBABILITY, or LOSS.",
        "Title": "Item name shown in the white strip between the badge and image.",
        "Image": "Picture filling the entire lower section of the card.",
    },
}

ROLE_LABELS = {
    "badge_primary": "Badge value",
    "badge_secondary": "Badge label / unit",
    "title": "Card title",
    "description": "Description",
    "image": "Image path / URL",
}

def model_headers(model_id: str) -> list[str]:
    return [header for header, _role in MODEL_SCHEMAS[model_id]]


class SpreadsheetTable(QTableWidget):
    data_edited = Signal()
    headers_changed = Signal()

    def __init__(self, parent=None) -> None:
        super().__init__(0, 0, parent)
        self.setAlternatingRowColors(True)
        self.setSelectionMode(QAbstractItemView.SelectionMode.ExtendedSelection)
        self.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectItems)
        self.verticalHeader().setDefaultSectionSize(40)
        self.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive)
        self.horizontalHeader().setMinimumSectionSize(90)
        self.horizontalHeader().setSectionsClickable(True)
        self.itemChanged.connect(lambda _item: self.data_edited.emit())

    def headers(self) -> list[str]:
        return [
            self.horizontalHeaderItem(column).text().strip() if self.horizontalHeaderItem(column) else f"Column {column + 1}"
            for column in range(self.columnCount())
        ]

    def data(self) -> SpreadsheetData:
        rows: list[list[str]] = []
        for row in range(self.rowCount()):
            rows.append([
                self.item(row, column).text().strip() if self.item(row, column) else ""
                for column in range(self.columnCount())
            ])
        return SpreadsheetData(self.headers(), rows).normalized()

    def set_data(self, data: SpreadsheetData) -> None:
        normalized = data.normalized()
        blocker = QSignalBlocker(self)
        self.clear()
        self.setColumnCount(len(normalized.headers))
        self.setRowCount(len(normalized.rows))
        self.setHorizontalHeaderLabels(normalized.headers)
        for row, values in enumerate(normalized.rows):
            for column, value in enumerate(values):
                self.setItem(row, column, QTableWidgetItem(value))
        del blocker
        self.headers_changed.emit()
        self.data_edited.emit()

    def append_row(self, after_row: int | None = None) -> None:
        if self.columnCount() == 0:
            for header in model_headers(MODEL_REFERENCE):
                self.add_column(header)
        row = self.rowCount() if after_row is None else max(0, min(self.rowCount(), after_row + 1))
        self.insertRow(row)
        for column in range(self.columnCount()):
            self.setItem(row, column, QTableWidgetItem(""))
        self.setCurrentCell(row, 0)
        self.data_edited.emit()

    def duplicate_selected_rows(self) -> None:
        rows = sorted({index.row() for index in self.selectedIndexes()})
        if not rows and self.currentRow() >= 0:
            rows = [self.currentRow()]
        if not rows:
            raise FriendlyError("Select a card row to duplicate.")
        copies = [
            [self.item(row, column).text() if self.item(row, column) else "" for column in range(self.columnCount())]
            for row in rows
        ]
        insert_at = rows[-1] + 1
        blocker = QSignalBlocker(self)
        for offset, values in enumerate(copies):
            target = insert_at + offset
            self.insertRow(target)
            for column, value in enumerate(values):
                self.setItem(target, column, QTableWidgetItem(value))
        del blocker
        self.selectRow(insert_at)
        self.data_edited.emit()

    def remove_selected_rows(self) -> None:
        rows = sorted({index.row() for index in self.selectedIndexes()}, reverse=True)
        if not rows and self.currentRow() >= 0:
            rows = [self.currentRow()]
        blocker = QSignalBlocker(self)
        for row in rows:
            self.removeRow(row)
        del blocker
        if rows:
            self.data_edited.emit()

    def add_column(self, name: str | None = None, at: int | None = None) -> int:
        if name is None:
            name, accepted = QInputDialog.getText(self, "Add data column", "Column name:")
            if not accepted:
                return -1
        name = name.strip() or f"Column {self.columnCount() + 1}"
        existing = set(self.headers())
        base, suffix = name, 2
        while name in existing:
            name = f"{base} ({suffix})"
            suffix += 1
        column = self.columnCount() if at is None else max(0, min(self.columnCount(), at))
        self.insertColumn(column)
        self.setHorizontalHeaderItem(column, QTableWidgetItem(name))
        for row in range(self.rowCount()):
            self.setItem(row, column, QTableWidgetItem(""))
        self.headers_changed.emit()
        self.data_edited.emit()
        return column

    def rename_column(self, column: int | None = None) -> None:
        column = self.currentColumn() if column is None else column
        if column < 0:
            raise FriendlyError("Select or double-click the field you want to rename.")
        current = self.headers()[column]
        name, accepted = QInputDialog.getText(self, "Rename data column", "Column name:", text=current)
        if accepted and name.strip():
            self.setHorizontalHeaderItem(column, QTableWidgetItem(name.strip()))
            self.headers_changed.emit()
            self.data_edited.emit()

    def remove_column_at(self, column: int | None = None) -> None:
        column = self.currentColumn() if column is None else column
        if column < 0:
            raise FriendlyError("Select the field you want to remove.")
        self.removeColumn(column)
        self.headers_changed.emit()
        self.data_edited.emit()

    def paste_as_table(self, text: str) -> None:
        data = parse_clipboard_data(text)
        if not data.headers:
            raise FriendlyError(
                "The clipboard does not contain a table.",
                "Copy rows from a spreadsheet; the first pasted row becomes column names.",
            )
        self.set_data(data)

    def paste_into_cells(self, text: str) -> None:
        if self.columnCount() == 0:
            self.paste_as_table(text)
            return
        delimiter = "\t" if "\t" in text[:4096] else ","
        rows = list(csv.reader(io.StringIO(text), delimiter=delimiter))
        start_row = max(0, self.currentRow())
        start_col = max(0, self.currentColumn())
        blocker = QSignalBlocker(self)
        while self.rowCount() < start_row + len(rows):
            self.insertRow(self.rowCount())
        needed_columns = max((len(row) for row in rows), default=0)
        while self.columnCount() < start_col + needed_columns:
            column = self.columnCount()
            self.insertColumn(column)
            self.setHorizontalHeaderItem(column, QTableWidgetItem(f"Column {column + 1}"))
        for row_offset, values in enumerate(rows):
            for column_offset, value in enumerate(values):
                self.setItem(start_row + row_offset, start_col + column_offset, QTableWidgetItem(value))
        del blocker
        self.headers_changed.emit()
        self.data_edited.emit()

    def keyPressEvent(self, event) -> None:  # noqa: N802
        if event.matches(QKeySequence.StandardKey.Paste):
            self.paste_into_cells(QApplication.clipboard().text())
            return
        if event.key() in (Qt.Key.Key_Delete, Qt.Key.Key_Backspace):
            blocker = QSignalBlocker(self)
            for item in self.selectedItems():
                item.setText("")
            del blocker
            self.data_edited.emit()
            return
        super().keyPressEvent(event)


class SoundtrackTable(QTableWidget):
    data_edited = Signal()
    HEADERS = ["File", "Start", "Trim In", "Trim Out", "Volume %", "Fade In", "Fade Out", "Loop"]

    def __init__(self, parent=None) -> None:
        super().__init__(0, len(self.HEADERS), parent)
        self.setHorizontalHeaderLabels(self.HEADERS)
        self.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
        for column in range(1, len(self.HEADERS)):
            self.horizontalHeader().setSectionResizeMode(column, QHeaderView.ResizeMode.ResizeToContents)
        self.itemChanged.connect(lambda _item: self.data_edited.emit())

    def append_track(self, track: AudioTrack) -> None:
        row = self.rowCount()
        self.insertRow(row)
        values = [
            track.path,
            f"{track.start_time:g}",
            f"{track.trim_start:g}",
            "" if track.trim_end is None else f"{track.trim_end:g}",
            f"{track.volume * 100:g}",
            f"{track.fade_in:g}",
            f"{track.fade_out:g}",
        ]
        for column, value in enumerate(values):
            self.setItem(row, column, QTableWidgetItem(value))
        loop_item = QTableWidgetItem("")
        loop_item.setFlags(loop_item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
        loop_item.setCheckState(Qt.CheckState.Checked if track.loop else Qt.CheckState.Unchecked)
        self.setItem(row, 7, loop_item)

    def set_tracks(self, tracks: list[AudioTrack]) -> None:
        blocker = QSignalBlocker(self)
        self.setRowCount(0)
        for track in tracks:
            self.append_track(track)
        del blocker
        self.data_edited.emit()

    def tracks(self) -> list[AudioTrack]:
        tracks: list[AudioTrack] = []
        for row in range(self.rowCount()):
            def cell(column: int) -> str:
                return self.item(row, column).text().strip() if self.item(row, column) else ""

            def number(column: int, default: float) -> float:
                text = cell(column)
                if not text:
                    return default
                try:
                    return float(text)
                except ValueError as exc:
                    raise FriendlyError(
                        f"Soundtrack row {row + 1} contains an invalid number: {text}",
                        "Times use seconds; Volume uses a percentage.",
                    ) from exc

            trim_out_text = cell(3)
            track = AudioTrack(
                path=cell(0),
                start_time=number(1, 0.0),
                trim_start=number(2, 0.0),
                trim_end=number(3, 0.0) if trim_out_text else None,
                volume=number(4, 100.0) / 100.0,
                fade_in=number(5, 0.0),
                fade_out=number(6, 0.0),
                loop=self.item(row, 7) is not None and self.item(row, 7).checkState() == Qt.CheckState.Checked,
            )
            tracks.append(track)
        return tracks

    def remove_selected(self) -> None:
        rows = sorted({index.row() for index in self.selectedIndexes()}, reverse=True)
        if not rows and self.currentRow() >= 0:
            rows = [self.currentRow()]
        for row in rows:
            self.removeRow(row)
        if rows:
            self.data_edited.emit()


class PreviewWidget(QFrame):
    field_clicked = Signal(float, float)
    inline_committed = Signal(int, str, str)
    inline_canceled = Signal()

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setObjectName("previewFrame")
        self.setMinimumSize(640, 360)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self._image: QImage | None = None
        self._empty_message = "Add a row to create the first styled card"
        self._video_rect = None
        self._last_click = None
        self._editor_card = -1
        self._editor_role = ""
        self._editor_active = False
        self.setCursor(Qt.CursorShape.PointingHandCursor)
        self.setToolTip("Click a visible card field to edit it directly")
        self._editor = QLineEdit(self)
        self._editor.setFrame(False)
        self._editor.returnPressed.connect(self._commit_inline_edit)
        self._editor.editingFinished.connect(self._commit_inline_edit)
        self._editor.installEventFilter(self)
        self._editor.hide()

    def set_pil_image(self, image) -> None:
        self._image = QImage(ImageQt(image)).copy()
        self.update()

    def set_empty_message(self, message: str) -> None:
        self._empty_message = message
        self.update()

    @property
    def is_inline_editing(self) -> bool:
        return self._editor_active

    def paintEvent(self, event) -> None:  # noqa: N802
        super().paintEvent(event)
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform, True)
        content = self.contentsRect().adjusted(8, 8, -8, -8)
        painter.fillRect(content, QColor("#05060f"))
        target_width = content.width()
        target_height = round(target_width * 9 / 16)
        if target_height > content.height():
            target_height = content.height()
            target_width = round(target_height * 16 / 9)
        x = content.x() + (content.width() - target_width) // 2
        y = content.y() + (content.height() - target_height) // 2
        target = content.__class__(x, y, target_width, target_height)
        self._video_rect = target
        if self._image is not None:
            painter.drawImage(target, self._image)
        else:
            painter.fillRect(target, QColor("#05060f"))
            painter.setPen(QColor("#7f8b9c"))
            painter.drawText(target, Qt.AlignmentFlag.AlignCenter, self._empty_message)

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if (
            event.button() == Qt.MouseButton.LeftButton
            and self._video_rect is not None
            and self._video_rect.contains(event.position().toPoint())
        ):
            point = event.position().toPoint()
            self._last_click = point
            normalized_x = (point.x() - self._video_rect.x()) / max(1, self._video_rect.width())
            normalized_y = (point.y() - self._video_rect.y()) / max(1, self._video_rect.height())
            self.field_clicked.emit(normalized_x, normalized_y)
            event.accept()
            return
        super().mousePressEvent(event)

    def begin_inline_edit(
        self,
        card_index: int,
        role: str,
        value: str,
        normalized_region: tuple[float, float, float, float],
    ) -> None:
        if self._video_rect is None:
            return
        nx, ny, nw, nh = normalized_region
        x = round(self._video_rect.x() + nx * self._video_rect.width())
        y = round(self._video_rect.y() + ny * self._video_rect.height())
        editor_width = max(12, round(nw * self._video_rect.width()))
        editor_height = max(12, round(nh * self._video_rect.height()))
        self._editor_card = card_index
        self._editor_role = role
        self._editor_active = True
        self._editor.setGeometry(x, y, editor_width, editor_height)
        color = "#fffaf4" if role.startswith("badge_") or role == "description" else "#111113"
        weight = 800 if role != "description" else 500
        size_factor = {
            "badge_primary": 0.46,
            "badge_secondary": 0.34,
            "title": 0.42,
            "description": 0.30,
            "image": 0.20,
        }.get(role, 0.36)
        font_size = max(10, min(40, round(editor_height * size_factor)))
        self._editor.setStyleSheet(
            "QLineEdit {"
            "background:transparent; border:none; padding:0;"
            f"color:{color}; font-size:{font_size}px; font-weight:{weight};"
            "selection-background-color:#806cff; selection-color:white;"
            "}"
        )
        alignment = (
            Qt.AlignmentFlag.AlignLeft | Qt.AlignmentFlag.AlignVCenter
            if role == "description"
            else Qt.AlignmentFlag.AlignCenter
        )
        self._editor.setAlignment(alignment)
        self._editor.setPlaceholderText("")
        self._editor.setText(value)
        self._editor.show()
        self._editor.raise_()
        self._editor.setFocus(Qt.FocusReason.MouseFocusReason)
        self._editor.setCursorPosition(len(value))

    def cancel_inline_edit(self) -> None:
        was_active = self._editor_active
        self._editor_active = False
        self._editor.hide()
        if was_active:
            self.inline_canceled.emit()

    def commit_inline_edit(self) -> None:
        self._commit_inline_edit()

    def _commit_inline_edit(self) -> None:
        if not self._editor_active:
            return
        self._editor_active = False
        card_index, role, value = self._editor_card, self._editor_role, self._editor.text()
        self._editor.hide()
        self.inline_committed.emit(card_index, role, value)

    def eventFilter(self, watched, event) -> bool:  # noqa: N802
        if watched is self._editor and event.type() == QEvent.Type.KeyPress and event.key() == Qt.Key.Key_Escape:
            self.cancel_inline_edit()
            return True
        return super().eventFilter(watched, event)


class StripPreviewDialog(QDialog):
    def __init__(self, analysis: SplitAnalysis, expected_count: int, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Review detected image cuts")
        self.resize(1060, 720)
        self.analysis = analysis
        self.expected_count = expected_count
        self.selected_analysis: SplitAnalysis | None = None
        layout = QVBoxLayout(self)
        heading = QLabel(
            f"Detected {analysis.count} images · {analysis.orientation.title()} strip · Expected {expected_count}"
        )
        heading.setStyleSheet("font-size:18px; font-weight:700;")
        layout.addWidget(heading)
        message = (
            "Cyan boxes are assigned images; red bands are removed dividers."
            if analysis.matches_expected
            else analysis.mismatch_message()
        )
        description = QLabel(message)
        description.setWordWrap(True)
        description.setStyleSheet("color:#aeb9c8;")
        layout.addWidget(description)
        image = QImage(ImageQt(preview_overlay(analysis))).copy()
        label = QLabel()
        label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        label.setPixmap(QPixmap.fromImage(image))
        label.setStyleSheet("background:#05060f; border:1px solid #293548; border-radius:8px;")
        label.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        layout.addWidget(label, 1)
        buttons = QDialogButtonBox()
        apply_button = buttons.addButton("Apply detected cuts", QDialogButtonBox.ButtonRole.AcceptRole)
        equal_button = buttons.addButton("Slice equally", QDialogButtonBox.ButtonRole.ActionRole)
        cancel_button = buttons.addButton(QDialogButtonBox.StandardButton.Cancel)
        apply_button.setEnabled(analysis.matches_expected)
        apply_button.clicked.connect(self._apply_detected)
        equal_button.clicked.connect(self._apply_equal)
        cancel_button.clicked.connect(self.reject)
        layout.addWidget(buttons)

    def _apply_detected(self) -> None:
        self.selected_analysis = self.analysis
        self.accept()

    def _apply_equal(self) -> None:
        self.selected_analysis = equal_slice_analysis(self.analysis.source_path, self.expected_count, self.analysis.orientation)
        self.accept()


class ExportProgressDialog(QDialog):
    export_finished = Signal(str)

    def __init__(self, worker: ExportWorker, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Export video")
        self.setModal(True)
        self.setMinimumWidth(540)
        self.worker = worker
        self._running = True
        layout = QVBoxLayout(self)
        self.stage_label = QLabel("Preparing export…")
        self.stage_label.setStyleSheet("font-size:18px; font-weight:700;")
        self.detail_label = QLabel("Checking project data")
        self.detail_label.setWordWrap(True)
        self.detail_label.setStyleSheet("color:#aab5c4;")
        self.progress = QProgressBar()
        self.progress.setRange(0, 1000)
        self.frame_label = QLabel("Frame 0 / 0")
        self.eta_label = QLabel("Estimated time remaining: calculating…")
        self.eta_label.setStyleSheet("color:#8f9bad;")
        self.cancel_button = QPushButton("Cancel export")
        self.cancel_button.setObjectName("danger")
        self.cancel_button.clicked.connect(self._cancel)
        layout.addWidget(self.stage_label)
        layout.addWidget(self.detail_label)
        layout.addSpacing(8)
        layout.addWidget(self.progress)
        row = QHBoxLayout()
        row.addWidget(self.frame_label)
        row.addStretch()
        row.addWidget(self.eta_label)
        layout.addLayout(row)
        layout.addWidget(self.cancel_button, alignment=Qt.AlignmentFlag.AlignRight)
        worker.stage_changed.connect(self._stage)
        worker.progress_changed.connect(self._progress)
        worker.completed.connect(self._completed)
        worker.failed.connect(self._failed)
        worker.canceled.connect(self._canceled)

    def start(self) -> None:
        self.worker.start()
        self.exec()

    def _stage(self, stage: str, detail: str) -> None:
        self.stage_label.setText(stage)
        self.detail_label.setText(detail)
        if stage == "Soundtrack":
            self.progress.setValue(0)
            self.frame_label.setText("Mixing audio")
            self.eta_label.setText("Applying track settings…")

    def _progress(self, current: int, total: int, eta: float) -> None:
        self.progress.setValue(round(current / max(1, total) * 1000))
        if self.stage_label.text() == "Soundtrack":
            self.frame_label.setText(f"Audio {round(current / max(1, total) * 100)}%")
        else:
            self.frame_label.setText(f"Frame {current:,} / {total:,}")
        self.eta_label.setText(f"Estimated time remaining: {format_duration(eta)}")

    def _cancel(self) -> None:
        if self._running:
            self.cancel_button.setEnabled(False)
            self.stage_label.setText("Canceling…")
            self.worker.request_cancel()

    def _completed(self, path: str) -> None:
        self._running = False
        self.progress.setValue(1000)
        self.export_finished.emit(path)
        self.accept()

    def _failed(self, summary: str, suggestion: str, details: str) -> None:
        self._running = False
        self.reject()
        show_error(self.parentWidget(), summary, suggestion, details)

    def _canceled(self) -> None:
        self._running = False
        self.reject()

    def closeEvent(self, event) -> None:  # noqa: N802
        if self._running:
            self._cancel()
            event.ignore()
        else:
            super().closeEvent(event)


def show_error(parent, summary: str, suggestion: str = "", details: str = "") -> None:
    box = QMessageBox(parent)
    box.setIcon(QMessageBox.Icon.Critical)
    box.setWindowTitle("Comparison Timeline Studio")
    box.setText(summary)
    if suggestion:
        box.setInformativeText(suggestion)
    if details:
        box.setDetailedText(details)
    box.exec()


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        # Qt controls can emit value-changed signals while the tabs are still being
        # constructed. Ignore those signals until every cross-tab dependency exists.
        self._ui_ready = False
        self._suspend_model_schema = False
        self.setWindowTitle("CTS — Comparison Timeline Studio")
        self.resize(1580, 940)
        self.setMinimumSize(1160, 740)
        self.renderer = TimelineRenderer(AssetCache())
        self.position_seconds = 0.0
        self._play_started_at = 0.0
        self._play_origin = 0.0
        self._export_worker: ExportWorker | None = None
        self.play_timer = QTimer(self)
        self.play_timer.setInterval(33)
        self.play_timer.timeout.connect(self._play_tick)
        self.preview_debounce = QTimer(self)
        self.preview_debounce.setSingleShot(True)
        self.preview_debounce.setInterval(140)
        self.preview_debounce.timeout.connect(self.update_preview)

        central = QWidget()
        root = QVBoxLayout(central)
        root.setContentsMargins(18, 16, 18, 18)
        root.setSpacing(14)
        root.addWidget(self._build_header())
        root.addWidget(self._build_content(), 1)
        self.setCentralWidget(central)
        self._ui_ready = True
        # Useful empty fields make the first edit obvious. They are conveniences only:
        # every field can still be unmapped, renamed, or deleted.
        initial_headers = model_headers(MODEL_REFERENCE)
        self.table.set_data(SpreadsheetData(initial_headers, [[""] * len(initial_headers) for _ in range(4)]))
        self._auto_map_fields()
        self._refresh_field_guide(MODEL_REFERENCE)
        self.position_seconds = self._editing_time_for_card(3)
        self.statusBar().showMessage("Ready · Reference Detail fields are prepared")
        self.update_preview()

    def _build_header(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("topBar")
        row = QHBoxLayout(bar)
        row.setContentsMargins(14, 11, 14, 11)
        row.setSpacing(10)
        mark = QLabel("CTS")
        mark.setObjectName("appMark")
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row.addWidget(mark)
        title_box = QVBoxLayout()
        title_box.setSpacing(1)
        title = QLabel("Comparison Timeline Studio")
        title.setStyleSheet("font-size:20px; font-weight:850;")
        subtitle = QLabel("Build, preview, and export data-driven comparison videos")
        subtitle.setObjectName("muted")
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        row.addLayout(title_box)
        row.addStretch()
        open_button = QPushButton("Open project")
        save_button = QPushButton("Save project")
        export_button = QPushButton("Export MP4")
        open_button.setObjectName("toolbar")
        save_button.setObjectName("toolbar")
        export_button.setObjectName("primary")
        open_button.clicked.connect(self.open_project)
        save_button.clicked.connect(self.save_project)
        export_button.clicked.connect(self.export_video)
        row.addWidget(open_button)
        row.addWidget(save_button)
        row.addWidget(export_button)
        return bar

    def _build_content(self) -> QSplitter:
        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.addWidget(self._build_preview_panel())
        splitter.addWidget(self._build_editor_panel())
        splitter.setStretchFactor(0, 3)
        splitter.setStretchFactor(1, 2)
        splitter.setSizes([950, 600])
        return splitter

    def _build_preview_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(10)
        preview_heading = QHBoxLayout()
        preview_title = QLabel("LIVE PREVIEW")
        preview_title.setObjectName("eyebrow")
        preview_hint = QLabel("Click any visible field to edit it in place")
        preview_hint.setObjectName("muted")
        preview_heading.addWidget(preview_title)
        preview_heading.addStretch()
        preview_heading.addWidget(preview_hint)
        layout.addLayout(preview_heading)
        self.preview = PreviewWidget()
        self.preview.field_clicked.connect(self._preview_field_clicked)
        self.preview.inline_committed.connect(self._commit_direct_edit)
        self.preview.inline_canceled.connect(self.update_preview)
        layout.addWidget(self.preview, 1)
        control_bar = QFrame()
        control_bar.setObjectName("controlBar")
        playback = QHBoxLayout(control_bar)
        playback.setContentsMargins(8, 7, 8, 7)
        add_card_button = QPushButton("＋ Add card")
        add_card_button.clicked.connect(self._add_card_from_preview)
        self.play_button = QPushButton("▶ Play")
        self.play_button.clicked.connect(self.toggle_playback)
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setRange(0, 10000)
        self.slider.sliderMoved.connect(self._slider_moved)
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setMinimumWidth(112)
        playback.addWidget(add_card_button)
        playback.addWidget(self.play_button)
        playback.addWidget(self.slider, 1)
        playback.addWidget(self.time_label)
        layout.addWidget(control_bar)

        settings_bar = QFrame()
        settings_bar.setObjectName("settingsBar")
        duration_row = QHBoxLayout(settings_bar)
        duration_row.setContentsMargins(10, 7, 10, 7)
        label = QLabel("Video length")
        label.setStyleSheet("font-weight:700;")
        self.auto_length = QCheckBox("Automatic")
        self.auto_length.setChecked(True)
        self.custom_length = QLineEdit()
        self.custom_length.setPlaceholderText("MM:SS or HH:MM:SS")
        self.custom_length.setMaximumWidth(150)
        self.custom_length.setEnabled(False)
        self.duration_info = QLabel()
        self.duration_info.setStyleSheet("color:#8f9bad;")
        self.auto_length.toggled.connect(self._duration_mode_changed)
        self.custom_length.editingFinished.connect(self._custom_duration_changed)
        duration_row.addWidget(label)
        duration_row.addWidget(self.auto_length)
        duration_row.addWidget(self.custom_length)
        duration_row.addWidget(self.duration_info, 1)
        animation_label = QLabel("Animation")
        animation_label.setStyleSheet("font-weight:700;")
        self.hexagons_bounce = QCheckBox("Hexagons bounce")
        self.hexagons_bounce.setChecked(True)
        self.hexagons_bounce.setToolTip(
            "Animate the red value badges during entrances and horizontal scrolling."
        )
        self.hexagons_bounce.toggled.connect(self._data_changed)
        duration_row.addWidget(animation_label)
        duration_row.addWidget(self.hexagons_bounce)
        layout.addWidget(settings_bar)
        return panel

    def _build_editor_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(10, 10, 10, 10)
        editor_heading = QHBoxLayout()
        editor_title = QLabel("PROJECT WORKSPACE")
        editor_title.setObjectName("eyebrow")
        editor_hint = QLabel("Data · Models · Soundtrack")
        editor_hint.setObjectName("muted")
        editor_heading.addWidget(editor_title)
        editor_heading.addStretch()
        editor_heading.addWidget(editor_hint)
        layout.addLayout(editor_heading)
        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_spreadsheet_tab(), "Spreadsheet")
        self.tabs.addTab(self._build_models_tab(), "Models")
        self.tabs.addTab(self._build_soundtrack_tab(), "Soundtrack")
        layout.addWidget(self.tabs)
        return panel

    def _build_spreadsheet_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        helper = QLabel(
            "One row = one card. Type directly, paste cells with Ctrl+V, or import a table. "
            "The selected model prepares the fields it uses; individual cells may stay blank."
        )
        helper.setWordWrap(True)
        helper.setStyleSheet("color:#8f9bad;")
        layout.addWidget(helper)
        self.field_guide = QLabel()
        self.field_guide.setWordWrap(True)
        self.field_guide.setTextFormat(Qt.TextFormat.PlainText)
        self.field_guide.setStyleSheet(
            "background:#101722; border:1px solid #273447; border-radius:8px; "
            "padding:9px; color:#c7d1df;"
        )
        layout.addWidget(self.field_guide)
        buttons = QGridLayout()
        import_button = QPushButton("Import XLSX")
        paste_button = QPushButton("Paste complete table")
        strip_button = QPushButton("Import image strip")
        image_button = QPushButton("Choose row image")
        import_button.clicked.connect(self.import_xlsx)
        paste_button.clicked.connect(self.paste_data)
        strip_button.clicked.connect(self.import_image_strip)
        image_button.clicked.connect(self.choose_row_image)
        buttons.addWidget(import_button, 0, 0)
        buttons.addWidget(paste_button, 0, 1)
        buttons.addWidget(strip_button, 1, 0)
        buttons.addWidget(image_button, 1, 1)
        layout.addLayout(buttons)
        self.table = SpreadsheetTable()
        self.table.data_edited.connect(self._data_changed)
        self.table.headers_changed.connect(self._headers_changed)
        self.table.horizontalHeader().sectionDoubleClicked.connect(self._rename_column_at)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._spreadsheet_context_menu)
        self.table.horizontalHeader().setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.horizontalHeader().customContextMenuRequested.connect(self._header_context_menu)
        layout.addWidget(self.table, 1)
        self.table_status = QLabel("0 cards · 0 fields")
        self.table_status.setStyleSheet("color:#7f8b9c;")
        layout.addWidget(self.table_status)

        row_buttons = QHBoxLayout()
        add_row = QPushButton("+ Add card")
        duplicate_row = QPushButton("Duplicate")
        remove_row = QPushButton("Delete card")
        add_row.clicked.connect(lambda: self.table.append_row())
        duplicate_row.clicked.connect(self._duplicate_rows)
        remove_row.clicked.connect(self.table.remove_selected_rows)
        row_buttons.addWidget(add_row)
        row_buttons.addWidget(duplicate_row)
        row_buttons.addWidget(remove_row)
        row_buttons.addStretch()
        layout.addLayout(row_buttons)

        field_buttons = QHBoxLayout()
        add_column = QPushButton("+ Add field")
        rename_column = QPushButton("Rename field")
        remove_column = QPushButton("Delete field")
        reset = QPushButton("New blank table")
        add_column.clicked.connect(lambda: self.table.add_column())
        rename_column.clicked.connect(self._rename_column)
        remove_column.clicked.connect(self._remove_column)
        reset.clicked.connect(self.clear_all)
        field_buttons.addWidget(add_column)
        field_buttons.addWidget(rename_column)
        field_buttons.addWidget(remove_column)
        field_buttons.addStretch()
        field_buttons.addWidget(reset)
        layout.addLayout(field_buttons)
        return page

    def _build_models_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        self.model_combo = QComboBox()
        for model_id, (name, _description) in MODEL_INFO.items():
            self.model_combo.addItem(name, model_id)
        self.model_description = QLabel()
        self.model_description.setWordWrap(True)
        self.model_description.setStyleSheet("color:#aab5c4;")
        self.model_fields = QLabel()
        self.model_fields.setWordWrap(True)
        self.model_fields.setStyleSheet("color:#7f8b9c; font-weight:600;")
        layout.addWidget(QLabel("Visual model"))
        layout.addWidget(self.model_combo)
        layout.addWidget(self.model_description)
        layout.addWidget(self.model_fields)

        viewport = QGroupBox("Viewport")
        viewport_form = QFormLayout(viewport)
        self.default_visible = QCheckBox("Use native model layout")
        self.default_visible.setChecked(True)
        self.visible_cards = QSpinBox()
        self.visible_cards.setRange(1, 8)
        self.visible_cards.setEnabled(False)
        self.default_visible.toggled.connect(self._visible_mode_changed)
        self.visible_cards.valueChanged.connect(self._data_changed)
        viewport_form.addRow(self.default_visible)
        viewport_form.addRow("Cards on screen", self.visible_cards)
        layout.addWidget(viewport)

        mapping_group = QGroupBox("Advanced mapping (usually automatic)")
        mapping_form = QFormLayout(mapping_group)
        self.mapping_combos: dict[str, QComboBox] = {}
        self.mapping_labels: dict[str, QLabel] = {}
        for role in FIELD_ROLES:
            combo = QComboBox()
            combo.currentIndexChanged.connect(self._data_changed)
            self.mapping_combos[role] = combo
            mapping_form.addRow(ROLE_LABELS[role], combo)
            self.mapping_labels[role] = mapping_form.labelForField(combo)
        layout.addWidget(mapping_group)
        auto_map = QPushButton("Auto-map recognizable headers")
        auto_map.clicked.connect(self._auto_map_fields)
        layout.addWidget(auto_map)
        layout.addStretch()
        self.model_combo.currentIndexChanged.connect(self._model_changed)
        self.model_description.setText(MODEL_INFO[MODEL_REFERENCE][1])
        self.model_fields.setText("Table fields: " + " · ".join(model_headers(MODEL_REFERENCE)))
        self.visible_cards.setValue(MODEL_DEFAULT_VISIBLE[MODEL_REFERENCE])
        self._update_mapping_visibility(MODEL_REFERENCE)
        return page

    def _build_soundtrack_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        helper = QLabel("Layer multiple audio files. Times are seconds; blank Trim Out means the end of the file. Loop repeats the trimmed region.")
        helper.setWordWrap(True)
        helper.setStyleSheet("color:#8f9bad;")
        layout.addWidget(helper)
        self.soundtrack_table = SoundtrackTable()
        layout.addWidget(self.soundtrack_table, 1)
        buttons = QHBoxLayout()
        add = QPushButton("+ Add audio")
        remove = QPushButton("Remove track")
        add.clicked.connect(self.add_soundtracks)
        remove.clicked.connect(self.soundtrack_table.remove_selected)
        buttons.addWidget(add)
        buttons.addWidget(remove)
        buttons.addStretch()
        layout.addLayout(buttons)
        master_row = QHBoxLayout()
        master_row.addWidget(QLabel("Master volume"))
        self.master_volume = QSpinBox()
        self.master_volume.setRange(0, 200)
        self.master_volume.setValue(100)
        self.master_volume.setSuffix(" %")
        master_row.addWidget(self.master_volume)
        master_row.addStretch()
        layout.addLayout(master_row)
        return page

    def spreadsheet_data(self) -> SpreadsheetData:
        return self.table.data()

    def field_mapping(self) -> dict[str, str]:
        return {
            role: combo.currentData()
            for role, combo in self.mapping_combos.items()
            if combo.currentData()
        }

    def cards(self):
        return resolve_cards(self.spreadsheet_data(), self.field_mapping())

    def project_settings(self) -> ProjectSettings:
        custom = None if self.auto_length.isChecked() else parse_duration(self.custom_length.text())
        return ProjectSettings(
            custom_duration=custom,
            model_id=self.model_combo.currentData() or MODEL_REFERENCE,
            visible_cards=0 if self.default_visible.isChecked() else self.visible_cards.value(),
            field_mapping=self.field_mapping(),
            soundtrack_master_volume=self.master_volume.value() / 100.0,
            hexagons_bounce=self.hexagons_bounce.isChecked(),
        )

    def _headers_changed(self) -> None:
        headers = self.table.headers()
        for combo in self.mapping_combos.values():
            previous = combo.currentData()
            blocker = QSignalBlocker(combo)
            combo.clear()
            combo.addItem("(None — leave empty)", "")
            for header in headers:
                combo.addItem(header, header)
            index = combo.findData(previous)
            combo.setCurrentIndex(index if index >= 0 else 0)
            del blocker
        self._data_changed()

    def _auto_map_fields(self) -> None:
        guessed = guess_field_mapping(self.table.headers())
        for role, combo in self.mapping_combos.items():
            index = combo.findData(guessed.get(role, ""))
            combo.setCurrentIndex(max(0, index))
        self._data_changed()

    def _model_changed(self) -> None:
        model_id = self.model_combo.currentData() or MODEL_REFERENCE
        self.model_description.setText(MODEL_INFO[model_id][1])
        self.model_fields.setText("Table fields: " + " · ".join(model_headers(model_id)))
        self._update_mapping_visibility(model_id)
        if self._ui_ready and not self._suspend_model_schema:
            self._apply_model_schema(model_id)
        self.visible_cards.setValue(MODEL_DEFAULT_VISIBLE[model_id])
        if self._ui_ready and not self._suspend_model_schema and self.table.rowCount():
            self.position_seconds = self._editing_time_for_card(
                min(self.table.rowCount() - 1, MODEL_DEFAULT_VISIBLE[model_id] - 1)
            )
        self._refresh_field_guide(model_id)
        self._data_changed()

    def _update_mapping_visibility(self, model_id: str) -> None:
        active_roles = {role for _header, role in MODEL_SCHEMAS[model_id]}
        for role, combo in self.mapping_combos.items():
            visible = role in active_roles
            combo.setVisible(visible)
            self.mapping_labels[role].setVisible(visible)

    def _refresh_field_guide(self, model_id: str | None = None) -> None:
        if not hasattr(self, "field_guide"):
            return
        model_id = model_id or self.model_combo.currentData() or MODEL_REFERENCE
        model_name = MODEL_INFO[model_id][0]
        lines = [f"{model_name} fields:"]
        for header, _role in MODEL_SCHEMAS[model_id]:
            lines.append(f"• {header} — {MODEL_FIELD_HELP[model_id][header]}")
        self.field_guide.setText("\n".join(lines))
        self._apply_header_help(model_id)

    def _apply_header_help(self, model_id: str) -> None:
        help_by_header = MODEL_FIELD_HELP[model_id]
        for column, header in enumerate(self.table.headers()):
            item = self.table.horizontalHeaderItem(column)
            if item is not None:
                item.setToolTip(help_by_header.get(header, "Extra imported field. Map it from Models or by right-clicking this field."))

    def _apply_model_schema(self, model_id: str) -> None:
        """Reshape the grid to the selected model while preserving mapped data."""
        current = self.spreadsheet_data().normalized()
        target_schema = MODEL_SCHEMAS[model_id]
        target_headers = [header for header, _role in target_schema]
        target_by_role = {role: header for header, role in target_schema}
        old_mapping = self.field_mapping()
        old_indexes = {header: index for index, header in enumerate(current.headers)}
        all_blank = not any(value.strip() for row in current.rows for value in row)

        if all_blank:
            row_count = max(1, len(current.rows))
            rebuilt = SpreadsheetData(target_headers, [[""] * len(target_headers) for _ in range(row_count)])
        else:
            consumed: set[str] = set()
            rebuilt_rows: list[list[str]] = []
            source_for_role: dict[str, int | None] = {}
            for _target_header, role in target_schema:
                source_header = old_mapping.get(role, "")
                source_index = old_indexes.get(source_header)
                if source_index is None:
                    source_index = old_indexes.get(target_by_role[role])
                source_for_role[role] = source_index
                if source_index is not None:
                    consumed.add(current.headers[source_index])

            extra_headers = [
                header
                for index, header in enumerate(current.headers)
                if header not in consumed and any(index < len(row) and row[index].strip() for row in current.rows)
            ]
            extra_indexes = [old_indexes[header] for header in extra_headers]
            for row in current.rows:
                values = [
                    row[source_for_role[role]] if source_for_role[role] is not None and source_for_role[role] < len(row) else ""
                    for _header, role in target_schema
                ]
                values.extend(row[index] if index < len(row) else "" for index in extra_indexes)
                rebuilt_rows.append(values)
            rebuilt = SpreadsheetData(target_headers + extra_headers, rebuilt_rows)

        self.table.set_data(rebuilt)
        for role, combo in self.mapping_combos.items():
            target = target_by_role.get(role, "")
            combo.setCurrentIndex(max(0, combo.findData(target)))
        self._apply_header_help(model_id)

    def _required_role_for_header(self, header: str) -> str | None:
        model_id = self.model_combo.currentData() or MODEL_REFERENCE
        required_roles = {role for _name, role in MODEL_SCHEMAS[model_id]}
        for role in required_roles:
            if self.mapping_combos[role].currentData() == header:
                return role
        return None

    def _visible_mode_changed(self, native: bool) -> None:
        self.visible_cards.setEnabled(not native)
        self._data_changed()

    def _data_changed(self) -> None:
        if not self._ui_ready:
            return
        if hasattr(self, "table_status"):
            self.table_status.setText(
                f"{self.table.rowCount()} card{'s' if self.table.rowCount() != 1 else ''} · "
                f"{self.table.columnCount()} field{'s' if self.table.columnCount() != 1 else ''}"
            )
        self.renderer = TimelineRenderer(AssetCache())
        self.pause_playback()
        self.position_seconds = min(self.position_seconds, self._duration_or_zero())
        self._refresh_duration_labels()
        self.preview_debounce.start()

    def _duration_or_zero(self) -> float:
        try:
            return self.project_settings().duration(len(self.cards()))
        except FriendlyError:
            return 0.0

    def _refresh_duration_labels(self) -> None:
        if not hasattr(self, "table"):
            return
        cards = self.cards()
        try:
            settings = self.project_settings()
            duration = settings.duration(len(cards))
            automatic = settings.auto_duration(len(cards))
            per_card = settings.seconds_per_card(len(cards))
            if self.auto_length.isChecked():
                self.duration_info.setText(f"{format_duration(automatic)} · model pace · {per_card:.2f}s/card")
            else:
                self.duration_info.setText(
                    f"{format_duration(duration)} · {settings.speed_multiplier(len(cards)):.2f}× speed · {per_card:.2f}s/card"
                )
            self.time_label.setText(f"{format_duration(self.position_seconds)} / {format_duration(duration)}")
        except FriendlyError:
            self.duration_info.setText("Enter a valid duration")

    def _duration_mode_changed(self, automatic: bool) -> None:
        self.custom_length.setEnabled(not automatic)
        if not automatic and not self.custom_length.text().strip():
            model_id = self.model_combo.currentData() or MODEL_REFERENCE
            visible = 0 if self.default_visible.isChecked() else self.visible_cards.value()
            settings = ProjectSettings(model_id=model_id, visible_cards=visible)
            self.custom_length.setText(format_duration(settings.auto_duration(len(self.cards()))))
        self._custom_duration_changed()

    def _custom_duration_changed(self) -> None:
        try:
            self.project_settings()
            self.custom_length.setStyleSheet("")
        except FriendlyError:
            if not self.auto_length.isChecked():
                self.custom_length.setStyleSheet("border-color:#ff6174;")
        self.pause_playback()
        self.position_seconds = min(self.position_seconds, self._duration_or_zero())
        self.update_preview()

    def update_preview(self) -> None:
        if hasattr(self, "preview") and self.preview.is_inline_editing:
            return
        cards = self.cards()
        if not cards:
            self.preview._image = None
            self.preview.set_empty_message("Add a row to create the first styled card")
            self.preview.update()
            self._refresh_duration_labels()
            return
        try:
            settings = self.project_settings()
            duration = settings.duration(len(cards))
            self.position_seconds = min(self.position_seconds, duration)
            image = self.renderer.render(cards, self.position_seconds, settings, size=(960, 540))
            self.preview.set_pil_image(image)
            blocker = QSignalBlocker(self.slider)
            self.slider.setValue(round(self.position_seconds / max(0.001, duration) * 10000))
            del blocker
            self._refresh_duration_labels()
        except FriendlyError as exc:
            self.statusBar().showMessage(exc.summary)

    def _editing_time_for_card(self, card_index: int) -> float:
        cards = self.cards()
        if not cards:
            return 0.0
        settings = self.project_settings()
        visible = settings.effective_visible_cards()
        card_index = max(0, min(card_index, len(cards) - 1))
        intro_time = min(len(cards), visible) * REFERENCE_REVEAL_SECONDS
        if card_index < visible:
            model_time = intro_time
        else:
            model_time = intro_time + (card_index - visible + 1) * REFERENCE_SCROLL_SECONDS
        return min(settings.duration(len(cards)), model_time / max(0.001, settings.speed_multiplier(len(cards))))

    def _add_card_from_preview(self) -> None:
        self.pause_playback()
        self.preview.cancel_inline_edit()
        self.table.append_row()
        new_index = self.table.rowCount() - 1
        self.position_seconds = self._editing_time_for_card(new_index)
        self.update_preview()
        self.statusBar().showMessage(f"Added card {new_index + 1} · click its fields in the preview to edit", 5000)

    def _preview_field_clicked(self, normalized_x: float, normalized_y: float) -> None:
        if self.preview.is_inline_editing:
            self.preview.commit_inline_edit()
        self.pause_playback()
        try:
            settings = self.project_settings()
            hit = self.renderer.hit_test(
                self.cards(),
                self.position_seconds,
                settings,
                normalized_x,
                normalized_y,
            )
            if hit is None:
                self.statusBar().showMessage("No editable card field is visible at that point", 3500)
                return
            card_index, role = hit
            header = self.field_mapping().get(role, "")
            if not header or header not in self.table.headers():
                self.statusBar().showMessage("That visual area is not mapped to a table field", 3500)
                return
            column = self.table.headers().index(header)
            item = self.table.item(card_index, column)
            value = item.text() if item else ""
            self.table.setCurrentCell(card_index, column)
            if role == "image":
                self._show_direct_image_menu(card_index, column, header, value)
            else:
                region = self.renderer.editor_region(
                    self.cards(), self.position_seconds, settings, card_index, role
                )
                if region is None:
                    return
                self.preview_debounce.stop()
                self._render_preview_without_field(card_index, role, settings)
                self.preview.begin_inline_edit(card_index, role, value, region)
                self.statusBar().showMessage("Type in place · Enter applies · Esc cancels", 5000)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _show_direct_image_menu(self, card_index: int, column: int, header: str, value: str) -> None:
        menu = QMenu(self)
        choose = menu.addAction("Choose image file…")
        paste_url = menu.addAction("Paste image URL")
        type_path = menu.addAction("Type image path or URL…")
        menu.addSeparator()
        clear = menu.addAction("Clear image")
        selected = menu.exec(QCursor.pos())
        if selected is choose:
            self._choose_image_for_row(card_index, column)
        elif selected is paste_url:
            source = normalize_image_source(QApplication.clipboard().text())
            if not is_remote_image_source(source):
                show_error(
                    self,
                    "The clipboard does not contain an HTTP(S) image URL.",
                    "Copy the image address from your browser, then choose Paste image URL again.",
                )
                return
            self.renderer.assets.invalidate(source)
            self._set_direct_value(card_index, column, source)
            self.statusBar().showMessage(f"Pasted image URL into card {card_index + 1}", 4500)
        elif selected is type_path:
            settings = self.project_settings()
            region = self.renderer.editor_region(
                self.cards(), self.position_seconds, settings, card_index, "image"
            )
            if region is not None:
                self.preview_debounce.stop()
                self._render_preview_without_field(card_index, "image", settings)
                self.preview.begin_inline_edit(card_index, "image", value, region)
        elif selected is clear:
            self._set_direct_value(card_index, column, "")

    def _commit_direct_edit(self, card_index: int, role: str, value: str) -> None:
        header = self.field_mapping().get(role, "")
        if not header or header not in self.table.headers():
            return
        column = self.table.headers().index(header)
        if role == "image":
            value = normalize_image_source(value)
        self._set_direct_value(card_index, column, value)
        self.statusBar().showMessage(f"Updated card {card_index + 1} · {header}", 3500)

    def _render_preview_without_field(
        self,
        card_index: int,
        role: str,
        settings: ProjectSettings,
    ) -> None:
        cards = [
            CardData(card.uploaded, card.title, card.description, card.image, card.badge_label)
            for card in self.cards()
        ]
        if not (0 <= card_index < len(cards)):
            return
        card = cards[card_index]
        if role == "badge_primary":
            card.uploaded = ""
        elif role == "badge_secondary":
            card.badge_label = ""
        elif role == "title":
            card.title = ""
        elif role == "description":
            card.description = ""
        elif role == "image":
            card.image = ""
        image = self.renderer.render(cards, self.position_seconds, settings, size=(960, 540))
        self.preview.set_pil_image(image)

    def _set_direct_value(self, card_index: int, column: int, value: str) -> None:
        if not (0 <= card_index < self.table.rowCount() and 0 <= column < self.table.columnCount()):
            return
        item = self.table.item(card_index, column)
        if item is None:
            item = QTableWidgetItem()
            self.table.setItem(card_index, column, item)
        self.renderer.assets.invalidate(item.text())
        self.renderer.assets.invalidate(value)
        item.setText(value)
        self.table.setCurrentCell(card_index, column)
        self.update_preview()

    def toggle_playback(self) -> None:
        if self.play_timer.isActive():
            self.pause_playback()
            return
        if not self.cards():
            show_error(self, "There are no cards to preview.", "Add one or more spreadsheet rows.")
            return
        duration = self._duration_or_zero()
        if self.position_seconds >= duration:
            self.position_seconds = 0.0
        self._play_origin = self.position_seconds
        self._play_started_at = time.monotonic()
        self.play_timer.start()
        self.play_button.setText("❚❚ Pause")

    def pause_playback(self) -> None:
        self.play_timer.stop()
        if hasattr(self, "play_button"):
            self.play_button.setText("▶ Play")

    def _play_tick(self) -> None:
        duration = self._duration_or_zero()
        self.position_seconds = self._play_origin + (time.monotonic() - self._play_started_at)
        if self.position_seconds >= duration:
            self.position_seconds = duration
            self.pause_playback()
        self.update_preview()

    def _slider_moved(self, value: int) -> None:
        self.pause_playback()
        self.position_seconds = self._duration_or_zero() * value / 10000
        self.update_preview()

    def import_xlsx(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Import spreadsheet", "", "Excel workbooks (*.xlsx)")
        if not path:
            return
        try:
            result = load_xlsx_table(path)
            self.table.set_data(result.data)
            self._auto_map_fields()
            self._apply_model_schema(self.model_combo.currentData() or MODEL_REFERENCE)
            self.position_seconds = 0.0
            self.update_preview()
            self.statusBar().showMessage(f"Imported {result.data.row_count} rows and {len(result.data.headers)} columns", 6000)
            if result.warnings:
                box = QMessageBox(self)
                box.setIcon(QMessageBox.Icon.Warning)
                box.setWindowTitle("Spreadsheet imported with warnings")
                box.setText("The workbook was imported, with readable warnings.")
                box.setDetailedText("\n".join(result.warnings))
                box.exec()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def paste_data(self) -> None:
        try:
            self.table.paste_as_table(QApplication.clipboard().text())
            self._auto_map_fields()
            self._apply_model_schema(self.model_combo.currentData() or MODEL_REFERENCE)
            self.position_seconds = 0.0
            self.update_preview()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _ensure_image_column(self) -> int:
        mapped = self.mapping_combos["image"].currentData()
        headers = self.table.headers()
        if mapped in headers:
            return headers.index(mapped)
        column = self.table.add_column("Image")
        self._headers_changed()
        combo = self.mapping_combos["image"]
        combo.setCurrentIndex(combo.findData(self.table.headers()[column]))
        return column

    def import_image_strip(self) -> None:
        count = self.table.rowCount()
        if count < 1:
            show_error(self, "Add spreadsheet rows before importing an image strip.", "The row count tells the splitter how many images to assign.")
            return
        path, _ = QFileDialog.getOpenFileName(self, "Import image strip", "", "Images (*.png *.jpg *.jpeg *.webp *.tif *.tiff)")
        if not path:
            return
        try:
            analysis = analyze_strip(path, expected_count=count)
            dialog = StripPreviewDialog(analysis, count, self)
            if dialog.exec() != QDialog.DialogCode.Accepted or dialog.selected_analysis is None:
                return
            images = split_to_directory(dialog.selected_analysis, tempfile.mkdtemp(prefix="comparison-studio-strip-"))
            image_column = self._ensure_image_column()
            blocker = QSignalBlocker(self.table)
            for row, image_path in enumerate(images):
                self.table.setItem(row, image_column, QTableWidgetItem(image_path))
            del blocker
            self.table.data_edited.emit()
            self.statusBar().showMessage(f"Assigned {len(images)} separated images", 6000)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def choose_row_image(self) -> None:
        row = self.table.currentRow()
        if row < 0:
            show_error(self, "Select a spreadsheet row first.")
            return
        self._choose_image_for_row(row, self._ensure_image_column())

    def _choose_image_for_row(self, row: int, column: int) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choose card image", "", "Images (*.png *.jpg *.jpeg *.webp *.gif *.tif *.tiff)")
        if path:
            self._set_direct_value(row, column, str(Path(path).resolve()))

    def _duplicate_rows(self) -> None:
        try:
            self.table.duplicate_selected_rows()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _rename_column(self) -> None:
        try:
            self._rename_column_at(self.table.currentColumn())
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _rename_column_at(self, column: int) -> None:
        try:
            if column < 0 or column >= self.table.columnCount():
                raise FriendlyError("Select or double-click the field you want to rename.")
            header = self.table.headers()[column]
            role = self._required_role_for_header(header)
            if role:
                model_name = MODEL_INFO[self.model_combo.currentData() or MODEL_REFERENCE][0]
                raise FriendlyError(
                    f"{header} is a {model_name} field.",
                    "Model fields keep their clear built-in names. Add an extra field if you need custom spreadsheet data.",
                )
            self.table.rename_column(column)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _remove_column(self) -> None:
        try:
            self._remove_column_at_checked(self.table.currentColumn())
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _remove_column_at_checked(self, column: int) -> None:
        if column < 0 or column >= self.table.columnCount():
            raise FriendlyError("Select the field you want to remove.")
        header = self.table.headers()[column]
        role = self._required_role_for_header(header)
        if role:
            model_name = MODEL_INFO[self.model_combo.currentData() or MODEL_REFERENCE][0]
            raise FriendlyError(
                f"{header} belongs to the {model_name} layout.",
                f"That model uses it as {ROLE_LABELS[role]}. Its cells may be blank, but the field stays in the model table.",
            )
        self.table.remove_column_at(column)

    def _map_header(self, role: str, header: str) -> None:
        combo = self.mapping_combos[role]
        index = combo.findData(header)
        if index >= 0:
            combo.setCurrentIndex(index)
            self.statusBar().showMessage(f"{header} → {ROLE_LABELS[role]}", 4000)

    def _field_menu(self, menu: QMenu, column: int) -> None:
        if column < 0 or column >= self.table.columnCount():
            return
        header = self.table.headers()[column]
        menu.addSeparator()
        heading = menu.addAction(f"Field: {header}")
        heading.setEnabled(False)
        rename = menu.addAction("Rename field…")
        add_right = menu.addAction("Add field to the right…")
        mapping = menu.addMenu("Use this field as")
        for role in FIELD_ROLES:
            action = mapping.addAction(ROLE_LABELS[role])
            action.setCheckable(True)
            action.setChecked(self.mapping_combos[role].currentData() == header)
            action.triggered.connect(
                lambda _checked=False, selected_role=role, selected_header=header: self._map_header(
                    selected_role, selected_header
                )
            )
        remove = menu.addAction("Delete field")
        rename.triggered.connect(lambda _checked=False, selected=column: self._rename_column_at(selected))
        add_right.triggered.connect(lambda _checked=False, selected=column: self.table.add_column(at=selected + 1))
        remove.triggered.connect(lambda _checked=False, selected=column: self._remove_column_from_menu(selected))

    def _remove_column_from_menu(self, column: int) -> None:
        try:
            self._remove_column_at_checked(column)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def _unmap_header(self, header: str) -> None:
        for combo in self.mapping_combos.values():
            if combo.currentData() == header:
                combo.setCurrentIndex(0)
        self.statusBar().showMessage(f"{header} is no longer used by the visual model", 4000)

    def _spreadsheet_context_menu(self, position) -> None:
        index = self.table.indexAt(position)
        if index.isValid():
            self.table.setCurrentCell(index.row(), index.column())
        menu = QMenu(self)
        add_card = menu.addAction("Add card below")
        duplicate = menu.addAction("Duplicate selected card(s)")
        delete = menu.addAction("Delete selected card(s)")
        add_card.triggered.connect(
            lambda _checked=False, row=index.row() if index.isValid() else self.table.rowCount() - 1: self.table.append_row(row)
        )
        duplicate.triggered.connect(lambda _checked=False: self._duplicate_rows())
        delete.triggered.connect(lambda _checked=False: self.table.remove_selected_rows())
        if index.isValid():
            choose_image = menu.addAction("Choose image for this card…")
            choose_image.triggered.connect(lambda _checked=False: self.choose_row_image())
            self._field_menu(menu, index.column())
        menu.exec(self.table.viewport().mapToGlobal(position))

    def _header_context_menu(self, position) -> None:
        header_view = self.table.horizontalHeader()
        column = header_view.logicalIndexAt(position)
        menu = QMenu(self)
        if column >= 0:
            self._field_menu(menu, column)
        else:
            add = menu.addAction("Add field…")
            add.triggered.connect(lambda _checked=False: self.table.add_column())
        menu.exec(header_view.mapToGlobal(position))

    def clear_all(self) -> None:
        if QMessageBox.question(
            self,
            "Start a new blank table?",
            "Remove the current data and restore four empty cards with this model's fields?",
        ) == QMessageBox.StandardButton.Yes:
            headers = model_headers(self.model_combo.currentData() or MODEL_REFERENCE)
            self.table.set_data(SpreadsheetData(headers, [[""] * len(headers) for _ in range(4)]))
            self._auto_map_fields()
            self.position_seconds = 0.0
            self.update_preview()

    def add_soundtracks(self) -> None:
        paths, _ = QFileDialog.getOpenFileNames(
            self,
            "Add soundtrack files",
            "",
            "Audio (*.mp3 *.wav *.m4a *.aac *.flac *.ogg *.opus *.wma);;All files (*)",
        )
        for path in paths:
            self.soundtrack_table.append_track(AudioTrack(path=str(Path(path).resolve())) )

    def save_project(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Save project", "comparison-project.cts.json", "Comparison Studio projects (*.cts.json)")
        if not path:
            return
        if not path.endswith(".cts.json"):
            path += ".cts.json"
        try:
            data = self.spreadsheet_data()
            project_path = Path(path).resolve()
            asset_directory = project_path.with_name(project_path.name.removesuffix(".cts.json") + "_assets")
            for row_index, row in enumerate(data.rows, start=1):
                for column_index, value in enumerate(row):
                    source = Path(value).expanduser() if value and not value.lower().startswith(("http://", "https://")) else None
                    if source is None or not source.is_file() or not source.parent.name.startswith("comparison-studio-"):
                        continue
                    asset_directory.mkdir(parents=True, exist_ok=True)
                    destination = asset_directory / f"asset_{row_index:03d}_{column_index + 1:02d}{source.suffix.lower() or '.bin'}"
                    shutil.copy2(source, destination)
                    row[column_index] = str(destination)
            save_project_json(project_path, data, self.project_settings(), self.soundtrack_table.tracks())
            self.table.set_data(data)
            self.statusBar().showMessage(f"Saved {project_path.name}", 5000)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            show_error(self, "Could not save the project.", "Choose another destination.", str(exc))

    def open_project(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Open project", "", "Comparison Studio projects (*.cts.json)")
        if not path:
            return
        try:
            document = load_project_document(path)
            self._suspend_model_schema = True
            self.table.set_data(document.data)
            model_index = self.model_combo.findData(document.settings.model_id)
            self.model_combo.setCurrentIndex(max(0, model_index))
            self.default_visible.setChecked(document.settings.visible_cards == 0)
            if document.settings.visible_cards:
                self.visible_cards.setValue(document.settings.visible_cards)
            for role, combo in self.mapping_combos.items():
                combo.setCurrentIndex(max(0, combo.findData(document.settings.field_mapping.get(role, ""))))
            if document.settings.custom_duration is None:
                self.auto_length.setChecked(True)
            else:
                self.auto_length.setChecked(False)
                self.custom_length.setText(format_duration(document.settings.custom_duration))
            self.master_volume.setValue(round(document.settings.soundtrack_master_volume * 100))
            self.hexagons_bounce.setChecked(document.settings.hexagons_bounce)
            self.soundtrack_table.set_tracks(document.audio_tracks)
            self._suspend_model_schema = False
            self._refresh_field_guide(document.settings.model_id)
            self.position_seconds = 0.0
            self.update_preview()
            self.statusBar().showMessage(f"Opened {Path(path).name}", 5000)
        except FriendlyError as exc:
            self._suspend_model_schema = False
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            self._suspend_model_schema = False
            show_error(
                self,
                "Could not finish opening the project.",
                "Your current project was not intentionally changed.",
                str(exc),
            )

    def export_video(self) -> None:
        cards = self.cards()
        if not cards:
            show_error(self, "There are no cards to export.", "Add at least one spreadsheet row; its cells may be empty.")
            return
        try:
            settings = self.project_settings()
            tracks = self.soundtrack_table.tracks()
            for track in tracks:
                track.validate()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
            return
        path, _ = QFileDialog.getSaveFileName(self, "Export MP4", "comparison-video.mp4", "MP4 video (*.mp4)")
        if not path:
            return
        if not path.lower().endswith(".mp4"):
            path += ".mp4"
        self.pause_playback()
        self._export_worker = ExportWorker(cards, settings, path, tracks, self)
        dialog = ExportProgressDialog(self._export_worker, self)
        dialog.export_finished.connect(self._export_success)
        dialog.start()

    def _export_success(self, path: str) -> None:
        QMessageBox.information(self, "Export complete", f"The video was exported successfully.\n\n{path}")
        self.statusBar().showMessage(f"Exported {Path(path).name}", 7000)
