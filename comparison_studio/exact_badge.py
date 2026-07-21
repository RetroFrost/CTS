from __future__ import annotations

import math
from dataclasses import dataclass

from PIL import Image, ImageChops, ImageDraw, ImageFilter

from .renderer import _font


@dataclass(frozen=True, slots=True)
class BadgeMotion:
    scale_x: float
    scale_y: float
    translation_x: float
    translation_y: float


@dataclass(frozen=True, slots=True)
class BadgeMotionKeyframe:
    at: float
    scale_x: float
    scale_y: float
    translation_x: float
    translation_y: float


# These values are measured against the first badge in the supplied video's first five
# seconds. They intentionally mirror the Android renderer's keyframes.
_MOTION = (
    BadgeMotionKeyframe(0.00, 0.08, 1.18, -0.72, -0.71),
    BadgeMotionKeyframe(0.30, 0.76, 1.12, -0.32, -0.10),
    BadgeMotionKeyframe(0.58, 0.98, 1.02, -0.12, -0.05),
    BadgeMotionKeyframe(0.76, 1.01, 1.025, -0.045, -0.035),
    BadgeMotionKeyframe(0.90, 1.006, 0.985, -0.012, 0.00),
    BadgeMotionKeyframe(1.00, 1.00, 1.00, 0.00, 0.00),
)


def clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return max(minimum, min(maximum, float(value)))


def smoothstep(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def ease_out_cubic(value: float) -> float:
    value = clamp(value)
    return 1.0 - (1.0 - value) ** 3


def track_progress(value: float, start: float, end: float) -> float:
    return clamp((value - start) / max(0.000001, end - start))


def _lerp(start: float, end: float, amount: float) -> float:
    return start + (end - start) * amount


def badge_motion_at(progress: float) -> BadgeMotion:
    progress = clamp(progress)
    if progress <= _MOTION[0].at:
        first = _MOTION[0]
        return BadgeMotion(
            first.scale_x,
            first.scale_y,
            first.translation_x,
            first.translation_y,
        )
    for lower, upper in zip(_MOTION, _MOTION[1:]):
        if progress <= upper.at:
            local = smoothstep((progress - lower.at) / (upper.at - lower.at))
            return BadgeMotion(
                _lerp(lower.scale_x, upper.scale_x, local),
                _lerp(lower.scale_y, upper.scale_y, local),
                _lerp(lower.translation_x, upper.translation_x, local),
                _lerp(lower.translation_y, upper.translation_y, local),
            )
    last = _MOTION[-1]
    return BadgeMotion(last.scale_x, last.scale_y, last.translation_x, last.translation_y)


def split_badge_label(value: str) -> list[str]:
    words = str(value or "").strip().split()
    if len(words) <= 2:
        return words
    best_index = 1
    best_difference = 1_000_000
    for index in range(1, len(words)):
        left = len(" ".join(words[:index]))
        right = len(" ".join(words[index:]))
        difference = abs(left - right)
        if difference < best_difference:
            best_difference = difference
            best_index = index
    return [" ".join(words[:best_index]), " ".join(words[best_index:])]


def _hexagon_points(width: int, height: int) -> list[tuple[int, int]]:
    return [
        (width // 2, 0),
        (width, round(height * 0.22)),
        (width, round(height * 0.78)),
        (width // 2, height),
        (0, round(height * 0.78)),
        (0, round(height * 0.22)),
    ]


def _fit_line_font(text: str, target_size: int, maximum_width: int):
    size = max(7, int(target_size))
    while size > 7:
        font = _font(size, True)
        bounds = font.getbbox(text)
        if bounds[2] - bounds[0] <= maximum_width:
            return font
        size -= 1
    return _font(7, True)


def _draw_motion_line(
    canvas: Image.Image,
    text: str,
    progress: float,
    center_y: float,
    target_size: int,
) -> None:
    if not text or progress <= 0.0:
        return

    width, height = canvas.size
    eased = ease_out_cubic(progress)
    reverse = 1.0 - eased
    base_x = -width * 0.18 * reverse
    base_y = -height * 0.10 * reverse
    trail_strength = clamp(1.0 - abs(progress * 2.0 - 1.0))
    font = _fit_line_font(text, target_size, round(width * 0.82))
    center = (width / 2, height * center_y)

    for trail in range(3, 0, -1):
        trail_layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        trail_draw = ImageDraw.Draw(trail_layer)
        x = center[0] + base_x * (1.0 + trail * 0.28)
        y = center[1] + base_y * (1.0 + trail * 0.24)
        alpha = round(255 * 0.17 * trail_strength * (4 - trail))
        trail_draw.text(
            (round(x), round(y)),
            text,
            font=font,
            fill=(255, 255, 255, alpha),
            anchor="mm",
        )
        trail_layer = trail_layer.filter(ImageFilter.GaussianBlur(1.2 + trail * 0.8))
        canvas.alpha_composite(trail_layer)

    main = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(main)
    x = center[0] + base_x
    y = center[1] + base_y
    alpha = round(255 * eased)
    shadow_offset = max(2, round(width * 0.012))
    draw.text(
        (round(x + shadow_offset), round(y + shadow_offset)),
        text,
        font=font,
        fill=(0, 0, 0, round(alpha * 0.48)),
        anchor="mm",
    )
    draw.text(
        (round(x), round(y)),
        text,
        font=font,
        fill=(255, 255, 255, alpha),
        anchor="mm",
    )
    canvas.alpha_composite(main)


def _draw_sheen(surface: Image.Image, mask: Image.Image, phase: float) -> None:
    progress = track_progress(phase, 0.79, 1.00)
    if progress <= 0.0 or progress >= 1.0:
        return

    width, height = surface.size
    fade = max(0.0, math.sin(progress * math.pi))
    center_x = -width * 0.38 + width * 1.78 * progress
    band_width = max(4, round(width * 0.23))
    band = Image.new("RGBA", (band_width * 2, round(height * 1.56)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(band)
    for x in range(band.width):
        normalized = abs((x / max(1, band.width - 1)) * 2.0 - 1.0)
        intensity = max(0.0, 1.0 - normalized)
        alpha = round(255 * 0.48 * fade * intensity * intensity)
        draw.line((x, 0, x, band.height), fill=(255, 255, 255, alpha))
    band = band.filter(ImageFilter.GaussianBlur(max(2, round(width * 0.022))))
    band = band.rotate(-18, resample=Image.Resampling.BICUBIC, expand=True)

    sheen = Image.new("RGBA", surface.size, (0, 0, 0, 0))
    sheen.alpha_composite(
        band,
        (
            round(center_x - band.width / 2),
            round(height / 2 - band.height / 2),
        ),
    )
    sheen.putalpha(ImageChops.multiply(sheen.getchannel("A"), mask))
    surface.alpha_composite(sheen)


def _paste_clipped(target: Image.Image, source: Image.Image, x: int, y: int) -> None:
    left = max(0, x)
    top = max(0, y)
    right = min(target.width, x + source.width)
    bottom = min(target.height, y + source.height)
    if right <= left or bottom <= top:
        return
    crop = source.crop((left - x, top - y, right - x, bottom - y))
    target.alpha_composite(crop, (left, top))


def render_reference_badge_layer(
    card_width: int,
    card_height: int,
    primary: str,
    secondary: str,
    phase: float,
) -> Image.Image:
    """Return a full-card transparent layer containing the exact animated badge."""
    result = Image.new("RGBA", (card_width, card_height), (0, 0, 0, 0))
    phase = clamp(phase)
    if phase <= 0.0:
        return result

    badge_width = max(24, round(card_width * 0.726))
    badge_height = max(28, round(card_height * 0.350))
    final_x = round(card_width * 0.164)
    final_y = round(card_height * 0.009)

    surface = Image.new("RGBA", (badge_width, badge_height), (0, 0, 0, 0))
    mask = Image.new("L", surface.size, 0)
    points = _hexagon_points(badge_width - 1, badge_height - 1)
    ImageDraw.Draw(mask).polygon(points, fill=255)

    gradient = Image.new("RGBA", surface.size, (0, 0, 0, 0))
    gradient_draw = ImageDraw.Draw(gradient)
    for y in range(badge_height):
        vertical = y / max(1, badge_height - 1)
        center_light = max(0.0, 1.0 - abs(vertical - 0.42) * 1.35)
        red = round(214 + 29 * center_light)
        green = round(0 + 16 * center_light)
        blue = round(8 + 13 * center_light)
        gradient_draw.line((0, y, badge_width, y), fill=(red, green, blue, 255))
    gradient.putalpha(mask)
    surface.alpha_composite(gradient)

    border = ImageDraw.Draw(surface)
    border.line(
        points + [points[0]],
        fill=(185, 0, 8, 255),
        width=max(1, round(badge_width * 0.006)),
        joint="curve",
    )

    _draw_sheen(surface, mask, phase)

    _draw_motion_line(
        surface,
        str(primary or ""),
        track_progress(phase, 0.33, 0.48),
        0.31,
        round(badge_width * 0.225),
    )
    label_lines = split_badge_label(secondary)
    if len(label_lines) == 1:
        _draw_motion_line(
            surface,
            label_lines[0],
            track_progress(phase, 0.46, 0.64),
            0.66,
            round(badge_width * 0.105),
        )
    elif len(label_lines) >= 2:
        _draw_motion_line(
            surface,
            label_lines[0],
            track_progress(phase, 0.41, 0.57),
            0.58,
            round(badge_width * 0.105),
        )
        _draw_motion_line(
            surface,
            label_lines[1],
            track_progress(phase, 0.57, 0.73),
            0.75,
            round(badge_width * 0.105),
        )

    motion = badge_motion_at(phase)
    transformed_width = max(1, round(badge_width * motion.scale_x))
    transformed_height = max(1, round(badge_height * motion.scale_y))
    transformed = surface.resize(
        (transformed_width, transformed_height),
        Image.Resampling.LANCZOS,
    )
    x = round(
        final_x
        + (badge_width - transformed_width) / 2
        + badge_width * motion.translation_x
    )
    y = round(
        final_y
        + (badge_height - transformed_height) / 2
        + badge_height * motion.translation_y
    )

    shadow_alpha = transformed.getchannel("A").filter(
        ImageFilter.GaussianBlur(max(3, round(card_width * 0.015)))
    )
    shadow_alpha = shadow_alpha.point(lambda value: round(value * 0.58))
    shadow = Image.new("RGBA", transformed.size, (0, 0, 0, 0))
    shadow.putalpha(shadow_alpha)
    shadow_offset = max(2, round(card_width * 0.012))
    _paste_clipped(result, shadow, x + shadow_offset, y + shadow_offset)
    _paste_clipped(result, transformed, x, y)
    return result
