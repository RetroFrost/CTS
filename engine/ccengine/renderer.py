from __future__ import annotations

from dataclasses import dataclass
from functools import lru_cache
from collections import OrderedDict
from pathlib import Path
import os
import platform
import subprocess
import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont, ImageOps

from .models import Card, Project
from .model_registry import MODEL_TYPES_OF_RELATIONSHIPS
from .reference_profiles import get_reference_profile
from .exact_reference_frames import continuous_card_x, relationships_fade_alpha, relationships_last_card_x
from .reference_motion import (
    age_later_badge_age,
    age_opening_badge_age,
    continuous_shift,
    relationships_artwork_reveal,
    relationships_badge_bbox,
    relationships_badge_scale,
    relationships_badge_text_age,
    relationships_description_reveal,
    relationships_shell_visible,
    relationships_title_reveal,
)
from .assets import materialize_remote_asset
from .brand_intro import (
    INTRO_OVERLAY_END_FRAME, render_relationships_intro,
    render_relationships_intro_overlay,
)
from .timing import (
    card_start_frames, card_start_times, content_duration, content_frame_count, locate_segment, seconds_to_frame, total_duration,
)


REFERENCE_WIDTH = 1920
REFERENCE_HEIGHT = 1080
REFERENCE_CARD_WIDTH = 480

# Body and badge timings measured from the supplied 60 FPS reference clip.
BODY_SECONDS = 1.34
BADGE_VISIBLE_START = 0.00
BADGE_ENTRY_END = 2.90

# After the opening four cards, the reference becomes a continuously moving
# conveyor. The incoming card never pauses in the rightmost slot: it keeps
# drifting left while its mostly formed hexagon falls vertically from above.
# The text settle and gloss remain consistent with the opening badges, but the
# sticker-like skew/stretch is used only by cards 1-4.
POST_INITIAL_BADGE_DELAY = 2.06
POST_INITIAL_BADGE_DURATION = 1.10
POST_INITIAL_BADGE_SPEED = BADGE_ENTRY_END / POST_INITIAL_BADGE_DURATION
# Text timing was measured independently from the shell: the first line begins
# while the stretched badge is still settling, then the remaining lines follow
# in quick succession with a vertical motion trail.
TEXT_START = 0.90
TEXT_LINE_DELAY = 0.10
TEXT_LINE_SECONDS = 0.42
# The diagonal gloss begins before the badge is fully settled and clears it in
# just under half a second.
SHINE_START = 1.72
SHINE_SECONDS = 0.52
DEEMPHASIS_SECONDS = 1.00

# The active source badge is about 298 x 344 px. Older badges step down twice,
# exactly as they do when later badges become the focus in the reference.
BADGE_ACTIVE_SCALE = 1.0
BADGE_MEDIUM_SCALE = 272.0 / 298.0
BADGE_SMALL_SCALE = 248.0 / 298.0
BADGE_CENTER = (240.0, 198.0)
BADGE_SOURCE_SIZE = (480, 430)
BADGE_POLYGON = (
    (224.0, 16.0),
    (396.0, 104.0),
    (396.0, 292.0),
    (252.0, 380.0),
    (72.0, 292.0),
    (72.0, 104.0),
)

# Opening-only affine keyframes reconstructed from the actual badge contour.
# They reproduce the sticker-like stretch, clockwise lean, skew and long settle
# used while the first four cards progressively fill the canvas.
# Matrix maps canonical local points to animated local points:
# x' = m00*x + m01*y + tx; y' = m10*x + m11*y + ty.
BADGE_AFFINE_KEYFRAMES = (
    # The first five keys are intentionally extreme. During the opening the
    # badge is already moving while its card body is being revealed, leaving a
    # bright red, motion-blurred slice before the full hexagon comes into view.
    (0.00, 0.3200, 0.0400, -0.4300, 1.0500, -230.00, -220.00),
    (0.10, 0.3300, 0.0400, -0.4300, 1.0500, -190.00, -170.00),
    (0.20, 0.3400, 0.0400, -0.4300, 1.0500, -185.00, -130.00),
    (0.30, 0.3500, 0.0380, -0.4300, 1.0500, -215.00, -115.00),
    (0.40, 0.3600, 0.0360, -0.4300, 1.0500, -154.00, -107.00),
    (0.50, 0.7200, -0.0300, -0.7200, 2.3500, -160.00, -454.00),
    (0.65, 0.9600, -0.0250, -0.5400, 2.0500, -145.00, -325.00),
    (0.80, 1.1818, -0.0169, -0.3636, 1.7548, -150.25, -235.22),
    (0.90, 1.2222, -0.0181, -0.2694, 1.6357, -141.90, -203.87),
    (1.00, 1.2492, 0.0509, -0.2054, 1.5002, -152.51, -164.83),
    (1.20, 1.2559, 0.0158, -0.1010, 1.4099, -121.12, -140.77),
    (1.50, 1.2088, -0.0758, -0.0337, 1.2452, -56.12, -83.62),
    (1.80, 1.1302, -0.0309, -0.0064, 1.1508, -37.57, -46.97),
    (2.30, 1.0808, 0.0209, 0.0000, 1.0698, -25.80, -16.16),
    (2.50, 1.0202, 0.0110, 0.0067, 1.0114, -9.13, -1.95),
    (2.70, 1.0067, 0.0114, -0.0067, 0.9886, -3.95, 5.95),
    (2.90, 1.0000, 0.0000, 0.0000, 1.0000, 0.00, 0.00),
)

# Cards 5 onward do not repeat the opening sticker deformation. The shell is
# already recognizably hexagonal and falls from above, briefly passing its rest
# point before a small rebound. Times use the same 0..2.9 animation-age clock
# as the text and gloss so all three parts remain synchronized.
#
# Each key is: age, scale, vertical_offset. Scale is performed around the badge
# center, so the badge remains horizontally centered over the incoming card.
POST_BADGE_FALL_KEYFRAMES = (
    (0.00, 1.120, -420.0),
    (0.28, 1.118, -376.0),
    (0.55, 1.112, -292.0),
    (0.82, 1.102, -194.0),
    (1.05, 1.090, -105.0),
    (1.25, 1.075, -38.0),
    (1.42, 1.058, 16.0),
    (1.60, 1.034, -9.0),
    (1.80, 1.016, 5.0),
    (2.02, 1.005, -2.0),
    (2.25, 1.000, 0.0),
    (2.90, 1.000, 0.0),
)

# Measured body travel. Sampling this curve is more faithful than a generic
# easing function, especially during the slow last third of every card slide.
BODY_PROGRESS_KEYFRAMES = (
    (0.000, 0.000),
    (0.033, 0.000),
    (0.083, 0.019),
    (0.166, 0.101),
    (0.250, 0.300),
    (0.333, 0.515),
    (0.416, 0.653),
    (0.500, 0.746),
    (0.583, 0.813),
    (0.666, 0.864),
    (0.750, 0.901),
    (0.833, 0.931),
    (0.916, 0.954),
    (1.000, 0.971),
    (1.083, 0.983),
    (1.166, 0.994),
    (1.250, 0.998),
    (1.333, 1.000),
)


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def lerp(start: float, end: float, amount: float) -> float:
    return start + (end - start) * amount


def smoothstep(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def ease_out_cubic(value: float) -> float:
    value = clamp(value)
    return 1.0 - (1.0 - value) ** 3


def ease_in_out_cubic(value: float) -> float:
    value = clamp(value)
    if value < 0.5:
        return 4.0 * value**3
    return 1.0 - ((-2.0 * value + 2.0) ** 3) / 2.0


def _sample_scalar(points: tuple[tuple[float, float], ...], value: float) -> float:
    if value <= points[0][0]:
        return points[0][1]
    if value >= points[-1][0]:
        return points[-1][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if value <= x1:
            local = smoothstep((value - x0) / max(1e-9, x1 - x0))
            return lerp(y0, y1, local)
    return points[-1][1]


def body_progress(local_time: float) -> float:
    return _sample_scalar(BODY_PROGRESS_KEYFRAMES, max(0.0, local_time))


def badge_entry_affine(age: float) -> tuple[float, float, float, float, float, float]:
    if age >= BADGE_ENTRY_END:
        return 1.0, 0.0, 0.0, 1.0, 0.0, 0.0
    if age <= BADGE_AFFINE_KEYFRAMES[0][0]:
        return BADGE_AFFINE_KEYFRAMES[0][1:]

    for left, right in zip(BADGE_AFFINE_KEYFRAMES, BADGE_AFFINE_KEYFRAMES[1:]):
        if age <= right[0]:
            local = smoothstep((age - left[0]) / max(1e-9, right[0] - left[0]))
            return tuple(lerp(left[i], right[i], local) for i in range(1, 7))  # type: ignore[return-value]
    return 1.0, 0.0, 0.0, 1.0, 0.0, 0.0


def post_badge_fall_affine(age: float) -> tuple[float, float, float, float, float, float]:
    """Return a centered, vertical-only falling transform for cards 5+."""
    if age >= BADGE_ENTRY_END:
        return 1.0, 0.0, 0.0, 1.0, 0.0, 0.0
    if age <= POST_BADGE_FALL_KEYFRAMES[0][0]:
        scale = POST_BADGE_FALL_KEYFRAMES[0][1]
        vertical = POST_BADGE_FALL_KEYFRAMES[0][2]
    else:
        scale = 1.0
        vertical = 0.0
        for left, right in zip(POST_BADGE_FALL_KEYFRAMES, POST_BADGE_FALL_KEYFRAMES[1:]):
            if age <= right[0]:
                local = smoothstep((age - left[0]) / max(1e-9, right[0] - left[0]))
                scale = lerp(left[1], right[1], local)
                vertical = lerp(left[2], right[2], local)
                break

    cx, cy = BADGE_CENTER
    return (
        scale,
        0.0,
        0.0,
        scale,
        cx * (1.0 - scale),
        cy * (1.0 - scale) + vertical,
    )


@dataclass(slots=True)
class RenderTheme:
    background: tuple[int, int, int] = (0, 0, 0)
    title_background: tuple[int, int, int] = (247, 247, 245)
    description_background: tuple[int, int, int] = (105, 102, 95)
    title_text: tuple[int, int, int] = (22, 22, 22)
    description_text: tuple[int, int, int] = (250, 250, 248)
    badge: tuple[int, int, int] = (211, 7, 13)
    badge_dark: tuple[int, int, int] = (166, 0, 8)
    badge_text: tuple[int, int, int] = (255, 255, 255)
    divider: tuple[int, int, int] = (20, 20, 20)


class FrameRenderer:
    """Pillow renderer shared by the live preview and final FFmpeg export."""

    def __init__(self) -> None:
        self.theme = RenderTheme()
        self._image_cache: OrderedDict[str, Image.Image | None] = OrderedDict()
        self._body_cache: OrderedDict[tuple[object, ...], Image.Image] = OrderedDict()
        self._badge_shell_cache: dict[str, Image.Image] = {}
        self._badge_final_cache: OrderedDict[tuple[str, str], Image.Image] = OrderedDict()
        self._max_image_cache = 48
        self._max_body_cache = 32
        self._max_badge_cache = 64
        self._active_settings = None
        self._active_profile = get_reference_profile("what-males-learn-at-each-age")

    @staticmethod
    @lru_cache(maxsize=256)
    def _resolve_font(value: str) -> str:
        """Resolve either a font-file path or an installed system-family name."""
        requested = (value or "").strip().strip('\"')
        if not requested:
            return ""
        direct = Path(requested).expanduser()
        if direct.exists():
            return str(direct)

        if os.name == "nt":
            try:
                import winreg
                wanted = requested.casefold()
                keys = (
                    (winreg.HKEY_LOCAL_MACHINE, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts"),
                    (winreg.HKEY_CURRENT_USER, r"SOFTWARE\Microsoft\Windows NT\CurrentVersion\Fonts"),
                )
                for hive, key_name in keys:
                    try:
                        with winreg.OpenKey(hive, key_name) as key:
                            index = 0
                            while True:
                                try:
                                    display, filename, _ = winreg.EnumValue(key, index)
                                except OSError:
                                    break
                                index += 1
                                clean_display = display.rsplit(" (", 1)[0].casefold()
                                if wanted == clean_display or wanted in clean_display:
                                    candidate = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts" / str(filename)
                                    if candidate.exists():
                                        return str(candidate)
                    except OSError:
                        continue
            except Exception:
                pass
        else:
            try:
                result = subprocess.run(
                    ["fc-match", "-f", "%{file}\n", requested],
                    check=False, capture_output=True, text=True, timeout=2,
                )
                candidate = Path(result.stdout.splitlines()[0].strip()) if result.stdout.strip() else None
                if candidate and candidate.exists():
                    return str(candidate)
            except Exception:
                pass
        return ""

    @staticmethod
    @lru_cache(maxsize=512)
    def _load_font(path: str, size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        if path:
            try:
                return ImageFont.truetype(path, size=size)
            except OSError:
                pass
        return ImageFont.load_default()

    def _font(self, size: int, bold: bool = False, role: str = "title") -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
        settings = self._active_settings
        custom = ""
        if settings is not None:
            custom = {
                "title": settings.font_title,
                "description": settings.font_description,
                "badge": settings.font_badge,
                "credits": settings.font_credits,
            }.get(role, settings.font_title) or ""
        resolved_custom = self._resolve_font(custom)
        if resolved_custom:
            return self._load_font(resolved_custom, size)
        # Pillow ships a deterministic scalable font. Using it on every OS keeps
        # line wrapping and glyph metrics identical for portable projects.
        try:
            return ImageFont.load_default(size=max(1, int(size)))
        except TypeError:  # Pillow < 10 compatibility
            return ImageFont.load_default()

    def _load_image(self, source: str) -> Image.Image | None:
        key = source.strip()
        if not key:
            return None
        if key in self._image_cache:
            cached = self._image_cache.pop(key)
            self._image_cache[key] = cached
            return cached.copy() if cached else None
        loaded: Image.Image | None = None
        try:
            path = materialize_remote_asset(key) if key.lower().startswith(("http://", "https://")) else Path(key).expanduser()
            if path.exists():
                loaded = Image.open(path).convert("RGBA")
        except Exception:
            loaded = None
        self._image_cache[key] = loaded.copy() if loaded else None
        while len(self._image_cache) > self._max_image_cache:
            self._image_cache.popitem(last=False)
        return loaded.copy() if loaded else None

    @staticmethod
    def _cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
        if image.mode != "RGBA":
            image = image.convert("RGBA")
        return ImageOps.fit(image, size, method=Image.Resampling.LANCZOS, centering=(0.5, 0.5))

    @staticmethod
    def _contain(image: Image.Image, size: tuple[int, int]) -> Image.Image:
        copy = image.copy()
        copy.thumbnail(size, Image.Resampling.LANCZOS)
        result = Image.new("RGBA", size, (0, 0, 0, 0))
        result.alpha_composite(copy, ((size[0] - copy.width) // 2, (size[1] - copy.height) // 2))
        return result

    @staticmethod
    def _wrapped_lines(
        draw: ImageDraw.ImageDraw,
        text: str,
        font: ImageFont.ImageFont,
        max_width: int,
        max_lines: int,
    ) -> list[str]:
        """Wrap text without duplicating words and ellipsize overflow reliably."""
        normalized = " ".join(str(text or "").split())
        if not normalized or max_width <= 0 or max_lines <= 0:
            return []

        def measured(value: str) -> int:
            box = draw.textbbox((0, 0), value, font=font)
            return box[2] - box[0]

        def ellipsize(value: str) -> str:
            value = value.strip()
            if measured(value) <= max_width:
                return value
            suffix = "…"
            while value and measured(value.rstrip() + suffix) > max_width:
                value = value[:-1]
            return (value.rstrip() + suffix) if value else suffix

        lines: list[str] = []
        current = ""
        for word in normalized.split():
            candidate = word if not current else f"{current} {word}"
            if measured(candidate) <= max_width:
                current = candidate
                continue
            if current:
                lines.append(current)
                current = word
            else:
                lines.append(ellipsize(word))
                current = ""

        if current:
            lines.append(current)

        if len(lines) <= max_lines:
            return lines

        visible = lines[:max_lines]
        overflow = " ".join(lines[max_lines - 1 :])
        visible[-1] = ellipsize(overflow)
        return visible

    def _draw_placeholder(self, canvas: Image.Image, box: tuple[int, int, int, int], card: Card) -> None:
        x0, y0, x1, y1 = box
        width, height = x1 - x0, y1 - y0
        layer = Image.new("RGB", (width, height), (0, 105, 211))
        draw = ImageDraw.Draw(layer)
        initial = (card.title.strip()[:1] or "?").upper()
        font = self._font(220, True, "title")
        draw.text((width / 2, height * 0.60), initial, font=font, fill=(235, 242, 250), anchor="mm")
        canvas.paste(layer, (x0, y0))

    @staticmethod
    def _has_free_transform(card: Card) -> bool:
        return (
            abs(float(card.image_x)) > 1e-6
            or abs(float(card.image_y)) > 1e-6
            or abs(float(card.image_scale) - 1.0) > 1e-6
            or abs(float(card.image_rotation)) > 1e-6
            or any(
                abs(float(value)) > 1e-6
                for value in (
                    card.image_crop_left,
                    card.image_crop_top,
                    card.image_crop_right,
                    card.image_crop_bottom,
                )
            )
            or str(card.image_layer).lower() == "front"
        )

    def _free_transform_artwork(
        self,
        source: Image.Image,
        card: Card,
        width: int,
        height: int,
        image_height: int,
    ) -> Image.Image:
        """Build a full-card RGBA artwork layer for one transformed card image."""
        rgba = source.convert("RGBA")
        left = max(0.0, min(0.49, float(card.image_crop_left)))
        top = max(0.0, min(0.49, float(card.image_crop_top)))
        right = max(0.0, min(0.49, float(card.image_crop_right)))
        bottom = max(0.0, min(0.49, float(card.image_crop_bottom)))
        x0 = min(rgba.width - 1, max(0, int(round(rgba.width * left))))
        y0 = min(rgba.height - 1, max(0, int(round(rgba.height * top))))
        x1 = max(x0 + 1, min(rgba.width, int(round(rgba.width * (1.0 - right)))))
        y1 = max(y0 + 1, min(rgba.height, int(round(rgba.height * (1.0 - bottom)))))
        cropped = rgba.crop((x0, y0, x1, y1))

        has_alpha = cropped.getextrema()[3][0] < 255
        fit_mode = getattr(self._active_settings, "image_fit_mode", "cover") if self._active_settings else "cover"
        if has_alpha:
            fitted = self._contain(cropped, (max(1, int(width * 0.96)), max(1, int(image_height * 0.96))))
        elif fit_mode == "contain":
            fitted = self._contain(cropped, (width, image_height))
        else:
            fitted = self._cover(cropped, (width, image_height))

        scale = max(0.05, min(8.0, float(card.image_scale)))
        # Never create an intermediate larger than about 64 megapixels.
        max_pixels = 64_000_000
        current_pixels = max(1, fitted.width * fitted.height)
        scale = min(scale, math.sqrt(max_pixels / current_pixels))
        if abs(scale - 1.0) > 1e-6:
            fitted = fitted.resize(
                (max(1, int(round(fitted.width * scale))), max(1, int(round(fitted.height * scale)))),
                Image.Resampling.LANCZOS,
            )
        rotation = float(card.image_rotation)
        if abs(rotation) > 1e-6:
            fitted = fitted.rotate(rotation, resample=Image.Resampling.BICUBIC, expand=True)

        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        centre_x = width / 2.0 + float(card.image_x)
        centre_y = image_height / 2.0 + float(card.image_y)
        destination = (int(round(centre_x - fitted.width / 2.0)), int(round(centre_y - fitted.height / 2.0)))
        layer.alpha_composite(fitted, destination)
        return layer

    def _draw_card_body_uncached(self, canvas: Image.Image, card: Card, x: float, width: int, height: int) -> None:
        ix = int(round(x))
        title = " ".join(str(card.title or "").split())
        has_title = bool(title)
        description = " ".join(str(card.description or "").split())
        has_description = bool(description)
        layout = self._active_profile.layout

        # Geometry is owned by the selected reference model. It is never
        # recomputed from percentages because that drifts by several pixels.
        # Empty optional bands collapse and donate their exact source pixels to
        # the artwork; this mirrors Android and prevents invisible blank gaps.
        canonical_description_height = max(0, layout.body_height - layout.description_top)
        description_height = min(height, canonical_description_height) if has_description else 0
        rule_height = layout.divider_width if has_description else 0
        title_height = min(layout.title_height, max(0, height - description_height - rule_height)) if has_title else 0
        image_height = max(0, height - title_height - rule_height - description_height)
        title_top = image_height
        title_bottom = title_top + title_height
        desc_top = title_bottom + rule_height

        source = self._load_image(card.image)
        image_box = (ix, 0, ix + width, image_height)
        transformed_layer: Image.Image | None = None
        transformed = source is not None and self._has_free_transform(card)

        if source is None:
            self._draw_placeholder(canvas, image_box, card)
        elif not transformed:
            has_alpha = source.mode == "RGBA" and source.getextrema()[3][0] < 255
            if has_alpha:
                background = Image.new("RGBA", (width, image_height), (0, 105, 211, 255))
                artwork = self._contain(source, (int(width * 0.96), int(image_height * 0.96)))
                background.alpha_composite(artwork, ((width - artwork.width) // 2, (image_height - artwork.height) // 2))
                canvas.paste(background.convert("RGB"), (ix, 0))
            else:
                fit_mode = getattr(self._active_settings, "image_fit_mode", "cover") if self._active_settings else "cover"
                if fit_mode == "contain":
                    background = Image.new("RGBA", (width, image_height), (0, 105, 211, 255))
                    artwork = self._contain(source, (width, image_height))
                    background.alpha_composite(artwork)
                    canvas.paste(background.convert("RGB"), (ix, 0))
                else:
                    canvas.paste(self._cover(source, (width, image_height)).convert("RGB"), (ix, 0))
        else:
            ImageDraw.Draw(canvas).rectangle(image_box, fill=(0, 105, 211))
            transformed_layer = self._free_transform_artwork(source, card, width, height, image_height)
            if str(card.image_layer).lower() != "front":
                canvas.paste(transformed_layer.convert("RGB"), (ix, 0), transformed_layer.getchannel("A"))

        draw = ImageDraw.Draw(canvas)
        if has_title:
            draw.rectangle((ix, title_top, ix + width, title_bottom), fill=layout.title_background)
        if has_description and desc_top < height:
            draw.rectangle((ix, desc_top, ix + width, height), fill=layout.description_background)
        # Relationships uses the visible orange horizontal rule from the
        # reference; Age uses narrow dark separators.
        if rule_height:
            draw.rectangle(
                (ix, title_bottom, ix + width, desc_top - 1),
                fill=layout.divider_color,
            )
        draw.line((ix, 0, ix, height), fill=self.theme.divider, width=2)
        draw.line((ix + width - 1, 0, ix + width - 1, height), fill=self.theme.divider, width=2)

        relationship = self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS
        if has_title:
            title_size = int(width * (0.105 if relationship else 0.072))
            title_font = self._font(max(27, title_size), not relationship, "title")
            title_box = draw.textbbox((0, 0), title, font=title_font)
            minimum = 22 if not relationship else 24
            while getattr(title_font, "size", minimum) > minimum and title_box[2] > width - 24:
                title_font = self._font(getattr(title_font, "size", minimum + 2) - 2, not relationship, "title")
                title_box = draw.textbbox((0, 0), title, font=title_font)
            draw.text(
                (ix + width / 2, title_top + title_height / 2),
                title,
                font=title_font,
                fill=self.theme.title_text,
                anchor="mm",
                align="center",
            )

        if has_description and desc_top < height:
            available_width = max(24, width - 34)
            available_height = max(12, height - desc_top - 12)
            maximum_size = max(9, int(width * (0.050 if relationship else 0.044)))
            minimum_size = max(7, int(width * 0.026))
            chosen_font = self._font(maximum_size, False, "description")
            chosen_lines: list[str] = []
            chosen_line_height = max(9, int(maximum_size * 1.16))
            for font_size in range(maximum_size, minimum_size - 1, -1):
                candidate_font = self._font(font_size, False, "description")
                line_height = max(9, int(font_size * 1.16))
                max_lines = max(1, min(5 if relationship else 4, available_height // line_height))
                lines = self._wrapped_lines(draw, description, candidate_font, available_width, max_lines=max_lines)
                if lines and len(lines) * line_height <= available_height:
                    chosen_font = candidate_font
                    chosen_lines = lines
                    chosen_line_height = line_height
                    break
            if not chosen_lines:
                chosen_lines = self._wrapped_lines(draw, description, chosen_font, available_width, max_lines=1)
            total = len(chosen_lines) * chosen_line_height
            start_y = desc_top + max(6, ((height - desc_top) - total) // 2)
            for line in chosen_lines:
                bbox = draw.textbbox((0, 0), line, font=chosen_font)
                text_width = bbox[2] - bbox[0]
                draw.text((ix + (width - text_width) / 2, start_y), line, font=chosen_font, fill=self.theme.description_text)
                start_y += chosen_line_height

    def _draw_card_body(self, canvas: Image.Image, card: Card, x: float, width: int, height: int) -> None:
        settings = self._active_settings
        key = (
            self._active_profile.model_id, card.title, card.value, card.description, card.image, width, height,
            getattr(settings, "font_title", ""), getattr(settings, "font_description", ""),
            getattr(settings, "image_fit_mode", "cover"),
            card.image_x, card.image_y, card.image_scale, card.image_rotation,
            card.image_crop_left, card.image_crop_top, card.image_crop_right, card.image_crop_bottom,
            card.image_layer,
        )
        body = self._body_cache.get(key)
        if body is None:
            body = Image.new("RGB", (width, height), self.theme.background)
            self._draw_card_body_uncached(body, card, 0, width, height)
            self._body_cache[key] = body
            while len(self._body_cache) > self._max_body_cache:
                self._body_cache.popitem(last=False)
        else:
            self._body_cache.move_to_end(key)
        canvas.paste(body, (int(round(x)), 0))

    def _draw_front_artwork(self, canvas: Image.Image, card: Card, x: float, width: int, height: int) -> None:
        if str(card.image_layer).lower() != "front" or not card.image:
            return
        source = self._load_image(card.image)
        if source is None:
            return
        description = bool(" ".join(str(card.description or "").split()))
        image_height = int(height * 0.768) if description else height - min(max(34, int(height * 0.105)), max(1, height - 1))
        layer = self._free_transform_artwork(source, card, width, height, image_height)
        canvas.paste(layer.convert("RGB"), (int(round(x)), 0), layer.getchannel("A"))

    @staticmethod
    def _value_lines(value: str) -> list[str]:
        words = value.upper().split()
        if not words:
            return []
        if len(words) == 1:
            return words
        if len(words) == 2:
            return words
        if words[-1] == "OLD":
            middle = " ".join(words[1:-1])
            return [words[0], middle, words[-1]] if middle else [words[0], words[-1]]
        return [words[0], " ".join(words[1:-1]), words[-1]]

    def _badge_shell(self) -> Image.Image:
        shape = self._active_profile.layout.badge_shape
        cached = self._badge_shell_cache.get(shape)
        if cached is not None:
            return cached.copy()

        layer = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        if shape == "octagon":
            polygon = [(240, 18), (373, 83), (421, 245), (348, 363), (132, 363), (59, 245), (107, 83)]
        else:
            polygon = [(round(x), round(y)) for x, y in BADGE_POLYGON]

        shadow_mask = Image.new("L", BADGE_SOURCE_SIZE, 0)
        shadow_draw = ImageDraw.Draw(shadow_mask)
        shadow_draw.polygon([(x + 6, y + 9) for x, y in polygon], fill=155)
        shadow_mask = shadow_mask.filter(ImageFilter.GaussianBlur(8.0))
        black = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 115))
        black.putalpha(ImageChops.multiply(shadow_mask, Image.new("L", BADGE_SOURCE_SIZE, 185)))
        layer.alpha_composite(black)

        draw = ImageDraw.Draw(layer)
        draw.polygon(polygon, fill=(*self.theme.badge, 255))
        outline = (239, 194, 72, 255) if shape == "octagon" else (*self.theme.badge_dark, 145)
        draw.line(polygon + [polygon[0]], fill=outline, width=3 if shape == "octagon" else 2, joint="curve")
        self._badge_shell_cache[shape] = layer.copy()
        return layer

    def _font_for_width(
        self,
        text: str,
        size: int,
        max_width: int,
        *,
        bold: bool = False,
        role: str = "credits",
        minimum: int = 12,
    ) -> ImageFont.ImageFont:
        probe = ImageDraw.Draw(Image.new("L", (1, 1)))
        font = self._font(size, bold, role)
        while size > minimum and probe.textbbox((0, 0), text, font=font)[2] > max_width:
            size -= 1
            font = self._font(size, bold, role)
        return font

    def _font_fitted(self, text: str, size: int, max_width: int) -> ImageFont.ImageFont:
        font = self._font(size, True, "badge")
        probe = ImageDraw.Draw(Image.new("L", (1, 1)))
        while getattr(font, "size", size) > 18 and probe.textbbox((0, 0), text, font=font)[2] > max_width:
            size -= 2
            font = self._font(size, True, "badge")
        return font

    def _text_layout(self, card: Card) -> list[tuple[str, float, int]]:
        if self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS:
            words = card.value.replace("\n", " ").split()
            lowered = [word.lower() for word in words]
            number = ""
            if "in" in lowered:
                position = lowered.index("in")
                if position + 1 < len(words):
                    number = words[position + 1]
            if not number:
                number = next((word for word in words if any(ch.isdigit() for ch in word)), card.value)
            return [("1 in", 104.0, 48), (number, 208.0, 112), ("People", 306.0, 45)]

        lines = self._value_lines(card.value)
        if len(lines) == 1:
            return [(lines[0], 219.0, 72)]
        if len(lines) == 2:
            return [(lines[0], 168.0, 72), (lines[1], 250.0, 40)]
        return [(lines[0], 136.0, 84), (lines[1], 210.0, 41), (lines[2], 263.0, 41)]

    @staticmethod
    def _text_landing_offset(age: float) -> float:
        # The words do not simply ease to their final coordinates. They land
        # low while the shell is still stretched, then rise gently as the
        # hexagon straightens. This secondary settle is visible for every
        # multi-line value in the reference.
        if age < 0.90:
            return 0.0
        if age < 1.15:
            return lerp(0.0, 40.0, smoothstep((age - 0.90) / 0.25))
        if age < 1.55:
            return 40.0
        if age < 1.85:
            return lerp(40.0, 18.0, smoothstep((age - 1.55) / 0.30))
        if age < 2.30:
            return lerp(18.0, 0.0, smoothstep((age - 1.85) / 0.45))
        return 0.0

    def _draw_badge_text_canonical(self, layer: Image.Image, card: Card, age: float, force_final: bool = False) -> None:
        layout = self._text_layout(card)
        for index, (text, target_y, size) in enumerate(layout):
            start = TEXT_START + index * TEXT_LINE_DELAY
            progress = 1.0 if force_final else clamp((age - start) / TEXT_LINE_SECONDS)
            if progress <= 0.0:
                continue

            eased = ease_out_cubic(progress)
            y = target_y + self._text_landing_offset(age) - (1.0 - eased) * 112.0
            alpha = int(255 * clamp(progress * 1.75))
            font = self._font_fitted(text, size, 264)

            text_layer = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))
            text_draw = ImageDraw.Draw(text_layer)

            if progress < 0.92:
                trail_length = (1.0 - progress) * 76.0
                for trail_index in range(8, 0, -1):
                    fraction = trail_index / 8.0
                    trail_y = y - trail_length * fraction
                    trail_alpha = int(alpha * (1.0 - fraction) * 0.18)
                    if trail_alpha > 0:
                        text_draw.text(
                            (BADGE_CENTER[0], trail_y),
                            text,
                            font=font,
                            fill=(*self.theme.badge_text, trail_alpha),
                            anchor="mm",
                        )

            # The source uses a soft downward shadow which remains visible
            # even while the letters are motion blurred.
            text_draw.text(
                (BADGE_CENTER[0] + 3, y + 5),
                text,
                font=font,
                fill=(20, 20, 20, int(alpha * 0.42)),
                anchor="mm",
            )
            text_draw.text(
                (BADGE_CENTER[0], y),
                text,
                font=font,
                fill=(*self.theme.badge_text, alpha),
                anchor="mm",
            )
            blur = max(0.0, (1.0 - progress) * 5.8)
            if blur > 0.2:
                text_layer = text_layer.filter(ImageFilter.GaussianBlur(blur))
            layer.alpha_composite(text_layer)

    def _add_entry_motion_streak(self, layer: Image.Image, age: float) -> None:
        """Add the bright dragged edge visible during the oversized ingress."""
        if age < 0.12 or age > 0.82:
            return
        fade_in = smoothstep((age - 0.12) / 0.16)
        fade_out = 1.0 - smoothstep((age - 0.42) / 0.22)
        strength = clamp(fade_in * fade_out)
        if strength <= 0.0:
            return

        mask = Image.new("L", BADGE_SOURCE_SIZE, 0)
        ImageDraw.Draw(mask).polygon([(round(x), round(y)) for x, y in BADGE_POLYGON], fill=255)

        streak = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        draw = ImageDraw.Draw(streak)
        # A tall, slightly diagonal band on the leading side of the shell. It
        # is transformed with the badge, producing the long white/red ribbon
        # seen as later cards begin entering over the previous card.
        center = lerp(118.0, 154.0, smoothstep(age / 0.82))
        draw.polygon(
            [
                (center - 38, -70),
                (center + 16, -70),
                (center - 18, 500),
                (center - 78, 500),
            ],
            fill=(255, 255, 255, int(116 * strength)),
        )
        streak = streak.filter(ImageFilter.GaussianBlur(12.0))
        streak.putalpha(ImageChops.multiply(streak.getchannel("A"), mask))
        layer.alpha_composite(streak)

    def _add_badge_shine(self, layer: Image.Image, age: float) -> None:
        progress = (age - SHINE_START) / SHINE_SECONDS
        if progress <= 0.0 or progress >= 1.0:
            return
        p = smoothstep(progress)
        # The streak is already touching the upper-left edge at the first
        # visible shine frame; it does not begin fully off-canvas.
        top_x = lerp(130.0, 420.0, p)
        bottom_x = top_x - 205.0

        mask = Image.new("L", BADGE_SOURCE_SIZE, 0)
        ImageDraw.Draw(mask).polygon([(round(x), round(y)) for x, y in BADGE_POLYGON], fill=255)

        broad = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        broad_draw = ImageDraw.Draw(broad)
        broad_width = 40.0
        broad_draw.polygon(
            [
                (top_x - broad_width, -80),
                (top_x + broad_width, -80),
                (bottom_x + broad_width, 500),
                (bottom_x - broad_width, 500),
            ],
            fill=(255, 255, 255, 48),
        )
        broad = broad.filter(ImageFilter.GaussianBlur(12.0))

        core = Image.new("RGBA", BADGE_SOURCE_SIZE, (0, 0, 0, 0))
        core_draw = ImageDraw.Draw(core)
        core_width = 5.0
        core_draw.polygon(
            [
                (top_x - core_width, -80),
                (top_x + core_width, -80),
                (bottom_x + core_width, 500),
                (bottom_x - core_width, 500),
            ],
            fill=(255, 255, 255, 82),
        )
        core = core.filter(ImageFilter.GaussianBlur(2.8))
        broad.alpha_composite(core)
        broad.putalpha(ImageChops.multiply(broad.getchannel("A"), mask))
        layer.alpha_composite(broad)

    def _badge_source(self, card: Card, age: float, *, sticker_entry: bool) -> Image.Image:
        dynamic = age < 2.30 or (SHINE_START < age < SHINE_START + SHINE_SECONDS)
        cache_key = (self._active_profile.model_id + "|" + card.value.upper().strip(), getattr(self._active_settings, "font_badge", "") if self._active_settings else "")
        if not dynamic and cache_key in self._badge_final_cache:
            cached = self._badge_final_cache.pop(cache_key)
            self._badge_final_cache[cache_key] = cached
            return cached.copy()

        layer = self._badge_shell()
        if sticker_entry:
            self._add_entry_motion_streak(layer, age)
        self._draw_badge_text_canonical(layer, card, age, force_final=not dynamic)
        self._add_badge_shine(layer, age)
        if not dynamic:
            self._badge_final_cache[cache_key] = layer.copy()
            while len(self._badge_final_cache) > self._max_badge_cache:
                self._badge_final_cache.popitem(last=False)
        return layer

    @staticmethod
    def _badge_delay(index: int) -> float:
        return 0.0 if index < 4 else POST_INITIAL_BADGE_DELAY

    @staticmethod
    def _badge_speed(index: int) -> float:
        return 1.0 if index < 4 else POST_INITIAL_BADGE_SPEED

    @classmethod
    def _badge_age(cls, index: int, seconds: float, starts: list[float]) -> float:
        return (seconds - starts[index] - cls._badge_delay(index)) * cls._badge_speed(index)

    @classmethod
    def _badge_clock_time(cls, index: int, starts: list[float], animation_age: float) -> float:
        return starts[index] + cls._badge_delay(index) + animation_age / cls._badge_speed(index)

    @classmethod
    def _stage_scale(cls, index: int, seconds: float, starts: list[float]) -> float:
        scale = BADGE_ACTIVE_SCALE
        if index + 1 < len(starts):
            next_index = index + 1
            trigger = cls._badge_clock_time(next_index, starts, SHINE_START)
            duration = DEEMPHASIS_SECONDS / cls._badge_speed(next_index)
            p = ease_in_out_cubic((seconds - trigger) / duration)
            scale = lerp(BADGE_ACTIVE_SCALE, BADGE_MEDIUM_SCALE, p)
        if index + 2 < len(starts):
            next_index = index + 2
            trigger = cls._badge_clock_time(next_index, starts, SHINE_START)
            duration = DEEMPHASIS_SECONDS / cls._badge_speed(next_index)
            p = ease_in_out_cubic((seconds - trigger) / duration)
            if p > 0.0:
                scale = lerp(BADGE_MEDIUM_SCALE, BADGE_SMALL_SCALE, p)
        return scale

    @staticmethod
    def _transform_point(
        point: tuple[float, float],
        matrix: tuple[float, float, float, float],
        translation: tuple[float, float],
    ) -> tuple[float, float]:
        x, y = point
        m00, m01, m10, m11 = matrix
        tx, ty = translation
        return m00 * x + m01 * y + tx, m10 * x + m11 * y + ty

    def _warp_badge(
        self,
        canvas: Image.Image,
        source: Image.Image,
        card_x: float,
        affine: tuple[float, float, float, float, float, float],
    ) -> None:
        m00, m01, m10, m11, tx, ty = affine
        global_translation = (card_x + tx, ty)
        matrix = (m00, m01, m10, m11)

        corners = [
            (0.0, 0.0),
            (float(source.width), 0.0),
            (float(source.width), float(source.height)),
            (0.0, float(source.height)),
        ]
        transformed = [self._transform_point(point, matrix, global_translation) for point in corners]
        min_x = max(0, math.floor(min(x for x, _ in transformed)) - 4)
        max_x = min(canvas.width, math.ceil(max(x for x, _ in transformed)) + 4)
        min_y = max(0, math.floor(min(y for _, y in transformed)) - 4)
        max_y = min(canvas.height, math.ceil(max(y for _, y in transformed)) + 4)
        if max_x <= min_x or max_y <= min_y:
            return

        determinant = m00 * m11 - m01 * m10
        if abs(determinant) < 1e-8:
            return
        inv00 = m11 / determinant
        inv01 = -m01 / determinant
        inv10 = -m10 / determinant
        inv11 = m00 / determinant
        gx, gy = global_translation
        coeffs = (
            inv00,
            inv01,
            inv00 * (min_x - gx) + inv01 * (min_y - gy),
            inv10,
            inv11,
            inv10 * (min_x - gx) + inv11 * (min_y - gy),
        )
        warped = source.transform(
            (max_x - min_x, max_y - min_y),
            Image.Transform.AFFINE,
            coeffs,
            resample=Image.Resampling.BICUBIC,
        )
        canvas.paste(warped.convert("RGB"), (min_x, min_y), warped.getchannel("A"))


    @staticmethod
    def _compose_source_scale(
        affine: tuple[float, float, float, float, float, float],
        scale: float,
        center: tuple[float, float] = BADGE_CENTER,
    ) -> tuple[float, float, float, float, float, float]:
        if abs(scale - 1.0) < 1e-6:
            return affine
        m00, m01, m10, m11, tx, ty = affine
        cx, cy = center
        return (
            m00 * scale,
            m01 * scale,
            m10 * scale,
            m11 * scale,
            tx + (1.0 - scale) * (m00 * cx + m01 * cy),
            ty + (1.0 - scale) * (m10 * cx + m11 * cy),
        )

    def _age_deemphasis_scale(self, index: int, global_frame: int, starts: list[int]) -> float:
        # The reference visibly shrinks older settled hexagons as newer cards
        # enter.  The original 1.0.1 code had the measured scale constants but
        # never applied them, so old badges stayed full size.
        def trigger_for(next_index: int) -> float:
            if next_index < 4:
                return starts[next_index] + 35.0 + (SHINE_START / BADGE_ENTRY_END) * 85.0
            # Later conveyor cards make the previous badge step down as soon
            # as their card begins sliding in; waiting for the late badge drop
            # leaves the visible opening badges too large for hundreds of
            # frames.
            return float(starts[next_index])

        scale = BADGE_ACTIVE_SCALE
        if index + 1 < len(starts):
            p = ease_in_out_cubic((global_frame - trigger_for(index + 1)) / (DEEMPHASIS_SECONDS * 60.0))
            scale = lerp(BADGE_ACTIVE_SCALE, BADGE_MEDIUM_SCALE, p)
        if index + 2 < len(starts):
            p = ease_in_out_cubic((global_frame - trigger_for(index + 2)) / (DEEMPHASIS_SECONDS * 60.0))
            if p > 0.0:
                scale = lerp(BADGE_MEDIUM_SCALE, BADGE_SMALL_SCALE, p)
        return scale

    def _draw_badge(
        self,
        canvas: Image.Image,
        project: Project,
        index: int,
        card_x: float,
        global_frame: int,
        starts: list[int],
    ) -> None:
        card = project.cards[index]
        if not card.value or index >= len(starts):
            return
        local_frame = int(global_frame) - starts[index]
        relationship = self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS

        if relationship:
            if not relationships_shell_visible(local_frame):
                return
            target = relationships_badge_bbox(local_frame)
            if target is None:
                return
            if index < 4:
                text_age = relationships_badge_text_age(local_frame)
            else:
                text_progress = clamp((local_frame - 18) / 32.0)
                text_age = 0.9 + text_progress * 1.4
            source = self._badge_source(card, text_age, sticker_entry=False)
            target_x, target_y, target_w, target_h = target
            # The source octagon occupies x=59..421 and y=18..363 in the
            # 480x430 badge canvas.  Mapping those exact bounds reproduces the
            # measured source component on every opening frame.
            scale_x = target_w / 362.0
            scale_y = target_h / 345.0
            affine = (
                scale_x, 0.0, 0.0, scale_y,
                target_x - 59.0 * scale_x,
                target_y - 18.0 * scale_y,
            )
            self._warp_badge(canvas, source, card_x, affine)
            return

        if index < 4:
            if local_frame < 35:
                return
            age = age_opening_badge_age(local_frame)
            source = self._badge_source(card, age, sticker_entry=True)
            affine = badge_entry_affine(age) if age < BADGE_ENTRY_END else (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        else:
            age = age_later_badge_age(local_frame)
            if age < 0.0:
                return
            source = self._badge_source(card, age, sticker_entry=False)
            affine = post_badge_fall_affine(age) if age < 2.25 else (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        affine = self._compose_source_scale(affine, self._age_deemphasis_scale(index, int(global_frame), starts))
        self._warp_badge(canvas, source, card_x, affine)

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
        small = self._font(23, False, "credits")
        heading_font = self._font_for_width(settings.credits_heading, 43, panel_width - 70, bold=True, role="credits", minimum=20)
        item = self._font(23, True, "credits")
        muted = (246, 246, 246)

        top_text = settings.credits_top_text.strip()
        if top_text:
            lines = self._wrapped_lines(draw, top_text, small, panel_width - 70, 4)
            y = 38
            for line in lines:
                draw.text((panel_width / 2, y), line, font=small, fill=muted, anchor="ma")
                y += 30
        draw.line((50, 200, panel_width - 50, 200), fill=(175, 175, 175), width=2)
        if settings.credits_heading.strip():
            draw.text((panel_width / 2, 286), settings.credits_heading, font=heading_font, fill=(255, 255, 255), anchor="mm")

        rows = [
            (settings.credits_project_name, item),
            (settings.credits_created_with_label, small),
            (settings.credits_created_with_value, item),
            (settings.credits_design_label, small),
            (settings.credits_design_value, item),
        ]
        y = 370
        for text, font in rows:
            text = text.strip()
            if not text:
                continue
            is_item = font is item
            fitted = self._font_for_width(text, 23, panel_width - 70, bold=is_item, role="credits", minimum=13)
            draw.text((panel_width / 2, y), text, font=fitted, fill=(255, 255, 255), anchor="mm")
            y += 45 if is_item else 40
        if settings.credits_footer.strip():
            draw.text((panel_width / 2, panel_height - 38), settings.credits_footer, font=self._font(18, True, "credits"), fill=(165, 165, 165), anchor="mm")
        canvas.paste(panel, (x, 0))

    def _draw_relationships_end_group(
        self,
        canvas: Image.Image,
        project: Project,
        outro_local_frame: int,
    ) -> None:
        # Geometry and text clocks measured from canonical frames
        # 10780..11120.  The final card itself is drawn separately at x=780.
        draw = ImageDraw.Draw(canvas)

        # Watch-more panel: canonical settled bounds x=1314..1865, y=79..970.
        panel_alpha = round(255 * clamp((outro_local_frame - 35) / 28.0))
        if panel_alpha > 0:
            panel = Image.new("RGBA", (552, 892), (0, 0, 0, 0))
            pd = ImageDraw.Draw(panel)
            pd.rounded_rectangle((0, 0, 551, 891), radius=18, fill=(31, 31, 31, panel_alpha))
            canvas.paste(panel.convert("RGB"), (1314, 79), panel.getchannel("A"))
            label_alpha = round(255 * clamp((outro_local_frame - 38) / 22.0))
            if label_alpha:
                draw.text(
                    (1590, 94),
                    "WATCH MORE",
                    font=self._font(43, False, "title"),
                    fill=(244, 244, 244, label_alpha),
                    anchor="ma",
                )

        # Question begins at source frame ~10780 and is complete by ~10880.
        question = "Which relationship type are you in right now?"
        q_progress = clamp((outro_local_frame - 42) / 80.0)
        visible = question[: round(len(question) * q_progress)]
        if visible:
            font = self._font(57, False, "title")
            lines = self._wrapped_lines(draw, visible, font, 742, max_lines=3)
            y = 211
            for line in lines:
                draw.text((26, y), line, font=font, fill=(255, 255, 255), anchor="la")
                y += 58

        # Orange CTA types immediately after the question (10880..10900).
        comment = "Comment below!"
        c_progress = clamp((outro_local_frame - 142) / 40.0)
        c_text = comment[: round(len(comment) * c_progress)]
        if c_text:
            draw.text(
                (22, 323), c_text,
                font=self._font(64, False, "title"),
                fill=(234, 127, 28), anchor="la",
            )

        # Subscription CTA types across 10960..11040, with the canonical red
        # command followed by white continuation text on two lines.
        sub_progress = clamp((outro_local_frame - 206) / 94.0)
        full = "SUBSCRIBE for more comparison videos."
        typed = full[: round(len(full) * sub_progress)]
        if typed:
            font = self._font(80, False, "title")
            first = typed[:9]
            rest = typed[9:]
            x0, y0 = 18, 641
            if first:
                draw.text((x0, y0), first, font=font, fill=(238, 31, 35), anchor="la")
            x_after = x0 + draw.textlength("SUBSCRIBE", font=font) + 12
            # Keep "for more" on the first line and "comparison videos." on
            # the second, matching the reference end screen.
            if rest:
                target1 = " for more"
                line1 = rest[:len(target1)]
                line2 = rest[len(target1):]
                if line1:
                    draw.text((x_after, y0), line1, font=font, fill=(244, 244, 244), anchor="la")
                if line2:
                    draw.text((18, y0 + 88), line2.lstrip(), font=font, fill=(244, 244, 244), anchor="la")

    def _draw_relationships_final_card(
        self,
        canvas: Image.Image,
        project: Project,
        x: float,
        global_frame: int,
        starts: list[int],
    ) -> None:
        if not project.cards:
            return
        index = len(project.cards) - 1
        layout = self._active_profile.layout
        self._draw_card_body(
            canvas,
            project.cards[index],
            x + layout.body_inset,
            layout.body_width,
            layout.body_height,
        )
        self._draw_badge(canvas, project, index, x, global_frame, starts)
        self._draw_front_artwork(
            canvas,
            project.cards[index],
            x + layout.body_inset,
            layout.body_width,
            layout.body_height,
        )

    def _draw_end_group(self, canvas: Image.Image, top: float, project: Project) -> None:
        # Canonical Age end-screen geometry measured at settled frames
        # 16415..16669.  The first 1440 px are the end-screen region; the
        # final card remains locked in the rightmost 480 px slot.
        layer = Image.new("RGBA", (1440, REFERENCE_HEIGHT), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        red = (212, 9, 10, 255)
        label_font = self._font(35, True)

        boxes = [
            (40, 210, 689, 669, project.settings.end_best_label),
            (750, 210, 1400, 669, project.settings.end_newest_label),
        ]
        for x0, y0, x1, y1, label in boxes:
            draw.rounded_rectangle((x0, y0, x1, y1), radius=18, fill=red)
            fitted = self._font_for_width(label, 35, x1 - x0 - 50, bold=True, role="title", minimum=22)
            draw.text(((x0 + x1) / 2, y0 + 28), label, font=fitted, fill=(255, 255, 255, 255), anchor="ma")

        credit_x, credit_y, credit_w, credit_h = 468, 741, 502, 269
        draw.rounded_rectangle(
            (credit_x, credit_y, credit_x + credit_w, credit_y + credit_h),
            radius=22,
            fill=(81, 77, 67, 255),
        )
        draw.text(
            (credit_x + credit_w / 2, credit_y + 30),
            project.settings.end_credit_label,
            font=self._font(25, True, "credits"),
            fill=(255, 255, 255, 255), anchor="ma",
        )
        draw.text(
            (credit_x + credit_w / 2, credit_y + 92),
            project.settings.end_credit_value or project.name,
            font=self._font(18, False, "credits"),
            fill=(244, 244, 244, 255), anchor="ma",
        )
        canvas.paste(layer.convert("RGB"), (0, int(round(top))), layer.getchannel("A"))

    @staticmethod
    def _age_end_group_top(outro_local_frame: int) -> float:
        # Absolute source-frame offsets measured from frames 16403..16413.
        # The group drops in from above; it never rises from the bottom.
        local = int(outro_local_frame)
        keys = (
            (0, -1080.0),
            (31, -671.0),
            (32, -270.0),
            (33, -224.0),
            (34, -182.0),
            (35, -144.0),
            (36, -108.0),
            (37, -78.0),
            (38, -50.0),
            (39, -30.0),
            (40, -14.0),
            (41, -4.0),
            (42, 0.0),
        )
        if local <= keys[0][0]:
            return keys[0][1]
        if local >= keys[-1][0]:
            return 0.0
        for (f0, y0), (f1, y1) in zip(keys, keys[1:]):
            if local <= f1:
                return lerp(y0, y1, (local - f0) / max(1, f1 - f0))
        return 0.0


    def _positions_for_frame(
        self,
        project: Project,
        global_frame: int,
        starts: list[int],
    ) -> dict[int, float]:
        pitch = self._active_profile.layout.slot_pitch
        timeline = self._active_profile.timeline
        frame = int(global_frame)

        if frame >= timeline.continuous_start_frame and len(project.cards) > 4:
            positions: dict[int, float] = {}
            shift = continuous_shift(self._active_profile.model_id, frame)
            # Use the decoded per-frame source coordinates directly. The
            # arithmetic clock remains only as a fallback outside measured
            # source ranges and for non-reference models.
            for card_index in range(len(project.cards)):
                x = continuous_card_x(self._active_profile.model_id, frame, card_index)
                if (
                    self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS
                    and card_index == len(project.cards) - 1
                ):
                    final_x = relationships_last_card_x(frame)
                    if final_x is not None:
                        x = final_x
                if x is None:
                    x = (card_index - shift) * pitch
                if -pitch < x < REFERENCE_WIDTH + pitch:
                    positions[card_index] = x
            return positions

        if self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS:
            # The first four Relationships panels appear in fixed slots.  They
            # do not use the Age model's left-to-right body slide.
            return {
                index: index * pitch
                for index in range(min(4, len(project.cards)))
                if index < len(starts) and frame >= starts[index]
            }

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
        movement = body_progress(local_seconds)
        if active == 0:
            positions[0] = lerp(-pitch, 0.0, movement)
        else:
            positions[active] = lerp((active - 1) * pitch, active * pitch, movement)
        return positions

    def _credits_x_for_frame(self, global_frame: int, starts: list[int]) -> float | None:
        if self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS:
            return None
        pitch = self._active_profile.layout.slot_pitch
        frame = int(global_frame)
        active = -1
        for index, start_frame in enumerate(starts[:4]):
            if frame >= start_frame:
                active = index
            else:
                break
        if active < 0:
            return REFERENCE_WIDTH
        local_time = (frame - starts[active]) / 60.0
        if active == 0:
            return lerp(REFERENCE_WIDTH, REFERENCE_WIDTH - pitch, body_progress(local_time))
        if active < 3:
            return REFERENCE_WIDTH - pitch
        if active == 3:
            return lerp(REFERENCE_WIDTH - pitch, REFERENCE_WIDTH, body_progress(local_time))
        return None

    def _draw_relationships_opening_body(
        self,
        canvas: Image.Image,
        card: Card,
        x: float,
        local_frame: int,
    ) -> None:
        layout = self._active_profile.layout
        width, height = layout.body_width, layout.body_height
        tile = Image.new("RGB", (width, height), (31, 31, 31))
        self._draw_card_body_uncached(tile, card, 0, width, height)
        draw = ImageDraw.Draw(tile)
        has_title = bool(" ".join(str(card.title or "").split()))
        has_description = bool(" ".join(str(card.description or "").split()))
        description_height = max(0, layout.body_height - layout.description_top) if has_description else 0
        rule_height = layout.divider_width if has_description else 0
        title_height = layout.title_height if has_title else 0
        image_height = max(0, height - description_height - rule_height - title_height)
        title_bottom = image_height + title_height
        description_top = title_bottom + rule_height

        artwork = relationships_artwork_reveal(local_frame)
        title = relationships_title_reveal(local_frame)
        description = relationships_description_reveal(local_frame)
        reveal_y = int(round(image_height * artwork))
        if reveal_y < image_height:
            draw.rectangle((0, reveal_y, width, image_height), fill=(31, 31, 31))
        if has_title and title < 1.0:
            draw.rectangle(
                (0, image_height, width, title_bottom),
                fill=layout.title_background,
            )
        if rule_height:
            draw.rectangle(
                (0, title_bottom, width, description_top - 1),
                fill=layout.divider_color,
            )
        if has_description and description < 1.0:
            draw.rectangle((0, description_top, width, height), fill=layout.description_background)

        opacity = clamp((local_frame + 1) / 10.0)
        left = int(round(x + layout.body_inset))
        if opacity >= 1.0:
            canvas.paste(tile, (left, 0))
        else:
            faded = Image.blend(Image.new("RGB", tile.size, self.theme.background), tile, opacity)
            canvas.paste(faded, (left, 0))

    def _draw_relationships_disclaimer(self, canvas: Image.Image, project: Project, global_frame: int) -> None:
        if not project.settings.credits_enabled or global_frame < 434 or global_frame >= 795:
            return
        opacity = clamp((global_frame - 434) / 45.0)
        pitch = self._active_profile.layout.slot_pitch
        panel = Image.new("RGBA", (pitch, REFERENCE_HEIGHT), (18, 18, 18, int(170 * opacity)))
        draw = ImageDraw.Draw(panel)
        heading = "DISCLAIMER:"
        body = project.settings.credits_top_text.strip() or (
            "This comparison video is based on public data, surveys, public comments "
            "and discussions. Values are approximate estimates and may vary."
        )
        draw.text((24, 298), heading, font=self._font(24, True, "credits"), fill=(224, 17, 27, int(255 * opacity)))
        lines = self._wrapped_lines(draw, body, self._font(22, False, "credits"), pitch - 48, max_lines=12)
        y = 332
        for line in lines:
            draw.text((24, y), line, font=self._font(22, False, "credits"), fill=(210, 210, 210, int(225 * opacity)))
            y += 28
        canvas.paste(panel.convert("RGB"), (REFERENCE_WIDTH - pitch, 0), panel.getchannel("A"))

    def _render_content_frame(
        self,
        base: Image.Image,
        project: Project,
        global_frame: int,
        starts: list[int],
    ) -> None:
        positions = self._positions_for_frame(project, global_frame, starts)
        layout = self._active_profile.layout
        relationship = self._active_profile.model_id == MODEL_TYPES_OF_RELATIONSHIPS

        body_order = sorted(positions)
        if not relationship and global_frame < self._active_profile.timeline.continuous_start_frame:
            # During the Age opening the incoming body travels underneath the
            # already settled card at the exact boundary frame.
            active = max(body_order, default=-1)
            body_order = ([active] if active >= 0 else []) + [index for index in body_order if index != active]

        for card_index in body_order:
            slot_x = positions[card_index]
            if relationship and card_index < 4 and global_frame < self._active_profile.timeline.continuous_start_frame:
                self._draw_relationships_opening_body(
                    base,
                    project.cards[card_index],
                    slot_x,
                    global_frame - starts[card_index],
                )
            else:
                self._draw_card_body(
                    base,
                    project.cards[card_index],
                    slot_x + layout.body_inset,
                    layout.body_width,
                    layout.body_height,
                )

        if relationship:
            self._draw_relationships_disclaimer(base, project, global_frame)
        else:
            credits_x = self._credits_x_for_frame(global_frame, starts)
            if credits_x is not None:
                self._draw_credits_panel(base, credits_x, project)

        for card_index in sorted(positions):
            self._draw_badge(base, project, card_index, positions[card_index], global_frame, starts)
        for card_index in sorted(positions):
            self._draw_front_artwork(
                base,
                project.cards[card_index],
                positions[card_index] + layout.body_inset,
                layout.body_width,
                layout.body_height,
            )

    def _final_content_image(self, project: Project, starts: list[int]) -> Image.Image:
        frame = max(0, content_frame_count(project) - 1)
        image = Image.new("RGB", (REFERENCE_WIDTH, REFERENCE_HEIGHT), self.theme.background)
        self._render_content_frame(image, project, frame, starts)
        return image

    def render_output_frame(self, project: Project, seconds: float) -> Image.Image:
        """Render the exact frame used by export at the project output resolution."""
        return self.render(
            project,
            seconds,
            (int(project.settings.width), int(project.settings.height)),
        )

    def render(self, project: Project, seconds: float, output_size: tuple[int, int] | None = None) -> Image.Image:
        self._active_settings = project.settings
        self._active_profile = get_reference_profile(project.settings.model_id)
        base = Image.new("RGB", (REFERENCE_WIDTH, REFERENCE_HEIGHT), self.theme.background)
        if not project.cards:
            draw = ImageDraw.Draw(base)
            draw.text((REFERENCE_WIDTH / 2, REFERENCE_HEIGHT * 0.44), "Click to Insert Data", font=self._font(64, True), fill=(242, 244, 248), anchor="mm")
            draw.text((REFERENCE_WIDTH / 2, REFERENCE_HEIGHT * 0.54), "Paste rows or import CSV / XLSX", font=self._font(30, False), fill=(150, 157, 170), anchor="mm")
            return ImageOps.pad(base, output_size, method=Image.Resampling.LANCZOS, color=self.theme.background) if output_size else base

        starts = card_start_frames(project)
        global_frame = seconds_to_frame(project, seconds)
        segment, progress, _segment_start = locate_segment(project, seconds)
        if segment is None:
            return ImageOps.pad(base, output_size, method=Image.Resampling.LANCZOS, color=self.theme.background) if output_size else base
        p = clamp(progress)
        content_end = content_frame_count(project)
        relationship = project.settings.model_id == MODEL_TYPES_OF_RELATIONSHIPS

        if segment.kind == "brand_intro":
            base = render_relationships_intro(global_frame)

        elif segment.kind == "card_cycle":
            self._render_content_frame(base, project, global_frame, starts)
            if relationship and global_frame < INTRO_OVERLAY_END_FRAME:
                identity = render_relationships_intro_overlay(global_frame)
                base.paste(identity.convert("RGB"), (0, 0), identity.getchannel("A"))

        elif segment.kind == "end_wipe":
            final = self._final_content_image(project, starts)
            local = max(0, global_frame - content_end)
            if relationship:
                # Source path: the remaining strip clears left, then the final
                # card rebounds into the three-part end layout.
                if local <= 32:
                    x = lerp(960.0, 0.0, ease_in_out_cubic(local / 32.0))
                else:
                    x = lerp(0.0, 320.0, ease_out_cubic((local - 32) / 10.0))
                self._draw_relationships_end_group(base, project, local)
                self._draw_relationships_final_card(base, project, x, content_end - 1, starts)
            else:
                # The first three Age cards drop out in ten frames; the final
                # card remains locked in the rightmost slot.
                pitch = self._active_profile.layout.slot_pitch
                drop = round(REFERENCE_HEIGHT * ease_in_out_cubic(p))
                base.paste(final.crop((0, 0, pitch * 3, REFERENCE_HEIGHT)), (0, drop))
                base.paste(final.crop((pitch * 3, 0, REFERENCE_WIDTH, REFERENCE_HEIGHT)), (pitch * 3, 0))

        elif segment.kind in {"end_rise", "end_hold", "fade"}:
            final = self._final_content_image(project, starts)
            outro_local = max(0, global_frame - content_end)
            if relationship:
                self._draw_relationships_end_group(base, project, outro_local)
                if segment.kind == "end_rise":
                    local = outro_local - self._active_profile.timeline.end_wipe_frames
                    if local <= 20:
                        x = lerp(320.0, 928.0, ease_out_cubic(local / 20.0))
                    elif local <= 35:
                        x = lerp(928.0, 780.0, ease_in_out_cubic((local - 20) / 15.0))
                    else:
                        x = 780.0
                else:
                    x = 780.0
                self._draw_relationships_final_card(base, project, x, content_end - 1, starts)
            else:
                pitch = self._active_profile.layout.slot_pitch
                base.paste(final.crop((pitch * 3, 0, REFERENCE_WIDTH, REFERENCE_HEIGHT)), (pitch * 3, 0))
                self._draw_end_group(base, self._age_end_group_top(outro_local), project)

            if segment.kind == "fade":
                fade_opacity = 1.0 - relationships_fade_alpha(global_frame) if relationship else p
                fade = Image.new("RGBA", base.size, (0, 0, 0, int(255 * fade_opacity)))
                base = Image.alpha_composite(base.convert("RGBA"), fade).convert("RGB")

        elif segment.kind == "black_tail":
            base = Image.new("RGB", (REFERENCE_WIDTH, REFERENCE_HEIGHT), self.theme.background)

        if output_size and output_size != base.size:
            target_w, target_h = max(2, int(output_size[0])), max(2, int(output_size[1]))
            fitted = ImageOps.contain(base, (target_w, target_h), method=Image.Resampling.LANCZOS)
            result = Image.new("RGB", (target_w, target_h), self.theme.background)
            result.paste(fitted, ((target_w - fitted.width) // 2, (target_h - fitted.height) // 2))
            return result
        return base

    def duration(self, project: Project) -> float:
        return total_duration(project)
