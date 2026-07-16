from __future__ import annotations

import base64
import json
from dataclasses import dataclass, replace

from PIL import Image, ImageOps
from PySide6.QtCore import QPoint, QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen

from .inline_preview import InlinePreviewCanvas, hit_test_field
from .model import Card, MODEL_COMPACT, MODEL_ILLUSTRATED, MODEL_REFERENCE, Project
from .render import Renderer, render_badge
from .timing import Timeline

_MARKER = "||ctsimg:"


@dataclass(frozen=True, slots=True)
class ImageTransform:
    scale: float = 1.0
    x: float = 0.0
    y: float = 0.0
    mode: str = "auto"

    def normalized(self) -> "ImageTransform":
        mode = self.mode if self.mode in {"auto", "fit", "fill"} else "auto"
        return ImageTransform(
            scale=max(0.25, min(4.0, float(self.scale))),
            x=max(-2.0, min(2.0, float(self.x))),
            y=max(-2.0, min(2.0, float(self.y))),
            mode=mode,
        )


def parse_image_reference(value: str) -> tuple[str, ImageTransform]:
    text = str(value or "").strip()
    if _MARKER not in text:
        return text, ImageTransform()
    source, encoded = text.rsplit(_MARKER, 1)
    try:
        padding = "=" * (-len(encoded) % 4)
        payload = json.loads(base64.urlsafe_b64decode(encoded + padding).decode("utf-8"))
        transform = ImageTransform(
            scale=float(payload.get("s", 1.0)),
            x=float(payload.get("x", 0.0)),
            y=float(payload.get("y", 0.0)),
            mode=str(payload.get("m", "auto")),
        ).normalized()
        return source, transform
    except Exception:
        return text, ImageTransform()


def format_image_reference(source: str, transform: ImageTransform) -> str:
    source = str(source or "").strip()
    transform = transform.normalized()
    if not source:
        return ""
    if transform == ImageTransform():
        return source
    payload = json.dumps(
        {"s": round(transform.scale, 5), "x": round(transform.x, 5), "y": round(transform.y, 5), "m": transform.mode},
        separators=(",", ":"),
    ).encode("utf-8")
    encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
    return f"{source}{_MARKER}{encoded}"


def source_only(value: str) -> str:
    return parse_image_reference(value)[0]


def transform_only(value: str) -> ImageTransform:
    return parse_image_reference(value)[1]


def image_region(model_id: str) -> tuple[float, float, float, float]:
    if model_id == MODEL_ILLUSTRATED:
        return (0.0125, 0.0, 0.9875, 0.730)
    if model_id == MODEL_COMPACT:
        return (0.010, 0.495, 0.990, 1.0)
    return (0.080, 0.670, 0.920, 1.0)


class TransformRenderer(Renderer):
    """Renderer that applies the same saved image transform in preview and export."""

    def _transformed_image(
        self,
        value: str,
        target_size: tuple[int, int],
        *,
        default_contain: bool,
    ) -> Image.Image | None:
        source_value, transform = parse_image_reference(value)
        source = self.assets.load(source_value)
        if source is None:
            return None
        target_width, target_height = max(1, target_size[0]), max(1, target_size[1])
        alpha_minimum = source.getchannel("A").getextrema()[0]
        contain = transform.mode == "fit" or (
            transform.mode == "auto" and (default_contain or alpha_minimum < 255)
        )
        if contain:
            base = ImageOps.contain(source, (target_width, target_height), Image.Resampling.LANCZOS)
        else:
            base = ImageOps.fit(source, (target_width, target_height), Image.Resampling.LANCZOS)
        scaled_width = max(1, round(base.width * transform.scale))
        scaled_height = max(1, round(base.height * transform.scale))
        if (scaled_width, scaled_height) != base.size:
            base = base.resize((scaled_width, scaled_height), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (target_width, target_height), (0, 0, 0, 0))
        left = round((target_width - base.width) / 2 + transform.x * target_width / 2)
        top = round((target_height - base.height) / 2 + transform.y * target_height / 2)
        canvas.alpha_composite(base, (left, top))
        return canvas

    def _paste_image(self, layer, draw, source_value, box, contain):
        left, top, right, bottom = box
        transformed = self._transformed_image(
            source_value,
            (max(1, right - left), max(1, bottom - top)),
            default_contain=contain,
        )
        if transformed is None:
            self._draw_missing_image(draw, box)
            return
        layer.alpha_composite(transformed, (left, top))

    def _render_illustrated(self, card: Card, width: int, height: int, badge_scale: float):
        source_value, _transform = parse_image_reference(card.image)
        if not source_value:
            return super()._render_illustrated(replace(card, image=""), width, height, badge_scale)
        layer = super()._render_illustrated(replace(card, image=""), width, height, badge_scale)
        divider = max(2, round(width * 0.0125))
        artwork_bottom = round(height * 0.730)
        transformed = self._transformed_image(
            card.image,
            (max(1, width - divider * 2), max(1, artwork_bottom)),
            default_contain=False,
        )
        if transformed is None:
            return super()._render_illustrated(replace(card, image=source_value), width, height, badge_scale)
        layer.alpha_composite(transformed, (divider, 0))
        badge = render_badge(card.value, card.label, width, height, badge_scale)
        layer.alpha_composite(badge, ((width - badge.width) // 2, max(0, round(height * 0.004))))
        return layer


@dataclass(frozen=True, slots=True)
class ImageHit:
    card_index: int
    rect: QRect


def _image_hit(project: Project, output_time: float, video_rect: tuple[int, int, int, int], point: QPoint) -> ImageHit | None:
    video_x, video_y, video_width, video_height = video_rect
    if not (video_x <= point.x() <= video_x + video_width and video_y <= point.y() <= video_y + video_height):
        return None
    local_x = point.x() - video_x
    local_y = point.y() - video_y
    cards = project.content_cards()
    timeline = Timeline(project, len(cards))
    card_width = video_width / timeline.visible_cards
    left_n, top_n, right_n, bottom_n = image_region(project.model_id)
    for placement in reversed(timeline.placements(output_time, float(video_width))):
        if placement.index >= len(cards):
            continue
        y_offset = round((1.0 - placement.alpha) * video_height * 0.018)
        rect = QRect(
            video_x + round(placement.x + left_n * card_width),
            video_y + round(y_offset + top_n * video_height),
            max(1, round((right_n - left_n) * card_width)),
            max(1, round((bottom_n - top_n) * video_height)),
        )
        if rect.contains(point):
            return ImageHit(placement.index, rect)
    return None


class TransformPreviewCanvas(InlinePreviewCanvas):
    """Inline text editor plus direct image drag and wheel zoom."""

    image_transform_changed = Signal(int, str)
    image_selected = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._selected_image = -1
        self._selected_rect: QRect | None = None
        self._drag_hit: ImageHit | None = None
        self._drag_origin = QPoint()
        self._drag_transform = ImageTransform()
        self.setToolTip("Click text to type. Drag artwork to move it. Use the mouse wheel to zoom.")

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        if self._selected_rect is None:
            return
        painter = QPainter(self)
        painter.setPen(QPen(QColor("#67a8e4"), 2, Qt.PenStyle.DashLine))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawRect(self._selected_rect.adjusted(1, 1, -2, -2))
        painter.setBrush(QColor("#67a8e4"))
        painter.setPen(Qt.PenStyle.NoPen)
        for point in (
            self._selected_rect.topLeft(),
            self._selected_rect.topRight(),
            self._selected_rect.bottomLeft(),
            self._selected_rect.bottomRight(),
        ):
            painter.drawRect(QRect(point.x() - 3, point.y() - 3, 7, 7))

    def mousePressEvent(self, event) -> None:
        if event.button() != Qt.MouseButton.LeftButton or self._project is None or self._video_rect is None:
            super().mousePressEvent(event)
            return
        point = event.position().toPoint()
        video_x, video_y, video_width, video_height = self._video_rect
        field = hit_test_field(
            self._project,
            self._time,
            video_width,
            video_height,
            point.x() - video_x,
            point.y() - video_y,
        )
        if field is not None:
            super().mousePressEvent(event)
            return
        hit = _image_hit(self._project, self._time, self._video_rect, point)
        if hit is None:
            self._selected_image = -1
            self._selected_rect = None
            super().mousePressEvent(event)
            self.update()
            return
        cards = self._project.content_cards()
        source, transform = parse_image_reference(cards[hit.card_index].image)
        if not source:
            super().mousePressEvent(event)
            return
        self._selected_image = hit.card_index
        self._selected_rect = hit.rect
        self._drag_hit = hit
        self._drag_origin = point
        self._drag_transform = transform
        self.card_clicked.emit(hit.card_index)
        self.image_selected.emit(hit.card_index)
        self.setCursor(Qt.CursorShape.ClosedHandCursor)
        event.accept()
        self.update()

    def mouseMoveEvent(self, event) -> None:
        if self._drag_hit is None or self._project is None:
            super().mouseMoveEvent(event)
            return
        point = event.position().toPoint()
        delta = point - self._drag_origin
        rect = self._drag_hit.rect
        transform = ImageTransform(
            scale=self._drag_transform.scale,
            x=self._drag_transform.x + (2.0 * delta.x() / max(1, rect.width())),
            y=self._drag_transform.y + (2.0 * delta.y() / max(1, rect.height())),
            mode=self._drag_transform.mode,
        ).normalized()
        card = self._project.content_cards()[self._drag_hit.card_index]
        source, _ = parse_image_reference(card.image)
        encoded = format_image_reference(source, transform)
        card.image = encoded
        self.image_transform_changed.emit(self._drag_hit.card_index, encoded)
        event.accept()

    def mouseReleaseEvent(self, event) -> None:
        if self._drag_hit is not None and event.button() == Qt.MouseButton.LeftButton:
            self._drag_hit = None
            self.unsetCursor()
            event.accept()
            return
        super().mouseReleaseEvent(event)

    def wheelEvent(self, event) -> None:
        if self._project is None or self._video_rect is None:
            super().wheelEvent(event)
            return
        point = event.position().toPoint()
        hit = _image_hit(self._project, self._time, self._video_rect, point)
        if hit is None:
            super().wheelEvent(event)
            return
        card = self._project.content_cards()[hit.card_index]
        source, transform = parse_image_reference(card.image)
        if not source:
            super().wheelEvent(event)
            return
        factor = 1.10 if event.angleDelta().y() > 0 else 1 / 1.10
        transform = ImageTransform(transform.scale * factor, transform.x, transform.y, transform.mode).normalized()
        encoded = format_image_reference(source, transform)
        card.image = encoded
        self._selected_image = hit.card_index
        self._selected_rect = hit.rect
        self.image_transform_changed.emit(hit.card_index, encoded)
        event.accept()

    def mouseDoubleClickEvent(self, event) -> None:
        if self._project is None or self._video_rect is None:
            super().mouseDoubleClickEvent(event)
            return
        hit = _image_hit(self._project, self._time, self._video_rect, event.position().toPoint())
        if hit is None:
            super().mouseDoubleClickEvent(event)
            return
        card = self._project.content_cards()[hit.card_index]
        source, _ = parse_image_reference(card.image)
        if source:
            encoded = format_image_reference(source, ImageTransform())
            card.image = encoded
            self.image_transform_changed.emit(hit.card_index, encoded)
            event.accept()

    def select_image(self, card_index: int) -> None:
        if self._project is None or self._video_rect is None:
            return
        cards = self._project.content_cards()
        if not (0 <= card_index < len(cards)):
            return
        video_x, video_y, video_width, video_height = self._video_rect
        timeline = Timeline(self._project, len(cards))
        card_width = video_width / timeline.visible_cards
        left_n, top_n, right_n, bottom_n = image_region(self._project.model_id)
        for placement in timeline.placements(self._time, float(video_width)):
            if placement.index != card_index:
                continue
            y_offset = round((1.0 - placement.alpha) * video_height * 0.018)
            self._selected_image = card_index
            self._selected_rect = QRect(
                video_x + round(placement.x + left_n * card_width),
                video_y + round(y_offset + top_n * video_height),
                max(1, round((right_n - left_n) * card_width)),
                max(1, round((bottom_n - top_n) * video_height)),
            )
            self.update()
            return
