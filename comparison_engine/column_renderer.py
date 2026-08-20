"""Full Column Renderer with animated badge drops, shines, and card slide-ups."""

import os
from typing import Optional
from PIL import Image, ImageDraw
from .models import ComparisonItem
from .badge_renderer import render_badge
from .card_renderer import render_bottom_card

def create_fallback_background(width: int, height: int, item_index: int) -> Image.Image:
    """Generate a clean procedural background gradient."""
    bg = Image.new("RGBA", (width, height), (240, 240, 245, 255))
    draw = ImageDraw.Draw(bg, "RGBA")
    
    palettes = [
        ((30, 80, 50), (120, 180, 100)),   # Prehistoric Green
        ((180, 120, 50), (230, 200, 140)), # Ancient Sand
        ((40, 70, 130), (130, 180, 230)),  # Medieval Blue
        ((120, 40, 80), (210, 140, 180)),  # Modern Magenta
        ((20, 120, 130), (100, 200, 210)), # Future Cyan
    ]
    p_dark, p_light = palettes[item_index % len(palettes)]
    
    for y in range(height):
        t = y / height
        r = int(p_light[0] * (1 - t) + p_dark[0] * t)
        g = int(p_light[1] * (1 - t) + p_dark[1] * t)
        b = int(p_light[2] * (1 - t) + p_dark[2] * t)
        draw.line([(0, y), (width, y)], fill=(r, g, b, 255))

    return bg

def render_column_background(
    item: ComparisonItem,
    width: int = 480,
    height: int = 1080,
    item_index: int = 0
) -> Image.Image:
    """Renders just the background image + right border separator."""
    if item.image_path and os.path.exists(item.image_path):
        try:
            raw_img = Image.open(item.image_path).convert("RGBA")
            img_ratio = raw_img.width / raw_img.height
            target_ratio = width / height
            if img_ratio > target_ratio:
                new_h = height
                new_w = int(raw_img.width * (height / raw_img.height))
                resized = raw_img.resize((new_w, new_h), Image.Resampling.LANCZOS)
                crop_x = (new_w - width) // 2
                col_img = resized.crop((crop_x, 0, crop_x + width, height))
            else:
                new_w = width
                new_h = int(raw_img.height * (width / raw_img.width))
                resized = raw_img.resize((new_w, new_h), Image.Resampling.LANCZOS)
                crop_y = (new_h - height) // 2
                col_img = resized.crop((0, crop_y, width, crop_y + height))
        except Exception:
            col_img = create_fallback_background(width, height, item_index)
    else:
        col_img = create_fallback_background(width, height, item_index)

    draw = ImageDraw.Draw(col_img, "RGBA")
    draw.line([(width - 1, 0), (width - 1, height)], fill=(0, 0, 0, 255), width=2)
    return col_img

def render_column(
    item: ComparisonItem,
    width: int = 480,
    height: int = 1080,
    item_index: int = 0,
    entry_progress: float = 1.0,
    shine_progress: Optional[float] = None,
    base_bg: Optional[Image.Image] = None
) -> Image.Image:
    """Renders a complete column with all dynamic badge and card animations."""
    if base_bg is not None:
        col_img = base_bg.copy()
    else:
        col_img = render_column_background(item, width, height, item_index)

    # 1. Badge overlay with drop-down and shine
    badge = render_badge(
        width=width,
        height=height,
        value_text=item.badge_value,
        unit_text=item.badge_unit,
        base_color=item.badge_color,
        entry_progress=entry_progress,
        shine_progress=shine_progress
    )
    col_img = Image.alpha_composite(col_img, badge)

    # 2. Bottom card overlay with slide-up
    card = render_bottom_card(
        width=width,
        height=height,
        title=item.title,
        description=item.description,
        entry_progress=entry_progress
    )
    col_img = Image.alpha_composite(col_img, card)

    return col_img
