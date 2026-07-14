from __future__ import annotations

import math

from PIL import Image, ImageDraw

from . import exporter as exporter_module
from .illustrated_shadow_polish import ShadowPolishedMainWindow, ShadowPolishedTimelineRenderer
from .studio_ui import StudioAssetCache


class WordSafeTimelineRenderer(ShadowPolishedTimelineRenderer):
    """Keep ordinary words intact before considering character-level wrapping."""

    def _make_text_plan(
        self,
        primary: str,
        secondary: str,
        card_width: int,
        top_height: int,
        animated_scale: float,
        manual_scale: float,
    ) -> dict[str, object]:
        plan = super()._make_text_plan(
            primary,
            secondary,
            card_width,
            top_height,
            animated_scale,
            manual_scale,
        )

        probe = Image.new("RGB", (8, 8))
        draw = ImageDraw.Draw(probe)
        primary_font = plan["primary_font"]
        secondary_font = plan["secondary_font"]

        longest_primary = max(
            (self._text_width(draw, word, primary_font) for word in primary.strip().split()),
            default=0,
        )
        longest_secondary = max(
            (self._text_width(draw, word, secondary_font) for word in secondary.strip().split()),
            default=0,
        )
        longest_word = max(longest_primary, longest_secondary)

        # The text area is roughly 82% of the polygon body. Widen enough to keep a
        # normal word whole, but retain the existing hard card-width limit for truly
        # extreme unbroken strings such as URLs.
        current_width = int(plan["width"])
        max_body_width = max(current_width, round(card_width * 0.86 * max(1.0, manual_scale)))
        max_body_width = min(max_body_width, round(card_width * 1.05))
        whole_word_width = math.ceil(longest_word / 0.82) if longest_word else current_width
        body_width = min(max_body_width, max(current_width, whole_word_width))

        if body_width <= current_width:
            return plan

        inner_width = max(12, round(body_width * 0.82))
        primary_lines = self._wrap_everything(draw, primary, primary_font, inner_width)
        secondary_lines = self._wrap_everything(draw, secondary, secondary_font, inner_width)

        primary_size = int(plan["primary_size"])
        secondary_size = int(plan["secondary_size"])
        primary_line_height = self._line_height(draw, primary_font)
        secondary_line_height = self._line_height(draw, secondary_font)
        primary_spacing = max(2, round(primary_size * 0.11))
        secondary_spacing = max(1, round(secondary_size * 0.10))
        primary_block = (
            len(primary_lines) * primary_line_height
            + max(0, len(primary_lines) - 1) * primary_spacing
        )
        secondary_block = (
            len(secondary_lines) * secondary_line_height
            + max(0, len(secondary_lines) - 1) * secondary_spacing
        )
        gap = max(4, round(int(plan["height"]) * 0.055)) if primary_lines and secondary_lines else 0
        required_height = (
            round((primary_block + secondary_block + gap) / 0.68)
            if primary_block or secondary_block
            else int(plan["height"])
        )

        plan["width"] = body_width
        plan["height"] = max(int(plan["height"]), required_height)
        plan["primary_lines"] = primary_lines
        plan["secondary_lines"] = secondary_lines
        return plan


# Preview and export must share the exact same whole-word-safe renderer.
exporter_module.TimelineRenderer = WordSafeTimelineRenderer


class WordSafeMainWindow(ShadowPolishedMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.renderer = WordSafeTimelineRenderer(StudioAssetCache())
        self.statusBar().showMessage("Ready · whole-word text fit · subtle Illustrated shadows")
        self.update_preview()

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = WordSafeTimelineRenderer(StudioAssetCache())
            if hasattr(self, "preview_debounce"):
                self.preview_debounce.start()
