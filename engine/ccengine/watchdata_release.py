from __future__ import annotations

from PIL import Image, ImageDraw, ImageFont

from . import renderer as _base
from .models import Card
from .watchdata_final import FinalWatchDataFrameRenderer, POPPINS_BOLD
from .watchdata_renderer import POPPINS_MEDIUM, POPPINS_SEMI_BOLD


class ReleaseWatchDataFrameRenderer(FinalWatchDataFrameRenderer):
    """2.0.5 release renderer with final badge-shine and long-text fixes."""

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

    @staticmethod
    def _full_wrap(
        draw: ImageDraw.ImageDraw,
        text: str,
        font: ImageFont.ImageFont,
        max_width: int,
        max_lines: int,
    ) -> tuple[list[str], bool]:
        """Wrap all text without turning the final visible line into an ellipsis."""
        normalized = " ".join(str(text or "").split())
        if not normalized or max_width <= 0 or max_lines <= 0:
            return [], True

        def measured(value: str) -> int:
            bounds = draw.textbbox((0, 0), value, font=font)
            return bounds[2] - bounds[0]

        lines: list[str] = []
        current = ""
        words = normalized.split()

        def push(value: str) -> bool:
            if not value:
                return True
            if len(lines) >= max_lines:
                return False
            lines.append(value)
            return True

        for word in words:
            candidate = word if not current else f"{current} {word}"
            if measured(candidate) <= max_width:
                current = candidate
                continue

            if current:
                if not push(current):
                    return lines[:max_lines], False
                current = ""

            if measured(word) <= max_width:
                current = word
                continue

            # A single very long token should wrap rather than force the whole
            # block to become microscopic or disappear past the card edge.
            chunk = ""
            for char in word:
                candidate = chunk + char
                if chunk and measured(candidate) > max_width:
                    if not push(chunk):
                        return lines[:max_lines], False
                    chunk = char
                else:
                    chunk = candidate
            current = chunk

        if current and not push(current):
            return lines[:max_lines], False
        return lines[:max_lines], True

    def _draw_readable_text_block(
        self,
        draw: ImageDraw.ImageDraw,
        text: str,
        box: tuple[int, int, int, int],
        *,
        maximum_size: int,
        minimum_size: int,
        max_lines: int,
        bold: bool,
        role: str,
        fill: tuple[int, int, int],
        line_spacing: float = 1.08,
    ) -> None:
        normalized = " ".join(str(text or "").split())
        left, top, right, bottom = box
        available_width = max(1, right - left)
        available_height = max(1, bottom - top)
        if not normalized:
            return

        chosen_font = self._font(minimum_size, bold, role)
        chosen_lines: list[str] = []
        chosen_line_height = max(1, int(round(minimum_size * line_spacing)))

        for size in range(maximum_size, minimum_size - 1, -1):
            font = self._font(size, bold, role)
            line_height = max(1, int(round(size * line_spacing)))
            line_limit = max(1, min(max_lines, available_height // line_height))
            lines, complete = self._full_wrap(
                draw,
                normalized,
                font,
                available_width,
                line_limit,
            )
            if complete and lines and len(lines) * line_height <= available_height:
                chosen_font = font
                chosen_lines = lines
                chosen_line_height = line_height
                break

        if not chosen_lines:
            line_limit = max(1, min(max_lines, available_height // chosen_line_height))
            chosen_lines, _ = self._full_wrap(
                draw,
                normalized,
                chosen_font,
                available_width,
                line_limit,
            )

        block_height = len(chosen_lines) * chosen_line_height
        y = top + max(0, (available_height - block_height) // 2)
        center_x = (left + right) / 2.0
        for line in chosen_lines:
            draw.text(
                (center_x, y),
                line,
                font=chosen_font,
                fill=fill,
                anchor="ma",
            )
            y += chosen_line_height

    def _add_badge_shine(self, layer: Image.Image, age: float) -> None:
        """Finish the diagonal sweep cleanly instead of leaving a bright strip."""
        shine_start = _base.SHINE_START
        shine_end = shine_start + _base.SHINE_SECONDS
        progress = (float(age) - shine_start) / _base.SHINE_SECONDS
        if progress <= 0.0 or float(age) >= shine_end - 1e-6:
            return

        # Draw the measured sweep to a temporary layer, then fade its final
        # fifth. The old implementation kept full opacity while the streak was
        # still crossing the lower half of the hexagon, which looked frozen on
        # the last visible shine frames.
        overlay = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        super()._add_badge_shine(overlay, age)
        if progress > 0.78:
            fade = 1.0 - _base.smoothstep((progress - 0.78) / 0.22)
            alpha = overlay.getchannel("A").point(
                lambda value: int(round(value * max(0.0, min(1.0, fade))))
            )
            overlay.putalpha(alpha)
        layer.alpha_composite(overlay)

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
        lines = [line for line in lines if line][:3]
        if not lines:
            return True

        left, top, right, bottom = box
        available_width = max(1, right - left)
        available_height = max(1, bottom - top)
        target_width = min(380, available_width)
        chosen_font = self._font(20, True, "title")
        chosen_size = 20
        for size in range(50, 19, -1):
            font = self._font(size, True, "title")
            widths = []
            for line in lines:
                bounds = draw.textbbox((0, 0), line, font=font)
                widths.append(bounds[2] - bounds[0])
            line_height = max(23, int(round(size * 1.05)))
            if (
                max(widths, default=0) <= target_width
                and line_height * len(lines) <= available_height
            ):
                chosen_font = font
                chosen_size = size
                break

        line_height = max(23, int(round(chosen_size * 1.05)))
        block_height = line_height * len(lines)
        center_x = (left + right) / 2.0
        center_y = top + (available_height - block_height) / 2.0 + line_height / 2.0 + 2.0
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
        target_width = min(365, max(1, width - 34))
        self._draw_readable_text_block(
            draw,
            description,
            (ix + (width - target_width) // 2, top + 4, ix + (width + target_width) // 2, bottom - 4),
            maximum_size=25,
            minimum_size=15,
            max_lines=5,
            bold=False,
            role="description",
            fill=self.theme.description_text,
            line_spacing=1.13,
        )

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
                self._draw_readable_text_block(
                    draw,
                    " ".join(raw_title.split()),
                    (ix + 12, title_top + 2, ix + width - 12, title_bottom - 2),
                    maximum_size=54,
                    minimum_size=22,
                    max_lines=3,
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
