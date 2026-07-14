from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter
from PySide6.QtGui import QCursor
from PySide6.QtWidgets import QMenu

from . import exporter as exporter_module
from .data import CardData
from .direct_transform import TransformBox, _clamp_box
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .reselect_fix import ReselectFixedMainWindow
from .studio_ui import StudioAssetCache


class CardRelativeTransformRenderer(OptionalHexagonRenderer):
    """Keep transformed objects attached to their card while the timeline moves."""

    def _card_screen_frame(self, cards, output_time: float, settings, card_index: int):
        if not cards or not (0 <= card_index < len(cards)):
            return None
        model_time = settings.model_time(output_time, len(cards))
        if model_time >= settings.auto_duration(len(cards)):
            return None
        visible = settings.effective_visible_cards()
        placements = self._placements(
            len(cards),
            model_time,
            visible,
            1.0,
            settings.hexagons_bounce,
        )
        placement = next((item for item in placements if item[0] == card_index), None)
        if placement is None or placement[2] < 0.08:
            return None
        return placement[1], 0.0, 1.0 / visible, 1.0

    def local_to_screen(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        frame = self._card_screen_frame(cards, output_time, settings, card_index)
        if frame is None:
            return None
        card_x, _card_y, card_width, _card_height = frame
        local_x, local_y, local_width, local_height = _clamp_box(box)
        return (
            card_x + local_x * card_width,
            local_y,
            local_width * card_width,
            local_height,
        )

    def screen_to_local(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        frame = self._card_screen_frame(cards, output_time, settings, card_index)
        if frame is None:
            return None
        card_x, _card_y, card_width, _card_height = frame
        screen_x, screen_y, screen_width, screen_height = box
        return _clamp_box(
            (
                (screen_x - card_x) / max(1e-9, card_width),
                screen_y,
                screen_width / max(1e-9, card_width),
                screen_height,
            )
        )

    def _original_editor_region(self, cards, output_time: float, settings, card_index: int, role: str):
        previous = self._applying_transforms
        self._applying_transforms = True
        try:
            return super().editor_region(cards, output_time, settings, card_index, role)
        finally:
            self._applying_transforms = previous

    def editor_region(self, cards, output_time: float, settings, card_index: int, role: str):
        if not self._applying_transforms:
            local = self.transforms.get((card_index, role))
            if local is not None:
                current = self.local_to_screen(cards, output_time, settings, card_index, local)
                if current is not None:
                    return current
        return self._original_editor_region(cards, output_time, settings, card_index, role)

    def hit_test(self, cards, output_time: float, settings, normalized_x: float, normalized_y: float):
        for (card_index, role), local in reversed(list(self.transforms.items())):
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            current = self.local_to_screen(cards, output_time, settings, card_index, local)
            if current is None:
                continue
            x, y, width, height = current
            if x <= normalized_x <= x + width and y <= normalized_y <= y + height:
                return card_index, role
        return OptionalHexagonRenderer.hit_test(
            self,
            cards,
            output_time,
            settings,
            normalized_x,
            normalized_y,
        )

    def _base_render(self, cards, output_time: float, settings, size=None):
        previous = self._applying_transforms
        self._applying_transforms = True
        try:
            return super().render(cards, output_time, settings, size)
        finally:
            self._applying_transforms = previous

    @staticmethod
    def _source_pixel_box(region, size: tuple[int, int]):
        width, height = size
        x, y, box_width, box_height = region
        left = max(0, min(width - 1, round(x * width)))
        top = max(0, min(height - 1, round(y * height)))
        right = max(left + 1, min(width, round((x + box_width) * width)))
        bottom = max(top + 1, min(height, round((y + box_height) * height)))
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
        if not self.transforms:
            return self._base_render(cards, output_time, settings, size)

        pristine = self._base_render(cards, output_time, settings, size)
        active = []
        for (card_index, role), local_target in self.transforms.items():
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            source = self._original_editor_region(cards, output_time, settings, card_index, role)
            target = self.local_to_screen(cards, output_time, settings, card_index, local_target)
            if source is not None and target is not None:
                active.append((card_index, role, source, target))
        if not active:
            return pristine

        blank_cards = [deepcopy(card) for card in cards]
        for card_index, role, _source, _target in active:
            self._blank_role(blank_cards[card_index], role)

        result = self._base_render(blank_cards, output_time, settings, size).convert("RGBA")
        pristine_rgba = pristine.convert("RGBA")
        blank_rgba = result.copy()
        frame_width, frame_height = pristine_rgba.size

        for _card_index, _role, source, target in active:
            source_box = self._source_pixel_box(source, pristine_rgba.size)
            foreground = pristine_rgba.crop(source_box)
            background = blank_rgba.crop(source_box)
            difference = ImageChops.difference(foreground, background).convert("L")
            alpha = difference.point(lambda value: 255 if value > 8 else 0).filter(
                ImageFilter.GaussianBlur(0.55)
            )
            foreground.putalpha(alpha)

            target_x, target_y, target_width, target_height = target
            pixel_width = max(1, round(target_width * frame_width))
            pixel_height = max(1, round(target_height * frame_height))
            foreground = foreground.resize((pixel_width, pixel_height), Image.Resampling.LANCZOS)
            self._composite_clipped(
                result,
                foreground,
                round(target_x * frame_width),
                round(target_y * frame_height),
            )

        return result.convert("RGB")


exporter_module.TimelineRenderer = CardRelativeTransformRenderer


class CardRelativeTransformMainWindow(ReselectFixedMainWindow):
    """UI that stores move/resize boxes in card-local coordinates."""

    transform_space = "card"

    def _new_renderer(self) -> CardRelativeTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return CardRelativeTransformRenderer(StudioAssetCache(), self.transform_overrides)

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
            self.statusBar().showMessage("No text or image object is visible at that point", 3000)
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
        transform = menu.addAction("Transform image" if role == "image" else "Transform text box")
        edit = menu.addAction("Replace image…" if role == "image" else "Edit text")
        reset = menu.addAction("Reset position and size")
        menu.addSeparator()
        deselect = menu.addAction("Deselect object")
        selected = menu.exec(QCursor.pos())

        if selected is transform:
            self.preview.begin_transform(card_index, role, current_screen)
            self.statusBar().showMessage(
                "Drag inside to move · drag a corner to resize · the object stays attached to its card",
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

    def _transform_changed(self, card_index: int, role: str, box: object) -> None:
        if not (isinstance(box, tuple) and len(box) == 4):
            return
        settings = self.project_settings()
        cards = self.cards()
        screen_box = tuple(float(value) for value in box)
        local_box = self.renderer.screen_to_local(
            cards,
            self.position_seconds,
            settings,
            card_index,
            screen_box,
        )
        if local_box is None:
            return
        self.transform_overrides[(card_index, role)] = local_box
        self.renderer = self._new_renderer()
        self.update_preview()
        current_screen = self.renderer.local_to_screen(
            cards,
            self.position_seconds,
            settings,
            card_index,
            local_box,
        )
        if current_screen is not None:
            self.preview.begin_transform(card_index, role, current_screen)

    def open_project(self) -> None:
        super().open_project()
        loaded_space = getattr(self, "_loaded_transform_space", "screen")
        if loaded_space == "card" or not self.transform_overrides:
            self.transform_space = "card"
            return

        # Migrate 0.4.5 projects whose boxes were stored in Program Monitor space.
        cards = self.cards()
        settings = self.project_settings()
        migrated = {}
        renderer = self._new_renderer()
        for (card_index, role), screen_box in self.transform_overrides.items():
            edit_time = self._editing_time_for_card(card_index)
            local_box = renderer.screen_to_local(
                cards,
                edit_time,
                settings,
                card_index,
                screen_box,
            )
            if local_box is not None:
                migrated[(card_index, role)] = local_box
        self.transform_overrides.clear()
        self.transform_overrides.update(migrated)
        self.transform_space = "card"
        self.renderer = self._new_renderer()
        self.update_preview()
        self.statusBar().showMessage(
            "Migrated older transforms so objects stay attached to their cards during playback",
            6000,
        )

    def __init__(self) -> None:
        super().__init__()
        self.transform_space = "card"
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transformed objects stay fixed inside their cards during playback"
        )
        self.update_preview()
