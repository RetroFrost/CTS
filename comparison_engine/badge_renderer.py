"""Hexagonal Year/Value Badge Renderer with 3D Bevel, Drop-Down Entry, and Specular Sweep."""

from typing import Tuple, Optional
from PIL import Image, ImageDraw, ImageFilter
from .typography import get_font, draw_tracked_text
from .animation_curves import clamp, ease_out_back, ease_out_cubic

def render_badge(
    width: int = 480,
    height: int = 1080,
    value_text: str = "400K",
    unit_text: str = "YEARS AGO",
    base_color: Tuple[int, int, int] = (200, 16, 46),
    entry_progress: float = 1.0,
    shine_progress: Optional[float] = None
) -> Image.Image:
    """
    Renders the top hexagonal badge with drop-down entry animation and specular sweep.
    """
    badge_layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    
    # Drop-down animation offset
    entry_p = clamp(entry_progress)
    if entry_p <= 0.0:
        return badge_layer
        
    y_offset = int((1.0 - ease_out_back(entry_p, overshoot=1.2)) * -260)
    
    x_left = 35
    x_right = width - 35  # 445
    x_center = width // 2  # 240
    y_top = y_offset
    y_side = 195 + y_offset
    y_tip = 245 + y_offset
    
    outer_poly = [
        (x_left, y_top),
        (x_right, y_top),
        (x_right, y_side),
        (x_center, y_tip),
        (x_left, y_side)
    ]
    
    # 1. Soft Drop Shadow
    shadow_img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_img)
    shadow_draw.polygon([(x, y + 6) for x, y in outer_poly], fill=(0, 0, 0, 120))
    shadow_img = shadow_img.filter(ImageFilter.GaussianBlur(radius=6))
    badge_layer = Image.alpha_composite(badge_layer, shadow_img)

    draw = ImageDraw.Draw(badge_layer, "RGBA")
    
    # 2. Base Fill & Outer Dark Outline
    draw.polygon(outer_poly, fill=base_color + (255,), outline=(60, 0, 10, 255), width=3)
    
    # 3. 3D Bevel Facets
    r, g, b = base_color
    darker_tint = (max(0, r - 40), max(0, g - 6), max(0, b - 15), 255)
    lighter_tint = (min(255, r + 30), min(255, g + 25), min(255, b + 25), 255)
    
    draw.polygon([
        (x_left + 4, y_side - 2),
        (x_center, y_tip - 4),
        (x_center, y_tip - 15),
        (x_left + 4, y_side - 15)
    ], fill=darker_tint)
    draw.line([(x_left + 2, y_top + 2), (x_right - 2, y_top + 2)], fill=lighter_tint, width=2)
    
    # 4. Static Specular Glass Glare
    shine_layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    shine_draw = ImageDraw.Draw(shine_layer)
    shine_draw.polygon([
        (x_center - 40, y_top),
        (x_right - 2, y_top),
        (x_right - 2, y_side - 60),
        (x_center, y_tip - 10)
    ], fill=(255, 255, 255, 50))
    shine_draw.polygon([
        (x_center + 30, y_top),
        (x_right - 2, y_top),
        (x_right - 2, y_side - 110)
    ], fill=(255, 255, 255, 60))

    # 5. Dynamic Light Ray Sweep Animation
    if shine_progress is not None:
        sp = clamp(shine_progress)
        sweep_x = int(x_left - 120 + sp * (width + 240))
        sweep_w = 60
        
        sweep_poly = [
            (sweep_x, y_top),
            (sweep_x + sweep_w, y_top),
            (sweep_x + sweep_w - 70, y_tip + 30),
            (sweep_x - 70, y_tip + 30)
        ]
        
        sweep_mask = Image.new("L", (width, height), 0)
        mask_draw = ImageDraw.Draw(sweep_mask)
        mask_draw.polygon(outer_poly, fill=255)
        
        sweep_img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        sweep_draw = ImageDraw.Draw(sweep_img)
        sweep_draw.polygon(sweep_poly, fill=(255, 255, 255, 110))
        
        sweep_final = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        sweep_final.paste(sweep_img, (0, 0), sweep_mask)
        shine_layer = Image.alpha_composite(shine_layer, sweep_final)

    badge_layer = Image.alpha_composite(badge_layer, shine_layer)
    draw = ImageDraw.Draw(badge_layer, "RGBA")
    
    # 6. Inner Border Rim
    inner_poly = [
        (x_left + 4, y_top + 3),
        (x_right - 4, y_top + 3),
        (x_right - 4, y_side - 2),
        (x_center, y_tip - 5),
        (x_left + 4, y_side - 2)
    ]
    draw.polygon(inner_poly, outline=(255, 255, 255, 45), width=1)

    # 7. Typography (Value & Tracked Unit)
    font_val = get_font(size=66, bold=True)
    draw.text((x_center, 82 + y_offset), value_text, font=font_val, fill=(255, 255, 255, 255), anchor="mm")

    font_unit = get_font(size=25, bold=True)
    draw_tracked_text(draw, (x_center, 150 + y_offset), unit_text, font=font_unit, fill=(255, 255, 255, 255), letter_spacing=3, anchor="mm")

    return badge_layer
