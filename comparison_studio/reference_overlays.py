from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from .shared_contract import (
    END_HOLD_SECONDS,
    FADE_SECONDS,
    INTRO_TAIL_HOLD_SECONDS,
    OUTRO_CONTENT_DELAY_SECONDS,
    OUTRO_COVER_SECONDS,
    OUTRO_HOLD_SECONDS,
    REVEAL_SECONDS,
    SCROLL_SECONDS,
    VISIBLE_CARDS,
    material_ease,
)


def _parts(card_count: int) -> tuple[float, float, float]:
    intro = min(card_count, VISIBLE_CARDS) * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
    scroll = max(0, card_count - VISIBLE_CARDS) * SCROLL_SECONDS
    return intro, scroll, intro + scroll


def intro_credits_visible(card_count: int, model_time: float) -> bool:
    intro, _scroll, _end = _parts(card_count)
    return card_count > 0 and model_time < intro


def outro_cover_progress(card_count: int, model_time: float) -> float:
    _intro, _scroll, scroll_end = _parts(card_count)
    return material_ease((model_time - scroll_end - END_HOLD_SECONDS) / max(0.001, OUTRO_COVER_SECONDS))


def outro_content_alpha(card_count: int, model_time: float) -> float:
    _intro, _scroll, scroll_end = _parts(card_count)
    start = scroll_end + END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS
    x = max(0.0, min(1.0, (model_time - start) / 0.12))
    return x * x * (3.0 - 2.0 * x)


def _font(size: int, bold: bool = False):
    candidates = [
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
    ]
    for path in candidates:
        if path.exists():
            return ImageFont.truetype(str(path), size=max(8, size))
    return ImageFont.load_default()


def _center(draw: ImageDraw.ImageDraw, box, text: str, fill, size: int, bold: bool = False):
    font = _font(size, bold)
    left, top, right, bottom = box
    draw.multiline_text(
        ((left + right) / 2, (top + bottom) / 2),
        text,
        font=font,
        fill=fill,
        anchor="mm",
        align="center",
        spacing=max(2, size // 4),
    )


def draw_intro_credits(frame: Image.Image) -> None:
    draw = ImageDraw.Draw(frame)
    width, height = frame.size
    left = round(width * 0.75)
    draw.rectangle((left, 0, width, height), fill=(32, 32, 32, 255))
    _center(draw, (left + 16, 10, width - 16, height * 0.14), "The values presented are average milestones\nand may vary.", (255, 255, 255, 255), round(height * 0.017))
    draw.line((left + width * 0.025, height * 0.21, width - width * 0.025, height * 0.21), fill=(190, 190, 190, 255), width=max(1, width // 960))
    _center(draw, (left, height * 0.24, width, height * 0.35), "Credits", (255, 255, 255, 255), round(height * 0.043), True)
    _center(draw, (left + 8, height * 0.35, width - 8, height * 0.86), "Lead Research & Sourcing\n\nIndependent Fact Check\n\nLead Graphic Designer\n\nEdit & Post-Production\n\nThumbnail Designer\n\nVideo Idea & Quality Check", (255, 255, 255, 255), round(height * 0.017), True)
    _center(draw, (left + 8, height * 0.88, width - 8, height - 8), "DISCLAIMER\nCOMMUNITY DISCUSSIONS AND RELEVANT SOURCES", (200, 200, 200, 255), round(height * 0.009))


def draw_outro(frame: Image.Image, cover: float, alpha: float) -> None:
    draw = ImageDraw.Draw(frame)
    width, height = frame.size
    right = round(width * 0.75)
    if cover > 0.0:
        draw.rectangle((0, 0, right, round(height * max(0.0, min(1.0, cover)))), fill=(17, 17, 17, 255))
    if alpha <= 0.0:
        return
    overlay = Image.new("RGBA", frame.size, (0, 0, 0, 0))
    odraw = ImageDraw.Draw(overlay)
    odraw.rectangle((0, 0, right, height), fill=(17, 17, 17, round(255 * alpha)))
    margin = round(width * 0.02)
    gap = round(width * 0.025)
    top = round(height * 0.17)
    bottom = round(height * 0.53)
    box_width = (right - margin * 2 - gap) // 2
    red = (224, 0, 0, round(255 * alpha))
    odraw.rounded_rectangle((margin, top, margin + box_width, bottom), radius=14, fill=red)
    odraw.rounded_rectangle((margin + box_width + gap, top, right - margin, bottom), radius=14, fill=red)
    _center(odraw, (margin, top, margin + box_width, top + height * 0.12), "BEST VIDEO FOR YOU", (255, 255, 255, round(255 * alpha)), round(height * 0.027), True)
    _center(odraw, (margin + box_width + gap, top, right - margin, top + height * 0.12), "NEWEST VIDEO", (255, 255, 255, round(255 * alpha)), round(height * 0.027), True)
    credits = (round(right * 0.32), round(height * 0.62), round(right * 0.68), round(height * 0.84))
    odraw.rounded_rectangle(credits, radius=14, fill=(98, 95, 86, round(255 * alpha)))
    _center(odraw, credits, "Video Made By\n\nResearch · Editing · Design · Quality Check", (255, 255, 255, round(255 * alpha)), round(height * 0.017), True)
    frame.alpha_composite(overlay)
