from __future__ import annotations

import math

from PIL import Image, ImageDraw, ImageFilter, ImageOps

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeMainWindow, CardRelativeRenderer
from .data import MODEL_ILLUSTRATED, REFERENCE_REVEAL_SECONDS
from .illustrated_video_profile import (
    ILLUSTRATED_SCROLL_SECONDS,
    install_illustrated_video_profile,
)
from .interaction_runtime import RuntimeTransformRenderer
from .renderer import _clamp, _ease_out_back, _font, _smoothstep
from .reselect_fix import ReselectAwareRenderer
from .studio_ui import StudioAssetCache


install_illustrated_video_profile()


def _wrap_whole_words(
    draw: ImageDraw.ImageDraw,
    text: str,
    font,
    maximum_width: int,
    maximum_lines: int,
) -> list[str] | None:
    """Wrap only at spaces; never cut a word into fragments."""
    words = text.strip().split()
    if not words:
        return []

    def width(value: str) -> int:
        left, _top, right, _bottom = draw.textbbox((0, 0), value, font=font)
        return right - left

    lines: list[str] = []
    current = ""
    for word in words:
        if width(word) > maximum_width:
            return None
        candidate = word if not current else f"{current} {word}"
        if current and width(candidate) > maximum_width:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines if len(lines) <= maximum_lines else None


def _draw_whole_word_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    fill: tuple[int, int, int, int],
    maximum_size: int,
    minimum_size: int,
    maximum_lines: int,
    bold: bool = True,
) -> None:
    """Fit complete words into a box by shrinking instead of splitting letters."""
    if not text.strip():
        return
    left, top, right, bottom = box
    available_width = max(1, right - left)
    available_height = max(1, bottom - top)

    chosen_font = _font(minimum_size, bold)
    chosen_lines = [text.strip()]
    chosen_spacing = max(1, round(minimum_size * 0.08))
    chosen_line_height = max(1, draw.textbbox((0, 0), "Ag", font=chosen_font)[3])

    for size in range(maximum_size, minimum_size - 1, -1):
        font = _font(size, bold)
        lines = _wrap_whole_words(draw, text, font, available_width, maximum_lines)
        if lines is None:
            continue
        line_height = max(1, draw.textbbox((0, 0), "Ag", font=font)[3])
        spacing = max(1, round(size * 0.08))
        total_height = len(lines) * line_height + max(0, len(lines) - 1) * spacing
        if total_height <= available_height:
            chosen_font = font
            chosen_lines = lines
            chosen_spacing = spacing
            chosen_line_height = line_height
            break

    total_height = (
        len(chosen_lines) * chosen_line_height
        + max(0, len(chosen_lines) - 1) * chosen_spacing
    )
    y = top + (available_height - total_height) / 2
    for line in chosen_lines:
        bounds = draw.textbbox((0, 0), line, font=chosen_font)
        line_width = bounds[2] - bounds[0]
        x = left + (available_width - line_width) / 2
        draw.text((round(x), round(y)), line, font=chosen_font, fill=fill)
        y += chosen_line_height + chosen_spacing


class ReferenceIllustratedRenderer(CardRelativeRenderer):
    """Clone the uploaded comparison video's Illustrated Cards composition.

    The measured 640×360 reference has four cards across. Each card uses 73.0% artwork,
    11.2% title, a 0.6% orange separator, and a 15.2% description band.
    """

    @staticmethod
    def _field_box(model_id: str, role: str):
        if model_id == MODEL_ILLUSTRATED:
            return {
                "badge_primary": (0.16, 0.045, 0.68, 0.145),
                "badge_secondary": (0.16, 0.190, 0.68, 0.145),
                "title": (0.025, 0.730, 0.95, 0.112),
                "description": (0.035, 0.848, 0.93, 0.152),
                "image": (0.0, 0.0, 1.0, 0.730),
            }.get(role)
        return CardRelativeRenderer._field_box(model_id, role)

    @staticmethod
    def _field_at(model_id: str, local_x: float, local_y: float) -> str | None:
        if model_id == MODEL_ILLUSTRATED:
            if local_y >= 0.848:
                return "description"
            if local_y >= 0.730:
                return "title"
            if 0.14 <= local_x <= 0.86 and local_y <= 0.375:
                return "badge_primary" if local_y <= 0.190 else "badge_secondary"
            return "image"
        return CardRelativeRenderer._field_at(model_id, local_x, local_y)

    def hit_test(self, cards, output_time, settings, normalized_x, normalized_y):
        self._studio_settings = settings
        return super().hit_test(cards, output_time, settings, normalized_x, normalized_y)

    def _card_placement(self, cards, output_time, settings, card_index):
        self._studio_settings = settings
        return super()._card_placement(cards, output_time, settings, card_index)

    def _placements(
        self,
        card_count: int,
        model_time: float,
        visible_cards: int,
        width: float,
        hexagons_bounce: bool = True,
    ) -> list[tuple[int, float, float, float]]:
        """Use the reference video's 4.4-second card travel only for Illustrated Cards."""
        card_width = width / visible_cards
        initial_count = min(card_count, visible_cards)
        intro_duration = initial_count * REFERENCE_REVEAL_SECONDS
        placements: list[tuple[int, float, float, float]] = []

        if model_time < intro_duration:
            latest_visible = max(
                0,
                min(initial_count - 1, int(model_time // REFERENCE_REVEAL_SECONDS)),
            )
            focus_center = (latest_visible + 0.5) * card_width
            for index in range(initial_count):
                local = model_time - index * REFERENCE_REVEAL_SECONDS
                if local < 0:
                    continue
                progress = _smoothstep(local / 0.62)
                x = index * card_width
                center = x + card_width / 2
                if hexagons_bounce:
                    badge_scale = self._badge_scale(center, focus_center, card_width)
                    badge_scale *= min(1.0, _ease_out_back(local / 0.58))
                else:
                    badge_scale = 1.0
                placements.append((index, x, progress, badge_scale))
            return placements

        settings = getattr(self, "_studio_settings", None)
        scroll_seconds = (
            ILLUSTRATED_SCROLL_SECONDS
            if getattr(settings, "model_id", MODEL_ILLUSTRATED) == MODEL_ILLUSTRATED
            else settings.base_scroll_seconds()
            if settings is not None
            else ILLUSTRATED_SCROLL_SECONDS
        )
        scroll_elapsed = max(0.0, model_time - intro_duration)
        shift = min(
            max(0, card_count - visible_cards),
            scroll_elapsed / max(0.001, scroll_seconds),
        ) * card_width
        focus_center = (visible_cards - 0.5) * card_width
        for index in range(card_count):
            x = index * card_width - shift
            if x >= width or x + card_width <= 0:
                continue
            center = x + card_width / 2
            badge_scale = (
                self._badge_scale(center, focus_center, card_width)
                if hexagons_bounce
                else 1.0
            )
            placements.append((index, x, 1.0, badge_scale))
        return placements

    @staticmethod
    def _render_reference_badge(
        primary: str,
        secondary: str,
        card_width: int,
        card_height: int,
        scale: float,
    ) -> Image.Image:
        badge_width = max(30, round(card_width * 0.69 * scale))
        badge_height = max(34, round(card_height * 0.285 * scale))
        padding = max(3, round(badge_width * 0.045))
        canvas = Image.new(
            "RGBA",
            (badge_width + padding * 2, badge_height + padding * 2),
            (0, 0, 0, 0),
        )
        x0, y0 = padding, padding
        x1, y1 = x0 + badge_width, y0 + badge_height
        cut_x = round(badge_width * 0.23)
        cut_y = round(badge_height * 0.23)
        points = [
            (x0 + cut_x, y0),
            (x1 - cut_x, y0),
            (x1, y0 + cut_y),
            (x1, y1 - cut_y),
            (x1 - cut_x, y1),
            (x0 + cut_x, y1),
            (x0, y1 - cut_y),
            (x0, y0 + cut_y),
        ]

        mask = Image.new("L", canvas.size, 0)
        ImageDraw.Draw(mask).polygon(points, fill=255)
        shadow_mask = mask.filter(ImageFilter.GaussianBlur(max(2, round(padding * 0.65))))
        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow.putalpha(shadow_mask.point(lambda value: round(value * 0.58)))
        canvas.alpha_composite(shadow, (max(1, padding // 4), max(1, padding // 2)))

        gradient = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        gradient_draw = ImageDraw.Draw(gradient)
        for y in range(y0, y1 + 1):
            position = (y - y0) / max(1, badge_height)
            highlight = _clamp(1.0 - abs(position - 0.28) * 1.45)
            gradient_draw.line(
                (x0, y, x1, y),
                fill=(
                    round(215 + 40 * highlight),
                    round(18 + 42 * highlight),
                    round(28 + 35 * highlight),
                    255,
                ),
            )
        gradient.putalpha(mask)
        canvas.alpha_composite(gradient)

        draw = ImageDraw.Draw(canvas)
        draw.line(
            points + [points[0]],
            fill=(255, 166, 170, 255),
            width=max(1, round(badge_width * 0.010)),
            joint="curve",
        )

        inner_left = x0 + round(badge_width * 0.09)
        inner_right = x1 - round(badge_width * 0.09)
        if secondary.strip():
            _draw_whole_word_text(
                draw,
                primary,
                (
                    inner_left,
                    y0 + round(badge_height * 0.18),
                    inner_right,
                    y0 + round(badge_height * 0.52),
                ),
                (255, 250, 247, 255),
                maximum_size=max(8, round(badge_height * 0.23)),
                minimum_size=max(5, round(badge_height * 0.085)),
                maximum_lines=2,
            )
            _draw_whole_word_text(
                draw,
                secondary,
                (
                    inner_left,
                    y0 + round(badge_height * 0.50),
                    inner_right,
                    y0 + round(badge_height * 0.86),
                ),
                (255, 250, 247, 255),
                maximum_size=max(7, round(badge_height * 0.14)),
                minimum_size=max(4, round(badge_height * 0.060)),
                maximum_lines=3,
            )
        else:
            _draw_whole_word_text(
                draw,
                primary,
                (
                    inner_left,
                    y0 + round(badge_height * 0.17),
                    inner_right,
                    y0 + round(badge_height * 0.84),
                ),
                (255, 250, 247, 255),
                maximum_size=max(8, round(badge_height * 0.25)),
                minimum_size=max(5, round(badge_height * 0.075)),
                maximum_lines=4,
            )
        return canvas

    def _render_illustrated_card(
        self,
        card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)

        divider = max(2, round(width * 0.0125))
        artwork_bottom = round(height * 0.730)
        title_bottom = round(height * 0.842)
        separator_bottom = round(height * 0.848)

        # Measured sky/floor fallback beneath transparent lineal-color artwork.
        horizon = round(artwork_bottom * 0.64)
        draw.rectangle((divider, 0, width - divider, horizon), fill=(89, 207, 229, 255))
        draw.rectangle(
            (divider, horizon, width - divider, artwork_bottom),
            fill=(241, 216, 158, 255),
        )

        source = self.assets.load(card.image)
        if source is not None:
            source = source.convert("RGBA")
            target_size = (max(1, width - divider * 2), max(1, artwork_bottom))
            alpha_minimum = source.getchannel("A").getextrema()[0]
            if alpha_minimum < 255:
                # Transparent lineal-color art must remain fully visible.
                fitted = ImageOps.contain(source, target_size, Image.Resampling.LANCZOS)
                x = divider + (target_size[0] - fitted.width) // 2
                y = (target_size[1] - fitted.height) // 2
            else:
                # Complete illustrated scenes fill the card exactly like the source video.
                fitted = ImageOps.fit(source, target_size, Image.Resampling.LANCZOS)
                x, y = divider, 0
            layer.alpha_composite(fitted, (x, y))

        # Exact title and description bands measured from the 640×360 reference.
        draw = ImageDraw.Draw(layer)
        draw.rectangle((0, artwork_bottom, width, title_bottom), fill=(247, 246, 242, 255))
        draw.rectangle((0, title_bottom, width, separator_bottom), fill=(165, 96, 0, 255))
        draw.rectangle((0, separator_bottom, width, height), fill=(23, 23, 23, 255))
        draw.rectangle((0, 0, divider, height), fill=(5, 5, 6, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(5, 5, 6, 255))

        from .renderer import _draw_text_box

        title_padding = round(width * 0.035)
        _draw_text_box(
            draw,
            card.title,
            (title_padding, artwork_bottom + 2, width - title_padding, title_bottom - 2),
            (23, 23, 23, 255),
            maximum_size=max(9, round(height * 0.044)),
            minimum_size=max(7, round(height * 0.020)),
            max_lines=2,
            bold=True,
        )

        if card.description.strip():
            description_padding = round(width * 0.055)
            _draw_text_box(
                draw,
                card.description,
                (
                    description_padding,
                    separator_bottom + round(height * 0.010),
                    width - description_padding,
                    height - round(height * 0.010),
                ),
                (218, 218, 218, 255),
                maximum_size=max(7, round(height * 0.026)),
                minimum_size=max(6, round(height * 0.014)),
                max_lines=4,
                bold=False,
            )

        if getattr(self, "_show_hexagons", True):
            badge = self._render_reference_badge(
                card.uploaded,
                card.badge_label,
                width,
                height,
                badge_scale,
            )
            layer.alpha_composite(
                badge,
                ((width - badge.width) // 2, max(0, round(height * 0.004))),
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
