"""Interactive YouTube Subscribe Prompt & End-Screen Overlay Animations."""

import math
from typing import Tuple, Optional
from PIL import Image, ImageDraw, ImageFilter
from .typography import get_font
from .animation_curves import clamp, ease_out_back, ease_out_cubic, bell_wiggle

def render_subscribe_prompt(
    progress: float,
    canvas_size: Tuple[int, int] = (1920, 1080)
) -> Image.Image:
    """
    Renders the animated YouTube Subscribe + Bell Prompt overlay.
    
    Phases (progress 0.0 -> 1.0, duration ~4.0s):
      0.0 - 0.2: Pill fades in & slides up from bottom-left
      0.2 - 0.4: Red "SUBSCRIBE" state + Cursor pointer flies in
      0.4 - 0.5: Cursor clicks -> Button transforms to grey "SUBSCRIBED" with checkmark
      0.5 - 0.7: Cursor moves to Bell icon and clicks
      0.7 - 0.9: Bell wiggles/rings with radiating wave ripples
      0.9 - 1.0: Whole prompt fades out / slides down
    """
    overlay = Image.new("RGBA", canvas_size, (0, 0, 0, 0))
    p = clamp(progress)
    
    # Entrance & Exit alpha / vertical slide
    if p < 0.15:
        alpha = int(255 * (p / 0.15))
        slide_y = int((1.0 - ease_out_back(p / 0.15)) * 60)
    elif p > 0.88:
        alpha = int(255 * ((1.0 - p) / 0.12))
        slide_y = int(((p - 0.88) / 0.12) * 60)
    else:
        alpha = 255
        slide_y = 0
        
    if alpha <= 0:
        return overlay
        
    # Position: lower left corner
    box_x = 40
    box_y = 960 + slide_y
    box_w = 340
    box_h = 68
    
    # 1. Base Container Pill (Dark semi-translucent glass or solid white/dark)
    shadow_img = Image.new("RGBA", canvas_size, (0, 0, 0, 0))
    s_draw = ImageDraw.Draw(shadow_img)
    s_draw.rounded_rectangle([box_x, box_y + 4, box_x + box_w, box_y + box_h + 4], radius=34, fill=(0, 0, 0, int(alpha * 0.5)))
    shadow_img = shadow_img.filter(ImageFilter.GaussianBlur(radius=6))
    overlay = Image.alpha_composite(overlay, shadow_img)
    
    draw = ImageDraw.Draw(overlay, "RGBA")
    draw.rounded_rectangle([box_x, box_y, box_x + box_w, box_y + box_h], radius=34, fill=(245, 245, 245, alpha), outline=(210, 210, 210, alpha), width=2)
    
    # State switches:
    is_subscribed = (p >= 0.45)
    is_bell_clicked = (p >= 0.65)
    
    # 2. Subscribe Button
    sub_x = box_x + 10
    sub_y = box_y + 8
    sub_w = 200
    sub_h = box_h - 16
    
    font_sub = get_font(size=18, bold=True)
    if not is_subscribed:
        # Bright YouTube Red
        draw.rounded_rectangle([sub_x, sub_y, sub_x + sub_w, sub_y + sub_h], radius=26, fill=(204, 0, 0, alpha))
        draw.text((sub_x + sub_w // 2, sub_y + sub_h // 2), "SUBSCRIBE", font=font_sub, fill=(255, 255, 255, alpha), anchor="mm")
    else:
        # Grey Subscribed State
        draw.rounded_rectangle([sub_x, sub_y, sub_x + sub_w, sub_y + sub_h], radius=26, fill=(225, 225, 225, alpha))
        draw.text((sub_x + sub_w // 2 + 10, sub_y + sub_h // 2), "SUBSCRIBED", font=font_sub, fill=(35, 35, 35, alpha), anchor="mm")
        # Checkmark icon
        chk_cx = sub_x + 22
        chk_cy = sub_y + sub_h // 2
        draw.line([(chk_cx - 6, chk_cy), (chk_cx - 2, chk_cy + 5), (chk_cx + 7, chk_cy - 6)], fill=(35, 35, 35, alpha), width=3)
        
    # 3. Notification Bell
    bell_cx = box_x + box_w - 60
    bell_cy = box_y + box_h // 2
    
    # Bell wiggle angle
    if 0.65 <= p <= 0.88:
        wiggle_progress = (p - 0.65) / 0.23
        wiggle_deg = bell_wiggle(wiggle_progress)
    else:
        wiggle_deg = 0.0
        
    # Draw simple vector bell
    bell_color = (20, 20, 20, alpha) if not is_bell_clicked else (204, 0, 0, alpha)
    # Bell dome
    draw.chord([bell_cx - 14, bell_cy - 16, bell_cx + 14, bell_cy + 12], start=180, end=360, fill=bell_color)
    draw.rectangle([bell_cx - 14, bell_cy - 2, bell_cx + 14, bell_cy + 8], fill=bell_color)
    draw.line([(bell_cx - 18, bell_cy + 8), (bell_cx + 18, bell_cy + 8)], fill=bell_color, width=3)
    # Clapper
    draw.ellipse([bell_cx - 4, bell_cy + 9, bell_cx + 4, bell_cy + 15], fill=bell_color)
    
    # Radiating sound waves when ringing
    if is_bell_clicked and 0.65 <= p <= 0.85:
        wave_p = (p - 0.65) / 0.20
        wave_rad = int(22 + wave_p * 18)
        wave_alpha = int(alpha * (1.0 - wave_p))
        draw.arc([bell_cx - wave_rad, bell_cy - wave_rad, bell_cx + wave_rad, bell_cy + wave_rad], start=210, end=330, fill=(204, 0, 0, wave_alpha), width=2)
        
    # 4. Animated Hand / Cursor Pointer
    if 0.20 <= p <= 0.80:
        # Move from bottom towards Subscribe button (0.2 -> 0.4)
        # Click Subscribe button at 0.45
        # Move to Bell (0.5 -> 0.65)
        # Click Bell at 0.65
        if p < 0.45:
            move_p = ease_out_cubic((p - 0.20) / 0.25)
            cur_x = int(box_x - 40 + move_p * (sub_x + sub_w // 2 - (box_x - 40)))
            cur_y = int(box_y + 120 - move_p * (box_y + 120 - (sub_y + sub_h // 2 + 10)))
        elif p < 0.65:
            move_p = ease_out_cubic((p - 0.45) / 0.20)
            cur_x = int((sub_x + sub_w // 2) + move_p * (bell_cx - (sub_x + sub_w // 2)))
            cur_y = int((sub_y + sub_h // 2 + 10) + move_p * (bell_cy + 10 - (sub_y + sub_h // 2 + 10)))
        else:
            cur_x = bell_cx
            cur_y = bell_cy + 10
            
        # Draw clean vector cursor pointer
        cursor_poly = [
            (cur_x, cur_y),
            (cur_x + 12, cur_y + 12),
            (cur_x + 5, cur_y + 13),
            (cur_x + 9, cur_y + 22),
            (cur_x + 5, cur_y + 24),
            (cur_x + 1, cur_y + 15),
            (cur_x - 4, cur_y + 18)
        ]
        draw.polygon(cursor_poly, fill=(255, 255, 255, alpha), outline=(0, 0, 0, alpha), width=2)

    return overlay
