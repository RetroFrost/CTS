from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

from .models import Card
from .watchdata_measured import MeasuredWatchDataFrameRenderer
from .watchdata_renderer import POPPINS_MEDIUM, POPPINS_SEMI_BOLD


POPPINS_BOLD = "Poppins-Bold.ttf"


class FinalWatchDataFrameRenderer(MeasuredWatchDataFrameRenderer):
    """2.0.4 text styling matched against WatchData reference crops."""

    def _font(
        self,
        size: int,
        bold: bool = False,
        role: str = "title",
    ) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        settings = self._active_settings
        setting_name = {
            "title": "font_title",
            "description": "font_description",
            "badge": "font_badge",
            "credits": "font_credits",
        }.get(role, "font_title")
        custom = (
            str(getattr(settings, setting_name, "") or "").strip()
            if settings is not None
            else ""
        )
        if custom:
            return super()._font(size, bold, role)

        if role in {"title", "badge"}:
            filename = POPPINS_BOLD
        elif role == "credits" and bold:
            filename = POPPINS_SEMI_BOLD
        else:
            filename = POPPINS_MEDIUM
        candidate = self._bundled_font_path(filename)
        if candidate.is_file():
            return self._load_font(str(candidate), max(1, int(size)))
        return super()._font(size, bold, role)

    def _font_named_fitted(
        self,
        filename: str,
        text: str,
        maximum_size: int,
        max_width: int,
        minimum_size: int = 16,
    ) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        candidate = self._bundled_font_path(filename)
        for size in range(maximum_size, minimum_size - 1, -1):
            if candidate.is_file():
                font = self._load_font(str(candidate), size)
            else:
                font = self._font(size, True, "badge")
            probe = Image.new("L", (4, 4), 0)
            bounds = ImageDraw.Draw(probe).textbbox((0, 0), text, font=font)
            if bounds[2] - bounds[0] <= max_width:
                return font
        if candidate.is_file():
            return self._load_font(str(candidate), minimum_size)
        return self._font(minimum_size, True, "badge")

    def _draw_badge_text_canonical(
        self,
        layer: Image.Image,
        card: Card,
        age: float,
        force_final: bool = False,
    ) -> None:
        layout = self._text_layout(card)
        for index, (text, target_y, size) in enumerate(layout):
            start = 0.90 + index * 0.10
            progress = 1.0 if force_final else max(0.0, min(1.0, (age - start) / 0.42))
            if progress <= 0.0:
                continue

            eased = 1.0 - (1.0 - progress) ** 3
            y = target_y + self._text_landing_offset(age) - (1.0 - eased) * 112.0
            alpha = int(255 * max(0.0, min(1.0, progress * 1.75)))
            filename = POPPINS_BOLD if index == 0 else POPPINS_SEMI_BOLD
            font = self._font_named_fitted(filename, text, size, 280)

            text_layer = Image.new("RGBA", (480, 430), (0, 0, 0, 0))
            text_draw = ImageDraw.Draw(text_layer)
            if progress < 0.92:
                trail_length = (1.0 - progress) * 76.0
                for trail_index in range(8, 0, -1):
                    fraction = trail_index / 8.0
                    trail_y = y - trail_length * fraction
                    trail_alpha = int(alpha * (1.0 - fraction) * 0.15)
                    if trail_alpha > 0:
                        text_draw.text(
                            (236.5, trail_y),
                            text,
                            font=font,
                            fill=(*self.theme.badge_text, trail_alpha),
                            anchor="mm",
                        )

            text_draw.text(
                (239.5, y + 6),
                text,
                font=font,
                fill=(18, 18, 18, int(alpha * 0.32)),
                anchor="mm",
            )
            text_draw.text(
                (236.5, y),
                text,
                font=font,
                fill=(*self.theme.badge_text, alpha),
                anchor="mm",
            )
            blur = max(0.0, (1.0 - progress) * 5.2)
            if blur > 0.2:
                text_layer = text_layer.filter(ImageFilter.GaussianBlur(blur))
            layer.alpha_composite(text_layer)

    def _draw_card_body_uncached(
        self,
        canvas: Image.Image,
        card: Card,
        x: float,
        width: int,
        height: int,
    ) -> None:
        super()._draw_card_body_uncached(canvas, card, x, width, height)

        ix = int(round(x))
        title = " ".join(str(card.title or "").split())
        description = " ".join(str(card.description or "").split())
        has_title = bool(title)
        has_description = bool(description)
        layout = self._active_profile.layout
        description_height = (
            max(0, layout.body_height - layout.description_top)
            if has_description
            else 0
        )
        rule_height = layout.divider_width if has_description else 0
        title_height = (
            min(layout.title_height, max(0, height - description_height - rule_height))
            if has_title
            else 0
        )
        image_height = max(0, height - title_height - rule_height - description_height)
        title_top = image_height
        title_bottom = title_top + title_height
        desc_top = title_bottom + rule_height
        draw = ImageDraw.Draw(canvas)

        if has_title:
            draw.rectangle(
                (ix, title_top, ix + width, title_bottom),
                fill=layout.title_background,
            )
            self._draw_fitted_text_block(
                draw,
                title,
                (ix + 12, title_top - 10, ix + width - 12, title_bottom - 10),
                maximum_size=54,
                minimum_size=30,
                max_lines=2,
                bold=True,
                role="title",
                fill=self.theme.title_text,
            )

        if has_description and desc_top < height:
            draw.rectangle(
                (ix, desc_top, ix + width, height),
                fill=layout.description_background,
            )
            available_width = max(24, width - 34)
            available_height = max(12, height - desc_top - 8)
            chosen_font = self._font(18, False, "description")
            chosen_lines: list[str] = []
            chosen_line_height = 27
            for font_size in range(26, 17, -1):
                candidate = self._font(font_size, False, "description")
                line_height = max(22, int(round(font_size * 1.13)))
                max_lines = max(1, min(4, available_height // line_height))
                lines = self._wrapped_lines(
                    draw,
                    description,
                    candidate,
                    available_width,
                    max_lines,
                )
                if lines and len(lines) * line_height <= available_height:
                    chosen_font = candidate
                    chosen_lines = lines
                    chosen_line_height = line_height
                    break
            if not chosen_lines:
                chosen_lines = self._wrapped_lines(
                    draw,
                    description,
                    chosen_font,
                    available_width,
                    1,
                )
            y = desc_top + 8
            for line in chosen_lines:
                bounds = draw.textbbox((0, 0), line, font=chosen_font)
                line_width = bounds[2] - bounds[0]
                draw.text(
                    (ix + (width - line_width) / 2.0, y),
                    line,
                    font=chosen_font,
                    fill=self.theme.description_text,
                )
                y += chosen_line_height

        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line(
            (ix + width - 1, 0, ix + width - 1, height),
            fill=self.theme.divider,
            width=2,
        )
