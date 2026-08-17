from __future__ import annotations

import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from .exact_reference_frames import continuous_card_x
from .reference_motion import age_later_badge_age, age_opening_badge_age, continuous_shift
from .renderer import (
    BADGE_ACTIVE_SCALE,
    BADGE_CENTER,
    BADGE_ENTRY_END,
    BADGE_POLYGON,
    BADGE_SOURCE_SIZE,
    REFERENCE_HEIGHT,
    REFERENCE_WIDTH,
    FrameRenderer,
    badge_entry_affine,
    post_badge_fall_affine,
)


LAB_NAME = "Gemini Reference Experiment"
LAB_VERSION = "2.0.5-lab-gemini1"


class LabFrameRenderer(FrameRenderer):
    """Experimental renderer for ideas which intentionally deviate from 2.0.5.

    The reviewed 2.0.5 renderer remains untouched. This subclass is used only
    by the Lab application and deliberately tests stronger badge depth,
    integer-locked card transforms, a wider off-screen buffer, a centre-focus
    badge pop, a damped final conveyor settle and full-frame output scaling.
    """

    def _badge_shell(self) -> Image.Image:
        shape = "lab-gemini|" + self._active_profile.layout.badge_shape
        cached = self._badge_shell_cache.get(shape)
        if cached is not None:
            return cached.copy()

        polygon = [(round(x), round(y)) for x, y in BADGE_POLYGON]
        layer = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))

        # Lab experiment: stronger depth without inventing a white keyline.
        shadow_mask = Image.new("L", BADGE_SOURCE_SIZE, 0)
        shadow_draw = ImageDraw.Draw(shadow_mask)
        shadow_draw.polygon([(x + 5, y + 7) for x, y in polygon], fill=180)
        shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(6.0))
        shadow = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 92))
        shadow.putalpha(ImageChops.multiply(shadow_mask, Image.new("L", BADGE_SOURCE_SIZE, 145)))
        layer.alpha_composite(shadow)

        draw = ImageDraw.Draw(layer)
        draw.polygon(polygon, fill=(*self.theme.badge, 255))
        # Keep only the subtle dark edge. The supplied reference has no thick
        # white outline around the badge.
        draw.line(polygon + [polygon[0]], fill=(*self.theme.badge_dark, 170), width=2, joint="curve")
        self._badge_shell_cache[shape] = layer.copy()
        return layer

    @staticmethod
    def _lab_ease_out_exponential(value: float) -> float:
        p = max(0.0, min(1.0, float(value)))
        if p <= 0.0:
            return 0.0
        if p >= 1.0:
            return 1.0
        raw = 1.0 - 2.0 ** (-8.0 * p)
        return raw / (1.0 - 2.0 ** -8.0)

    def _raw_continuous_x(self, frame: int, card_index: int, pitch: int) -> float:
        x = continuous_card_x(self._active_profile.model_id, int(frame), card_index)
        if x is None:
            shift = continuous_shift(self._active_profile.model_id, int(frame))
            x = (card_index - shift) * pitch
        return float(x)

    def _positions_for_frame(self, project, global_frame: int, starts: list[int]) -> dict[int, float]:
        pitch = self._active_profile.layout.slot_pitch
        timeline = self._active_profile.timeline
        frame = int(global_frame)

        if frame >= timeline.continuous_start_frame and len(project.cards) > 4:
            # Gemini experiment: keep two complete card pitches pre-rendered on
            # each side and quantise the shared card transform once. Body,
            # badge and foreground artwork therefore inherit the same integer X.
            content_end = timeline.content_end_frame(len(project.cards))
            settle_end = max(timeline.continuous_start_frame, content_end - (17 if len(project.cards) == timeline.canonical_card_count else 1))
            settle_start = max(timeline.continuous_start_frame, settle_end - 96)
            positions: dict[int, float] = {}
            for card_index in range(len(project.cards)):
                if settle_start <= frame <= settle_end:
                    start_x = self._raw_continuous_x(settle_start, card_index, pitch)
                    end_x = self._raw_continuous_x(settle_end, card_index, pitch)
                    p = self._lab_ease_out_exponential((frame - settle_start) / max(1, settle_end - settle_start))
                    x = start_x + (end_x - start_x) * p
                else:
                    x = self._raw_continuous_x(frame, card_index, pitch)
                x = float(round(x))
                if -2 * pitch < x < REFERENCE_WIDTH + 2 * pitch:
                    positions[card_index] = x
            return positions

        # Opening still follows the measured 2.0.5 curve, but all layers share
        # a single integer X to test whether pixel locking improves stability.
        return {index: float(round(x)) for index, x in super()._positions_for_frame(project, frame, starts).items()}

    @staticmethod
    def _centre_pop_scale(card_x: float) -> float:
        badge_centre_x = float(card_x) + BADGE_CENTER[0]
        left = REFERENCE_WIDTH * 0.35
        right = REFERENCE_WIDTH * 0.65
        if badge_centre_x <= left or badge_centre_x >= right:
            return 1.0
        p = (badge_centre_x - left) / max(1.0, right - left)
        return 1.0 + 0.08 * math.sin(math.pi * p)

    def _draw_badge(self, canvas, project, index: int, card_x: float, global_frame: int, starts: list[int]) -> None:
        if not project.settings.show_badges:
            return
        card = project.cards[index]
        if not card.value or index >= len(starts):
            return
        local_frame = int(global_frame) - starts[index]

        if index < 4:
            if local_frame < 35:
                return
            age = age_opening_badge_age(local_frame)
            source = self._badge_source(card, age, sticker_entry=True)
            affine = badge_entry_affine(age)
        else:
            age = age_later_badge_age(local_frame)
            if age < 0.0:
                return
            source = self._badge_source(card, age, sticker_entry=False)
            affine = post_badge_fall_affine(age)

        scale = self._age_deemphasis_scale(index, int(global_frame), starts)
        scale *= self._centre_pop_scale(card_x)
        affine = self._compose_source_scale(affine, scale)
        self._warp_badge(canvas, source, float(round(card_x)), affine)

    def _draw_card_body_uncached(self, canvas, card, x: float, width: int, height: int) -> None:
        super()._draw_card_body_uncached(canvas, card, x, width, height)

        # Lab experiment: subtle dark separation only. Do not add white rules
        # which are absent from the supplied reference.
        title = " ".join(str(card.title or "").split())
        description = " ".join(str(card.description or "").split())
        layout = self._active_profile.layout
        description_height = max(0, layout.body_height - layout.description_top) if description else 0
        rule_height = layout.divider_width if description else 0
        title_height = min(layout.title_height, max(0, height - description_height - rule_height)) if title else 0
        image_height = max(0, height - title_height - rule_height - description_height)
        ix = int(round(x))
        draw = ImageDraw.Draw(canvas)
        if title_height:
            draw.line((ix, image_height, ix + width - 1, image_height), fill=(30, 30, 30), width=2)
        if description_height:
            desc_top = image_height + title_height + rule_height
            draw.line((ix, desc_top, ix + width - 1, desc_top), fill=(35, 35, 35), width=3)

    def render(self, project, seconds: float, output_size: tuple[int, int] | None = None) -> Image.Image:
        # Render the canonical full 1920x1080 scene first. The stable build uses
        # aspect-preserving contain/pillarbox for unusual output sizes. Lab
        # deliberately stretches the complete scene to the requested viewport
        # so no cards or credits are discarded and no black bars are introduced.
        base = super().render(project, seconds, None)
        if output_size and output_size != base.size:
            target_w = max(2, int(output_size[0]))
            target_h = max(2, int(output_size[1]))
            return base.resize((target_w, target_h), Image.Resampling.LANCZOS)
        return base
