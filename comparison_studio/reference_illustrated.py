from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter, ImageOps

from . import exporter as exporter_module
from .card_relative_transform import CardRelativeMainWindow, CardRelativeRenderer
from .data import MODEL_ILLUSTRATED
from .interaction_runtime import RuntimeTransformRenderer
from .reselect_fix import ReselectAwareRenderer
from .studio_ui import StudioAssetCache


class ReferenceIllustratedRenderer(CardRelativeRenderer):
    """Render Illustrated Cards using the tall reference-video composition.

    Artwork remains the image subcard owned by the parent card. The title and optional
    description are overlays, so changing their layout does not break image transforms.
    """

    @staticmethod
    def _field_box(model_id: str, role: str):
        if model_id == MODEL_ILLUSTRATED:
            return {
                "badge_primary": (0.14, 0.045, 0.72, 0.16),
                "badge_secondary": (0.18, 0.205, 0.64, 0.085),
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
            if 0.10 <= local_x <= 0.90 and local_y <= 0.34:
                return "badge_primary" if local_y <= 0.205 else "badge_secondary"
            return "image"
        return CardRelativeRenderer._field_at(model_id, local_x, local_y)

    @staticmethod
    def _gold_outline_badge(badge: Image.Image) -> Image.Image:
        """Add the warm reference-video edge while retaining CTS's badge shadow."""
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
        title_top = round(height * 0.628)
        title_bottom = round(height * 0.728)
        description_top = title_bottom

        # Artwork fills the same owner frame used by image transforms. The title and
        # optional description are then painted over it, just like the reference video.
        source = self.assets.load(card.image)
        artwork_bottom = round(height * 0.88)
        if source is not None:
            fitted = ImageOps.fit(
                source,
                (max(1, width - divider * 2), max(1, artwork_bottom)),
                Image.Resampling.LANCZOS,
                centering=(0.5, 0.5),
            )
            layer.paste(fitted, (divider, 0))
        else:
            horizon = round(artwork_bottom * 0.64)
            draw.rectangle((divider, 0, width - divider, horizon), fill=(70, 204, 226, 255))
            draw.rectangle(
                (divider, horizon, width - divider, artwork_bottom),
                fill=(242, 198, 111, 255),
            )
            draw.line(
                (divider, horizon, width - divider, horizon),
                fill=(43, 122, 143, 255),
                width=max(2, divider),
            )

        # Soft separation shadow where the artwork meets the white title strip.
        shadow_height = max(8, round(height * 0.018))
        shadow = Image.new("RGBA", layer.size, (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.rectangle(
            (divider, title_top - shadow_height // 2, width - divider, title_top + 2),
            fill=(0, 0, 0, 105),
        )
        shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, round(height * 0.006))))
        layer.alpha_composite(shadow)

        # Reference title and description bands.
        draw = ImageDraw.Draw(layer)
        draw.rectangle((0, title_top, width, title_bottom), fill=(247, 246, 242, 255))
        draw.rectangle((0, description_top, width, height), fill=(22, 22, 22, 255))
        accent_height = max(2, round(height * 0.007))
        draw.rectangle(
            (0, title_bottom - accent_height, width, title_bottom),
            fill=(188, 99, 0, 255),
        )
        draw.rectangle((0, 0, divider, height), fill=(18, 18, 18, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(18, 18, 18, 255))

        title_padding = round(width * 0.028)
        from .renderer import _draw_text_box

        _draw_text_box(
            draw,
            card.title,
            (title_padding, title_top + 3, width - title_padding, title_bottom - accent_height - 3),
            (20, 20, 20, 255),
            maximum_size=round(height * 0.049),
            minimum_size=max(8, round(height * 0.021)),
            max_lines=2,
            bold=True,
        )

        # Description is intentionally optional: an empty value draws no text and never
        # blocks card creation/import, while the reference composition remains stable.
        if card.description.strip():
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

        top_height = round(height * 0.40)
        badge = self._render_badge(
            card.uploaded,
            card.badge_label,
            width,
            top_height,
            badge_scale * 1.03,
            primary_max_lines=3,
            secondary_max_lines=2,
            minimum_text_scale=0.075,
        )
        badge = self._gold_outline_badge(badge)
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(2, round(height * 0.006))),
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
