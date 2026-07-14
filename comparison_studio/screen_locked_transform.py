from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeTransformRenderer
from .direct_transform import TransformBox, _clamp_box
from .final_runtime import FinalMainWindow, TimelineStableTransformRenderer
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .studio_ui import StudioAssetCache


class ScreenLockedTransformRenderer(TimelineStableTransformRenderer):
    """Render transformed fields as persistent Program Monitor overlays.

    Transform boxes are stored in normalized output-frame coordinates. The original
    field is removed from its scrolling card, then an isolated copy is composited at
    the saved screen position for the complete sequence. Card motion, reveal timing,
    and card visibility never change the transformed object's position, size, or
    visibility.
    """

    def __init__(self, asset_cache=None, transforms=None) -> None:
        super().__init__(asset_cache or StudioAssetCache(), transforms)
        self._isolated_layer_cache: dict[tuple[object, ...], Image.Image] = {}

    def local_to_screen(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        del cards, output_time, settings, card_index
        return _clamp_box(box)

    def screen_to_local(self, cards, output_time: float, settings, card_index: int, box: TransformBox):
        del cards, output_time, settings, card_index
        return _clamp_box(box)

    def editor_region(self, cards, output_time: float, settings, card_index: int, role: str):
        transformed = self.transforms.get((card_index, role))
        if transformed is not None:
            return _clamp_box(transformed)
        return self._original_editor_region(cards, output_time, settings, card_index, role)

    def hit_test(self, cards, output_time: float, settings, normalized_x: float, normalized_y: float):
        # Fixed overlays remain selectable even while their original card is off-screen.
        for (card_index, role), raw_box in reversed(list(self.transforms.items())):
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
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
        source_settings = deepcopy(settings)
        source_settings.custom_duration = None
        cache_key = (repr(card), role, repr(source_settings), size)
        cached = self._isolated_layer_cache.get(cache_key)
        if cached is not None:
            return cached

        stable_time = 1.0
        pristine = self._base_render([card], stable_time, source_settings, size).convert("RGBA")
        blank_card = deepcopy(card)
        self._blank_role(blank_card, role)
        blank = self._base_render([blank_card], stable_time, source_settings, size).convert("RGBA")

        local = self._field_box(source_settings.model_id, role)
        if local is None:
            return None
        local_x, local_y, local_width, local_height = local
        card_width = 1.0 / source_settings.effective_visible_cards()
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
        self._isolated_layer_cache[cache_key] = foreground
        return foreground

    def render(self, cards, output_time: float, settings, size=None):
        if not self.transforms:
            return self._base_render(cards, output_time, settings, size)

        frame_size = size or (settings.width, settings.height)
        blank_cards = [deepcopy(card) for card in cards]
        active: list[tuple[int, str, TransformBox]] = []
        for (card_index, role), target in self.transforms.items():
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue

            # Remove the moving source copy whenever it would be rendered. The fixed
            # overlay itself is always active, regardless of the card's timeline state.
            self._blank_role(blank_cards[card_index], role)
            active.append((card_index, role, _clamp_box(target)))

        result = self._base_render(blank_cards, output_time, settings, frame_size).convert("RGBA")
        if not active:
            return result.convert("RGB")

        for card_index, role, target in active:
            layer = self._isolated_role_layer(cards[card_index], role, settings, frame_size)
            if layer is None:
                continue
            target_x, target_y, target_width, target_height = target
            pixel_width = max(1, round(target_width * frame_size[0]))
            pixel_height = max(1, round(target_height * frame_size[1]))
            layer = layer.resize((pixel_width, pixel_height), Image.Resampling.LANCZOS)
            self._composite_clipped(
                result,
                layer,
                round(target_x * frame_size[0]),
                round(target_y * frame_size[1]),
            )
        return result.convert("RGB")


exporter_module.TimelineRenderer = ScreenLockedTransformRenderer


class ScreenLockedMainWindow(FinalMainWindow):
    """Current CTS runtime with persistent monitor-locked transformed objects."""

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
            "Converted card-relative transforms into persistent Program Monitor overlays",
            5000,
        )

    def __init__(self) -> None:
        super().__init__()
        self.transform_space = "screen"
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transformed objects stay visible and fixed for the complete sequence"
        )
        self.update_preview()
