"""Bottom Info Card Renderer with Slide-Up Spring and Divider Expansion."""

from typing import Tuple
from PIL import Image, ImageDraw, ImageFilter
from .typography import get_font, wrap_text
from .animation_curves import clamp, ease_out_back, ease_out_cubic

def render_bottom_card(
    width: int = 480,
    height: int = 1080,
    title: str = "Language Section Of Brain Develops",
    description: str = "The FOXP2 gene gave us the language part of our brain",
    entry_progress: float = 1.0
) -> Image.Image:
    """
    Renders the bottom white info card with slide-up spring and text fade-in.
    """
    card_layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    
    p = clamp(entry_progress)
    if p <= 0.0:
        return card_layer
        
    slide_y = int((1.0 - ease_out_back(p, overshoot=1.15)) * 140)
    alpha = int(255 * min(1.0, p * 1.5))
    
    card_w, card_h = 440, 210
    x0, x1 = (width - card_w) // 2, (width - card_w) // 2 + card_w
    y0 = 850 + slide_y
    y1 = y0 + card_h
    radius = 14
    
    # 1. Soft Drop Shadow
    shadow_img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow_img)
    shadow_draw.rounded_rectangle([x0 - 2, y0 + 6, x1 + 2, y1 + 10], radius=radius + 2, fill=(0, 0, 0, int(alpha * 0.45)))
    shadow_img = shadow_img.filter(ImageFilter.GaussianBlur(radius=8))
    card_layer = Image.alpha_composite(card_layer, shadow_img)

    draw = ImageDraw.Draw(card_layer, "RGBA")
    
    # 2. Main Card Container
    draw.rounded_rectangle([x0, y0, x1, y1], radius=radius, fill=(255, 255, 255, alpha), outline=(26, 26, 26, alpha), width=2)
    
    # 3. Title Text
    font_title = get_font(size=23, bold=True)
    title_lines = wrap_text(title, font_title, max_width=card_w - 40)
    cx = width // 2
    
    if len(title_lines) == 1:
        draw.text((cx, y0 + 32), title_lines[0], font=font_title, fill=(17, 17, 17, alpha), anchor="mm")
        divider_y = y0 + 65
    else:
        draw.text((cx, y0 + 24), title_lines[0], font=font_title, fill=(17, 17, 17, alpha), anchor="mm")
        draw.text((cx, y0 + 50), title_lines[1], font=font_title, fill=(17, 17, 17, alpha), anchor="mm")
        divider_y = y0 + 75
        
    # 4. Animated Divider Line (expands outward from center)
    div_p = ease_out_cubic(clamp((p - 0.2) / 0.8))
    div_half_w = int((card_w - 50) / 2 * div_p)
    draw.line([(cx - div_half_w, divider_y), (cx + div_half_w, divider_y)], fill=(225, 225, 225, alpha), width=1)
    
    # 5. Description Text
    font_desc = get_font(size=17, bold=False)
    desc_lines = wrap_text(description, font_desc, max_width=card_w - 40)
    line_h = 24
    start_desc_y = divider_y + 24 + (12 if len(desc_lines) == 1 else (4 if len(desc_lines) == 2 else 0))

    for i, line in enumerate(desc_lines[:3]):
        draw.text((cx, start_desc_y + i * line_h), line, font=font_desc, fill=(50, 50, 50, alpha), anchor="mm")

    return card_layer
