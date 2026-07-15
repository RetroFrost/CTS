from __future__ import annotations

from copy import deepcopy

from PIL import Image, ImageChops, ImageFilter

from . import exporter as exporter_module
from .data import REFERENCE_FADE_SECONDS
from .direct_transform import TransformBox, TransformKey
from .interaction_runtime import RuntimeTransformRenderer
from .renderer import BACKGROUND, _smoothstep
from .reselect_fix import ReselectAwareRenderer, ReselectFixedMainWindow
from .studio_ui import StudioAssetCache


class CardRelativeRenderer(ReselectAwareRenderer):
    """Render transformed fields inside the owning card before timeline placement.

    Transform coordinates are normalized against one card, not the Program Monitor
    and not the complete card strip.  A transformed object is therefore clipped to,
    revealed with, and moved with only the card whose row owns that transform.
    """

    ACTIVE_TRANSFORMS: dict[TransformKey, TransformBox] = {}

    @staticmethod
    def _clamp_card_box(box: TransformBox) -> TransformBox:
        """Clamp a transform to the normalized 0..1 rectangle of one card."""
        x, y, width, height = (float(value) for value in box)
        width = max(0.025, min(1.0, width))
        height = max(0.025, min(1.0, height))
        x = max(0.0, min(1.0 - width, x))
        y = max(0.0, min(1.0 - height, y))
        return x, y, width, height

    def local_to_global(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        local_box: TransformBox,
    ) -> TransformBox | None:
        """Map one card-local box to the card's current animated monitor position."""
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = self._clamp_card_box(local_box)
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
        """Store a dragged monitor box relative to its one owning card."""
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, alpha = placement
        x, y, width, height = (float(value) for value in global_box)
        y_offset = (1.0 - alpha) * 0.018
        return self._clamp_card_box(
            (
                (x - card_x) / max(0.000001, card_width),
                y - y_offset,
                width / max(0.000001, card_width),
                height,
            )
        )

    def _render_transformed_card_group(
        self,
        card,
        active: list[tuple[str, TransformBox]],
        card_width: int,
        card_height: int,
        badge_scale: float,
        alpha: float,
        model_id: str,
    ) -> Image.Image:
        """Build one complete card-sized group, including all of its transforms."""
        pristine = super()._render_card(
            card,
            card_width,
            card_height,
            badge_scale,
            1.0,
            model_id,
        ).convert("RGBA")

        blank_card = deepcopy(card)
        for role, _target in active:
            self._blank_role(blank_card, role)
        group = super()._render_card(
            blank_card,
            card_width,
            card_height,
            badge_scale,
            1.0,
            model_id,
        ).convert("RGBA")

        for role, raw_target in active:
            source_region = self._field_box(model_id, role)
            if source_region is None:
                continue

            source_box = self._local_pixel_box(source_region, pristine.size)
            foreground = pristine.crop(source_box)
            background = group.crop(source_box)
            difference = ImageChops.difference(foreground, background).convert("L")
            mask = difference.point(lambda value: 255 if value > 8 else 0).filter(
                ImageFilter.GaussianBlur(0.55)
            )
            if mask.getbbox() is None:
                continue
            foreground.putalpha(mask)

            local_x, local_y, local_width, local_height = self._clamp_card_box(raw_target)
            target_size = (
                max(1, round(local_width * card_width)),
                max(1, round(local_height * card_height)),
            )
            foreground = foreground.resize(target_size, Image.Resampling.LANCZOS)
            self._composite_clipped(
                group,
                foreground,
                round(local_x * card_width),
                round(local_y * card_height),
            )

        # The reveal animation is applied once to the complete owning card.  A
        # transformed image can never appear before or move separately from it.
        if alpha < 0.999:
            channel = group.getchannel("A").point(lambda value: round(value * alpha))
            group.putalpha(channel)
        return group

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
        card_width_float = width / visible_cards
        card_width = max(1, round(card_width_float))
        placements = self._placements(
            len(cards), model_time, visible_cards, width, settings.hexagons_bounce
        )

        for card_index, card_x, alpha, badge_scale in placements:
            active = [
                (role, self._clamp_card_box(box))
                for (index, role), box in self.transforms.items()
                if index == card_index
                and (self._show_hexagons or role not in {"badge_primary", "badge_secondary"})
                and self._field_box(settings.model_id, role) is not None
            ]
            y_offset = round((1.0 - alpha) * height * 0.018)

            if active:
                card_group = self._render_transformed_card_group(
                    cards[card_index],
                    active,
                    card_width,
                    height,
                    badge_scale,
                    alpha,
                    settings.model_id,
                )
                self._composite_clipped(
                    frame,
                    card_group,
                    round(card_x),
                    y_offset,
                )
            else:
                card_layer = super()._render_card(
                    cards[card_index],
                    card_width,
                    height,
                    badge_scale,
                    alpha,
                    settings.model_id,
                )
                self._composite_clipped(frame, card_layer, round(card_x), y_offset)

        fade_start = automatic_duration - REFERENCE_FADE_SECONDS
        result = frame.convert("RGB")
        if model_time > fade_start:
            fade = _smoothstep((model_time - fade_start) / REFERENCE_FADE_SECONDS)
            result = Image.blend(result, Image.new("RGB", result.size, BACKGROUND), fade)
        return result


# Preview and MP4 export resolve the same strictly card-owned renderer.
exporter_module.TimelineRenderer = CardRelativeRenderer


class CardRelativeMainWindow(ReselectFixedMainWindow):
    """CTS window whose transforms are owned and bounded by one specific card."""

    transform_space = "card_relative_card_bounds_v2"

    def _new_renderer(self) -> CardRelativeRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        CardRelativeRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return CardRelativeRenderer(StudioAssetCache(), self.transform_overrides)

    def _normalize_loaded_transforms(self, transforms, transform_space: str):
        # card_relative_v1 already used per-card coordinates but allowed overflow.
        # Keep those coordinates and simply constrain them to their owning card.
        if transform_space in {"card_relative_v1", self.transform_space}:
            converted = transforms
        else:
            converted = super()._normalize_loaded_transforms(transforms, transform_space)
        return {
            key: CardRelativeRenderer._clamp_card_box(box)
            for key, box in converted.items()
        }

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · every transformed object is positioned inside and moves with its own card"
        )
        self.update_preview()
