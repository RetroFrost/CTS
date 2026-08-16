from __future__ import annotations

from PIL import Image, ImageDraw, ImageFont

from .models import Card
from .watchdata_final import FinalWatchDataFrameRenderer, POPPINS_BOLD
from .watchdata_renderer import POPPINS_MEDIUM, POPPINS_SEMI_BOLD


class ReleaseWatchDataFrameRenderer(FinalWatchDataFrameRenderer):
    """2.0.4 release layer for WatchData-authored title blocks."""

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

        if role == "title":
            filename = POPPINS_SEMI_BOLD
        elif role == "badge":
            filename = POPPINS_BOLD
        elif role == "credits" and bold:
            filename = POPPINS_SEMI_BOLD
        else:
            filename = POPPINS_MEDIUM
        candidate = self._bundled_font_path(filename)
        if candidate.is_file():
            return self._load_font(str(candidate), max(1, int(size)))
        return super()._font(size, bold, role)

    def _draw_explicit_title(
        self,
        draw: ImageDraw.ImageDraw,
        raw_title: str,
        box: tuple[int, int, int, int],
        fill: tuple[int, int, int],
    ) -> bool:
        if "\n" not in raw_title and "\r" not in raw_title:
            return False
        lines = [" ".join(line.split()) for line in raw_title.replace("\r", "").split("\n")]
        lines = [line for line in lines if line]
        if not lines:
            return True
        lines = lines[:2]
        left, top, right, bottom = box
        available_width = max(1, right - left)
        available_height = max(1, bottom - top)

        chosen_font = self._font(30, True, "title")
        chosen_line_height = 32
        for size in range(54, 29, -1):
            font = self._font(size, True, "title")
            line_height = max(1, int(round(size * 1.08)))
            widths = []
            for line in lines:
                bounds = draw.textbbox((0, 0), line, font=font)
                widths.append(bounds[2] - bounds[0])
            if max(widths, default=0) <= available_width and len(lines) * line_height <= available_height:
                chosen_font = font
                chosen_line_height = line_height
                break

        total_height = len(lines) * chosen_line_height
        y = top + max(0, (available_height - total_height) // 2)
        for line in lines:
            bounds = draw.textbbox((0, 0), line, font=chosen_font)
            line_width = bounds[2] - bounds[0]
            draw.text(
                (left + (available_width - line_width) / 2.0, y),
                line,
                font=chosen_font,
                fill=fill,
            )
            y += chosen_line_height
        return True

    def _draw_card_body_uncached(
        self,
        canvas: Image.Image,
        card: Card,
        x: float,
        width: int,
        height: int,
    ) -> None:
        super()._draw_card_body_uncached(canvas, card, x, width, height)
        raw_title = str(card.title or "")
        if not raw_title.strip():
            return

        layout = self._active_profile.layout
        description = " ".join(str(card.description or "").split())
        description_height = (
            max(0, layout.body_height - layout.description_top)
            if description
            else 0
        )
        rule_height = layout.divider_width if description else 0
        title_height = min(
            layout.title_height,
            max(0, height - description_height - rule_height),
        )
        image_height = max(0, height - title_height - rule_height - description_height)
        title_top = image_height
        title_bottom = title_top + title_height
        ix = int(round(x))
        draw = ImageDraw.Draw(canvas)
        draw.rectangle(
            (ix, title_top, ix + width, title_bottom),
            fill=layout.title_background,
        )
        box = (ix + 12, title_top - 10, ix + width - 12, title_bottom - 10)
        if not self._draw_explicit_title(draw, raw_title, box, self.theme.title_text):
            self._draw_fitted_text_block(
                draw,
                " ".join(raw_title.split()),
                box,
                maximum_size=54,
                minimum_size=30,
                max_lines=2,
                bold=True,
                role="title",
                fill=self.theme.title_text,
            )
        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line(
            (ix + width - 1, 0, ix + width - 1, height),
            fill=self.theme.divider,
            width=2,
        )
