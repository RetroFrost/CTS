from __future__ import annotations

from PIL import Image, ImageDraw

from . import renderer as _base
from .models import Card, Project
from .watchdata_renderer import (
    WATCHDATA_BADGE_POLYGON,
    WatchDataFrameRenderer,
)


class MeasuredWatchDataFrameRenderer(WatchDataFrameRenderer):
    """Final 2.0.4 renderer measured against the supplied WatchData video.

    This layer intentionally leaves the decoded conveyor positions untouched.
    It only corrects the remaining rendered differences found by the 2.0.4
    visual-audit frames: opening badge contour/scale, shine clock, title wrap,
    badge typography and description placement.
    """

    @staticmethod
    def _wd_sample(
        keys: tuple[tuple[float, ...], ...],
        value: float,
    ) -> tuple[float, ...]:
        if value <= keys[0][0]:
            return keys[0][1:]
        if value >= keys[-1][0]:
            return keys[-1][1:]
        for left, right in zip(keys, keys[1:]):
            if value <= right[0]:
                amount = (value - left[0]) / max(1e-9, right[0] - left[0])
                return tuple(
                    _base.lerp(left[index], right[index], amount)
                    for index in range(1, len(left))
                )
        return keys[-1][1:]

    def _text_layout(self, card: Card) -> list[tuple[str, float, int]]:
        lines = self._value_lines(card.value)
        if len(lines) == 1:
            return [(lines[0], 199.0, 98)]
        # Source-frame measurement: active primary glyphs are about 71-73 px
        # high and the qualifier about 31 px high on the 298x344 shell.
        return [(lines[0], 168.0, 98), (lines[1], 243.0, 45)]

    def _opening_entry_affine(
        self,
        local_frame: int,
        age: float,
    ) -> tuple[float, float, float, float, float, float]:
        # Post-affine corrections measured from the actual red contour. They
        # preserve the original skew/rotation during entry while compensating
        # for the corrected 298x344 settled polygon. By f160 the shell is the
        # standard settled badge.
        keys = (
            (35.0, 1.075, 1.000, 0.0, -2.0),
            (40.0, 1.075, 1.000, 0.0, -2.0),
            (60.0, 1.095, 1.050, 1.0, 3.5),
            (80.0, 1.095, 1.050, -1.5, 4.5),
            (100.0, 1.085, 1.100, -2.7, -3.2),
            (110.0, 1.095, 1.100, -3.6, -5.5),
            (120.0, 1.115, 1.075, 1.0, -2.5),
            (130.0, 1.107, 1.090, 0.5, -3.5),
            (145.0, 1.060, 1.050, 0.0, -2.0),
            (155.0, 1.025, 1.020, 0.0, -1.0),
            (160.0, 1.000, 1.000, 0.0, 0.0),
        )
        base_age = min(float(age), _base.BADGE_ENTRY_END)
        affine = _base.badge_entry_affine(base_age)
        if local_frame > 120:
            affine = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        sx, sy, delta_cx, delta_cy = self._wd_sample(keys, float(local_frame))
        if local_frame >= 160:
            return affine

        m00, m01, m10, m11, tx, ty = affine
        transformed = [
            (m00 * x + m01 * y + tx, m10 * x + m11 * y + ty)
            for x, y in WATCHDATA_BADGE_POLYGON
        ]
        min_x = min(point[0] for point in transformed)
        max_x = max(point[0] for point in transformed)
        min_y = min(point[1] for point in transformed)
        max_y = max(point[1] for point in transformed)
        center_x = (min_x + max_x) / 2.0
        center_y = (min_y + max_y) / 2.0
        return (
            m00 * sx,
            m01 * sx,
            m10 * sy,
            m11 * sy,
            sx * tx + (1.0 - sx) * center_x + delta_cx,
            sy * ty + (1.0 - sy) * center_y + delta_cy,
        )

    def _opening_stage_scale(self, local_frame: int) -> float:
        del local_frame
        return _base.BADGE_ACTIVE_SCALE

    @staticmethod
    def _opening_source_age(local_frame: int) -> float:
        measured = _base.age_opening_badge_age(local_frame)
        # The WatchData opening shine begins around f100 and is clear by f160.
        if local_frame < 100:
            return measured
        if local_frame < 160:
            progress = (local_frame - 100.0) / 60.0
            return _base.SHINE_START + progress * _base.SHINE_SECONDS
        return _base.SHINE_START + _base.SHINE_SECONDS + 1e-4

    @staticmethod
    def _later_source_age(local_frame: int) -> float:
        measured = _base.age_later_badge_age(local_frame)
        # First continuous badge settles at global f734/local206; the moving
        # gloss then crosses the shell until approximately f790/local262.
        if local_frame < 206:
            return measured
        if local_frame < 262:
            progress = (local_frame - 206.0) / 56.0
            return _base.SHINE_START + progress * _base.SHINE_SECONDS
        return _base.SHINE_START + _base.SHINE_SECONDS + 1e-4

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
            affine = self._compose_source_scale(
                affine,
                self._opening_stage_scale(local_frame),
                center=(240.0, 240.0),
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
            affine = self._compose_source_scale(
                affine,
                self._age_deemphasis_scale(index, int(global_frame), starts),
                center=(240.0, 240.0),
            )
        self._warp_badge(canvas, source, card_x, affine)

    def _draw_card_body_uncached(
        self,
        canvas: Image.Image,
        card: Card,
        x: float,
        width: int,
        height: int,
    ) -> None:
        # Keep shared artwork geometry, then repaint only the two WatchData text
        # bands. The reference uses a narrower title measure than the full card,
        # producing balanced wraps such as "Ape Noises" / "And Gestures".
        super()._draw_card_body_uncached(canvas, card, x, width, height)

        ix = int(round(x))
        title = " ".join(str(card.title or "").split())
        description = " ".join(str(card.description or "").split())
        has_title = bool(title)
        has_description = bool(description)
        layout = self._active_profile.layout
        description_height = (
            max(0, layout.body_height - layout.description_top)
            if has_description
            else 0
        )
        rule_height = layout.divider_width if has_description else 0
        title_height = (
            min(
                layout.title_height,
                max(0, height - description_height - rule_height),
            )
            if has_title
            else 0
        )
        image_height = max(0, height - title_height - rule_height - description_height)
        title_top = image_height
        title_bottom = title_top + title_height
        desc_top = title_bottom + rule_height
        draw = ImageDraw.Draw(canvas)

        if has_title:
            draw.rectangle(
                (ix, title_top, ix + width, title_bottom),
                fill=layout.title_background,
            )
            text_width = min(380, max(1, width - 24))
            left = ix + (width - text_width) // 2
            self._draw_fitted_text_block(
                draw,
                title,
                (left, title_top - 10, left + text_width, title_bottom - 10),
                maximum_size=50,
                minimum_size=30,
                max_lines=2,
                bold=True,
                role="title",
                fill=self.theme.title_text,
            )

        if has_description and desc_top < height:
            draw.rectangle(
                (ix, desc_top, ix + width, height),
                fill=layout.description_background,
            )
            available_width = max(24, width - 34)
            available_height = max(12, height - desc_top - 8)
            chosen_font = self._font(20, False, "description")
            chosen_lines: list[str] = []
            chosen_line_height = 30
            for font_size in range(29, 19, -1):
                candidate = self._font(font_size, False, "description")
                line_height = max(25, int(round(font_size * 1.12)))
                max_lines = max(1, min(4, available_height // line_height))
                lines = self._wrapped_lines(
                    draw,
                    description,
                    candidate,
                    available_width,
                    max_lines,
                )
                if lines and len(lines) * line_height <= available_height:
                    chosen_font = candidate
                    chosen_lines = lines
                    chosen_line_height = line_height
                    break
            if not chosen_lines:
                chosen_lines = self._wrapped_lines(
                    draw,
                    description,
                    chosen_font,
                    available_width,
                    1,
                )
            y = desc_top + 8
            for line in chosen_lines:
                bbox = draw.textbbox((0, 0), line, font=chosen_font)
                line_width = bbox[2] - bbox[0]
                draw.text(
                    (ix + (width - line_width) / 2.0, y),
                    line,
                    font=chosen_font,
                    fill=self.theme.description_text,
                )
                y += chosen_line_height

        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line(
            (ix + width - 1, 0, ix + width - 1, height),
            fill=self.theme.divider,
            width=2,
        )
