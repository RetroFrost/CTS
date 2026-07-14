from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeTransformRenderer
from .data import REFERENCE_FADE_SECONDS
from .direct_transform import TransformBox, _clamp_box
from .final_runtime import FinalMainWindow, TimelineStableTransformRenderer
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .renderer import _smoothstep
from .studio_ui import StudioAssetCache


class ScreenLockedTransformRenderer(TimelineStableTransformRenderer):
    """Render transformed fields as fixed Program Monitor overlays.

    Transform boxes are stored in normalized output-frame coordinates. The original
    field is removed from its scrolling card, then an isolated copy is composited at
    the saved screen position. Card motion therefore never changes the transformed
    object's position or size.
    """

    def local_to_screen(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        del cards, output_time, settings, card_index
        return _clamp_box(box)

    def screen_to_local(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        del cards, output_time, settings, card_index
        return _clamp_box(box)

    def _card_visibility(self, cards, output_time: float, settings, card_index: int) -> float:
        if not cards or not (0 <= card_index < len(cards)):
            return 0.0
        model_time = settings.model_time(output_time, len(cards))
        automatic_duration = settings.auto_duration(len(cards))
        if model_time >= automatic_duration:
            return 0.0

        visible = settings.effective_visible_cards()
        placements = self._placements(
            len(cards),
            model_time,
            visible,
            1.0,
            settings.hexagons_bounce,
        )
        placement = next((item for item in placements if item[0] == card_index), None)
        if placement is None:
            return 0.0

        alpha = float(placement[2])
        fade_start = automatic_duration - REFERENCE_FADE_SECONDS
        if model_time > fade_start:
            alpha *= 1.0 - _smoothstep((model_time - fade_start) / REFERENCE_FADE_SECONDS)
        return max(0.0, min(1.0, alpha))

    def editor_region(self, cards, output_time: float, settings, card_index: int, role: str):
        transformed = self.transforms.get((card_index, role))
        if transformed is not None:
            if self._card_visibility(cards, output_time, settings, card_index) < 0.08:
                return None
            return _clamp_box(transformed)
        return self._original_editor_region(cards, output_time, settings, card_index, role)

    def hit_test(self, cards, output_time: float, settings, normalized_x: float, normalized_y: float):
        for (card_index, role), raw_box in reversed(list(self.transforms.items())):
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            if self._card_visibility(cards, output_time, settings, card_index) < 0.08:
                continue
            x, y, width, height = _clamp_box(raw_box)
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

    @staticmethod
    def _pixel_box_unclipped(region: TransformBox, size: tuple[int, int]) -> tuple[int, int, int, int]:
        frame_width, frame_height = size
        x, y, width, height = region
        return (
            round(x * frame_width),
            round(y * frame_height),
            round((x + width) * frame_width),
            round((y + height) * frame_height),
        )

    def _isolated_role_layer(self, card, role: str, settings, size: tuple[int, int]) -> Image.Image | None:
        """Extract a complete role without depending on its animated screen position."""
        stable_time = 1.0
        pristine = self._base_render([card], stable_time, settings, size).convert("RGBA")
        blank_card = deepcopy(card)
        self._blank_role(blank_card, role)
        blank = self._base_render([blank_card], stable_time, settings, size).convert("RGBA")

        local = self._field_box(settings.model_id, role)
        if local is None:
            return None
        local_x, local_y, local_width, local_height = local
        card_width = 1.0 / settings.effective_visible_cards()
        source_region = (
            local_x * card_width,
            local_y,
            local_width * card_width,
            local_height,
        )
        left, top, right, bottom = self._pixel_box_unclipped(source_region, size)
        left = max(0, min(size[0] - 1, left))
        top = max(0, min(size[1] - 1, top))
        right = max(left + 1, min(size[0], right))
        bottom = max(top + 1, min(size[1], bottom))

        foreground = pristine.crop((left, top, right, bottom))
        background = blank.crop((left, top, right, bottom))
        difference = ImageChops.difference(foreground, background).convert("L")
        alpha = difference.point(lambda value: 255 if value > 8 else 0).filter(
            ImageFilter.GaussianBlur(0.55)
        )
        foreground.putalpha(alpha)
        return foreground

    @staticmethod
    def _apply_opacity(layer: Image.Image, opacity: float) -> Image.Image:
        if opacity >= 0.999:
            return layer
        adjusted = layer.copy()
        alpha = adjusted.getchannel("A").point(
            lambda value: max(0, min(255, round(value * opacity)))
        )
        adjusted.putalpha(alpha)
        return adjusted

    def render(self, cards, output_time: float, settings, size=None):
        if not self.transforms:
            return self._base_render(cards, output_time, settings, size)

        frame_size = size or (settings.width, settings.height)
        blank_cards = [deepcopy(card) for card in cards]
        active: list[tuple[int, str, TransformBox, float]] = []
        for (card_index, role), target in self.transforms.items():
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            visibility = self._card_visibility(cards, output_time, settings, card_index)
            if visibility <= 0.001:
                continue
            self._blank_role(blank_cards[card_index], role)
            active.append((card_index, role, _clamp_box(target), visibility))

        result = self._base_render(blank_cards, output_time, settings, frame_size).convert("RGBA")
        if not active:
            return result.convert("RGB")

        for card_index, role, target, visibility in active:
            layer = self._isolated_role_layer(cards[card_index], role, settings, frame_size)
            if layer is None:
                continue
            target_x, target_y, target_width, target_height = target
            pixel_width = max(1, round(target_width * frame_size[0]))
            pixel_height = max(1, round(target_height * frame_size[1]))
            layer = layer.resize((pixel_width, pixel_height), Image.Resampling.LANCZOS)
            layer = self._apply_opacity(layer, visibility)
            self._composite_clipped(
                result,
                layer,
                round(target_x * frame_size[0]),
                round(target_y * frame_size[1]),
            )
        return result.convert("RGB")


exporter_module.TimelineRenderer = ScreenLockedTransformRenderer


class ScreenLockedMainWindow(FinalMainWindow):
    """Current CTS runtime with monitor-locked transformed objects."""

    transform_space = "screen"

    def _new_renderer(self) -> ScreenLockedTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return ScreenLockedTransformRenderer(StudioAssetCache(), self.transform_overrides)

    def open_project(self) -> None:
        super().open_project()
        loaded_space = getattr(self, "_loaded_transform_space", "screen")
        if loaded_space != "card" or not self.transform_overrides:
            self.transform_space = "screen"
            return

        # Convert the short-lived card-relative hotfix format back to the intended
        # fixed Program Monitor coordinate system.
        cards = self.cards()
        settings = self.project_settings()
        converter = CardRelativeTransformRenderer(StudioAssetCache(), {})
        converted = {}
        for (card_index, role), local_box in self.transform_overrides.items():
            edit_time = self._editing_time_for_card(card_index)
            screen_box = converter.local_to_screen(
                cards,
                edit_time,
                settings,
                card_index,
                local_box,
            )
            if screen_box is not None:
                converted[(card_index, role)] = _clamp_box(screen_box)
        self.transform_overrides.clear()
        self.transform_overrides.update(converted)
        self.transform_space = "screen"
        self.renderer = self._new_renderer()
        self.update_preview()
        self.statusBar().showMessage(
            "Converted card-relative transforms into fixed Program Monitor overlays",
            5000,
        )

    def __init__(self) -> None:
        super().__init__()
        self.transform_space = "screen"
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transformed objects stay fixed on the Program Monitor during playback"
        )
        self.update_preview()
