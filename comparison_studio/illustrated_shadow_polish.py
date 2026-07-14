from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter

from . import exporter as exporter_module
from .studio_ui import StudioAssetCache, _clamp
from .text_fit_final import TrueFitStudioMainWindow, TrueFitStudioTimelineRenderer


class ShadowPolishedTimelineRenderer(TrueFitStudioTimelineRenderer):
    """Illustrated renderer with the subtle depth used by the reference cards."""

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
        canvas = Image.new(
            "RGBA",
            (badge_width + padding * 2, badge_height + padding * 2),
            (0, 0, 0, 0),
        )
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

        # Soft, slightly downward shadow: visible enough to separate the badge from
        # artwork, but not strong enough to make it look like it is floating.
        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        blur_radius = max(3, round(padding * 0.62))
        shadow_mask = mask.filter(ImageFilter.GaussianBlur(blur_radius))
        shadow.putalpha(shadow_mask.point(lambda value: round(value * 0.40)))
        canvas.alpha_composite(shadow, (max(1, padding // 8), max(2, padding // 3)))

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
            primary_box = (
                inner_left,
                y0 + round(badge_height * 0.13),
                inner_right,
                y0 + round(badge_height * 0.68),
            )
            secondary_box = (
                inner_left,
                y0 + round(badge_height * 0.67),
                inner_right,
                y0 + round(badge_height * 0.90),
            )
        else:
            primary_box = (
                inner_left,
                y0 + round(badge_height * 0.12),
                inner_right,
                y0 + round(badge_height * 0.88),
            )
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
        layer = super()._render_illustrated_card(card, width, height, badge_scale)
        title_height = round(height * 0.12)
        title_top = height - title_height

        # A narrow blurred shadow just above the white strip. It gives the same slight
        # separation as the references without darkening the title area itself.
        shadow_height = max(5, round(height * 0.014))
        shadow = Image.new("RGBA", (width, shadow_height * 3), (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.rectangle(
            (0, shadow_height, width, shadow_height * 2),
            fill=(0, 0, 0, 72),
        )
        shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, round(shadow_height * 0.62))))
        shadow_y = max(0, title_top - shadow_height * 2)
        layer.alpha_composite(shadow, (0, shadow_y))
        return layer


# Program Monitor and MP4 export must keep using the same polished renderer.
exporter_module.TimelineRenderer = ShadowPolishedTimelineRenderer


class ShadowPolishedMainWindow(TrueFitStudioMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.renderer = ShadowPolishedTimelineRenderer(StudioAssetCache())
        self.statusBar().showMessage("Ready · subtle Illustrated shadows · complete text fit")
        self.update_preview()

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = ShadowPolishedTimelineRenderer(StudioAssetCache())
            if hasattr(self, "preview_debounce"):
                self.preview_debounce.start()
