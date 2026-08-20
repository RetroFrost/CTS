"""Typography and text formatting helpers for high-fidelity rendering."""

import os
from typing import List, Tuple, Optional
from PIL import ImageFont, ImageDraw, Image

# Standard system font search paths
FONT_PATHS = [
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
]

_font_cache = {}

def get_font(size: int, bold: bool = True, custom_path: Optional[str] = None) -> ImageFont.FreeTypeFont:
    """Retrieve or cache a TTF font."""
    key = (size, bold, custom_path)
    if key in _font_cache:
        return _font_cache[key]

    if custom_path and os.path.exists(custom_path):
        font = ImageFont.truetype(custom_path, size)
        _font_cache[key] = font
        return font

    # Find closest matching font
    target_files = [f for f in FONT_PATHS if ("Bold" in f) == bold]
    fallback_files = target_files + FONT_PATHS

    for path in fallback_files:
        if os.path.exists(path):
            try:
                font = ImageFont.truetype(path, size)
                _font_cache[key] = font
                return font
            except Exception:
                continue

    # Fallback to default
    font = ImageFont.load_default()
    _font_cache[key] = font
    return font

def wrap_text(text: str, font: ImageFont.ImageFont, max_width: int) -> List[str]:
    """Wrap text to fit within max_width pixels."""
    words = text.split()
    if not words:
        return []

    lines = []
    current_line = []

    for word in words:
        test_line = " ".join(current_line + [word])
        bbox = font.getbbox(test_line)
        w = bbox[2] - bbox[0]
        if w <= max_width or not current_line:
            current_line.append(word)
        else:
            lines.append(" ".join(current_line))
            current_line = [word]

    if current_line:
        lines.append(" ".join(current_line))

    return lines

def draw_tracked_text(
    draw: ImageDraw.ImageDraw,
    xy: Tuple[int, int],
    text: str,
    font: ImageFont.ImageFont,
    fill: Tuple[int, int, int, int],
    letter_spacing: int = 3,
    anchor: str = "mm"
):
    """Draw text with extra letter-spacing (tracking) and anchor support."""
    if not text:
        return

    # Calculate total width with tracking
    char_widths = []
    for ch in text:
        bbox = font.getbbox(ch)
        char_widths.append(bbox[2] - bbox[0])
    
    total_w = sum(char_widths) + (len(text) - 1) * letter_spacing
    sample_bbox = font.getbbox(text)
    total_h = sample_bbox[3] - sample_bbox[1]

    cx, cy = xy

    if "m" in anchor:  # middle X
        start_x = cx - total_w / 2
    elif "r" in anchor:  # right X
        start_x = cx - total_w
    else:  # left X
        start_x = cx

    if "m" in anchor:  # middle Y
        start_y = cy - total_h / 2
    elif "b" in anchor:  # bottom Y
        start_y = cy - total_h
    else:  # top Y
        start_y = cy

    cur_x = start_x
    for i, ch in enumerate(text):
        draw.text((cur_x, start_y), ch, font=font, fill=fill)
        cur_x += char_widths[i] + letter_spacing
