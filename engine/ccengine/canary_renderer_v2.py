from __future__ import annotations

"""Second Canary renderer pass: source-measured badge-bound alignment.

This remains entirely inside the clean Canary renderer family. It subclasses
Canary v1 only to keep the already verified card/outro implementation and
replaces the badge compositor wherever dense source measurements exist.
"""

from PIL import Image

from . import canary_reference as ref
from .canary_badge_reference import continuous_badge_red_bounds
from .canary_renderer import FrameRenderer as CanaryFrameRendererV1, clamp


# Effective visible-red bounds inside Canary v1's 469x400 badge source. These
# coordinates were recovered from v1's rendered shell before its transparent
# crop was applied, so mapping them to source measurements preserves the
# surrounding shadow instead of cropping it away.
_SOURCE_RED_LEFT = 72.0
_SOURCE_RED_TOP = 13.0
_SOURCE_RED_WIDTH = 330.0
_SOURCE_RED_HEIGHT = 373.0


class FrameRenderer(CanaryFrameRendererV1):
    """Canary renderer with direct source-frame badge-bound placement."""

    def _place_source_on_red_bounds(
        self,
        canvas: Image.Image,
        source: Image.Image,
        body_x: int,
        red_bounds: tuple[int, int, int, int],
    ) -> None:
        x_offset, y, width, height = red_bounds
        if width <= 0 or height <= 0:
            return

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

    def _draw_continuous_precise(
        self,
        canvas: Image.Image,
        card,
        body_x: int,
        local_frame: int,
        red_bounds: tuple[int, int, int, int],
    ) -> None:
        # The source value is fully present by local frame 200. Gloss crosses
        # the face on the measured continuous-card clock.
        shine = clamp((local_frame - 205) / 40.0) if 205 <= local_frame <= 245 else -1.0
        source = self._badge_source(card, 1.0, shine)
        self._place_source_on_red_bounds(canvas, source, body_x, red_bounds)

    def _draw_opening_stage_precise(
        self,
        canvas: Image.Image,
        card,
        body_x: int,
        effective_local_frame: int,
        red_bounds: tuple[int, int, int, int],
    ) -> None:
        # At the old affine->stage handoff (effective frame 120) v1 restarted
        # text_progress from zero, visibly deleting the value. The reference
        # keeps both value lines continuously present. Its opening gloss is
        # still crossing the hex around f120 and has cleared by ~f131.
        shine = clamp((effective_local_frame - 100) / 30.0) if 100 <= effective_local_frame <= 130 else -1.0
        source = self._badge_source(card, 1.0, shine)
        self._place_source_on_red_bounds(canvas, source, body_x, red_bounds)

    def _draw_badge(self, canvas, project, index: int, body_x: int, frame: int) -> None:
        if not getattr(project.settings, "show_badges", True):
            return
        card = project.cards[index]
        if not str(card.value or "").strip():
            return

        local_frame = int(frame) - ref.card_start_frame(index)

        if index < 4:
            delay = ref.FOURTH_OPENING_BADGE_DELAY if index == 3 else 0
            effective = local_frame - delay
            # Preserve v1's dense affine table while the opening badge is
            # actually deforming. Only the settled/staged handoff is replaced.
            if effective < ref.OPENING_ENTRY_LAST_LOCAL_FRAME:
                super()._draw_badge(canvas, project, index, body_x, frame)
                return
            stage = ref.opening_badge_stage(index, int(frame))
            if stage is None:
                return
            self._draw_opening_stage_precise(canvas, card, body_x, effective, stage)
            return

        precise = continuous_badge_red_bounds(local_frame)
        if precise is not None:
            self._draw_continuous_precise(canvas, card, body_x, local_frame, precise)
            return

        # Frames before 200 are still partly above the 1080p canvas. V1's
        # full-shell table retains the correct off-screen clipping there.
        super()._draw_badge(canvas, project, index, body_x, frame)
