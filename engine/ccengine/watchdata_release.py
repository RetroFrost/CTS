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
        lines = [
            " ".join(line.split())
            for line in raw_title.replace("\r", "").split("\n")
        ]
        lines = [line for line in lines if line][:2]
        if not lines:
            return True

        left, top, right, bottom = box
        available_width = max(1, right - left)
        available_height = max(1, bottom - top)
        # Reference f528: long authored lines top out around 374 px while
        # shorter two-line titles stay larger. Width, not a single fixed font
        # size, is the controlling measurement.
        target_width = min(380, available_width)
        chosen_font = self._font(30, True, "title")
        chosen_size = 30
        for size in range(50, 29, -1):
            font = self._font(size, True, "title")
            widths = []
            for line in lines:
                bounds = draw.textbbox((0, 0), line, font=font)
                widths.append(bounds[2] - bounds[0])
            if max(widths, default=0) <= target_width:
                chosen_font = font
                chosen_size = size
                break

        line_height = max(34, int(round(chosen_size * 1.05)))
        block_height = line_height * len(lines)
        center_x = (left + right) / 2.0
        center_y = top + (available_height - block_height) / 2.0 + line_height / 2.0 + 3.0
        for line in lines:
            draw.text(
                (center_x, center_y),
                line,
                font=chosen_font,
                fill=fill,
                anchor="mm",
            )
            center_y += line_height
        return True

    def _draw_reference_description(
        self,
        draw: ImageDraw.ImageDraw,
        description: str,
        ix: int,
        width: int,
        top: int,
        bottom: int,
    ) -> None:
        if not description or top >= bottom:
            return
        # f528 measurements keep the description block around 350-365 px wide.
        # This is what moves "with" to the second line on card one while still
        # keeping the FOXP2 sentence on the same two lines as the source.
        target_width = min(365, max(1, width - 34))
        chosen_font = self._font(18, False, "description")
        chosen_lines: list[str] = []
        chosen_line_height = 26
        for font_size in range(25, 17, -1):
            font = self._font(font_size, False, "description")
            line_height = max(21, int(round(font_size * 1.13)))
            lines = self._wrapped_lines(
                draw,
                description,
                font,
                target_width,
                4,
            )
            if lines and not any(line.endswith("…") for line in lines):
                chosen_font = font
                chosen_lines = lines
                chosen_line_height = line_height
                break
        if not chosen_lines:
            chosen_lines = self._wrapped_lines(
                draw,
                description,
                chosen_font,
                target_width,
                4,
            )

        y = top + 8
        center_x = ix + width / 2.0
        for line in chosen_lines:
            draw.text(
                (center_x, y),
                line,
                font=chosen_font,
                fill=self.theme.description_text,
                anchor="ma",
            )
            y += chosen_line_height

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
        description = " ".join(str(card.description or "").split())
        if not raw_title.strip() and not description:
            return

        layout = self._active_profile.layout
        description_height = (
            max(0, layout.body_height - layout.description_top)
            if description
            else 0
        )
        rule_height = layout.divider_width if description else 0
        title_height = (
            min(
                layout.title_height,
                max(0, height - description_height - rule_height),
            )
            if raw_title.strip()
            else 0
        )
        image_height = max(
            0,
            height - title_height - rule_height - description_height,
        )
        title_top = image_height
        title_bottom = title_top + title_height
        desc_top = title_bottom + rule_height
        ix = int(round(x))
        draw = ImageDraw.Draw(canvas)

        if raw_title.strip():
            draw.rectangle(
                (ix, title_top, ix + width, title_bottom),
                fill=layout.title_background,
            )
            title_box = (ix + 12, title_top, ix + width - 12, title_bottom)
            if not self._draw_explicit_title(
                draw,
                raw_title,
                title_box,
                self.theme.title_text,
            ):
                self._draw_fitted_text_block(
                    draw,
                    " ".join(raw_title.split()),
                    (ix + 12, title_top - 10, ix + width - 12, title_bottom - 10),
                    maximum_size=54,
                    minimum_size=30,
                    max_lines=2,
                    bold=True,
                    role="title",
                    fill=self.theme.title_text,
                )

        if description and desc_top < height:
            draw.rectangle(
                (ix, desc_top, ix + width, height),
                fill=layout.description_background,
            )
            self._draw_reference_description(
                draw,
                description,
                ix,
                width,
                desc_top,
                height,
            )

        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line(
            (ix + width - 1, 0, ix + width - 1, height),
            fill=self.theme.divider,
            width=2,
        )
