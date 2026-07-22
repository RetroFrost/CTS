from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter, ImageOps

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeMainWindow, CardRelativeRenderer
from .interaction_runtime import RuntimeTransformRenderer
from .renderer import BACKGROUND, _clamp, _draw_text_box, _smoothstep
from .reselect_fix import ReselectAwareRenderer
from .shared_contract import (
    BADGE_DELAY_SECONDS,
    BADGE_FRAME,
    BADGE_SETTLE_SECONDS,
    BODY_WIPE_SECONDS,
    COLORS,
    DESCRIPTION_FRAME,
    FADE_SECONDS,
    IMAGE_FRAME,
    INTRO_TAIL_HOLD_SECONDS,
    MODEL_ID,
    REVEAL_SECONDS,
    SCROLL_SECONDS,
    TITLE_FRAME,
    VISIBLE_CARDS,
    automatic_duration,
    material_ease,
    model_time as shared_model_time,
    placement_shift,
)
from .studio_ui import StudioAssetCache


def _rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


def _content_frames(card) -> tuple[tuple[float, float, float, float], tuple[float, float, float, float] | None, tuple[float, float, float, float] | None]:
    """Collapse blank text rows and give their height to the artwork."""
    left, image_top, width, _image_height = IMAGE_FRAME
    content_bottom = DESCRIPTION_FRAME[1] + DESCRIPTION_FRAME[3]
    cursor = content_bottom
    description = None
    if str(getattr(card, "description", "")).strip():
        cursor -= DESCRIPTION_FRAME[3]
        description = (left, cursor, width, DESCRIPTION_FRAME[3])
    title = None
    if str(getattr(card, "title", "")).strip():
        cursor -= TITLE_FRAME[3]
        title = (left, cursor, width, TITLE_FRAME[3])
    image = (left, image_top, width, max(0.0, cursor - image_top))
    return image, title, description


class ReferenceIllustratedRenderer(CardRelativeRenderer):
    """Desktop implementation of the canonical Android reference timeline.

    The renderer deliberately ignores historical visual-model choices. Project data and
    transforms survive, but every project is presented with the same four-column design,
    timing curve, wipe, badge entrance, hold, and fade used by CTS Android.
    """

    @staticmethod
    def _field_box(_model_id: str, role: str):
        return {
            "badge_primary": BADGE_FRAME,
            "badge_secondary": BADGE_FRAME,
            "title": TITLE_FRAME,
            "description": DESCRIPTION_FRAME,
            "image": IMAGE_FRAME,
        }.get(role)

    @staticmethod
    def _field_at(_model_id: str, local_x: float, local_y: float) -> str | None:
        badge_x, badge_y, badge_width, badge_height = BADGE_FRAME
        if (
            badge_x <= local_x <= badge_x + badge_width
            and badge_y <= local_y <= badge_y + badge_height
        ):
            split = badge_y + badge_height * 0.58
            return "badge_primary" if local_y <= split else "badge_secondary"
        if local_y < TITLE_FRAME[1]:
            return "image"
        if local_y < DESCRIPTION_FRAME[1]:
            return "title"
        return "description"

    @staticmethod
    def _fit_artwork(source: Image.Image, size: tuple[int, int]) -> Image.Image:
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
            ((target_width - contained.width) // 2, (target_height - contained.height) // 2),
        )
        return canvas

    def _placements(
        self,
        card_count: int,
        timeline_time: float,
        _visible_cards: int,
        width: float,
        _hexagons_bounce: bool = True,
    ) -> list[tuple[int, float, float, float]]:
        """Return Android-compatible x, body-wipe, and badge-settle values."""
        if card_count <= 0:
            return []
        card_width = width / VISIBLE_CARDS
        initial_count = min(card_count, VISIBLE_CARDS)
        scroll_start = initial_count * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        placements: list[tuple[int, float, float, float]] = []

        if timeline_time < scroll_start:
            for index in range(initial_count):
                local_time = timeline_time - index * REVEAL_SECONDS
                if local_time < 0.0:
                    continue
                badge_time = local_time - BADGE_DELAY_SECONDS
                placements.append(
                    (
                        index,
                        index * card_width,
                        material_ease(local_time / BODY_WIPE_SECONDS),
                        material_ease(badge_time / BADGE_SETTLE_SECONDS)
                        if badge_time >= 0.0
                        else 0.0,
                    )
                )
            return placements

        maximum_shift = max(0, card_count - VISIBLE_CARDS)
        eased_shift = placement_shift(timeline_time - scroll_start, maximum_shift)
        for index in range(card_count):
            x_in_cards = index - eased_shift
            if x_in_cards >= VISIBLE_CARDS or x_in_cards + 1.0 <= 0.0:
                continue
            if index < initial_count:
                badge_start = index * REVEAL_SECONDS + BADGE_DELAY_SECONDS
            else:
                badge_start = scroll_start + (index - initial_count + 1) * SCROLL_SECONDS
            badge_time = timeline_time - badge_start
            placements.append(
                (
                    index,
                    x_in_cards * card_width,
                    1.0,
                    material_ease(badge_time / BADGE_SETTLE_SECONDS)
                    if badge_time >= 0.0
                    else 0.0,
                )
            )
        return placements

    @staticmethod
    def _apply_wipe(layer: Image.Image, reveal: float) -> Image.Image:
        reveal = _clamp(reveal)
        if reveal >= 0.999:
            return layer
        width = max(0, min(layer.width, round(layer.width * reveal)))
        result = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        if width:
            result.alpha_composite(layer.crop((0, 0, width, layer.height)), (0, 0))
        return result

    def render(self, cards, output_time: float, settings, size=None):
        self._studio_settings = settings
        width, height = size or (settings.width, settings.height)
        frame = Image.new("RGBA", (width, height), _rgb(COLORS["background"]) + (255,))
        if not cards:
            return frame.convert("RGB")

        custom_duration = getattr(settings, "custom_duration", None)
        timeline_time = shared_model_time(len(cards), output_time, custom_duration)
        duration = automatic_duration(len(cards))
        if timeline_time >= duration:
            return frame.convert("RGB")

        card_width_float = width / VISIBLE_CARDS
        card_width = max(1, round(card_width_float))
        placements = self._placements(len(cards), timeline_time, VISIBLE_CARDS, width, True)

        for card_index, card_x, body_reveal, badge_settle in placements:
            active = [
                (role, self._clamp_unit_box(box))
                for (index, role), box in self.transforms.items()
                if index == card_index and self._field_box(MODEL_ID, role) is not None
            ]
            if active:
                card_group = super()._render_transformed_card_group(
                    cards[card_index],
                    active,
                    card_width,
                    height,
                    badge_settle,
                    1.0,
                    MODEL_ID,
                )
                card_group = self._apply_wipe(card_group, body_reveal)
                self._composite_clipped(frame, card_group, round(card_x), 0)
            else:
                card_layer = super()._render_card(
                    cards[card_index],
                    card_width,
                    height,
                    badge_settle,
                    1.0,
                    MODEL_ID,
                )
                card_layer = self._apply_wipe(card_layer, body_reveal)
                self._composite_clipped(frame, card_layer, round(card_x), 0)

        result = frame.convert("RGB")
        fade_start = duration - FADE_SECONDS
        if timeline_time > fade_start:
            fade = _smoothstep((timeline_time - fade_start) / FADE_SECONDS)
            result = Image.blend(
                result,
                Image.new("RGB", result.size, _rgb(COLORS["background"])),
                fade,
            )
        return result

    def _card_box_to_global(self, cards, output_time, settings, card_index, card_box):
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, _reveal = placement
        x, y, width, height = card_box
        return card_x + x * card_width, y, width * card_width, height

    def _global_box_to_card(self, cards, output_time, settings, card_index, global_box):
        placement = self._card_placement(cards, output_time, settings, card_index)
        if placement is None:
            return None
        card_x, card_width, _reveal = placement
        x, y, width, height = (float(value) for value in global_box)
        return (
            (x - card_x) / max(0.000001, card_width),
            y,
            width / max(0.000001, card_width),
            height,
        )

    def _render_reference_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        return self._render_illustrated_card(card, width, height, badge_scale)

    def _render_classic_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        return self._render_illustrated_card(card, width, height, badge_scale)

    def _render_illustrated_card(
        self,
        card,
        width: int,
        height: int,
        _badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.008))

        image_frame, title_frame, description_frame = _content_frames(card)
        image_left = round(width * image_frame[0])
        image_top = round(height * image_frame[1])
        image_right = round(width * (image_frame[0] + image_frame[2]))
        image_bottom = round(height * (image_frame[1] + image_frame[3]))
        image_box = (image_left, image_top, image_right, image_bottom)

        image_top_color = _rgb(COLORS["image_top"])
        image_bottom_color = _rgb(COLORS["image_bottom"])
        image_height = max(1, image_bottom - image_top)
        for row in range(image_height):
            progress = row / max(1, image_height - 1)
            if progress <= 0.72:
                color = image_top_color
            else:
                local = (progress - 0.72) / 0.28
                color = tuple(
                    round(start + (end - start) * local)
                    for start, end in zip(image_top_color, image_bottom_color)
                )
            draw.line((image_left, image_top + row, image_right, image_top + row), fill=color + (255,))

        source = self.assets.load(card.image)
        if source is not None:
            fitted = self._fit_artwork(
                source,
                (max(1, image_right - image_left), max(1, image_bottom - image_top)),
            )
            layer.alpha_composite(fitted, (image_left, image_top))

        title_box = None
        if title_frame is not None:
            title_left = round(width * title_frame[0])
            title_top = round(height * title_frame[1])
            title_right = round(width * (title_frame[0] + title_frame[2]))
            title_bottom = round(height * (title_frame[1] + title_frame[3]))
            title_box = (title_left, title_top, title_right, title_bottom)
            draw.rectangle(title_box, fill=_rgb(COLORS["title_background"]) + (255,))

        description_box = None
        if description_frame is not None:
            description_left = round(width * description_frame[0])
            description_top = round(height * description_frame[1])
            description_right = round(width * (description_frame[0] + description_frame[2]))
            description_bottom = round(height * (description_frame[1] + description_frame[3]))
            description_box = (
                description_left,
                description_top,
                description_right,
                description_bottom,
            )
            draw.rectangle(
                description_box,
                fill=_rgb(COLORS["description_background"]) + (255,),
            )

        divider_color = _rgb(COLORS["divider"]) + (255,)
        draw.rectangle((0, 0, divider, height), fill=divider_color)
        draw.rectangle((width - divider, 0, width, height), fill=divider_color)
        if title_box is not None:
            draw.rectangle((0, title_box[1], width, title_box[1] + divider), fill=divider_color)
        if description_box is not None:
            draw.rectangle(
                (0, description_box[1], width, description_box[1] + divider),
                fill=divider_color,
            )
        draw.rectangle((0, height - divider, width, height), fill=divider_color)

        padding = round(width * 0.035)
        if title_box is not None:
            _draw_text_box(
                draw,
                card.title,
                (
                    title_box[0] + padding,
                    title_box[1] + 2,
                    title_box[2] - padding,
                    title_box[3] - 2,
                ),
                _rgb(COLORS["title_text"]) + (255,),
                maximum_size=max(12, round(height * 0.043)),
                minimum_size=max(8, round(height * 0.018)),
                max_lines=2,
                bold=True,
            )
        if description_box is not None:
            _draw_text_box(
                draw,
                card.description,
                (
                    description_box[0] + padding,
                    description_box[1] + 2,
                    description_box[2] - padding,
                    description_box[3] - 2,
                ),
                _rgb(COLORS["description_text"]) + (255,),
                maximum_size=max(10, round(height * 0.027)),
                minimum_size=max(7, round(height * 0.014)),
                max_lines=3,
                bold=True,
            )

        settle = _clamp(float(getattr(self, "_active_badge_opacity", 0.0)))
        if settle > 0.0:
            previous_opacity = getattr(self, "_active_badge_opacity", 1.0)
            self._active_badge_opacity = 1.0
            try:
                entrance_scale = 1.42 - 0.42 * settle
                badge = self._render_badge(
                    card.uploaded,
                    card.badge_label,
                    width,
                    round(height * 0.36),
                    0.74 * entrance_scale,
                    primary_max_lines=2,
                    secondary_max_lines=2,
                    minimum_text_scale=0.08,
                )
            finally:
                self._active_badge_opacity = previous_opacity

            settled_y = round(height * BADGE_FRAME[1])
            translation = round(-badge.height * 0.42 * (1.0 - settle))
            badge_x = (width - badge.width) // 2
            badge_y = settled_y + translation
            layer.alpha_composite(badge, (badge_x, badge_y))

            if settle < 0.94:
                gloss = Image.new("RGBA", layer.size, (0, 0, 0, 0))
                gloss_draw = ImageDraw.Draw(gloss)
                progress = settle / 0.94
                shine_x = badge_x - badge.width * 0.30 + badge.width * 1.65 * progress
                gloss_draw.polygon(
                    [
                        (round(shine_x - badge.width * 0.06), badge_y),
                        (round(shine_x + badge.width * 0.06), badge_y),
                        (round(shine_x + badge.width * 0.22), badge_y + badge.height),
                        (round(shine_x + badge.width * 0.10), badge_y + badge.height),
                    ],
                    fill=(255, 255, 255, round(86 * (1.0 - settle))),
                )
                gloss = gloss.filter(ImageFilter.GaussianBlur(max(1, round(width * 0.006))))
                layer.alpha_composite(gloss)

        return layer


# Preview and desktop MP4 export must resolve the same renderer.
exporter_module.TimelineRenderer = ReferenceIllustratedRenderer


class ReferenceIllustratedMainWindow(CardRelativeMainWindow):
    def project_settings(self):
        settings = super().project_settings()
        settings.model_id = MODEL_ID
        settings.visible_cards = VISIBLE_CARDS
        settings.hexagons_bounce = True
        return settings

    def _new_renderer(self) -> ReferenceIllustratedRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReselectAwareRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        CardRelativeRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        ReferenceIllustratedRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return ReferenceIllustratedRenderer(StudioAssetCache(), self.transform_overrides)
