from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

from . import renderer as _base
from .models import Card, Project


# Settled active-badge red contour measured directly from the supplied
# 1920x1080 WatchData-style reference at source frame 528. Coordinates are
# card-local in the canonical 480x430 badge source plane.
WATCHDATA_BADGE_POLYGON: tuple[tuple[float, float], ...] = (
    (232.0, 32.0),
    (385.0, 116.0),
    (385.0, 289.0),
    (239.0, 375.0),
    (88.0, 289.0),
    (88.0, 116.0),
)
WATCHDATA_BADGE_CENTER = (236.5, 203.5)
WATCHDATA_BADGE_FILL = (211, 8, 9)

# These are generated at build time from the official google/fonts Poppins
# directory and bundled into both Windows and Android. Keeping the names here
# makes a missing package font an obvious fallback rather than silently using a
# platform-specific system typeface.
POPPINS_EXTRA_BOLD = "Poppins-ExtraBold.ttf"
POPPINS_SEMI_BOLD = "Poppins-SemiBold.ttf"
POPPINS_MEDIUM = "Poppins-Medium.ttf"


class WatchDataFrameRenderer(_base.FrameRenderer):
    """Reference-fidelity renderer for the WatchData comparison template.

    The timeline/conveyor math remains inherited from the measured renderer.
    This class corrects the rendered geometry and typography which were still
    visibly different in the 2.0.2/2.0.3 exported MP4.
    """

    @staticmethod
    def _font_root() -> Path:
        return Path(__file__).resolve().parent / "fonts"

    @classmethod
    def _bundled_font_path(cls, filename: str) -> Path:
        return cls._font_root() / filename

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
        custom = str(getattr(settings, setting_name, "") or "").strip() if settings is not None else ""
        if custom:
            return super()._font(size, bold, role)

        if role in {"title", "badge"}:
            filename = POPPINS_EXTRA_BOLD
        elif role == "credits" and bold:
            filename = POPPINS_SEMI_BOLD
        else:
            filename = POPPINS_MEDIUM
        candidate = self._bundled_font_path(filename)
        if candidate.is_file():
            return self._load_font(str(candidate), max(1, int(size)))
        # Source/test trees deliberately do not commit generated font binaries.
        # Builds fetch them before packaging; this fallback keeps source tests
        # runnable without changing platform geometry code.
        return super()._font(size, bold, role)

    def _font_from_bundle(
        self,
        filename: str,
        size: int,
        *,
        fallback_bold: bool = False,
        role: str = "credits",
    ) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        candidate = self._bundled_font_path(filename)
        if candidate.is_file():
            return self._load_font(str(candidate), max(1, int(size)))
        return self._font(size, fallback_bold, role)

    def _badge_shell(self) -> Image.Image:
        key = "watchdata-v204-settled"
        cached = self._badge_shell_cache.get(key)
        if cached is not None:
            return cached.copy()

        layer = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        polygon = [(round(x), round(y)) for x, y in WATCHDATA_BADGE_POLYGON]

        # The source has a soft lower/right drop shadow, but the face itself is
        # flat red once the moving shine has passed.
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
        draw.polygon(polygon, fill=(*WATCHDATA_BADGE_FILL, 255))
        draw.line(
            polygon + [polygon[0]],
            fill=(171, 0, 6, 105),
            width=1,
            joint="curve",
        )
        self._badge_shell_cache[key] = layer.copy()
        return layer

    def _text_layout(self, card: Card) -> list[tuple[str, float, int]]:
        lines = self._value_lines(card.value)
        if len(lines) == 1:
            return [(lines[0], 199.0, 104)]
        # Frame-528 reference measurement: primary glyph bounds are roughly
        # 72 px high; the qualifier is roughly 32 px high and spans almost the
        # entire 298 px badge width.
        return [(lines[0], 168.0, 104), (lines[1], 243.0, 46)]

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
            progress = 1.0 if force_final else _base.clamp((age - start) / _base.TEXT_LINE_SECONDS)
            if progress <= 0.0:
                continue

            eased = _base.ease_out_cubic(progress)
            y = target_y + self._text_landing_offset(age) - (1.0 - eased) * 112.0
            alpha = int(255 * _base.clamp(progress * 1.75))
            font = self._font_fitted(text, size, 280)

            text_layer = Image.new("RGBA", _base.BADGE_SOURCE_SIZE, (0, 0, 0, 0))
            text_draw = ImageDraw.Draw(text_layer)
            if progress < 0.92:
                trail_length = (1.0 - progress) * 76.0
                for trail_index in range(8, 0, -1):
                    fraction = trail_index / 8.0
                    trail_y = y - trail_length * fraction
                    trail_alpha = int(alpha * (1.0 - fraction) * 0.18)
                    if trail_alpha > 0:
                        text_draw.text(
                            (WATCHDATA_BADGE_CENTER[0], trail_y),
                            text,
                            font=font,
                            fill=(*self.theme.badge_text, trail_alpha),
                            anchor="mm",
                        )

            text_draw.text(
                (WATCHDATA_BADGE_CENTER[0] + 3, y + 6),
                text,
                font=font,
                fill=(15, 15, 15, int(alpha * 0.46)),
                anchor="mm",
            )
            text_draw.text(
                (WATCHDATA_BADGE_CENTER[0], y),
                text,
                font=font,
                fill=(*self.theme.badge_text, alpha),
                anchor="mm",
            )
            blur = max(0.0, (1.0 - progress) * 5.2)
            if blur > 0.2:
                text_layer = text_layer.filter(ImageFilter.GaussianBlur(blur))
            layer.alpha_composite(text_layer)

    def _badge_source(self, card: Card, age: float, *, sticker_entry: bool) -> Image.Image:
        # Never cache a shine/streak frame as the settled badge. The released
        # Android export showed a diagonal highlight stuck on old badges; the
        # reference returns to a flat red face after the sweep is gone.
        settled = age >= (_base.SHINE_START + _base.SHINE_SECONDS + 1e-6)
        custom_font = getattr(self._active_settings, "font_badge", "") if self._active_settings else ""
        cache_key = (
            "watchdata-v204|" + self._active_profile.model_id + "|" + card.value.upper().strip(),
            custom_font,
        )
        if settled and cache_key in self._badge_final_cache:
            cached = self._badge_final_cache.pop(cache_key)
            self._badge_final_cache[cache_key] = cached
            return cached.copy()

        layer = self._badge_shell()
        if not settled:
            if sticker_entry:
                self._add_entry_motion_streak(layer, age)
            self._draw_badge_text_canonical(layer, card, age, force_final=False)
            self._add_badge_shine(layer, age)
            return layer

        self._draw_badge_text_canonical(layer, card, age, force_final=True)
        self._badge_final_cache[cache_key] = layer.copy()
        while len(self._badge_final_cache) > self._max_badge_cache:
            self._badge_final_cache.popitem(last=False)
        return layer

    def _draw_card_body_uncached(
        self,
        canvas: Image.Image,
        card: Card,
        x: float,
        width: int,
        height: int,
    ) -> None:
        # Preserve all measured body/image geometry, then repaint the two text
        # bands with WatchData typography. This avoids touching the exact card
        # conveyor or artwork compositing code.
        super()._draw_card_body_uncached(canvas, card, x, width, height)

        ix = int(round(x))
        title = " ".join(str(card.title or "").split())
        description = " ".join(str(card.description or "").split())
        has_title = bool(title)
        has_description = bool(description)
        layout = self._active_profile.layout
        description_height = max(0, layout.body_height - layout.description_top) if has_description else 0
        rule_height = layout.divider_width if has_description else 0
        title_height = min(layout.title_height, max(0, height - description_height - rule_height)) if has_title else 0
        image_height = max(0, height - title_height - rule_height - description_height)
        title_top = image_height
        title_bottom = title_top + title_height
        desc_top = title_bottom + rule_height

        draw = ImageDraw.Draw(canvas)
        if has_title:
            draw.rectangle((ix, title_top, ix + width, title_bottom), fill=layout.title_background)
            self._draw_fitted_text_block(
                draw,
                title,
                (ix + 12, title_top + 2, ix + width - 12, title_bottom - 2),
                maximum_size=46,
                minimum_size=28,
                max_lines=2,
                bold=True,
                role="title",
                fill=self.theme.title_text,
            )

        if has_description and desc_top < height:
            draw.rectangle((ix, desc_top, ix + width, height), fill=layout.description_background)
            available_width = max(24, width - 34)
            available_height = max(12, height - desc_top - 10)
            chosen_font = self._font(20, False, "description")
            chosen_lines: list[str] = []
            chosen_line_height = 23
            for font_size in range(29, 19, -1):
                candidate = self._font(font_size, False, "description")
                line_height = max(22, int(round(font_size * 1.13)))
                max_lines = max(1, min(4, available_height // line_height))
                lines = self._wrapped_lines(draw, description, candidate, available_width, max_lines)
                if lines and len(lines) * line_height <= available_height:
                    chosen_font = candidate
                    chosen_lines = lines
                    chosen_line_height = line_height
                    break
            if not chosen_lines:
                chosen_lines = self._wrapped_lines(draw, description, chosen_font, available_width, 1)
            total = len(chosen_lines) * chosen_line_height
            y = desc_top + max(4, ((height - desc_top) - total) // 2)
            for line in chosen_lines:
                bbox = draw.textbbox((0, 0), line, font=chosen_font)
                text_width = bbox[2] - bbox[0]
                draw.text(
                    (ix + (width - text_width) / 2.0, y),
                    line,
                    font=chosen_font,
                    fill=self.theme.description_text,
                )
                y += chosen_line_height

        # Repaint the slot borders because the band repaint intentionally
        # covers the old fallback-font text right to the edges.
        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line((ix + width - 1, 0, ix + width - 1, height), fill=self.theme.divider, width=2)

    def _draw_credits_panel(self, canvas: Image.Image, left: float, project: Project) -> None:
        if not project.settings.credits_enabled:
            return
        layout = self._active_profile.layout
        panel_width = layout.body_width
        panel_height = layout.body_height
        x = int(round(left + layout.body_inset))
        if x >= canvas.width or x + panel_width <= 0:
            return

        settings = project.settings
        panel = Image.new("RGB", (panel_width, panel_height), (28, 28, 29))
        draw = ImageDraw.Draw(panel)
        white = (248, 248, 248)
        small = self._font(23, False, "credits")

        top_text = settings.credits_top_text.strip()
        if top_text:
            lines = self._wrapped_lines(draw, top_text, small, panel_width - 70, 4)
            y = 34
            for line in lines:
                draw.text((panel_width / 2, y), line, font=small, fill=white, anchor="ma")
                y += 30

        draw.line((50, 232, panel_width - 50, 232), fill=(184, 184, 184), width=2)
        if settings.credits_heading.strip():
            heading = self._font_from_bundle(POPPINS_EXTRA_BOLD, 50, fallback_bold=True)
            draw.text(
                (panel_width / 2, 300),
                settings.credits_heading,
                font=heading,
                fill=(255, 255, 255),
                anchor="mm",
            )

        role_font = self._font(24, True, "credits")
        value_font = self._font(23, False, "credits")
        rows: list[tuple[str, str]] = []
        project_name = settings.credits_project_name.strip()
        if project_name:
            rows.append(("", project_name))
        if settings.credits_created_with_label.strip() or settings.credits_created_with_value.strip():
            rows.append((settings.credits_created_with_label.strip(), settings.credits_created_with_value.strip()))
        if settings.credits_design_label.strip() or settings.credits_design_value.strip():
            rows.append((settings.credits_design_label.strip(), settings.credits_design_value.strip()))

        y = 388
        for role_text, value_text in rows:
            if role_text:
                fitted_role = self._font_for_width(role_text, 24, panel_width - 60, bold=True, role="credits", minimum=17)
                draw.text((panel_width / 2, y), role_text, font=fitted_role, fill=white, anchor="mm")
                y += 35
            if value_text:
                fitted_value = self._font_for_width(value_text, 23, panel_width - 60, bold=False, role="credits", minimum=16)
                draw.text((panel_width / 2, y), value_text, font=fitted_value, fill=white, anchor="mm")
                y += 64
            else:
                y += 28

        footer = settings.credits_footer.strip()
        if footer:
            footer_font = self._font_for_width(footer, 13, panel_width - 60, bold=False, role="credits", minimum=9)
            footer_lines = self._wrapped_lines(draw, footer, footer_font, panel_width - 60, 4)
            line_height = 16
            start_y = panel_height - 22 - line_height * len(footer_lines)
            for line in footer_lines:
                draw.text((panel_width / 2, start_y), line, font=footer_font, fill=(184, 184, 184), anchor="ma")
                start_y += line_height

        canvas.paste(panel, (x, 0))
