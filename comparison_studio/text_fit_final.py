from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter

from . import exporter as exporter_module
from . import renderer as renderer_module
from .iteration_fixes import ResponsiveStudioTimelineRenderer
from .layout_hotfix import FinalStudioMainWindow
from .studio_ui import StudioAssetCache, _clamp, _draw_text_box


class TrueFitStudioTimelineRenderer(ResponsiveStudioTimelineRenderer):
    """Illustrated renderer whose auto-fit badge never ellipsizes typed text."""

    @staticmethod
    def _text_width(draw: ImageDraw.ImageDraw, text: str, font) -> int:
        if not text:
            return 0
        left, _top, right, _bottom = draw.textbbox((0, 0), text, font=font)
        return right - left

    @classmethod
    def _split_long_token(cls, draw: ImageDraw.ImageDraw, token: str, font, max_width: int) -> list[str]:
        pieces: list[str] = []
        remaining = token
        while remaining:
            low, high, best = 1, len(remaining), 1
            while low <= high:
                middle = (low + high) // 2
                if cls._text_width(draw, remaining[:middle], font) <= max_width:
                    best = middle
                    low = middle + 1
                else:
                    high = middle - 1
            pieces.append(remaining[:best])
            remaining = remaining[best:]
        return pieces

    @classmethod
    def _wrap_everything(cls, draw: ImageDraw.ImageDraw, text: str, font, max_width: int) -> list[str]:
        """Wrap all text without truncation or an ellipsis."""
        words = text.strip().split()
        if not words:
            return []

        expanded: list[str] = []
        for word in words:
            if cls._text_width(draw, word, font) <= max_width:
                expanded.append(word)
            else:
                expanded.extend(cls._split_long_token(draw, word, font, max_width))

        lines: list[str] = []
        current = ""
        for word in expanded:
            candidate = word if not current else f"{current} {word}"
            if current and cls._text_width(draw, candidate, font) > max_width:
                lines.append(current)
                current = word
            else:
                current = candidate
        if current:
            lines.append(current)
        return lines

    @staticmethod
    def _line_height(draw: ImageDraw.ImageDraw, font) -> int:
        left, top, right, bottom = draw.textbbox((0, 0), "Ag", font=font)
        return max(1, bottom - top)

    def _make_text_plan(
        self,
        primary: str,
        secondary: str,
        card_width: int,
        top_height: int,
        animated_scale: float,
        manual_scale: float,
    ) -> dict[str, object]:
        """Choose width, height, font sizes, and complete wrapped lines."""
        motion_scale = max(0.87, animated_scale)
        base_scale = motion_scale * manual_scale
        base_width = max(28, round(card_width * 0.69 * base_scale))
        base_height = max(30, round(top_height * 0.73 * base_scale))

        # The whole badge, including shadow padding, should normally remain inside its card.
        max_body_width = max(base_width, round(card_width * 0.86 * max(1.0, manual_scale)))
        max_body_width = min(max_body_width, round(card_width * 1.05))

        probe = Image.new("RGB", (8, 8))
        draw = ImageDraw.Draw(probe)
        primary_size = max(12, round(base_height * 0.25))
        secondary_size = max(8, round(base_height * 0.13))

        # Prefer widening first. Only reduce the font for truly extreme input that would
        # otherwise need more than six lines inside one comparison card.
        chosen: tuple[int, int, int, list[str], list[str], object, object] | None = None
        minimum_primary = max(10, round(primary_size * 0.72))
        for size in range(primary_size, minimum_primary - 1, -1):
            primary_font = renderer_module._font(size, True)
            secondary_font = renderer_module._font(max(7, round(secondary_size * size / primary_size)), True)
            for body_width in range(base_width, max_body_width + 1, max(4, round(card_width * 0.015))):
                inner_width = max(12, round(body_width * 0.82))
                primary_lines = self._wrap_everything(draw, primary, primary_font, inner_width)
                secondary_lines = self._wrap_everything(draw, secondary, secondary_font, inner_width)
                if len(primary_lines) <= 6 and len(secondary_lines) <= 3:
                    chosen = (
                        body_width,
                        size,
                        max(7, round(secondary_size * size / primary_size)),
                        primary_lines,
                        secondary_lines,
                        primary_font,
                        secondary_font,
                    )
                    break
            if chosen is not None:
                break

        if chosen is None:
            size = minimum_primary
            secondary_final = max(7, round(secondary_size * size / primary_size))
            primary_font = renderer_module._font(size, True)
            secondary_font = renderer_module._font(secondary_final, True)
            inner_width = max(12, round(max_body_width * 0.82))
            chosen = (
                max_body_width,
                size,
                secondary_final,
                self._wrap_everything(draw, primary, primary_font, inner_width),
                self._wrap_everything(draw, secondary, secondary_font, inner_width),
                primary_font,
                secondary_font,
            )

        body_width, primary_size, secondary_size, primary_lines, secondary_lines, primary_font, secondary_font = chosen
        primary_line_height = self._line_height(draw, primary_font)
        secondary_line_height = self._line_height(draw, secondary_font)
        primary_spacing = max(2, round(primary_size * 0.11))
        secondary_spacing = max(1, round(secondary_size * 0.10))
        primary_block = (
            len(primary_lines) * primary_line_height + max(0, len(primary_lines) - 1) * primary_spacing
        )
        secondary_block = (
            len(secondary_lines) * secondary_line_height
            + max(0, len(secondary_lines) - 1) * secondary_spacing
        )
        gap = max(4, round(base_height * 0.055)) if primary_lines and secondary_lines else 0

        # Polygon tips need breathing room above and below the text block.
        required_height = round((primary_block + secondary_block + gap) / 0.68) if (primary_block or secondary_block) else base_height
        body_height = max(base_height, required_height)
        body_height = min(body_height, round(top_height * 1.48 * max(1.0, manual_scale)))

        return {
            "width": body_width,
            "height": body_height,
            "primary_lines": primary_lines,
            "secondary_lines": secondary_lines,
            "primary_font": primary_font,
            "secondary_font": secondary_font,
            "primary_size": primary_size,
            "secondary_size": secondary_size,
        }

    @staticmethod
    def _draw_centered_lines(
        draw: ImageDraw.ImageDraw,
        lines: list[str],
        font,
        box: tuple[int, int, int, int],
        fill: tuple[int, int, int, int],
        spacing: int,
    ) -> None:
        if not lines:
            return
        left, top, right, bottom = box
        line_height = max(1, draw.textbbox((0, 0), "Ag", font=font)[3])
        total_height = len(lines) * line_height + max(0, len(lines) - 1) * spacing
        y = top + max(0, (bottom - top - total_height) / 2)
        for line in lines:
            bounds = draw.textbbox((0, 0), line, font=font)
            width = bounds[2] - bounds[0]
            x = left + (right - left - width) / 2
            draw.text((round(x), round(y)), line, font=font, fill=fill)
            y += line_height + spacing

    @classmethod
    def _render_true_fit_badge(cls, plan: dict[str, object]) -> Image.Image:
        badge_width = int(plan["width"])
        badge_height = int(plan["height"])
        primary_lines = list(plan["primary_lines"])
        secondary_lines = list(plan["secondary_lines"])
        primary_font = plan["primary_font"]
        secondary_font = plan["secondary_font"]
        primary_size = int(plan["primary_size"])
        secondary_size = int(plan["secondary_size"])

        padding = max(8, round(badge_width * 0.065))
        canvas = Image.new("RGBA", (badge_width + padding * 2, badge_height + padding * 2), (0, 0, 0, 0))
        mask = Image.new("L", canvas.size, 0)
        mask_draw = ImageDraw.Draw(mask)
        x0, y0 = padding, padding
        x1, y1 = padding + badge_width, padding + badge_height
        points = [
            ((x0 + x1) // 2, y0),
            (x1, y0 + round(badge_height * 0.20)),
            (x1, y0 + round(badge_height * 0.78)),
            ((x0 + x1) // 2, y1),
            (x0, y0 + round(badge_height * 0.78)),
            (x0, y0 + round(badge_height * 0.20)),
        ]
        mask_draw.polygon(points, fill=255)

        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow_mask = mask.filter(ImageFilter.GaussianBlur(max(3, round(padding * 0.55))))
        shadow.putalpha(shadow_mask.point(lambda value: round(value * 0.72)))
        canvas.alpha_composite(shadow, (max(1, padding // 6), max(2, padding // 3)))

        gradient = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        gradient_draw = ImageDraw.Draw(gradient)
        for y in range(y0, y1 + 1):
            position = (y - y0) / max(1, badge_height)
            center_light = 1.0 - abs(position - 0.38) * 1.3
            red = round(205 + 43 * _clamp(center_light))
            green = round(2 + 12 * _clamp(center_light))
            blue = round(8 + 7 * _clamp(center_light))
            gradient_draw.line((x0, y, x1, y), fill=(red, green, blue, 255))
        gradient.putalpha(mask)
        canvas.alpha_composite(gradient)

        border = ImageDraw.Draw(canvas)
        border.line(
            points + [points[0]],
            fill=(125, 0, 6, 235),
            width=max(1, round(badge_width * 0.012)),
            joint="curve",
        )

        text_draw = ImageDraw.Draw(canvas)
        inner_left = x0 + round(badge_width * 0.09)
        inner_right = x1 - round(badge_width * 0.09)
        if secondary_lines:
            primary_box = (inner_left, y0 + round(badge_height * 0.13), inner_right, y0 + round(badge_height * 0.68))
            secondary_box = (inner_left, y0 + round(badge_height * 0.67), inner_right, y0 + round(badge_height * 0.90))
        else:
            primary_box = (inner_left, y0 + round(badge_height * 0.12), inner_right, y0 + round(badge_height * 0.88))
            secondary_box = (inner_left, y0, inner_right, y0)

        cls._draw_centered_lines(
            text_draw,
            primary_lines,
            primary_font,
            primary_box,
            (255, 250, 244, 255),
            max(2, round(primary_size * 0.11)),
        )
        cls._draw_centered_lines(
            text_draw,
            secondary_lines,
            secondary_font,
            secondary_box,
            (255, 248, 240, 255),
            max(1, round(secondary_size * 0.10)),
        )
        return canvas

    def _render_illustrated_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        title_height = round(height * 0.12)
        title_top = height - title_height
        divider = max(2, round(width * 0.008))

        background_id = getattr(self._studio_settings, "illustrated_background", "beach")
        self._draw_background(draw, (divider, 0, width - divider, title_top), background_id)

        manual_image_scale = self._image_scale
        manual_badge_scale = _clamp(
            float(getattr(self._studio_settings, "illustrated_badge_scale", 1.0)),
            0.45,
            2.0,
        )
        auto_enabled = bool(getattr(self._studio_settings, "illustrated_auto_size", False))
        top_height = round(height * 0.37)
        animated_scale = badge_scale * 0.87

        image_auto_factor = 1.0
        if auto_enabled:
            plan = self._make_text_plan(
                card.uploaded,
                card.badge_label,
                width,
                top_height,
                animated_scale,
                manual_badge_scale,
            )
            badge = self._render_true_fit_badge(plan)
            width_growth = int(plan["width"]) / max(1.0, width * 0.69 * manual_badge_scale)
            height_growth = int(plan["height"]) / max(1.0, top_height * 0.73 * manual_badge_scale)
            image_auto_factor = _clamp(
                1.0 - max(0.0, width_growth - 1.0) * 0.05 - max(0.0, height_growth - 1.0) * 0.06,
                0.90,
                1.0,
            )
        else:
            badge = self._render_badge(
                card.uploaded,
                card.badge_label,
                width,
                top_height,
                animated_scale * manual_badge_scale,
            )

        source = self.assets.load(card.image)
        if source is not None:
            target_size = (width - divider * 2, title_top)
            artwork = self._scaled_fit(source, target_size, manual_image_scale * image_auto_factor)
            layer.alpha_composite(artwork, (divider, 0))

        draw.rectangle((0, title_top, width, height), fill=(249, 248, 244, 255))
        draw.rectangle((0, 0, divider, height), fill=(30, 30, 28, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(30, 30, 28, 255))
        draw.rectangle((0, title_top, width, title_top + divider), fill=(30, 30, 28, 255))
        _draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), title_top + 3, width - round(width * 0.035), height - 3),
            (18, 18, 16, 255),
            maximum_size=round(height * 0.048),
            minimum_size=round(height * 0.022),
            max_lines=2,
            bold=True,
        )

        badge_x = (width - badge.width) // 2
        badge_y = max(3, round(height * 0.025))
        layer.alpha_composite(badge, (badge_x, badge_y))
        return layer


# Preview and MP4 export use exactly the same final renderer.
exporter_module.TimelineRenderer = TrueFitStudioTimelineRenderer


class TrueFitStudioMainWindow(FinalStudioMainWindow):
    """Final test window with complete, non-ellipsized Illustrated badge fitting."""

    def __init__(self) -> None:
        super().__init__()
        self.renderer = TrueFitStudioTimelineRenderer(StudioAssetCache())
        if hasattr(self, "illustrated_auto_size"):
            self.illustrated_auto_size.setText("Fit hexagon to all typed text")
            self.illustrated_auto_size.setToolTip(
                "Widens and, when necessary, raises the Illustrated hexagon until all typed badge text fits."
            )
        self.statusBar().showMessage("Ready · complete text-fit hexagons · laptop layout widened")
        self.update_preview()

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = TrueFitStudioTimelineRenderer(StudioAssetCache())
            if hasattr(self, "preview_debounce"):
                self.preview_debounce.start()
