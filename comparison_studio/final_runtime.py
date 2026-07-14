from __future__ import annotations

import math
from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter
from PySide6.QtCore import Qt

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeTransformRenderer
from .interaction_runtime import RuntimeTransformRenderer
from .studio_ui import StudioAssetCache
from . import update_system as update_system_module


# update_system uses this enum only when an update dialog is shown.
update_system_module.Qt = Qt
UpdateAwareMainWindow = update_system_module.UpdateAwareMainWindow


class TimelineStableTransformRenderer(CardRelativeTransformRenderer):
    """Card-relative transforms that preserve geometry at viewport edges."""

    def _original_editor_region(self, cards, output_time: float, settings, card_index: int, role: str):
        frame = self._card_screen_frame(cards, output_time, settings, card_index)
        local = self._field_box(settings.model_id, role)
        if frame is None or local is None:
            return None
        card_x, _card_y, card_width, _card_height = frame
        local_x, local_y, local_width, local_height = local
        return (
            card_x + local_x * card_width,
            local_y,
            local_width * card_width,
            local_height,
        )

    @staticmethod
    def _visible_source_mapping(region, frame_size: tuple[int, int]):
        frame_width, frame_height = frame_size
        x, y, width, height = region
        source_left = x * frame_width
        source_top = y * frame_height
        source_width = max(1e-6, width * frame_width)
        source_height = max(1e-6, height * frame_height)
        source_right = source_left + source_width
        source_bottom = source_top + source_height

        crop_left = max(0, math.floor(source_left))
        crop_top = max(0, math.floor(source_top))
        crop_right = min(frame_width, math.ceil(source_right))
        crop_bottom = min(frame_height, math.ceil(source_bottom))
        if crop_right <= crop_left or crop_bottom <= crop_top:
            return None

        u0 = max(0.0, min(1.0, (crop_left - source_left) / source_width))
        v0 = max(0.0, min(1.0, (crop_top - source_top) / source_height))
        u1 = max(0.0, min(1.0, (crop_right - source_left) / source_width))
        v1 = max(0.0, min(1.0, (crop_bottom - source_top) / source_height))
        return (crop_left, crop_top, crop_right, crop_bottom), (u0, v0, u1, v1)

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
            mapping = self._visible_source_mapping(source, pristine_rgba.size)
            if mapping is None:
                continue
            source_box, fractions = mapping
            u0, v0, u1, v1 = fractions
            foreground = pristine_rgba.crop(source_box)
            background = blank_rgba.crop(source_box)
            difference = ImageChops.difference(foreground, background).convert("L")
            alpha = difference.point(lambda value: 255 if value > 8 else 0).filter(
                ImageFilter.GaussianBlur(0.55)
            )
            foreground.putalpha(alpha)

            target_x, target_y, target_width, target_height = target
            full_target_left = target_x * frame_width
            full_target_top = target_y * frame_height
            full_target_width = max(1.0, target_width * frame_width)
            full_target_height = max(1.0, target_height * frame_height)

            partial_left = full_target_left + u0 * full_target_width
            partial_top = full_target_top + v0 * full_target_height
            partial_width = max(1, round((u1 - u0) * full_target_width))
            partial_height = max(1, round((v1 - v0) * full_target_height))
            foreground = foreground.resize(
                (partial_width, partial_height),
                Image.Resampling.LANCZOS,
            )
            self._composite_clipped(
                result,
                foreground,
                round(partial_left),
                round(partial_top),
            )

        return result.convert("RGB")


exporter_module.TimelineRenderer = TimelineStableTransformRenderer


class FinalMainWindow(UpdateAwareMainWindow):
    """Current CTS runtime: stable card transforms plus update checking."""

    def _new_renderer(self) -> TimelineStableTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return TimelineStableTransformRenderer(StudioAssetCache(), self.transform_overrides)

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · objects stay fixed in their cards · Help → Check for updates"
        )
        self.update_preview()
