from __future__ import annotations

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from . import renderer as _base
from .models import Card, Project
from .watchdata_release import ReleaseWatchDataFrameRenderer
from .watchdata_renderer import POPPINS_EXTRA_BOLD, POPPINS_SEMI_BOLD


# Badge contract measured directly from the supplied 1920x1080 / 60 FPS
# Evolution Of Language reference.  Keep these in source-plane pixels: the
# affine animation and stage scaling are applied afterwards by _warp_badge.
_REFERENCE_BADGE_POLYGON = (
    (243.0, 33.0),
    (391.0, 118.0),
    (391.0, 290.0),
    (245.0, 374.0),
    (96.0, 290.0),
    (96.0, 118.0),
)
_REFERENCE_BADGE_CENTER = (243.5, 203.5)
_REFERENCE_BADGE_FILL = (194, 0, 12)
_REFERENCE_BADGE_OUTLINE = (158, 0, 8, 118)

# Every badge keeps the original large emphasis size. Their measured vertical
# fall stays intact, but they never grow or shrink to highlight themselves.
_FIXED_BADGE_SCALE = 325.0 / 298.0

# A later badge is not visibly on-screen at its nominal animation start.  The
# source polygon remains entirely above the frame until roughly local f156.
# The preceding badge begins its measured shrink only once that incoming shell
# is actually visible.  It reaches the next emphasis stage about 28 frames
# later (e.g. source f904..f932 for 8000 BC -> 6600 BC).

# The old renderer stretched the gloss over ~56-60 frames.  Dense source-frame
# inspection shows a much quicker pass: opening f108..f132 and later badges
# about local f208..f240.  These frame clocks are intentionally independent of
# the text-entry clock.
_OPENING_SHINE_START_FRAME = 108
_OPENING_SHINE_END_FRAME = 133
_LATER_SHINE_START_FRAME = 208
_LATER_SHINE_END_FRAME = 241

# Measured top-edge centre of the diagonal shine.  The lower edge follows the
# same band ~182 px to the left.  Explicit samples avoid inventing a second
# easing curve on top of the measured motion.
_SHINE_TOP_X = (
    (0.00, 112.0),
    (0.08, 140.0),
    (0.16, 168.0),
    (0.30, 195.0),
    (0.38, 215.0),
    (0.46, 235.0),
    (0.54, 261.0),
    (0.62, 295.0),
    (0.69, 335.0),
    (0.77, 375.0),
    (0.85, 412.0),
    (0.92, 454.0),
    (1.00, 540.0),
)


def _linear_body_progress(local_time: float) -> float:
    """Interpolate the measured opening-body samples without adding easing."""
    value = max(0.0, float(local_time))
    points = _base.BODY_PROGRESS_KEYFRAMES
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if value <= x1:
            amount = (value - x0) / max(1e-9, x1 - x0)
            return _base.lerp(y0, y1, amount)
    return points[-1][1]


def _sample(points: tuple[tuple[float, float], ...], value: float) -> float:
    value = float(value)
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if value <= x1:
            amount = (value - x0) / max(1e-9, x1 - x0)
            return _base.lerp(y0, y1, amount)
    return points[-1][1]


class StrictReferenceFrameRenderer(ReleaseWatchDataFrameRenderer):
    """Frame-locked renderer for the supplied reference movie.

    The badge is treated as a hard reference element: source geometry, colour,
    typography centre, emphasis stages and gloss clocks are measured values.
    Generic easing remains only where the reference itself has not supplied a
    denser sample table.
    """

    def _badge_shell(self) -> Image.Image:
        key = "strict-reference-badge-v3"
        cached = self._badge_shell_cache.get(key)
        if cached is not None:
            return cached.copy()

        layer = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        polygon = [(round(x), round(y)) for x, y in _REFERENCE_BADGE_POLYGON]

        # The reference has a soft lower-right shadow.  Keep it separate from
        # the red face so animation/scale transforms preserve the same blur.
        shadow_mask = Image.new("L", _base.BADGE_SOURCE_SIZE, 0)
        ImageDraw.Draw(shadow_mask).polygon(
            [(x + 6, y + 8) for x, y in polygon],
            fill=150,
        )
        shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(8.0))
        shadow = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 112))
        shadow.putalpha(
            ImageChops.multiply(
                shadow_mask,
                Image.new("L", _base.BADGE_SOURCE_SIZE, 182),
            )
        )
        layer.alpha_composite(shadow)

        draw = ImageDraw.Draw(layer)
        draw.polygon(polygon, fill=(*_REFERENCE_BADGE_FILL, 255))
        draw.line(
            polygon + [polygon[0]],
            fill=_REFERENCE_BADGE_OUTLINE,
            width=1,
            joint="curve",
        )
        self._badge_shell_cache[key] = layer.copy()
        return layer

    def _text_layout(self, card: Card) -> list[tuple[str, float, int]]:
        lines = self._value_lines(card.value)
        header = " ".join(card.badge_header.upper().split())
        if header and len(lines) == 1:
            return [(header, 110.0, 36), (lines[0], 238.0, 82)]
        if header and len(lines) >= 2:
            return [
                (header, 110.0, 36),
                (lines[0], 215.0, 94),
                (" ".join(lines[1:]), 310.0, 38),
            ]
        if len(lines) == 1:
            return [(lines[0], 199.0, 98)]
        # Settled reference bounds: primary ~71-73 px tall; qualifier ~32-33
        # px tall and nearly the full face width.
        return [(lines[0], 168.0, 98), (lines[1], 243.0, 47)]

    def _draw_badge_text_canonical(
        self,
        layer: Image.Image,
        card: Card,
        age: float,
        force_final: bool = False,
    ) -> None:
        layout = self._text_layout(card)
        for index, (text, target_y, size) in enumerate(layout):
            start = _base.TEXT_START + index * _base.TEXT_LINE_DELAY
            progress = (
                1.0
                if force_final
                else _base.clamp((age - start) / _base.TEXT_LINE_SECONDS)
            )
            if progress <= 0.0:
                continue

            eased = _base.ease_out_cubic(progress)
            y = (
                target_y
                + self._text_landing_offset(age)
                - (1.0 - eased) * 112.0
            )
            alpha = int(255 * _base.clamp(progress * 1.75))
            has_header = bool(card.badge_header.strip())
            filename = POPPINS_EXTRA_BOLD if index == (1 if has_header else 0) else POPPINS_SEMI_BOLD
            font = self._font_named_fitted(filename, text, size, 286)

            text_layer = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
            text_draw = ImageDraw.Draw(text_layer)

            if progress < 0.92:
                trail_length = (1.0 - progress) * 76.0
                for trail_index in range(8, 0, -1):
                    fraction = trail_index / 8.0
                    trail_y = y - trail_length * fraction
                    trail_alpha = int(alpha * (1.0 - fraction) * 0.15)
                    if trail_alpha > 0:
                        text_draw.text(
                            (_REFERENCE_BADGE_CENTER[0], trail_y),
                            text,
                            font=font,
                            fill=(*self.theme.badge_text, trail_alpha),
                            anchor="mm",
                        )

            text_draw.text(
                (_REFERENCE_BADGE_CENTER[0] + 4.0, y + 6.0),
                text,
                font=font,
                fill=(18, 18, 18, int(alpha * 0.34)),
                anchor="mm",
            )
            text_draw.text(
                (_REFERENCE_BADGE_CENTER[0], y),
                text,
                font=font,
                fill=(*self.theme.badge_text, alpha),
                anchor="mm",
            )
            blur = max(0.0, (1.0 - progress) * 5.2)
            if blur > 0.2:
                text_layer = text_layer.filter(ImageFilter.GaussianBlur(blur))
            layer.alpha_composite(text_layer)

    @staticmethod
    def _opening_source_age(local_frame: int) -> float:
        measured = _base.age_opening_badge_age(local_frame)
        if local_frame < _OPENING_SHINE_START_FRAME:
            # Text may continue settling, but the gloss must not start early.
            return min(measured, _base.SHINE_START)
        if local_frame < _OPENING_SHINE_END_FRAME:
            progress = (
                (local_frame - _OPENING_SHINE_START_FRAME)
                / max(1, _OPENING_SHINE_END_FRAME - _OPENING_SHINE_START_FRAME)
            )
            return _base.SHINE_START + progress * (_base.SHINE_SECONDS - 1e-6)
        return _base.SHINE_START + _base.SHINE_SECONDS + 1e-4

    @staticmethod
    def _later_source_age(local_frame: int) -> float:
        measured = _base.age_later_badge_age(local_frame)
        if local_frame < _LATER_SHINE_START_FRAME:
            return min(measured, _base.SHINE_START)
        if local_frame < _LATER_SHINE_END_FRAME:
            progress = (
                (local_frame - _LATER_SHINE_START_FRAME)
                / max(1, _LATER_SHINE_END_FRAME - _LATER_SHINE_START_FRAME)
            )
            return _base.SHINE_START + progress * (_base.SHINE_SECONDS - 1e-6)
        return _base.SHINE_START + _base.SHINE_SECONDS + 1e-4

    def _add_badge_shine(self, layer: Image.Image, age: float) -> None:
        progress = (float(age) - _base.SHINE_START) / _base.SHINE_SECONDS
        if progress <= 0.0 or progress >= 1.0:
            return

        top_x = _sample(_SHINE_TOP_X, progress)
        bottom_x = top_x - 182.0
        polygon = [(round(x), round(y)) for x, y in _REFERENCE_BADGE_POLYGON]

        mask = Image.new("L", _base.BADGE_SOURCE_SIZE, 0)
        ImageDraw.Draw(mask).polygon(polygon, fill=255)

        broad = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        broad_draw = ImageDraw.Draw(broad)
        broad_width = 43.0
        broad_draw.polygon(
            [
                (top_x - broad_width, -80),
                (top_x + broad_width, -80),
                (bottom_x + broad_width, 500),
                (bottom_x - broad_width, 500),
            ],
            fill=(255, 255, 255, 50),
        )
        broad = broad.filter(ImageFilter.GaussianBlur(11.0))

        core = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        core_draw = ImageDraw.Draw(core)
        core_width = 6.0
        core_draw.polygon(
            [
                (top_x - core_width, -80),
                (top_x + core_width, -80),
                (bottom_x + core_width, 500),
                (bottom_x - core_width, 500),
            ],
            fill=(255, 255, 255, 88),
        )
        core = core.filter(ImageFilter.GaussianBlur(2.6))
        broad.alpha_composite(core)
        broad.putalpha(ImageChops.multiply(broad.getchannel("A"), mask))
        layer.alpha_composite(broad)

    def _reference_opening_scale(
        self,
        index: int,
        global_frame: int,
        local_frame: int,
        starts: list[int],
    ) -> float:
        del index, global_frame, local_frame, starts
        return _FIXED_BADGE_SCALE

    @staticmethod
    def _reference_later_scale(
        index: int,
        global_frame: int,
        starts: list[int],
    ) -> float:
        del index, global_frame, starts
        return _FIXED_BADGE_SCALE

    def _draw_badge(
        self,
        canvas: Image.Image,
        project: Project,
        index: int,
        card_x: float,
        global_frame: int,
        starts: list[int],
    ) -> None:
        if not project.settings.show_badges:
            return
        card = project.cards[index]
        if not card.value or index >= len(starts):
            return

        local_frame = int(global_frame) - starts[index]
        if index < 4:
            if local_frame < 35:
                return
            measured_age = _base.age_opening_badge_age(local_frame)
            source = self._badge_source(
                card,
                self._opening_source_age(local_frame),
                sticker_entry=True,
            )
            affine = self._opening_entry_affine(local_frame, measured_age)
            scale = self._reference_opening_scale(
                index,
                int(global_frame),
                local_frame,
                starts,
            )
        else:
            measured_age = _base.age_later_badge_age(local_frame)
            if measured_age < 0.0:
                return
            source = self._badge_source(
                card,
                self._later_source_age(local_frame),
                sticker_entry=False,
            )
            affine = _base.post_badge_fall_affine(measured_age)
            scale = self._reference_later_scale(index, int(global_frame), starts)

        affine = self._compose_source_scale(
            affine,
            scale,
            center=(240.0, 240.0),
        )
        self._warp_badge(canvas, source, card_x, affine)

    def _positions_for_frame(
        self,
        project,
        global_frame: int,
        starts: list[int],
    ) -> dict[int, float]:
        pitch = self._active_profile.layout.slot_pitch
        timeline = self._active_profile.timeline
        frame = int(global_frame)

        # Continuous motion already uses decoded source-frame coordinates in
        # the parent renderer. Keep that exact path unchanged.
        if frame >= timeline.continuous_start_frame and len(project.cards) > 4:
            return super()._positions_for_frame(project, frame, starts)

        active = -1
        for index, start_frame in enumerate(starts[:4]):
            if frame >= start_frame:
                active = index
            else:
                break
        if active < 0:
            return {}

        positions = {index: index * pitch for index in range(active)}
        local_seconds = (frame - starts[active]) / 60.0
        movement = _linear_body_progress(local_seconds)
        if active == 0:
            positions[0] = _base.lerp(-pitch, 0.0, movement)
        else:
            positions[active] = _base.lerp(
                (active - 1) * pitch,
                active * pitch,
                movement,
            )
        return positions

    def _credits_x_for_frame(
        self,
        global_frame: int,
        starts: list[int],
    ) -> float | None:
        pitch = self._active_profile.layout.slot_pitch
        frame = int(global_frame)
        active = -1
        for index, start_frame in enumerate(starts[:4]):
            if frame >= start_frame:
                active = index
            else:
                break

        if active < 0:
            return float(_base.REFERENCE_WIDTH)

        local_seconds = (frame - starts[active]) / 60.0
        movement = _linear_body_progress(local_seconds)
        if active == 0:
            return _base.lerp(
                _base.REFERENCE_WIDTH,
                _base.REFERENCE_WIDTH - pitch,
                movement,
            )
        if active < 3:
            return float(_base.REFERENCE_WIDTH - pitch)
        if active == 3:
            return _base.lerp(
                _base.REFERENCE_WIDTH - pitch,
                _base.REFERENCE_WIDTH,
                movement,
            )
        return None
