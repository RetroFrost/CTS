from __future__ import annotations

from functools import lru_cache

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont


INTRO_FRAME_COUNT = 374
INTRO_OVERLAY_END_FRAME = 550
INTRO_REFERENCE_SIZE = (1920, 1080)
INTRO_TEXT = "Infinite\nComparison"


def clamp(value: float, low: float = 0.0, high: float = 1.0) -> float:
    return max(low, min(high, value))


def smoothstep(value: float) -> float:
    value = clamp(value)
    return value * value * (3.0 - 2.0 * value)


def ease_out_cubic(value: float) -> float:
    value = clamp(value)
    return 1.0 - (1.0 - value) ** 3


def lerp(start: float, end: float, amount: float) -> float:
    return start + (end - start) * amount


@lru_cache(maxsize=16)
def _font(size: int) -> ImageFont.ImageFont:
    try:
        return ImageFont.load_default(size=size)
    except TypeError:
        return ImageFont.load_default()


def _draw_multistroke_arc(
    image: Image.Image,
    box: tuple[int, int, int, int],
    start: float,
    end: float,
    accent: tuple[int, int, int, int],
) -> None:
    """Draw the layered metallic/neon tube used by the canonical intro mark."""
    draw = ImageDraw.Draw(image)
    strokes = (
        (28, (64, 47, 18, 150)),
        (23, (8, 8, 8, 255)),
        (18, (180, 177, 166, 255)),
        (12, (244, 242, 227, 255)),
        (7, accent),
    )
    for width, colour in strokes:
        draw.arc(box, start=start, end=end, fill=colour, width=width)


def _final_mark_layer() -> Image.Image:
    layer = Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))
    # Final source bounds measured from frames 150..373.
    left_box = (585, 347, 937, 699)
    right_box = (983, 347, 1335, 699)
    _draw_multistroke_arc(layer, left_box, 43, 317, (198, 233, 0, 255))
    _draw_multistroke_arc(layer, right_box, 223, 497, (238, 91, 127, 255))

    draw = ImageDraw.Draw(layer)
    draw.line((914, 405, 1007, 637), fill=(64, 72, 73, 255), width=4)
    draw.line((913, 637, 1007, 405), fill=(76, 82, 84, 255), width=4)
    draw.line((915, 406, 1005, 635), fill=(153, 159, 159, 125), width=1)
    draw.line((915, 635, 1005, 406), fill=(153, 159, 159, 125), width=1)
    return layer


@lru_cache(maxsize=1)
def _mark_with_glow() -> Image.Image:
    mark = _final_mark_layer()
    alpha = mark.getchannel("A")
    glow = Image.new("RGBA", mark.size, (229, 185, 62, 0))
    glow.putalpha(alpha.filter(ImageFilter.GaussianBlur(18)))
    result = Image.new("RGBA", mark.size, (0, 0, 0, 0))
    result.alpha_composite(glow)
    result.alpha_composite(mark)
    return result


def _tail_opacity(frame: int) -> float:
    # Reference evidence: the completed logo remains fully visible while the
    # first card starts entering (frames 374..450), then fades while the card
    # and credits continue underneath. It is gone by approximately frame 550.
    if frame < 450:
        return 1.0
    if frame >= INTRO_OVERLAY_END_FRAME:
        return 0.0
    return 1.0 - smoothstep((frame - 450) / (INTRO_OVERLAY_END_FRAME - 450))


def _transform_mark(frame_index: int) -> Image.Image:
    frame = max(0, int(frame_index))
    shape_frame = min(frame, INTRO_FRAME_COUNT - 1)
    final = _mark_with_glow().copy()

    if shape_frame < 18:
        opacity = 0.0
        scale = 2.55
        rotation = -19.0
        offset_y = -45.0
    elif shape_frame < 92:
        progress = ease_out_cubic((shape_frame - 18) / 74.0)
        opacity = smoothstep((shape_frame - 18) / 18.0)
        scale = lerp(2.55, 1.22, progress)
        rotation = lerp(-19.0, 4.5, progress)
        offset_y = lerp(-45.0, 7.0, progress)
    elif shape_frame < 151:
        progress = smoothstep((shape_frame - 92) / 59.0)
        opacity = 1.0
        scale = lerp(1.22, 1.0, progress)
        rotation = lerp(4.5, 0.0, progress)
        offset_y = lerp(7.0, 0.0, progress)
    else:
        opacity = 1.0
        scale = 1.0
        rotation = 0.0
        offset_y = 0.0

    opacity *= _tail_opacity(frame)
    if opacity <= 0.0:
        return Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))

    crop = final.crop((520, 285, 1400, 755))
    scaled = crop.resize(
        (max(1, round(crop.width * scale)), max(1, round(crop.height * scale))),
        Image.Resampling.LANCZOS,
    )
    if abs(rotation) > 1e-6:
        scaled = scaled.rotate(rotation, resample=Image.Resampling.BICUBIC, expand=True)
    if opacity < 1.0:
        scaled.putalpha(scaled.getchannel("A").point(lambda value: round(value * opacity)))

    result = Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))
    x = round((INTRO_REFERENCE_SIZE[0] - scaled.width) / 2)
    y = round((INTRO_REFERENCE_SIZE[1] - scaled.height) / 2 + offset_y - 20)
    result.alpha_composite(scaled, (x, y))
    return result


def _text_layer(frame_index: int) -> Image.Image:
    layer = Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))
    frame = max(0, int(frame_index))
    shape_frame = min(frame, INTRO_FRAME_COUNT - 1)
    if shape_frame < 240 or frame >= INTRO_OVERLAY_END_FRAME:
        return layer

    character_progress = clamp((shape_frame - 240) / 90.0)
    visible_count = round(len(INTRO_TEXT.replace("\n", "")) * character_progress)
    first = "Infinite"
    second = "Comparison"
    first_visible = first[: min(len(first), visible_count)]
    second_visible = second[: max(0, visible_count - len(first))]
    opacity = _tail_opacity(frame)
    colour = (245, 245, 245, round(255 * opacity))

    draw = ImageDraw.Draw(layer)
    font = _font(52)
    if first_visible:
        draw.text((960, 701), first_visible, font=font, fill=colour, anchor="ma")
    if second_visible:
        draw.text((960, 758), second_visible, font=font, fill=colour, anchor="ma")
    return layer


def render_relationships_intro_overlay(frame_index: int) -> Image.Image:
    """Return the transparent identity layer for a source-frame index.

    The layer intentionally remains valid after the nominal 374-frame lead-in,
    because the canonical reference overlaps it with the first card and credits
    animation through frame 549.
    """
    frame = max(0, int(frame_index))
    overlay = Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))
    if frame == 0 or frame >= INTRO_OVERLAY_END_FRAME:
        return overlay
    overlay.alpha_composite(_transform_mark(frame))
    overlay.alpha_composite(_text_layer(frame))
    return overlay


def render_relationships_intro(frame_index: int) -> Image.Image:
    """Render one full 1920x1080 lead-in frame before card content begins."""
    frame = max(0, min(INTRO_FRAME_COUNT - 1, int(frame_index)))
    base = Image.new("RGB", INTRO_REFERENCE_SIZE, (9, 9, 9))
    overlay = render_relationships_intro_overlay(frame)
    base.paste(overlay.convert("RGB"), (0, 0), overlay.getchannel("A"))
    return base


def intro_signature(frame_index: int) -> tuple[tuple[int, int], tuple[int, int, int, int] | None]:
    """Small deterministic regression signature used without storing PNGs."""
    image = render_relationships_intro(frame_index)
    difference = ImageChops.difference(image, Image.new("RGB", image.size, (9, 9, 9)))
    return image.size, difference.getbbox()
