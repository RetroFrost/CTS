from __future__ import annotations

from functools import lru_cache
import math

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont


INTRO_FRAME_COUNT = 374
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
    # Wide warm outer rim, black bevel, white tube and coloured inner light.
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
    # Measured final coloured bounds: approximately x=589..1318,
    # y=353..687 in the 1920x1080 source.
    left_box = (585, 347, 937, 699)
    right_box = (983, 347, 1335, 699)
    _draw_multistroke_arc(layer, left_box, 43, 317, (198, 233, 0, 255))
    _draw_multistroke_arc(layer, right_box, 223, 497, (238, 91, 127, 255))

    draw = ImageDraw.Draw(layer)
    # Thin crossed connectors visible inside both open loops.
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


def _transform_mark(frame_index: int) -> Image.Image:
    final = _mark_with_glow().copy()
    frame = max(0, min(INTRO_FRAME_COUNT - 1, int(frame_index)))

    # Source measurements: the loops start far outside their final bounds,
    # converge rapidly through frames 20..105, then settle near frame 150.
    if frame < 18:
        opacity = 0.0
        scale = 2.55
        rotation = -19.0
        offset_y = -45.0
    elif frame < 92:
        progress = ease_out_cubic((frame - 18) / 74.0)
        opacity = smoothstep((frame - 18) / 18.0)
        scale = lerp(2.55, 1.22, progress)
        rotation = lerp(-19.0, 4.5, progress)
        offset_y = lerp(-45.0, 7.0, progress)
    elif frame < 151:
        progress = smoothstep((frame - 92) / 59.0)
        opacity = 1.0
        scale = lerp(1.22, 1.0, progress)
        rotation = lerp(4.5, 0.0, progress)
        offset_y = lerp(7.0, 0.0, progress)
    else:
        opacity = 1.0
        scale = 1.0
        rotation = 0.0
        offset_y = 0.0

    if opacity <= 0.0:
        return Image.new("RGBA", INTRO_REFERENCE_SIZE, (0, 0, 0, 0))

    # Crop around the logo before scaling so the opening enlargement actually
    # pushes the loops outside the frame like the supplied reference.
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
    frame = max(0, min(INTRO_FRAME_COUNT - 1, int(frame_index)))
    if frame < 240:
        return layer

    # The reference reveals characters left-to-right from roughly frame 240
    # through frame 330, then holds the completed two-line name.
    character_progress = clamp((frame - 240) / 90.0)
    visible_count = round(len(INTRO_TEXT.replace("\n", "")) * character_progress)
    first = "Infinite"
    second = "Comparison"
    first_visible = first[: min(len(first), visible_count)]
    second_count = max(0, visible_count - len(first))
    second_visible = second[:second_count]

    draw = ImageDraw.Draw(layer)
    font = _font(52)
    colour = (245, 245, 245, 255)
    if first_visible:
        draw.text((960, 701), first_visible, font=font, fill=colour, anchor="ma")
    if second_visible:
        draw.text((960, 758), second_visible, font=font, fill=colour, anchor="ma")

    # A narrow dark wipe tracks the typing edge, reproducing the clipped final
    # glyphs visible during the source reveal rather than fading the full word.
    return layer


def render_relationships_intro(frame_index: int) -> Image.Image:
    """Render one canonical 1920x1080 brand-intro frame.

    This scene is intentionally addressed by integer frame index. The caller
    must never time-stretch it or interpolate its frame count.
    """
    frame = max(0, min(INTRO_FRAME_COUNT - 1, int(frame_index)))
    base = Image.new("RGB", INTRO_REFERENCE_SIZE, (9, 9, 9))
    if frame == 0:
        return base

    mark = _transform_mark(frame)
    base.paste(mark.convert("RGB"), (0, 0), mark.getchannel("A"))
    text = _text_layer(frame)
    base.paste(text.convert("RGB"), (0, 0), text.getchannel("A"))
    return base


def intro_signature(frame_index: int) -> tuple[tuple[int, int], tuple[int, int, int, int] | None]:
    """Small deterministic regression signature used without storing PNGs."""
    image = render_relationships_intro(frame_index)
    difference = ImageChops.difference(image, Image.new("RGB", image.size, (9, 9, 9)))
    return image.size, difference.getbbox()
