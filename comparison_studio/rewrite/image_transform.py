from __future__ import annotations

import base64
import json
from dataclasses import dataclass, replace

from PIL import Image, ImageOps
from PySide6.QtCore import QPoint, QRect, Qt, Signal
from PySide6.QtGui import QColor, QPainter, QPen

from .inline_preview import InlinePreviewCanvas, hit_test_field
from .model import Card, MODEL_COMPACT, MODEL_ILLUSTRATED, MODEL_REFERENCE, Project
from .render import AssetCache, Renderer, render_badge
from .timing import Timeline

_MARKER = "||ctsimg:"
_HANDLE_SIZE = 11
_MIN_OBJECT_SIZE = 14


@dataclass(frozen=True, slots=True)
class ImageTransform:
    scale: float = 1.0
    x: float = 0.0
    y: float = 0.0
    mode: str = "auto"
    width_scale: float = 1.0
    height_scale: float = 1.0

    def normalized(self) -> "ImageTransform":
        mode = self.mode if self.mode in {"auto", "fit", "fill"} else "auto"
        return ImageTransform(
            scale=max(0.25, min(4.0, float(self.scale))),
            x=max(-2.0, min(2.0, float(self.x))),
            y=max(-2.0, min(2.0, float(self.y))),
            mode=mode,
            width_scale=max(0.10, min(8.0, float(self.width_scale))),
            height_scale=max(0.10, min(8.0, float(self.height_scale))),
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
            width_scale=float(payload.get("w", 1.0)),
            height_scale=float(payload.get("h", 1.0)),
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
        {
            "s": round(transform.scale, 5),
            "x": round(transform.x, 5),
            "y": round(transform.y, 5),
            "m": transform.mode,
            "w": round(transform.width_scale, 5),
            "h": round(transform.height_scale, 5),
        },
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


def _contains_transparency(source: Image.Image) -> bool:
    return source.getchannel("A").getextrema()[0] < 255


def _use_contain(source: Image.Image, transform: ImageTransform, default_contain: bool) -> bool:
    return transform.mode == "fit" or (
        transform.mode == "auto" and (default_contain or _contains_transparency(source))
    )


def _base_image(
    source: Image.Image,
    target_size: tuple[int, int],
    transform: ImageTransform,
    default_contain: bool,
) -> Image.Image:
    target_width, target_height = max(1, target_size[0]), max(1, target_size[1])
    if _use_contain(source, transform, default_contain):
        return ImageOps.contain(source, (target_width, target_height), Image.Resampling.LANCZOS)
    return ImageOps.fit(source, (target_width, target_height), Image.Resampling.LANCZOS)


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
        base = _base_image(source, (target_width, target_height), transform, default_contain)
        scaled_width = max(
            1,
            round(base.width * transform.scale * transform.width_scale),
        )
        scaled_height = max(
            1,
            round(base.height * transform.scale * transform.height_scale),
        )
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
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(0, round(height * 0.004))),
        )
        return layer


@dataclass(frozen=True, slots=True)
class ImageHit:
    card_index: int
    rect: QRect


def _target_rect_for_card(
    project: Project,
    output_time: float,
    video_rect: tuple[int, int, int, int],
    card_index: int,
) -> QRect | None:
    video_x, video_y, video_width, video_height = video_rect
    cards = project.content_cards()
    timeline = Timeline(project, len(cards))
    card_width = video_width / timeline.visible_cards
    left_n, top_n, right_n, bottom_n = image_region(project.model_id)
    for placement in timeline.placements(output_time, float(video_width)):
        if placement.index != card_index:
            continue
        y_offset = round((1.0 - placement.alpha) * video_height * 0.018)
        return QRect(
            video_x + round(placement.x + left_n * card_width),
            video_y + round(y_offset + top_n * video_height),
            max(1, round((right_n - left_n) * card_width)),
            max(1, round((bottom_n - top_n) * video_height)),
        )
    return None


def _image_hit(
    project: Project,
    output_time: float,
    video_rect: tuple[int, int, int, int],
    point: QPoint,
) -> ImageHit | None:
    video_x, video_y, video_width, video_height = video_rect
    if not (
        video_x <= point.x() <= video_x + video_width
        and video_y <= point.y() <= video_y + video_height
    ):
        return None
    cards = project.content_cards()
    timeline = Timeline(project, len(cards))
    for placement in reversed(timeline.placements(output_time, float(video_width))):
        rect = _target_rect_for_card(project, output_time, video_rect, placement.index)
        if rect is not None and rect.contains(point):
            return ImageHit(placement.index, rect)
    return None


def _object_rect_for_card(
    project: Project,
    output_time: float,
    video_rect: tuple[int, int, int, int],
    card_index: int,
    assets: AssetCache,
) -> QRect | None:
    cards = project.content_cards()
    if not (0 <= card_index < len(cards)):
        return None
    target = _target_rect_for_card(project, output_time, video_rect, card_index)
    if target is None:
        return None
    source_value, transform = parse_image_reference(cards[card_index].image)
    source = assets.load(source_value) if source_value else None
    if source is None:
        return target
    base = _base_image(
        source,
        (target.width(), target.height()),
        transform,
        default_contain=False,
    )
    width = max(1, round(base.width * transform.scale * transform.width_scale))
    height = max(1, round(base.height * transform.scale * transform.height_scale))
    center_x = target.center().x() + transform.x * target.width() / 2
    center_y = target.center().y() + transform.y * target.height() / 2
    return QRect(
        round(center_x - width / 2),
        round(center_y - height / 2),
        width,
        height,
    )


def _handle_centers(rect: QRect) -> dict[str, QPoint]:
    return {
        "nw": rect.topLeft(),
        "n": QPoint(rect.center().x(), rect.top()),
        "ne": rect.topRight(),
        "e": QPoint(rect.right(), rect.center().y()),
        "se": rect.bottomRight(),
        "s": QPoint(rect.center().x(), rect.bottom()),
        "sw": rect.bottomLeft(),
        "w": QPoint(rect.left(), rect.center().y()),
    }


def _handle_rect(rect: QRect, name: str) -> QRect | None:
    center = _handle_centers(rect).get(name)
    if center is None:
        return None
    half = _HANDLE_SIZE // 2
    return QRect(center.x() - half, center.y() - half, _HANDLE_SIZE, _HANDLE_SIZE)


def _handle_at(rect: QRect, point: QPoint) -> str | None:
    for name in ("nw", "n", "ne", "e", "se", "s", "sw", "w"):
        handle = _handle_rect(rect, name)
        if handle is not None and handle.contains(point):
            return name
    return None


def resize_transform(
    transform: ImageTransform,
    object_rect: QRect,
    target_rect: QRect,
    handle: str,
    delta: QPoint,
) -> ImageTransform:
    """Resize one or two axes while keeping the opposite edge anchored."""
    left = float(object_rect.left())
    top = float(object_rect.top())
    right = float(object_rect.right() + 1)
    bottom = float(object_rect.bottom() + 1)

    if "w" in handle:
        left += delta.x()
    if "e" in handle:
        right += delta.x()
    if "n" in handle:
        top += delta.y()
    if "s" in handle:
        bottom += delta.y()

    if right - left < _MIN_OBJECT_SIZE:
        if "w" in handle:
            left = right - _MIN_OBJECT_SIZE
        else:
            right = left + _MIN_OBJECT_SIZE
    if bottom - top < _MIN_OBJECT_SIZE:
        if "n" in handle:
            top = bottom - _MIN_OBJECT_SIZE
        else:
            bottom = top + _MIN_OBJECT_SIZE

    old_width = max(1.0, float(object_rect.width()))
    old_height = max(1.0, float(object_rect.height()))
    new_width = max(1.0, right - left)
    new_height = max(1.0, bottom - top)
    old_center_x = object_rect.center().x()
    old_center_y = object_rect.center().y()
    new_center_x = (left + right) / 2
    new_center_y = (top + bottom) / 2

    width_scale = transform.width_scale
    height_scale = transform.height_scale
    if "w" in handle or "e" in handle:
        width_scale *= new_width / old_width
    if "n" in handle or "s" in handle:
        height_scale *= new_height / old_height

    return ImageTransform(
        scale=transform.scale,
        x=transform.x
        + 2.0 * (new_center_x - old_center_x) / max(1, target_rect.width()),
        y=transform.y
        + 2.0 * (new_center_y - old_center_y) / max(1, target_rect.height()),
        mode=transform.mode,
        width_scale=width_scale,
        height_scale=height_scale,
    ).normalized()


class TransformPreviewCanvas(InlinePreviewCanvas):
    """Inline text editor plus move, free resize, and proportional zoom for artwork."""

    image_transform_changed = Signal(int, str)
    image_selected = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._selected_image = -1
        self._selected_rect: QRect | None = None
        self._geometry_assets = AssetCache()
        self._drag_hit: ImageHit | None = None
        self._drag_origin = QPoint()
        self._drag_transform = ImageTransform()
        self._drag_object_rect: QRect | None = None
        self._drag_mode = ""
        self._resize_handle = ""
        self.setToolTip(
            "Click text to type. Drag artwork to move. Drag blue handles to resize freely. "
            "Use the mouse wheel to zoom proportionally."
        )

    @property
    def selected_image_rect(self) -> QRect | None:
        return QRect(self._selected_rect) if self._selected_rect is not None else None

    def resize_handle_rect(self, name: str) -> QRect | None:
        if self._selected_rect is None:
            return None
        return _handle_rect(self._selected_rect, name)

    def set_frame(self, project: Project, output_time: float, pixmap) -> None:
        super().set_frame(project, output_time, pixmap)
        self._refresh_selected_rect()

    def _refresh_selected_rect(self) -> None:
        if self._project is None or self._video_rect is None or self._selected_image < 0:
            self._selected_rect = None
            self.update()
            return
        self._selected_rect = _object_rect_for_card(
            self._project,
            self._time,
            self._video_rect,
            self._selected_image,
            self._geometry_assets,
        )
        self.update()

    def paintEvent(self, event) -> None:
        super().paintEvent(event)
        self._refresh_selected_rect_without_repaint()
        if self._selected_rect is None:
            return
        painter = QPainter(self)
        painter.setPen(QPen(QColor("#67a8e4"), 2, Qt.PenStyle.DashLine))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawRect(self._selected_rect.adjusted(1, 1, -2, -2))
        painter.setBrush(QColor("#67a8e4"))
        painter.setPen(QPen(QColor("#e4f2ff"), 1))
        for name in ("nw", "n", "ne", "e", "se", "s", "sw", "w"):
            handle = _handle_rect(self._selected_rect, name)
            if handle is not None:
                painter.drawRect(handle)

    def _refresh_selected_rect_without_repaint(self) -> None:
        if self._project is None or self._video_rect is None or self._selected_image < 0:
            return
        self._selected_rect = _object_rect_for_card(
            self._project,
            self._time,
            self._video_rect,
            self._selected_image,
            self._geometry_assets,
        )

    @staticmethod
    def _cursor_for_handle(handle: str) -> Qt.CursorShape:
        if handle in {"nw", "se"}:
            return Qt.CursorShape.SizeFDiagCursor
        if handle in {"ne", "sw"}:
            return Qt.CursorShape.SizeBDiagCursor
        if handle in {"n", "s"}:
            return Qt.CursorShape.SizeVerCursor
        return Qt.CursorShape.SizeHorCursor

    def mousePressEvent(self, event) -> None:
        if (
            event.button() != Qt.MouseButton.LeftButton
            or self._project is None
            or self._video_rect is None
        ):
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

        self._refresh_selected_rect_without_repaint()
        if self._selected_rect is not None and self._selected_image >= 0:
            handle = _handle_at(self._selected_rect, point)
            if handle is not None:
                target = _target_rect_for_card(
                    self._project,
                    self._time,
                    self._video_rect,
                    self._selected_image,
                )
                cards = self._project.content_cards()
                if target is not None and self._selected_image < len(cards):
                    _source, transform = parse_image_reference(
                        cards[self._selected_image].image
                    )
                    self._drag_hit = ImageHit(self._selected_image, target)
                    self._drag_origin = point
                    self._drag_transform = transform
                    self._drag_object_rect = QRect(self._selected_rect)
                    self._drag_mode = "resize"
                    self._resize_handle = handle
                    self.setCursor(self._cursor_for_handle(handle))
                    event.accept()
                    return

        hit = _image_hit(self._project, self._time, self._video_rect, point)
        if hit is None:
            self._selected_image = -1
            self._selected_rect = None
            self._drag_hit = None
            self._drag_mode = ""
            super().mousePressEvent(event)
            self.update()
            return
        cards = self._project.content_cards()
        source, transform = parse_image_reference(cards[hit.card_index].image)
        if not source:
            super().mousePressEvent(event)
            return
        self.clear_field_selection()
        self._selected_image = hit.card_index
        self._drag_hit = hit
        self._drag_origin = point
        self._drag_transform = transform
        self._drag_object_rect = _object_rect_for_card(
            self._project,
            self._time,
            self._video_rect,
            hit.card_index,
            self._geometry_assets,
        )
        self._drag_mode = "move"
        self._resize_handle = ""
        self.card_clicked.emit(hit.card_index)
        self.image_selected.emit(hit.card_index)
        self._refresh_selected_rect()
        self.setCursor(Qt.CursorShape.ClosedHandCursor)
        event.accept()

    def _apply_transform(self, card_index: int, transform: ImageTransform) -> None:
        if self._project is None:
            return
        cards = self._project.content_cards()
        if not (0 <= card_index < len(cards)):
            return
        source, _old = parse_image_reference(cards[card_index].image)
        encoded = format_image_reference(source, transform)
        cards[card_index].image = encoded
        self.image_transform_changed.emit(card_index, encoded)

    def mouseMoveEvent(self, event) -> None:
        if self._drag_hit is not None and self._project is not None:
            point = event.position().toPoint()
            delta = point - self._drag_origin
            if (
                self._drag_mode == "resize"
                and self._drag_object_rect is not None
                and self._resize_handle
            ):
                transform = resize_transform(
                    self._drag_transform,
                    self._drag_object_rect,
                    self._drag_hit.rect,
                    self._resize_handle,
                    delta,
                )
            else:
                rect = self._drag_hit.rect
                transform = ImageTransform(
                    scale=self._drag_transform.scale,
                    x=self._drag_transform.x
                    + (2.0 * delta.x() / max(1, rect.width())),
                    y=self._drag_transform.y
                    + (2.0 * delta.y() / max(1, rect.height())),
                    mode=self._drag_transform.mode,
                    width_scale=self._drag_transform.width_scale,
                    height_scale=self._drag_transform.height_scale,
                ).normalized()
            self._apply_transform(self._drag_hit.card_index, transform)
            event.accept()
            return

        super().mouseMoveEvent(event)
        point = event.position().toPoint()
        self._refresh_selected_rect_without_repaint()
        if self._selected_rect is not None:
            handle = _handle_at(self._selected_rect, point)
            if handle is not None:
                self.setCursor(self._cursor_for_handle(handle))
                return
            if self._selected_rect.contains(point):
                self.setCursor(Qt.CursorShape.OpenHandCursor)
                return
        if self._project is not None and self._video_rect is not None:
            hit = _image_hit(self._project, self._time, self._video_rect, point)
            if hit is not None:
                self.setCursor(Qt.CursorShape.OpenHandCursor)

    def mouseReleaseEvent(self, event) -> None:
        if self._drag_hit is not None and event.button() == Qt.MouseButton.LeftButton:
            self._drag_hit = None
            self._drag_object_rect = None
            self._drag_mode = ""
            self._resize_handle = ""
            self.unsetCursor()
            self._refresh_selected_rect()
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
        transform = ImageTransform(
            scale=transform.scale * factor,
            x=transform.x,
            y=transform.y,
            mode=transform.mode,
            width_scale=transform.width_scale,
            height_scale=transform.height_scale,
        ).normalized()
        self._selected_image = hit.card_index
        self._apply_transform(hit.card_index, transform)
        event.accept()

    def mouseDoubleClickEvent(self, event) -> None:
        if self._project is None or self._video_rect is None:
            super().mouseDoubleClickEvent(event)
            return
        hit = _image_hit(
            self._project,
            self._time,
            self._video_rect,
            event.position().toPoint(),
        )
        if hit is None:
            super().mouseDoubleClickEvent(event)
            return
        card = self._project.content_cards()[hit.card_index]
        source, _ = parse_image_reference(card.image)
        if source:
            self._selected_image = hit.card_index
            self._apply_transform(hit.card_index, ImageTransform())
            event.accept()

    def select_image(self, card_index: int) -> None:
        if self._project is None or self._video_rect is None:
            return
        cards = self._project.content_cards()
        if not (0 <= card_index < len(cards)):
            return
        source, _transform = parse_image_reference(cards[card_index].image)
        if not source:
            self._selected_image = -1
            self._selected_rect = None
            self.update()
            return
        self._selected_image = card_index
        self._refresh_selected_rect()
