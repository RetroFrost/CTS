from __future__ import annotations

"""Second Canary renderer pass: source-measured badge-bound alignment.

This remains entirely inside the clean Canary renderer family.  It subclasses
Canary v1 only to keep the already verified card/outro implementation and
replaces the post-opening badge compositor with dense MP4 measurements.
"""

from PIL import Image

from . import canary_reference as ref
from .canary_badge_reference import continuous_badge_red_bounds
from .canary_renderer import FrameRenderer as CanaryFrameRendererV1, clamp


# Effective red polygon bounds inside Canary v1's 469x400 badge source,
# measured from v1 output before its old transparent crop was applied.
_SOURCE_RED_LEFT = 72.0
_SOURCE_RED_TOP = 13.0
_SOURCE_RED_WIDTH = 330.0
_SOURCE_RED_HEIGHT = 373.0


class FrameRenderer(CanaryFrameRendererV1):
    """Canary renderer with direct continuous-badge source-frame bounds."""

    def _draw_source_to_red_bounds(
        self,
        canvas: Image.Image,
        card,
        body_x: int,
        local_frame: int,
        red_bounds: tuple[int, int, int, int],
    ) -> None:
        x_offset, y, width, height = red_bounds
        if width <= 0 or height <= 0:
            return

        # By local frame 200 both value lines are fully present in the source.
        # Keep the measured gloss clock, but never re-introduce a geometry ease.
        shine = clamp((local_frame - 205) / 40.0) if 205 <= local_frame <= 245 else -1.0
        source = self._badge_source(card, 1.0, shine)

        scale_x = width / _SOURCE_RED_WIDTH
        scale_y = height / _SOURCE_RED_HEIGHT
        scaled_width = max(1, int(round(source.width * scale_x)))
        scaled_height = max(1, int(round(source.height * scale_y)))
        scaled = source.resize((scaled_width, scaled_height), Image.Resampling.LANCZOS)

        destination_x = int(round(body_x + x_offset - _SOURCE_RED_LEFT * scale_x))
        destination_y = int(round(y - _SOURCE_RED_TOP * scale_y))
        if destination_x >= canvas.width or destination_y >= canvas.height:
            return
        if destination_x + scaled.width <= 0 or destination_y + scaled.height <= 0:
            return
        canvas.paste(scaled.convert("RGB"), (destination_x, destination_y), scaled.getchannel("A"))

    def _draw_badge(self, canvas, project, index: int, body_x: int, frame: int) -> None:
        # Opening cards keep the separately measured opening affine/stage path.
        if index < 4:
            super()._draw_badge(canvas, project, index, body_x, frame)
            return
        if not getattr(project.settings, "show_badges", True):
            return
        card = project.cards[index]
        if not str(card.value or "").strip():
            return

        local_frame = int(frame) - ref.card_start_frame(index)
        precise = continuous_badge_red_bounds(local_frame)
        if precise is not None:
            self._draw_source_to_red_bounds(canvas, card, body_x, local_frame, precise)
            return

        # Frames before 200 are still partly above the 1080p canvas.  The v1
        # full-shell table retains the correct off-screen clipping for them.
        super()._draw_badge(canvas, project, index, body_x, frame)
