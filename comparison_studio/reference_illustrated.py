from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter, ImageOps

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeMainWindow, CardRelativeRenderer
from .data import (
    MODEL_ILLUSTRATED,
    REFERENCE_REVEAL_SECONDS,
    REFERENCE_SCROLL_SECONDS,
)
from .exact_badge import clamp, render_reference_badge_layer
from .interaction_runtime import RuntimeTransformRenderer
from .renderer import (
    CARD_BODY,
    DIVIDER,
    TITLE_BACKGROUND,
    _draw_text_box,
    _smoothstep,
    date_lines,
)
from .reselect_fix import ReselectAwareRenderer
from .studio_ui import StudioAssetCache


BADGE_DELAY_SECONDS = 0.55
BADGE_ANIMATION_SECONDS = 1.60


class ReferenceIllustratedRenderer(CardRelativeRenderer):
    """Render Illustrated Cards using the tall reference-video composition.

    Artwork remains the image subcard owned by the parent card. The title and optional
    description are overlays, so changing their layout does not break image transforms.
    The badge animation is shared with Android and follows the supplied video's first
    five seconds frame for frame.
    """

    @staticmethod
    def _field_box(model_id: str, role: str):
        if model_id == MODEL_ILLUSTRATED:
            return {
                "badge_primary": (0.164, 0.009, 0.726, 0.185),
                "badge_secondary": (0.164, 0.194, 0.726, 0.165),
                "title": (0.025, 0.628, 0.95, 0.098),
                "description": (0.035, 0.742, 0.93, 0.235),
                # Keep the established full artwork owner frame so existing per-card
                # image transforms continue to load exactly where users placed them.
                "image": (0.01, 0.01, 0.98, 0.87),
            }.get(role)
        return CardRelativeRenderer._field_box(model_id, role)

    @staticmethod
    def _field_at(model_id: str, local_x: float, local_y: float) -> str | None:
        if model_id == MODEL_ILLUSTRATED:
            if local_y >= 0.73:
                return "description"
            if local_y >= 0.625:
                return "title"
            if 0.13 <= local_x <= 0.91 and local_y <= 0.36:
                return "badge_primary" if local_y <= 0.19 else "badge_secondary"
            return "image"
        return CardRelativeRenderer._field_at(model_id, local_x, local_y)

    def _placements(
        self,
        card_count: int,
        model_time: float,
        visible_cards: int,
        width: float,
        hexagons_bounce: bool = True,
    ) -> list[tuple[int, float, float, float]]:
        """Return the normal card placement plus the raw exact badge phase.

        The fourth tuple value used to be badge opacity. For this renderer it is the
        0..1 source-animation phase consumed by ``render_reference_badge_layer``.
        """
        card_width = width / visible_cards
        initial_count = min(card_count, visible_cards)
        intro_duration = initial_count * REFERENCE_REVEAL_SECONDS
        placements: list[tuple[int, float, float, float]] = []

        if model_time < intro_duration:
            for index in range(initial_count):
                local = model_time - index * REFERENCE_REVEAL_SECONDS
                if local < 0:
                    continue
                card_alpha = _smoothstep(local / 0.62)
                badge_time = local - BADGE_DELAY_SECONDS
                badge_phase = (
                    clamp(badge_time / BADGE_ANIMATION_SECONDS)
                    if badge_time > 0
                    else 0.0
                )
                placements.append((index, index * card_width, card_alpha, badge_phase))
            return placements

        scroll_elapsed = max(0.0, model_time - intro_duration)
        maximum_shift = max(0, card_count - visible_cards)
        raw_cycle = min(float(maximum_shift), scroll_elapsed / REFERENCE_SCROLL_SECONDS)
        completed_cycles = min(maximum_shift, int(raw_cycle))
        cycle_progress = raw_cycle - completed_cycles

        slide_portion = 0.78
        if completed_cycles >= maximum_shift:
            shift_cards = float(maximum_shift)
        elif cycle_progress < slide_portion:
            shift_cards = completed_cycles + _smoothstep(cycle_progress / slide_portion)
        else:
            shift_cards = completed_cycles + 1.0
        shift = min(float(maximum_shift), shift_cards) * card_width

        for index in range(card_count):
            x = index * card_width - shift
            if x >= width or x + card_width <= 0:
                continue
            if index < initial_count:
                badge_phase = 1.0
            else:
                badge_start = intro_duration + (index - initial_count + 1) * REFERENCE_SCROLL_SECONDS
                badge_time = model_time - badge_start
                badge_phase = (
                    clamp(badge_time / BADGE_ANIMATION_SECONDS)
                    if badge_time > 0
                    else 0.0
                )
            placements.append((index, x, 1.0, badge_phase))
        return placements

    @staticmethod
    def _fit_artwork(source: Image.Image, size: tuple[int, int]) -> Image.Image:
        """Contain transparent icons while keeping opaque artwork in cover mode."""
        target_width = max(1, int(size[0]))
        target_height = max(1, int(size[1]))
        rgba = source.convert("RGBA")
        alpha_min, _alpha_max = rgba.getchannel("A").getextrema()

        if alpha_min >= 250:
            return ImageOps.fit(
                rgba,
                (target_width, target_height),
                Image.Resampling.LANCZOS,
                centering=(0.5, 0.5),
            )

        contained = ImageOps.contain(
            rgba,
            (target_width, target_height),
            Image.Resampling.LANCZOS,
        )
        canvas = Image.new("RGBA", (target_width, target_height), (0, 0, 0, 0))
        canvas.alpha_composite(
            contained,
            (
                (target_width - contained.width) // 2,
                (target_height - contained.height) // 2,
            ),
        )
        return canvas

    def _gold_outline_badge(self, badge: Image.Image) -> Image.Image:
        """Retained for legacy models; the exact illustrated badge draws its own edge."""
        result = badge.copy()
        draw = ImageDraw.Draw(result)
        total_width, total_height = result.size
        padding_x = max(2, round(total_width * 0.068))
        padding_y = max(2, round(total_height * 0.068))
        x0, y0 = padding_x, padding_y
        x1, y1 = total_width - padding_x, total_height - padding_y
        badge_height = max(1, y1 - y0)
        points = [
            ((x0 + x1) // 2, y0),
            (x1, y0 + round(badge_height * 0.20)),
            (x1, y0 + round(badge_height * 0.78)),
            ((x0 + x1) // 2, y1),
            (x0, y0 + round(badge_height * 0.78)),
            (x0, y0 + round(badge_height * 0.20)),
        ]
        draw.line(
            points + [points[0]],
            fill=(235, 178, 87, 255),
            width=max(2, round(total_width * 0.007)),
            joint="curve",
        )
        return result

    def _render_reference_card(
        self,
        card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        """Collapse Reference Detail's empty description gap into a larger image area."""
        if card.description.strip():
            return super()._render_reference_card(card, width, height, badge_scale)

        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        top_height = round(height * 0.44)
        title_height = round(height * 0.098)
        title_top = top_height
        body_top = title_top + title_height
        divider_width = max(2, round(width * 0.008))

        draw.rectangle((0, title_top, width, title_top + title_height), fill=TITLE_BACKGROUND + (255,))
        draw.rectangle((0, body_top, width, height), fill=CARD_BODY + (255,))
        draw.rectangle((0, title_top, divider_width, height), fill=DIVIDER + (255,))
        draw.rectangle((width - divider_width, title_top, width, height), fill=DIVIDER + (255,))
        draw.rectangle(
            (0, title_top, width, title_top + max(2, divider_width // 2)),
            fill=DIVIDER + (255,),
        )
        draw.rectangle(
            (0, body_top, width, body_top + max(2, divider_width // 2)),
            fill=DIVIDER + (255,),
        )

        title_padding = round(width * 0.045)
        _draw_text_box(
            draw,
            card.title,
            (title_padding, title_top + 5, width - title_padding, body_top - 5),
            (15, 15, 17, 255),
            maximum_size=round(height * 0.047),
            minimum_size=round(height * 0.025),
            max_lines=2,
            bold=True,
        )

        image_margin = round(width * 0.085)
        image_top = body_top + max(6, round((height - body_top) * 0.025))
        image_box = (
            image_margin,
            image_top,
            width - image_margin,
            height - max(5, divider_width),
        )
        source_image = self.assets.load(card.image)
        if source_image is not None:
            fitted = self._fit_artwork(
                source_image,
                (image_box[2] - image_box[0], image_box[3] - image_box[1]),
            )
            layer.alpha_composite(fitted, (image_box[0], image_box[1]))
            draw.rectangle(
                image_box,
                outline=(24, 25, 23, 255),
                width=max(2, divider_width // 2),
            )

        primary, secondary = card.uploaded, card.badge_label
        if primary and not secondary:
            primary, secondary = date_lines(primary)
        badge = self._render_badge(primary, secondary, width, top_height, badge_scale)
        badge_x = (width - badge.width) // 2
        badge_y = max(4, round((top_height - badge.height) * 0.52))
        layer.alpha_composite(badge, (badge_x, badge_y))
        return layer

    def _render_illustrated_card(
        self,
        card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)

        divider = max(2, round(width * 0.008))
        has_description = bool(card.description.strip())
        if has_description:
            title_top = round(height * 0.628)
            title_bottom = round(height * 0.728)
            description_top = title_bottom
            artwork_bottom = round(height * 0.88)
        else:
            title_top = round(height * 0.88)
            title_bottom = height
            description_top = height
            artwork_bottom = title_top

        source = self.assets.load(card.image)
        background_id = getattr(self._studio_settings, "illustrated_background", "beach")
        self._draw_background(
            draw,
            (divider, 0, width - divider, artwork_bottom),
            background_id,
        )
        if source is not None:
            fitted = self._fit_artwork(
                source,
                (max(1, width - divider * 2), max(1, artwork_bottom)),
            )
            layer.alpha_composite(fitted, (divider, 0))

        shadow_height = max(8, round(height * 0.018))
        shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.rectangle(
            (divider, title_top - shadow_height // 2, width - divider, title_top + 2),
            fill=(0, 0, 0, 105),
        )
        shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, round(height * 0.006))))
        layer.alpha_composite(shadow)

        draw = ImageDraw.Draw(layer)
        draw.rectangle((0, title_top, width, title_bottom), fill=(247, 246, 242, 255))
        accent_height = max(2, round(height * 0.007))
        if has_description:
            draw.rectangle((0, description_top, width, height), fill=(22, 22, 22, 255))
            draw.rectangle(
                (0, title_bottom - accent_height, width, title_bottom),
                fill=(188, 99, 0, 255),
            )
        draw.rectangle((0, 0, divider, height), fill=(18, 18, 18, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(18, 18, 18, 255))

        title_padding = round(width * 0.028)
        title_text_bottom = title_bottom - (accent_height if has_description else 0) - 3
        _draw_text_box(
            draw,
            card.title,
            (title_padding, title_top + 3, width - title_padding, title_text_bottom),
            (20, 20, 20, 255),
            maximum_size=round(height * 0.049),
            minimum_size=max(8, round(height * 0.021)),
            max_lines=2,
            bold=True,
        )

        if has_description:
            description_padding = round(width * 0.055)
            _draw_text_box(
                draw,
                card.description,
                (
                    description_padding,
                    description_top + round(height * 0.018),
                    width - description_padding,
                    height - round(height * 0.018),
                ),
                (238, 238, 238, 255),
                maximum_size=round(height * 0.032),
                minimum_size=max(7, round(height * 0.017)),
                max_lines=4,
                bold=False,
            )

        phase = clamp(getattr(self, "_active_badge_opacity", 1.0))
        layer.alpha_composite(
            render_reference_badge_layer(
                width,
                height,
                card.uploaded,
                card.badge_label,
                phase,
            )
        )
        return layer


# Preview and desktop MP4 export must resolve the same renderer.
exporter_module.TimelineRenderer = ReferenceIllustratedRenderer


class ReferenceIllustratedMainWindow(CardRelativeMainWindow):
    def _new_renderer(self) -> ReferenceIllustratedRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        CardRelativeRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReferenceIllustratedRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return ReferenceIllustratedRenderer(StudioAssetCache(), self.transform_overrides)
