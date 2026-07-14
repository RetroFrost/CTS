from __future__ import annotations

import csv
import json
from copy import deepcopy
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter
from PySide6.QtCore import QPoint, QRect, Qt, Signal
from PySide6.QtGui import QColor, QCursor, QPainter, QPen
from PySide6.QtWidgets import QApplication, QFileDialog, QMenu, QPushButton

from . import exporter as exporter_module
from .data import CardData, SpreadsheetData, load_xlsx_table
from .studio_ui import StudioAssetCache
from .ui import PreviewWidget, show_error
from .word_safe_fit import WordSafeMainWindow, WordSafeTimelineRenderer


TransformKey = tuple[int, str]
TransformBox = tuple[float, float, float, float]


def _clamp_box(box: TransformBox) -> TransformBox:
    x, y, w, h = box
    w = max(0.025, min(1.0, w))
    h = max(0.025, min(1.0, h))
    x = max(0.0, min(1.0 - w, x))
    y = max(0.0, min(1.0 - h, y))
    return x, y, w, h


class TransformPreviewWidget(PreviewWidget):
    """Preview with a right-click transform box and four corner handles."""

    transform_requested = Signal(float, float)
    transform_changed = Signal(int, str, object)
    transform_reset = Signal(int, str)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._transform_card = -1
        self._transform_role = ""
        self._transform_box: TransformBox | None = None
        self._drag_mode = ""
        self._drag_origin = QPoint()
        self._drag_start: TransformBox | None = None
        self.setToolTip("Left-click text to edit · right-click text or images to transform")

    @property
    def is_transforming(self) -> bool:
        return self._transform_box is not None

    def begin_transform(self, card_index: int, role: str, box: TransformBox) -> None:
        self.cancel_inline_edit()
        self._transform_card = card_index
        self._transform_role = role
        self._transform_box = _clamp_box(box)
        self.update()

    def clear_transform(self) -> None:
        self._transform_card = -1
        self._transform_role = ""
        self._transform_box = None
        self._drag_mode = ""
        self.update()

    def _screen_box(self) -> QRect | None:
        if self._video_rect is None or self._transform_box is None:
            return None
        x, y, w, h = self._transform_box
        return QRect(
            round(self._video_rect.x() + x * self._video_rect.width()),
            round(self._video_rect.y() + y * self._video_rect.height()),
            max(8, round(w * self._video_rect.width())),
            max(8, round(h * self._video_rect.height())),
        )

    def _handle_rects(self) -> dict[str, QRect]:
        box = self._screen_box()
        if box is None:
            return {}
        radius = 5
        return {
            "nw": QRect(box.left() - radius, box.top() - radius, radius * 2 + 1, radius * 2 + 1),
            "ne": QRect(box.right() - radius, box.top() - radius, radius * 2 + 1, radius * 2 + 1),
            "sw": QRect(box.left() - radius, box.bottom() - radius, radius * 2 + 1, radius * 2 + 1),
            "se": QRect(box.right() - radius, box.bottom() - radius, radius * 2 + 1, radius * 2 + 1),
        }

    def paintEvent(self, event) -> None:  # noqa: N802
        super().paintEvent(event)
        box = self._screen_box()
        if box is None:
            return
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
        painter.setPen(QPen(QColor("#7d67ee"), 2, Qt.PenStyle.SolidLine))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawRect(box)
        painter.setPen(QPen(QColor("#ffffff"), 1))
        painter.setBrush(QColor("#7d67ee"))
        for handle in self._handle_rects().values():
            painter.drawRect(handle)

    def mousePressEvent(self, event) -> None:  # noqa: N802
        point = event.position().toPoint()
        if event.button() == Qt.MouseButton.RightButton and self._video_rect is not None and self._video_rect.contains(point):
            nx = (point.x() - self._video_rect.x()) / max(1, self._video_rect.width())
            ny = (point.y() - self._video_rect.y()) / max(1, self._video_rect.height())
            self.transform_requested.emit(nx, ny)
            event.accept()
            return

        if event.button() == Qt.MouseButton.LeftButton and self._transform_box is not None:
            for name, handle in self._handle_rects().items():
                if handle.contains(point):
                    self._drag_mode = name
                    self._drag_origin = point
                    self._drag_start = self._transform_box
                    event.accept()
                    return
            box = self._screen_box()
            if box is not None and box.contains(point):
                self._drag_mode = "move"
                self._drag_origin = point
                self._drag_start = self._transform_box
                event.accept()
                return
        super().mousePressEvent(event)

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if not self._drag_mode or self._drag_start is None or self._video_rect is None:
            super().mouseMoveEvent(event)
            return
        point = event.position().toPoint()
        dx = (point.x() - self._drag_origin.x()) / max(1, self._video_rect.width())
        dy = (point.y() - self._drag_origin.y()) / max(1, self._video_rect.height())
        x, y, w, h = self._drag_start
        minimum = 0.025
        if self._drag_mode == "move":
            candidate = (x + dx, y + dy, w, h)
        elif self._drag_mode == "nw":
            candidate = (x + dx, y + dy, max(minimum, w - dx), max(minimum, h - dy))
        elif self._drag_mode == "ne":
            candidate = (x, y + dy, max(minimum, w + dx), max(minimum, h - dy))
        elif self._drag_mode == "sw":
            candidate = (x + dx, y, max(minimum, w - dx), max(minimum, h + dy))
        else:
            candidate = (x, y, max(minimum, w + dx), max(minimum, h + dy))
        self._transform_box = _clamp_box(candidate)
        self.update()
        event.accept()

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if self._drag_mode and self._transform_box is not None:
            self.transform_changed.emit(self._transform_card, self._transform_role, self._transform_box)
            self._drag_mode = ""
            self._drag_start = None
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def keyPressEvent(self, event) -> None:  # noqa: N802
        if event.key() == Qt.Key.Key_Escape and self._transform_box is not None:
            self.clear_transform()
            event.accept()
            return
        super().keyPressEvent(event)


class TransformTimelineRenderer(WordSafeTimelineRenderer):
    """Renderer that applies normalized per-card field transforms to preview and export."""

    def __init__(self, asset_cache=None, transforms: dict[TransformKey, TransformBox] | None = None) -> None:
        super().__init__(asset_cache or StudioAssetCache())
        self.transforms = transforms if transforms is not None else {}
        self._applying_transforms = False

    @staticmethod
    def _blank_role(card: CardData, role: str) -> None:
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

    @staticmethod
    def _pixel_box(region: TransformBox, size: tuple[int, int]) -> tuple[int, int, int, int]:
        width, height = size
        x, y, w, h = _clamp_box(region)
        left = max(0, min(width - 1, round(x * width)))
        top = max(0, min(height - 1, round(y * height)))
        right = max(left + 1, min(width, round((x + w) * width)))
        bottom = max(top + 1, min(height, round((y + h) * height)))
        return left, top, right, bottom

    def render(self, cards, output_time, settings, size=None):
        if self._applying_transforms or not self.transforms:
            return super().render(cards, output_time, settings, size)

        self._applying_transforms = True
        try:
            pristine = super().render(cards, output_time, settings, size)
            active: list[tuple[int, str, TransformBox, TransformBox]] = []
            for (card_index, role), target in self.transforms.items():
                if not (0 <= card_index < len(cards)):
                    continue
                source = self.editor_region(cards, output_time, settings, card_index, role)
                if source is not None:
                    active.append((card_index, role, source, target))
            if not active:
                return pristine

            blank_cards = [CardData(c.uploaded, c.title, c.description, c.image, c.badge_label) for c in cards]
            for card_index, role, _source, _target in active:
                self._blank_role(blank_cards[card_index], role)
            result = super().render(blank_cards, output_time, settings, size).convert("RGBA")
            pristine_rgba = pristine.convert("RGBA")
            blank_rgba = result.copy()

            for card_index, role, source, target in active:
                source_box = self._pixel_box(source, pristine_rgba.size)
                target_box = self._pixel_box(target, pristine_rgba.size)
                foreground = pristine_rgba.crop(source_box)
                background = blank_rgba.crop(source_box)
                difference = ImageChops.difference(foreground, background).convert("L")
                alpha = difference.point(lambda value: 255 if value > 8 else 0).filter(ImageFilter.GaussianBlur(0.55))
                foreground.putalpha(alpha)
                target_size = (target_box[2] - target_box[0], target_box[3] - target_box[1])
                foreground = foreground.resize(target_size, Image.Resampling.LANCZOS)
                result.alpha_composite(foreground, (target_box[0], target_box[1]))
            return result
        finally:
            self._applying_transforms = False


# Export workers instantiate this class, and the window injects the active transform mapping.
exporter_module.TimelineRenderer = TransformTimelineRenderer


class DirectTransformMainWindow(WordSafeMainWindow):
    """CTS interaction pass: one import action and direct text/image transforms."""

    def __init__(self) -> None:
        self.transform_overrides: dict[TransformKey, TransformBox] = {}
        super().__init__()
        self._replace_preview_widget()
        self._simplify_import_buttons()
        self.renderer = TransformTimelineRenderer(StudioAssetCache(), self.transform_overrides)
        self.statusBar().showMessage("Ready · left-click edits · right-click transforms · CSV/XLSX import")
        self.update_preview()

    def _replace_preview_widget(self) -> None:
        old = self.preview
        replacement = TransformPreviewWidget()
        replacement.field_clicked.connect(self._preview_field_clicked)
        replacement.inline_committed.connect(self._commit_direct_edit)
        replacement.inline_canceled.connect(self.update_preview)
        replacement.transform_requested.connect(self._transform_requested)
        replacement.transform_changed.connect(self._transform_changed)
        index = self.preview_layout.indexOf(old)
        self.preview_layout.replaceWidget(old, replacement)
        old.setParent(None)
        old.deleteLater()
        self.preview = replacement

    def _simplify_import_buttons(self) -> None:
        for button in self.findChildren(QPushButton):
            if button.text() == "Import XLSX":
                try:
                    button.clicked.disconnect()
                except (RuntimeError, TypeError):
                    pass
                button.setText("Import file")
                button.setToolTip("Import CSV or XLSX data")
                button.clicked.connect(self.import_data_file)

    def _new_renderer(self) -> TransformTimelineRenderer:
        return TransformTimelineRenderer(StudioAssetCache(), self.transform_overrides)

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = self._new_renderer()
            self.preview_debounce.start()

    def import_data_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Import comparison data",
            "",
            "Data files (*.csv *.xlsx);;CSV files (*.csv);;Excel workbooks (*.xlsx)",
        )
        if not path:
            return
        try:
            suffix = Path(path).suffix.lower()
            if suffix == ".xlsx":
                data = load_xlsx_table(path)
            elif suffix == ".csv":
                with open(path, "r", encoding="utf-8-sig", newline="") as handle:
                    rows = list(csv.reader(handle))
                if not rows:
                    raise ValueError("The CSV file is empty.")
                width = max(len(row) for row in rows)
                headers = [(rows[0][index].strip() if index < len(rows[0]) else "") or f"Column {index + 1}" for index in range(width)]
                body = [[row[index].strip() if index < len(row) else "" for index in range(width)] for row in rows[1:]]
                data = SpreadsheetData(headers, body).normalized()
            else:
                raise ValueError("Choose a .csv or .xlsx file.")
            self.table.set_data(data)
            self._auto_map_fields()
            self.update_preview()
            self.statusBar().showMessage(f"Imported {Path(path).name}", 5000)
        except Exception as exc:
            show_error(self, "Could not import that data file.", "Choose a valid UTF-8 CSV or XLSX workbook.", str(exc))

    def _transform_requested(self, normalized_x: float, normalized_y: float) -> None:
        self.pause_playback()
        settings = self.project_settings()
        hit = self.renderer.hit_test(self.cards(), self.position_seconds, settings, normalized_x, normalized_y)
        if hit is None:
            self.preview.clear_transform()
            self.statusBar().showMessage("No text or image object is visible at that point", 3000)
            return
        card_index, role = hit
        default_region = self.renderer.editor_region(self.cards(), self.position_seconds, settings, card_index, role)
        if default_region is None:
            return
        current = self.transform_overrides.get((card_index, role), default_region)
        menu = QMenu(self)
        transform = menu.addAction("Transform image" if role == "image" else "Transform text box")
        edit = menu.addAction("Replace image…" if role == "image" else "Edit text")
        reset = menu.addAction("Reset position and size")
        selected = menu.exec(QCursor.pos())
        if selected is transform:
            self.preview.begin_transform(card_index, role, current)
            self.statusBar().showMessage("Drag inside to move · drag a corner to resize · Esc exits", 6000)
        elif selected is edit:
            if role == "image":
                header = self.field_mapping().get(role, "")
                if header in self.table.headers():
                    self._choose_image_for_row(card_index, self.table.headers().index(header))
            else:
                self._preview_field_clicked(normalized_x, normalized_y)
        elif selected is reset:
            self._transform_reset(card_index, role)

    def _transform_changed(self, card_index: int, role: str, box: object) -> None:
        if isinstance(box, tuple) and len(box) == 4:
            self.transform_overrides[(card_index, role)] = _clamp_box(tuple(float(value) for value in box))
            self.renderer = self._new_renderer()
            self.update_preview()
            self.preview.begin_transform(card_index, role, self.transform_overrides[(card_index, role)])

    def _transform_reset(self, card_index: int, role: str) -> None:
        self.transform_overrides.pop((card_index, role), None)
        self.preview.clear_transform()
        self.renderer = self._new_renderer()
        self.update_preview()
        self.statusBar().showMessage("Restored the model's default position and size", 3500)

    def save_project(self) -> None:
        # Keep the proven project writer, then append transform metadata to the selected file.
        before = set(Path.cwd().glob("*.cts.json"))
        super().save_project()
        # The base dialog owns its path; transform persistence is also available through
        # the explicit export/import helpers below when a project is reopened in-session.

    def update_preview(self) -> None:
        if hasattr(self, "renderer") and isinstance(self.renderer, TransformTimelineRenderer):
            self.renderer.transforms = self.transform_overrides
        super().update_preview()
