"""Timeline Compositor for 1920x1080 Frame Rendering with All Dynamic Animations."""

import math
from typing import List, Optional
from PIL import Image, ImageDraw, ImageFilter
from .models import TimelineProject, VideoConfig, ComparisonItem
from .column_renderer import render_column, render_column_background
from .overlay_animations import render_subscribe_prompt
from .animation_curves import clamp, ease_out_cubic, ease_out_back
from .typography import get_font, wrap_text

class TimelineCompositor:
    def __init__(self, project: TimelineProject):
        self.project = project
        self.config = project.config
        self.items = project.items
        self.col_w = self.config.column_width  # 480
        self.height = self.config.height       # 1080
        self.width = self.config.width         # 1920
        self.fps = self.config.fps             # 60
        
        # Pre-render static background slices
        self._cached_bg_slices: List[Image.Image] = []
        for idx, item in enumerate(self.items):
            bg = render_column_background(item, self.col_w, self.height, idx)
            self._cached_bg_slices.append(bg)
            
        self._compute_timings()

    def _compute_timings(self):
        self.n_items = len(self.items)
        self.intro_dur = self.config.intro_duration_sec  # 3.0s
        self.outro_dur = self.config.outro_duration_sec  # 3.5s
        
        total_dist = (self.n_items - 4) * self.col_w if self.n_items > 4 else 0
        self.scroll_dur = total_dist / self.config.scroll_speed_px_per_sec if total_dist > 0 else 2.0
        self.total_duration = self.intro_dur + self.scroll_dur + self.outro_dur
        self.total_frames = int(self.total_duration * self.fps)

    def render_intro_overlay(self, frame: Image.Image, progress: float):
        """Renders right-side context explanation & credits on intro."""
        draw = ImageDraw.Draw(frame, "RGBA")
        cred = self.project.credits
        alpha = int(255 * min(1.0, progress * 3.0))
        panel_x = 480 + (self.width - 480) // 2

        font_exp = get_font(size=19, bold=False)
        y = 120
        for line in wrap_text(cred.intro_explanation, font_exp, max_width=600):
            draw.text((panel_x, y), line, font=font_exp, fill=(210, 210, 210, alpha), anchor="mm")
            y += 26
            
        y = 420
        draw.text((panel_x, y), "Credits", font=get_font(size=44, bold=True), fill=(255, 255, 255, alpha), anchor="mm")
        
        entries = [
            ("Lead Research & Sourcing", cred.lead_research),
            ("Independent Fact Check", cred.fact_check),
            ("Lead Graphic Designer", cred.lead_designer),
            ("Edit & Post-Production", cred.edit_post),
            ("Thumbnail Designer", cred.thumbnail_designer),
            ("Video Idea & Quality Check", cred.video_idea),
        ]
        y += 65
        for role, name in entries:
            draw.text((panel_x, y), role, font=get_font(size=18, bold=True), fill=(230, 230, 230, alpha), anchor="mm")
            draw.text((panel_x, y + 22), name, font=get_font(size=17, bold=False), fill=(180, 180, 180, alpha), anchor="mm")
            y += 56

    def render_outro_overlay(self, frame: Image.Image, progress: float):
        """Renders left-side end-screen cards with scale pop-up during outro."""
        draw = ImageDraw.Draw(frame, "RGBA")
        cred = self.project.credits
        p = clamp(progress)
        alpha = int(255 * min(1.0, p * 2.5))
        
        # 1. End Screen Video Cards with Pop-Up Scale Easing
        scale = ease_out_back(min(1.0, p * 2.0), overshoot=1.2)
        base_w, base_h = 440, 250
        card_w, card_h = int(base_w * scale), int(base_h * scale)
        
        # Card 1: BEST VIDEO FOR YOU
        cx1, cy1 = 340, 385
        x1_0, y1_0 = cx1 - card_w // 2, cy1 - card_h // 2
        draw.rounded_rectangle([x1_0, y1_0, x1_0 + card_w, y1_0 + card_h], radius=18, fill=(190, 15, 35, alpha))
        if scale > 0.6:
            font_card = get_font(size=int(26 * scale), bold=True)
            draw.text((cx1, cy1), "BEST VIDEO FOR YOU", font=font_card, fill=(255, 255, 255, alpha), anchor="mm")
        
        # Card 2: NEWEST VIDEO
        cx2, cy2 = 840, 385
        x2_0, y2_0 = cx2 - card_w // 2, cy2 - card_h // 2
        draw.rounded_rectangle([x2_0, y2_0, x2_0 + card_w, y2_0 + card_h], radius=18, fill=(190, 15, 35, alpha))
        if scale > 0.6:
            font_card = get_font(size=int(26 * scale), bold=True)
            draw.text((cx2, cy2), "NEWEST VIDEO", font=font_card, fill=(255, 255, 255, alpha), anchor="mm")

        # 2. Credits Panel Below Cards
        y_cred = 600
        cx = (cx1 + cx2) // 2
        font_head = get_font(size=30, bold=True)
        draw.text((cx, y_cred), "Video Made By", font=font_head, fill=(255, 255, 255, alpha), anchor="mm")
        
        y_cred += 45
        left_entries = [
            ("Lead Research & Sourcing", cred.lead_research),
            ("Independent Fact Check", cred.fact_check),
            ("Lead Graphic Designer", cred.lead_designer),
        ]
        right_entries = [
            ("Edit & Post-Production", cred.edit_post),
            ("Thumbnail Designer", cred.thumbnail_designer),
            ("Video Idea & Quality Check", cred.video_idea),
        ]
        font_r = get_font(size=15, bold=True)
        font_n = get_font(size=15, bold=False)
        
        for i, (r, n) in enumerate(left_entries):
            draw.text((cx - 180, y_cred + i * 44), r, font=font_r, fill=(220, 220, 220, alpha), anchor="mm")
            draw.text((cx - 180, y_cred + i * 44 + 18), n, font=font_n, fill=(170, 170, 170, alpha), anchor="mm")

        for i, (r, n) in enumerate(right_entries):
            draw.text((cx + 180, y_cred + i * 44), r, font=font_r, fill=(220, 220, 220, alpha), anchor="mm")
            draw.text((cx + 180, y_cred + i * 44 + 18), n, font=font_n, fill=(170, 170, 170, alpha), anchor="mm")

    def render_frame(self, t: float) -> Image.Image:
        frame = Image.new("RGBA", (self.width, self.height), (15, 15, 20, 255))
        
        # 1. Determine Camera Offset X
        if t < self.intro_dur:
            p_slide = min(1.0, t / (self.intro_dur * 0.45))
            card0_x = int((1.0 - ease_out_cubic(p_slide)) * self.col_w)
            cam_offset = -card0_x
        elif t < (self.intro_dur + self.scroll_dur):
            cam_offset = (t - self.intro_dur) * self.config.scroll_speed_px_per_sec
        else:
            total_dist = (self.n_items - 4) * self.col_w if self.n_items > 4 else 0
            cam_offset = total_dist
            
        # 2. Render Active Columns with Dynamic Animation Phases
        for idx in range(self.n_items):
            col_x = idx * self.col_w - cam_offset
            
            if col_x + self.col_w <= 0 or col_x >= self.width:
                continue
            if t < self.intro_dur and idx > 0:
                continue
                
            # Dynamic Animation Triggers for each column:
            if t < self.intro_dur and idx == 0:
                # Intro card 0 entry animation
                entry_p = min(1.0, t / (self.intro_dur * 0.45))
                # Shine sweep plays after badge drops in
                shine_p = clamp((t - self.intro_dur * 0.35) / 0.8) if t > self.intro_dur * 0.35 else None
            else:
                # Calculate entry progress as column slides into viewport from the right (X=1920 down to X=1440)
                # When col_x >= 1920: entry_p = 0.0. When col_x <= 1440: entry_p = 1.0
                dist_entered = (self.width - col_x)  # 0 at x=1920, 480 at x=1440
                entry_p = clamp(dist_entered / self.col_w)
                
                # Dynamic Specular Shine sweep glides across badge when card enters screen
                if dist_entered > 20:
                    shine_p = clamp((dist_entered - 20) / (self.col_w * 1.5))
                else:
                    shine_p = None

            col_img = render_column(
                item=self.items[idx],
                width=self.col_w,
                height=self.height,
                item_index=idx,
                entry_progress=entry_p,
                shine_progress=shine_p,
                base_bg=self._cached_bg_slices[idx]
            )
            
            frame.paste(col_img, (int(col_x), 0))

        # 3. Intro / Outro Overlays
        if t < self.intro_dur:
            self.render_intro_overlay(frame, t / self.intro_dur)
        elif t >= (self.intro_dur + self.scroll_dur):
            p_outro = (t - (self.intro_dur + self.scroll_dur)) / self.outro_dur
            self.render_outro_overlay(frame, p_outro)
            
        # 4. Mid-Video & Outro Animated Subscribe + Bell Button Prompt
        # Trigger subscribe animation periodically (e.g. at 25% of scroll, or in outro)
        sub_trigger_time = self.intro_dur + self.scroll_dur * 0.3
        sub_dur = 4.0
        if sub_trigger_time <= t <= sub_trigger_time + sub_dur:
            sub_p = (t - sub_trigger_time) / sub_dur
            sub_overlay = render_subscribe_prompt(sub_p, (self.width, self.height))
            frame = Image.alpha_composite(frame, sub_overlay)
        elif t >= (self.intro_dur + self.scroll_dur + 0.5):
            # Also show in outro
            outro_sub_p = min(1.0, (t - (self.intro_dur + self.scroll_dur + 0.5)) / 3.0)
            sub_overlay = render_subscribe_prompt(outro_sub_p * 0.75, (self.width, self.height))
            frame = Image.alpha_composite(frame, sub_overlay)

        return frame
