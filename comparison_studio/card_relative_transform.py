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
    """Render every transformed field inside its card before timeline placement.

    A transformed object is not a Program Monitor overlay. It belongs to the card.
    The renderer therefore builds one complete card-local group, applies the card's
    reveal opacity to the whole group, and only then places that group at the card's
    animated timeline position.
    """

    ACTIVE_TRANSFORMS: dict[TransformKey, TransformBox] = {}

    @staticmethod
    def _group_bounds(active: list[tuple[str, TransformBox]]) -> tuple[float, float, float, float]:
        """Return card-local bounds large enough to preserve transformed overflow."""
        minimum_x = 0.0
        minimum_y = 0.0
        maximum_x = 1.0
        maximum_y = 1.0
        for _role, (x, y, width, height) in active:
            minimum_x = min(minimum_x, x)
            minimum_y = min(minimum_y, y)
            maximum_x = max(maximum_x, x + width)
            maximum_y = max(maximum_y, y + height)
        return minimum_x, minimum_y, maximum_x, maximum_y

    def _render_transformed_card_group(
        self,
        card,
        active: list[tuple[str, TransformBox]],
        card_width: int,
        card_height: int,
        badge_scale: float,
        alpha: float,
        model_id: str,
    ) -> tuple[Image.Image, int, int]:
        """Build the card and its transformed fields as one animated RGBA group."""
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
        blank = super()._render_card(
            blank_card,
            card_width,
            card_height,
            badge_scale,
            1.0,
            model_id,
        ).convert("RGBA")

        minimum_x, minimum_y, maximum_x, maximum_y = self._group_bounds(active)
        group_width = max(1, round((maximum_x - minimum_x) * card_width))
        group_height = max(1, round((maximum_y - minimum_y) * card_height))
        card_left = round(-minimum_x * card_width)
        card_top = round(-minimum_y * card_height)
        group = Image.new("RGBA", (group_width, group_height), (0, 0, 0, 0))
        self._composite_clipped(group, blank, card_left, card_top)

        for role, local_target in active:
            source_region = self._field_box(model_id, role)
            if source_region is None:
                continue

            source_box = self._local_pixel_box(source_region, pristine.size)
            foreground = pristine.crop(source_box)
            background = blank.crop(source_box)
            difference = ImageChops.difference(foreground, background).convert("L")
            mask = difference.point(lambda value: 255 if value > 8 else 0).filter(
                ImageFilter.GaussianBlur(0.55)
            )
            if mask.getbbox() is None:
                continue
            foreground.putalpha(mask)

            local_x, local_y, local_width, local_height = local_target
            target_size = (
                max(1, round(local_width * card_width)),
                max(1, round(local_height * card_height)),
            )
            foreground = foreground.resize(target_size, Image.Resampling.LANCZOS)
            target_left = round((local_x - minimum_x) * card_width)
            target_top = round((local_y - minimum_y) * card_height)
            self._composite_clipped(group, foreground, target_left, target_top)

        # Reveal the card and every transformed field together. Nothing in the group
        # can appear before the card or remain behind as an independent monitor layer.
        if alpha < 0.999:
            channel = group.getchannel("A").point(lambda value: round(value * alpha))
            group.putalpha(channel)

        return group, round(minimum_x * card_width), round(minimum_y * card_height)

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
                (role, box)
                for (index, role), box in self.transforms.items()
                if index == card_index
                and (self._show_hexagons or role not in {"badge_primary", "badge_secondary"})
                and self._field_box(settings.model_id, role) is not None
            ]
            y_offset = round((1.0 - alpha) * height * 0.018)

            if active:
                card_group, local_left, local_top = self._render_transformed_card_group(
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
                    round(card_x) + local_left,
                    y_offset + local_top,
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


# Export workers resolve this module global when export begins, so preview and MP4
# both use the exact same card-local renderer.
exporter_module.TimelineRenderer = CardRelativeRenderer


class CardRelativeMainWindow(ReselectFixedMainWindow):
    """CTS window whose transformed objects are inseparable from their cards."""

    def _new_renderer(self) -> CardRelativeRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        CardRelativeRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return CardRelativeRenderer(StudioAssetCache(), self.transform_overrides)

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transformed objects are rendered inside their cards and reveal with them"
        )
        self.update_preview()
