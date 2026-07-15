from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter
from PySide6.QtCore import Qt
from PySide6.QtGui import QCursor
from PySide6.QtWidgets import QApplication, QMenu

from . import exporter as exporter_module
from .data import REFERENCE_FADE_SECONDS
from .deselect_fix import DeselectablePreviewWidget, DeselectFixedMainWindow
from .direct_transform import TransformBox, TransformKey, _clamp_box
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .renderer import BACKGROUND, _smoothstep
from .studio_ui import StudioAssetCache


class ClickableTransformPreviewWidget(DeselectablePreviewWidget):
    """Keep drag-to-move, but treat a plain click as an edit/replace request."""

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self._move_click_candidate = False

    def mousePressEvent(self, event) -> None:  # noqa: N802
        self._move_click_candidate = False
        super().mousePressEvent(event)
        if event.button() == Qt.MouseButton.LeftButton and self._drag_mode == "move":
            self._move_click_candidate = True

    def mouseMoveEvent(self, event) -> None:  # noqa: N802
        if self._move_click_candidate:
            distance = (event.position().toPoint() - self._drag_origin).manhattanLength()
            if distance < QApplication.startDragDistance():
                event.accept()
                return
            self._move_click_candidate = False
        super().mouseMoveEvent(event)

    def mouseReleaseEvent(self, event) -> None:  # noqa: N802
        if (
            self._move_click_candidate
            and self._drag_mode == "move"
            and self._video_rect is not None
        ):
            point = event.position().toPoint()
            self._move_click_candidate = False
            self._drag_mode = ""
            self._drag_start = None
            if self._video_rect.contains(point):
                nx = (point.x() - self._video_rect.x()) / max(1, self._video_rect.width())
                ny = (point.y() - self._video_rect.y()) / max(1, self._video_rect.height())
                self.field_clicked.emit(nx, ny)
            event.accept()
            return
        self._move_click_candidate = False
        super().mouseReleaseEvent(event)


class ReselectAwareRenderer(OptionalHexagonRenderer):
    """Render transforms in card-local space so they inherit reveal and scrolling."""

    ACTIVE_TRANSFORMS: dict[TransformKey, TransformBox] = {}

    def __init__(
        self,
        asset_cache=None,
        transforms: dict[TransformKey, TransformBox] | None = None,
    ) -> None:
        active = transforms if transforms is not None else self.ACTIVE_TRANSFORMS
        super().__init__(asset_cache or StudioAssetCache(), active)

    @staticmethod
    def _clip_global_box(box: TransformBox) -> TransformBox | None:
        x, y, width, height = box
        left = max(0.0, x)
        top = max(0.0, y)
        right = min(1.0, x + width)
        bottom = min(1.0, y + height)
        if right <= left or bottom <= top:
            return None
        return left, top, right - left, bottom - top

    def _card_placement(self, cards, output_time: float, settings, card_index: int):
        if not cards or not (0 <= card_index < len(cards)):
            return None
        model_time = settings.model_time(output_time, len(cards))
        if model_time >= settings.auto_duration(len(cards)):
            return None
        visible = settings.effective_visible_cards()
        placement = next(
            (
                item
                for item in self._placements(
                    len(cards), model_time, visible, 1.0, settings.hexagons_bounce
                )
                if item[0] == card_index
            ),
            None,
        )
        if placement is None or placement[2] < 0.08:
            return None
        return placement[1], 1.0 / visible, placement[2]

    def local_to_global(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        local_box: TransformBox,
    ) -> TransformBox | None:
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = local_box
        y_offset = (1.0 - alpha) * 0.018
        return card_x + x * card_width, y_offset + y, width * card_width, height

    def global_to_local(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        global_box: TransformBox,
    ) -> TransformBox | None:
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = _clamp_box(global_box)
        y_offset = (1.0 - alpha) * 0.018
        return (
            (x - card_x) / max(0.000001, card_width),
            y - y_offset,
            width / max(0.000001, card_width),
            height,
        )

    @staticmethod
    def _local_pixel_box(region: TransformBox, size: tuple[int, int]) -> tuple[int, int, int, int]:
        width, height = size
        x, y, region_width, region_height = region
        left = max(0, min(width - 1, round(x * width)))
        top = max(0, min(height - 1, round(y * height)))
        right = max(left + 1, min(width, round((x + region_width) * width)))
        bottom = max(top + 1, min(height, round((y + region_height) * height)))
        return left, top, right, bottom

    @staticmethod
    def _composite_clipped(canvas: Image.Image, layer: Image.Image, left: int, top: int) -> None:
        right = left + layer.width
        bottom = top + layer.height
        visible_left = max(0, left)
        visible_top = max(0, top)
        visible_right = min(canvas.width, right)
        visible_bottom = min(canvas.height, bottom)
        if visible_right <= visible_left or visible_bottom <= visible_top:
            return
        crop = (
            visible_left - left,
            visible_top - top,
            visible_right - left,
            visible_bottom - top,
        )
        canvas.alpha_composite(layer.crop(crop), (visible_left, visible_top))

    def render(self, cards, output_time: float, settings, size=None):
        self._studio_settings = settings
        width, height = size or (settings.width, settings.height)
        frame = Image.new("RGBA", (width, height), BACKGROUND + (255,))
        if not cards:
            return frame.convert("RGB")

        model_time = settings.model_time(output_time, len(cards))
        automatic_duration = settings.auto_duration(len(cards))
        if model_time >= automatic_duration:
            return frame.convert("RGB")

        visible_cards = settings.effective_visible_cards()
        card_width = width / visible_cards
        rendered_card_width = max(1, round(card_width))
        placements = self._placements(
            len(cards), model_time, visible_cards, width, settings.hexagons_bounce
        )

        for card_index, card_x, alpha, badge_scale in placements:
            active = [
                (role, box)
                for (index, role), box in self.transforms.items()
                if index == card_index
                and (self._show_hexagons or role not in {"badge_primary", "badge_secondary"})
                and self._field_box(settings.model_id, role) is not None
            ]
            y_offset = round((1.0 - alpha) * height * 0.018)

            if not active:
                card_layer = super()._render_card(
                    cards[card_index],
                    rendered_card_width,
                    height,
                    badge_scale,
                    alpha,
                    settings.model_id,
                )
                self._composite_clipped(frame, card_layer, round(card_x), y_offset)
                continue

            pristine = super()._render_card(
                cards[card_index],
                rendered_card_width,
                height,
                badge_scale,
                1.0,
                settings.model_id,
            ).convert("RGBA")
            blank_card = deepcopy(cards[card_index])
            for role, _target in active:
                self._blank_role(blank_card, role)
            blank_full = super()._render_card(
                blank_card,
                rendered_card_width,
                height,
                badge_scale,
                1.0,
                settings.model_id,
            ).convert("RGBA")
            blank_visible = blank_full.copy()
            if alpha < 0.999:
                channel = blank_visible.getchannel("A").point(lambda value: round(value * alpha))
                blank_visible.putalpha(channel)
            self._composite_clipped(frame, blank_visible, round(card_x), y_offset)

            for role, local_target in active:
                source_region = self._field_box(settings.model_id, role)
                if source_region is None:
                    continue
                source_box = self._local_pixel_box(source_region, pristine.size)
                foreground = pristine.crop(source_box)
                background = blank_full.crop(source_box)
                difference = ImageChops.difference(foreground, background).convert("L")
                mask = difference.point(lambda value: 255 if value > 8 else 0).filter(
                    ImageFilter.GaussianBlur(0.55)
                )
                if mask.getbbox() is None:
                    continue
                if alpha < 0.999:
                    mask = mask.point(lambda value: round(value * alpha))
                foreground.putalpha(mask)

                local_x, local_y, local_width, local_height = local_target
                target_size = (
                    max(1, round(local_width * card_width)),
                    max(1, round(local_height * height)),
                )
                foreground = foreground.resize(target_size, Image.Resampling.LANCZOS)
                self._composite_clipped(
                    frame,
                    foreground,
                    round(card_x + local_x * card_width),
                    round(y_offset + local_y * height),
                )

        fade_start = automatic_duration - REFERENCE_FADE_SECONDS
        result = frame.convert("RGB")
        if model_time > fade_start:
            fade = _smoothstep((model_time - fade_start) / REFERENCE_FADE_SECONDS)
            overlay = Image.new("RGB", result.size, BACKGROUND)
            result = Image.blend(result, overlay, fade)
        return result

    def _transformed_hit(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        self._studio_settings = settings
        for (card_index, role), local_box in reversed(list(self.transforms.items())):
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            global_box = self.local_to_global(
                cards, output_time, settings, card_index, local_box
            )
            if global_box is None:
                continue
            x, y, width, height = global_box
            if x <= normalized_x <= x + width and y <= normalized_y <= y + height:
                return card_index, role
        return None

    def hit_test(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        transformed = self._transformed_hit(
            cards, output_time, settings, normalized_x, normalized_y
        )
        if transformed is not None:
            return transformed
        hit = super().hit_test(
            cards, output_time, settings, normalized_x, normalized_y
        )
        if hit is not None and hit in self.transforms:
            return None
        return hit

    def editor_region(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
    ):
        self._studio_settings = settings
        local_box = self.transforms.get((card_index, role))
        if local_box is not None:
            global_box = self.local_to_global(
                cards, output_time, settings, card_index, local_box
            )
            return self._clip_global_box(global_box) if global_box is not None else None
        return super().editor_region(
            cards, output_time, settings, card_index, role
        )


# Preview and MP4 export use the same card-relative transform renderer.
exporter_module.TimelineRenderer = ReselectAwareRenderer


class ReselectFixedMainWindow(DeselectFixedMainWindow):
    """Final interaction window with card-relative transforms and transformed-field editing."""

    transform_space = "card_relative_v1"

    def _replace_preview_widget(self) -> None:
        old = self.preview
        replacement = ClickableTransformPreviewWidget()
        replacement.field_clicked.connect(self._preview_field_clicked)
        replacement.inline_committed.connect(self._commit_direct_edit)
        replacement.inline_canceled.connect(self.update_preview)
        replacement.transform_requested.connect(self._transform_requested)
        replacement.transform_changed.connect(self._transform_changed)
        replacement.selection_cleared.connect(self._selection_was_cleared)
        index = self.preview_layout.indexOf(old)
        self.preview_layout.replaceWidget(old, replacement)
        old.setParent(None)
        old.deleteLater()
        self.preview = replacement

    def _new_renderer(self) -> ReselectAwareRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return ReselectAwareRenderer(StudioAssetCache(), self.transform_overrides)

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transforms follow their cards · click a selected object to edit or replace it"
        )
        self.update_preview()

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
            self.preview.clear_transform()
            self.statusBar().showMessage("No text or image object is visible at that point", 3000)
            return

        card_index, role = hit
        current = self.renderer.editor_region(
            cards, self.position_seconds, settings, card_index, role
        )
        if current is None:
            return

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
                "Drag inside to move · drag a corner to resize · click without dragging to edit",
                6500,
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

    def _transform_changed(self, card_index: int, role: str, box: object) -> None:
        if not (isinstance(box, tuple) and len(box) == 4):
            return
        settings = self.project_settings()
        cards = self.cards()
        local = self.renderer.global_to_local(
            cards,
            self.position_seconds,
            settings,
            card_index,
            tuple(float(value) for value in box),
        )
        if local is None:
            self.statusBar().showMessage(
                "That card is not visible, so the transform was not changed.", 3500
            )
            return
        self.transform_overrides[(card_index, role)] = local
        self.renderer = self._new_renderer()
        self.update_preview()
        current = self.renderer.editor_region(
            cards, self.position_seconds, settings, card_index, role
        )
        if current is not None:
            self.preview.begin_transform(card_index, role, current)

    def _normalize_loaded_transforms(self, transforms, transform_space: str):
        if transform_space == self.transform_space:
            return transforms

        # 0.4.5 stored monitor-space boxes. Convert them at the same fully visible
        # editing position CTS normally uses for each card, then save them in local space.
        cards = self.cards()
        settings = self.project_settings()
        converter = ReselectAwareRenderer(StudioAssetCache(), {})
        converted = {}
        for (card_index, role), global_box in transforms.items():
            if not (0 <= card_index < len(cards)):
                continue
            editing_time = self._editing_time_for_card(card_index)
            local = converter.global_to_local(
                cards, editing_time, settings, card_index, global_box
            )
            if local is not None:
                converted[(card_index, role)] = local
        return converted
