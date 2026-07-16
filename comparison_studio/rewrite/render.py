from __future__ import annotations

import re
import threading
import urllib.request
from io import BytesIO
from pathlib import Path
from urllib.parse import unquote, urlparse

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

from .model import Card, MODEL_COMPACT, MODEL_ILLUSTRATED, MODEL_REFERENCE, Project
from .timing import Timeline

BACKGROUND = (6, 7, 12)
BLACK = (5, 5, 7, 255)
WHITE = (247, 246, 242, 255)
DESCRIPTION = (23, 23, 23, 255)
ORANGE = (165, 96, 0, 255)


def _font_path(bold: bool) -> str:
    candidates = (
        (
            "/usr/share/fonts/opentype/urw-base35/URWGothic-Demi.otf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "C:/Windows/Fonts/arialbd.ttf",
            "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        )
        if bold
        else (
            "/usr/share/fonts/opentype/urw-base35/URWGothic-Book.otf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/System/Library/Fonts/Supplemental/Arial.ttf",
        )
    )
    for candidate in candidates:
        if Path(candidate).is_file():
            return candidate
    return candidates[1]


REGULAR_FONT = _font_path(False)
BOLD_FONT = _font_path(True)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(BOLD_FONT if bold else REGULAR_FONT, max(5, int(size)))


def normalize_source(source: str) -> str:
    value = str(source or "").strip()
    markdown = re.fullmatch(r"!?\[[^\]]*\]\((.+?)\)", value)
    if markdown:
        value = markdown.group(1).strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        value = value[1:-1].strip()
    if value.startswith("<") and value.endswith(">"):
        value = value[1:-1].strip()
    if value.startswith("//"):
        value = "https:" + value
    elif value.lower().startswith("www."):
        value = "https://" + value
    return value


class AssetCache:
    """Small thread-safe image cache shared by preview and export."""

    def __init__(self) -> None:
        self._images: dict[str, Image.Image] = {}
        self._errors: dict[str, str] = {}
        self._lock = threading.RLock()

    @property
    def errors(self) -> dict[str, str]:
        with self._lock:
            return dict(self._errors)

    def clear(self, source: str | None = None) -> None:
        with self._lock:
            if source is None:
                self._images.clear()
                self._errors.clear()
            else:
                key = normalize_source(source)
                self._images.pop(key, None)
                self._errors.pop(key, None)

    def load(self, source: str) -> Image.Image | None:
        key = normalize_source(source)
        if not key:
            return None
        with self._lock:
            if key in self._images:
                return self._images[key]
            if key in self._errors:
                return None
        try:
            if re.match(r"^https?://", key, flags=re.IGNORECASE):
                request = urllib.request.Request(
                    key,
                    headers={
                        "User-Agent": "Mozilla/5.0 CTS/1.0",
                        "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                    },
                )
                with urllib.request.urlopen(request, timeout=15) as response:
                    payload = response.read(40 * 1024 * 1024 + 1)
                if len(payload) > 40 * 1024 * 1024:
                    raise ValueError("image is larger than 40 MiB")
                image = Image.open(BytesIO(payload)).convert("RGBA")
            elif key.lower().startswith("file://"):
                parsed = urlparse(key)
                image = Image.open(Path(unquote(parsed.path))).convert("RGBA")
            else:
                image = Image.open(Path(key).expanduser()).convert("RGBA")
            image.load()
            with self._lock:
                self._images[key] = image
            return image
        except Exception as exc:
            with self._lock:
                self._errors[key] = str(exc)
            return None


def _measure(draw: ImageDraw.ImageDraw, text: str, selected_font: ImageFont.FreeTypeFont) -> int:
    bounds = draw.textbbox((0, 0), text, font=selected_font)
    return bounds[2] - bounds[0]


def _whole_word_lines(
    draw: ImageDraw.ImageDraw,
    text: str,
    selected_font: ImageFont.FreeTypeFont,
    maximum_width: int,
    maximum_lines: int,
) -> list[str] | None:
    words = text.strip().split()
    if not words:
        return []
    if any(_measure(draw, word, selected_font) > maximum_width for word in words):
        return None
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else f"{current} {word}"
        if current and _measure(draw, candidate, selected_font) > maximum_width:
            lines.append(current)
            current = word
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines if len(lines) <= maximum_lines else None


def draw_text_box(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    fill: tuple[int, int, int, int],
    maximum_size: int,
    minimum_size: int,
    maximum_lines: int,
    bold: bool = False,
) -> None:
    """Fit text by shrinking and wrapping only between complete words."""
    text = str(text or "").strip()
    if not text:
        return
    left, top, right, bottom = box
    available_width = max(1, right - left)
    available_height = max(1, bottom - top)

    chosen_font = font(minimum_size, bold)
    chosen_lines = [text]
    chosen_line_height = max(1, draw.textbbox((0, 0), "Ag", font=chosen_font)[3])
    chosen_spacing = max(1, round(minimum_size * 0.08))

    for size in range(maximum_size, minimum_size - 1, -1):
        candidate_font = font(size, bold)
        lines = _whole_word_lines(
            draw,
            text,
            candidate_font,
            available_width,
            maximum_lines,
        )
        if lines is None:
            continue
        line_height = max(1, draw.textbbox((0, 0), "Ag", font=candidate_font)[3])
        spacing = max(1, round(size * 0.08))
        total_height = len(lines) * line_height + max(0, len(lines) - 1) * spacing
        if total_height <= available_height:
            chosen_font = candidate_font
            chosen_lines = lines
            chosen_line_height = line_height
            chosen_spacing = spacing
            break

    total_height = (
        len(chosen_lines) * chosen_line_height
        + max(0, len(chosen_lines) - 1) * chosen_spacing
    )
    y = top + (available_height - total_height) / 2
    for line in chosen_lines[:maximum_lines]:
        line_width = _measure(draw, line, chosen_font)
        x = left + (available_width - line_width) / 2
        draw.text((round(x), round(y)), line, font=chosen_font, fill=fill)
        y += chosen_line_height + chosen_spacing


def _octagon_points(x0: int, y0: int, x1: int, y1: int) -> list[tuple[int, int]]:
    width = x1 - x0
    height = y1 - y0
    cut_x = round(width * 0.22)
    cut_y = round(height * 0.22)
    return [
        (x0 + cut_x, y0),
        (x1 - cut_x, y0),
        (x1, y0 + cut_y),
        (x1, y1 - cut_y),
        (x1 - cut_x, y1),
        (x0 + cut_x, y1),
        (x0, y1 - cut_y),
        (x0, y0 + cut_y),
    ]


def render_badge(
    primary: str,
    secondary: str,
    card_width: int,
    card_height: int,
    scale: float,
    compact: bool = False,
) -> Image.Image:
    badge_width = max(28, round(card_width * (0.70 if not compact else 0.66) * scale))
    badge_height = max(34, round(card_height * (0.285 if not compact else 0.255) * scale))
    padding = max(4, round(badge_width * 0.055))
    canvas = Image.new(
        "RGBA",
        (badge_width + padding * 2, badge_height + padding * 2),
        (0, 0, 0, 0),
    )
    x0, y0 = padding, padding
    x1, y1 = x0 + badge_width, y0 + badge_height
    points = _octagon_points(x0, y0, x1, y1)

    mask = Image.new("L", canvas.size, 0)
    ImageDraw.Draw(mask).polygon(points, fill=255)
    shadow_mask = mask.filter(ImageFilter.GaussianBlur(max(2, round(padding * 0.75))))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.putalpha(shadow_mask.point(lambda value: round(value * 0.52)))
    canvas.alpha_composite(shadow, (max(1, padding // 4), max(1, padding // 2)))

    gradient = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    gradient_draw = ImageDraw.Draw(gradient)
    for y in range(y0, y1 + 1):
        position = (y - y0) / max(1, badge_height)
        highlight = max(0.0, min(1.0, 1.0 - abs(position - 0.28) * 1.45))
        gradient_draw.line(
            (x0, y, x1, y),
            fill=(
                round(216 + 37 * highlight),
                round(20 + 36 * highlight),
                round(31 + 30 * highlight),
                255,
            ),
        )
    gradient.putalpha(mask)
    canvas.alpha_composite(gradient)

    draw = ImageDraw.Draw(canvas)
    draw.line(
        points + [points[0]],
        fill=(255, 170, 174, 255),
        width=max(1, round(badge_width * 0.010)),
        joint="curve",
    )
    inner_left = x0 + round(badge_width * 0.09)
    inner_right = x1 - round(badge_width * 0.09)
    if secondary.strip():
        draw_text_box(
            draw,
            primary,
            (inner_left, y0 + round(badge_height * 0.15), inner_right, y0 + round(badge_height * 0.50)),
            (255, 250, 247, 255),
            max(8, round(badge_height * 0.23)),
            max(5, round(badge_height * 0.075)),
            2,
            True,
        )
        draw_text_box(
            draw,
            secondary,
            (inner_left, y0 + round(badge_height * 0.48), inner_right, y0 + round(badge_height * 0.87)),
            (255, 250, 247, 255),
            max(7, round(badge_height * 0.15)),
            max(4, round(badge_height * 0.052)),
            3,
            True,
        )
    else:
        draw_text_box(
            draw,
            primary,
            (inner_left, y0 + round(badge_height * 0.15), inner_right, y0 + round(badge_height * 0.86)),
            (255, 250, 247, 255),
            max(8, round(badge_height * 0.25)),
            max(5, round(badge_height * 0.065)),
            4,
            True,
        )
    return canvas


class Renderer:
    """Single deterministic renderer used by the widget and FFmpeg exporter."""

    def __init__(self, assets: AssetCache | None = None) -> None:
        self.assets = assets or AssetCache()

    def render(
        self,
        project: Project,
        output_time: float,
        size: tuple[int, int] | None = None,
    ) -> Image.Image:
        project.normalize()
        width, height = size or (project.width, project.height)
        frame = Image.new("RGBA", (width, height), BACKGROUND + (255,))
        cards = project.content_cards()
        timeline = Timeline(project, len(cards))
        if not cards or output_time >= timeline.output_duration:
            return frame.convert("RGB")

        card_width_float = width / timeline.visible_cards
        card_width = max(1, round(card_width_float))
        for placement in timeline.placements(output_time, float(width)):
            if placement.index >= len(cards):
                continue
            layer = self._render_card(
                project,
                cards[placement.index],
                card_width,
                height,
                placement.badge_scale,
            )
            if placement.alpha < 0.999:
                alpha = layer.getchannel("A").point(
                    lambda value: round(value * placement.alpha)
                )
                layer.putalpha(alpha)
            y_offset = round((1.0 - placement.alpha) * height * 0.018)
            self._composite_clipped(frame, layer, round(placement.x), y_offset)

        fade = timeline.fade_amount(output_time)
        result = frame.convert("RGB")
        if fade > 0:
            result = Image.blend(
                result,
                Image.new("RGB", result.size, BACKGROUND),
                fade,
            )
        return result

    def _render_card(
        self,
        project: Project,
        card: Card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        if project.model_id == MODEL_ILLUSTRATED:
            return self._render_illustrated(card, width, height, badge_scale)
        if project.model_id == MODEL_COMPACT:
            return self._render_compact(card, width, height, badge_scale)
        return self._render_reference(card, width, height, badge_scale)

    def _render_illustrated(
        self,
        card: Card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.0125))
        artwork_bottom = round(height * 0.730)
        title_bottom = round(height * 0.842)
        separator_bottom = round(height * 0.848)

        horizon = round(artwork_bottom * 0.64)
        draw.rectangle((divider, 0, width - divider, horizon), fill=(89, 207, 229, 255))
        draw.rectangle(
            (divider, horizon, width - divider, artwork_bottom),
            fill=(241, 216, 158, 255),
        )

        source = self.assets.load(card.image)
        if source is not None:
            target_size = (max(1, width - divider * 2), max(1, artwork_bottom))
            alpha_minimum = source.getchannel("A").getextrema()[0]
            if alpha_minimum < 255:
                fitted = ImageOps.contain(source, target_size, Image.Resampling.LANCZOS)
                x = divider + (target_size[0] - fitted.width) // 2
                y = (target_size[1] - fitted.height) // 2
            else:
                fitted = ImageOps.fit(source, target_size, Image.Resampling.LANCZOS)
                x, y = divider, 0
            layer.alpha_composite(fitted, (x, y))
        elif card.image.strip():
            self._draw_missing_image(draw, (divider, 0, width - divider, artwork_bottom))

        draw.rectangle((0, artwork_bottom, width, title_bottom), fill=WHITE)
        draw.rectangle((0, title_bottom, width, separator_bottom), fill=ORANGE)
        draw.rectangle((0, separator_bottom, width, height), fill=DESCRIPTION)
        draw.rectangle((0, 0, divider, height), fill=BLACK)
        draw.rectangle((width - divider, 0, width, height), fill=BLACK)

        title_padding = round(width * 0.035)
        draw_text_box(
            draw,
            card.title,
            (title_padding, artwork_bottom + 2, width - title_padding, title_bottom - 2),
            (23, 23, 23, 255),
            max(9, round(height * 0.044)),
            max(7, round(height * 0.020)),
            2,
            True,
        )
        draw_text_box(
            draw,
            card.description,
            (
                round(width * 0.055),
                separator_bottom + round(height * 0.010),
                width - round(width * 0.055),
                height - round(height * 0.010),
            ),
            (218, 218, 218, 255),
            max(7, round(height * 0.026)),
            max(6, round(height * 0.014)),
            4,
            False,
        )

        badge = render_badge(card.value, card.label, width, height, badge_scale)
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(0, round(height * 0.004))),
        )
        return layer

    def _render_reference(
        self,
        card: Card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.010))
        badge_bottom = round(height * 0.44)
        title_bottom = round(height * 0.538)
        image_top = round(height * 0.67)

        draw.rectangle((0, 0, width, badge_bottom), fill=(12, 13, 16, 255))
        draw.rectangle((0, badge_bottom, width, title_bottom), fill=(241, 241, 242, 255))
        draw.rectangle((0, title_bottom, width, height), fill=(115, 121, 109, 255))
        draw.rectangle((0, 0, divider, height), fill=BLACK)
        draw.rectangle((width - divider, 0, width, height), fill=BLACK)
        draw.rectangle((0, badge_bottom, width, badge_bottom + divider), fill=BLACK)
        draw.rectangle((0, title_bottom, width, title_bottom + divider), fill=BLACK)

        draw_text_box(
            draw,
            card.title,
            (round(width * 0.04), badge_bottom + 4, width - round(width * 0.04), title_bottom - 4),
            (18, 18, 19, 255),
            max(9, round(height * 0.047)),
            max(7, round(height * 0.021)),
            2,
            True,
        )
        draw_text_box(
            draw,
            card.description,
            (round(width * 0.05), title_bottom + round(height * 0.018), width - round(width * 0.05), image_top - round(height * 0.012)),
            (235, 236, 230, 255),
            max(7, round(height * 0.030)),
            max(6, round(height * 0.016)),
            4,
            False,
        )

        image_box = (
            round(width * 0.08),
            image_top,
            width - round(width * 0.08),
            height - divider,
        )
        self._paste_image(layer, draw, card.image, image_box, contain=False)
        badge = render_badge(card.value, card.label, width, height, badge_scale * 0.96)
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(2, round(height * 0.035))),
        )
        return layer

    def _render_compact(
        self,
        card: Card,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.010))
        badge_bottom = round(height * 0.39)
        title_bottom = round(height * 0.495)
        draw.rectangle((0, 0, width, badge_bottom), fill=(14, 15, 18, 255))
        draw.rectangle((0, badge_bottom, width, title_bottom), fill=(241, 241, 241, 255))
        draw.rectangle((0, title_bottom, width, height), fill=(103, 108, 103, 255))
        draw.rectangle((0, 0, divider, height), fill=BLACK)
        draw.rectangle((width - divider, 0, width, height), fill=BLACK)
        draw.rectangle((0, badge_bottom, width, badge_bottom + divider), fill=BLACK)
        draw.rectangle((0, title_bottom, width, title_bottom + divider), fill=BLACK)

        draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), badge_bottom + 3, width - round(width * 0.035), title_bottom - 3),
            (15, 15, 15, 255),
            max(9, round(height * 0.047)),
            max(7, round(height * 0.018)),
            3,
            True,
        )
        self._paste_image(
            layer,
            draw,
            card.image,
            (divider, title_bottom + divider, width - divider, height),
            contain=False,
        )
        badge = render_badge(
            card.value,
            card.label,
            width,
            height,
            badge_scale * 0.92,
            compact=True,
        )
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(2, round(height * 0.025))),
        )
        return layer

    def _paste_image(
        self,
        layer: Image.Image,
        draw: ImageDraw.ImageDraw,
        source_value: str,
        box: tuple[int, int, int, int],
        contain: bool,
    ) -> None:
        left, top, right, bottom = box
        target_size = (max(1, right - left), max(1, bottom - top))
        source = self.assets.load(source_value)
        if source is None:
            self._draw_missing_image(draw, box)
            return
        if contain:
            fitted = ImageOps.contain(source, target_size, Image.Resampling.LANCZOS)
            x = left + (target_size[0] - fitted.width) // 2
            y = top + (target_size[1] - fitted.height) // 2
        else:
            fitted = ImageOps.fit(source, target_size, Image.Resampling.LANCZOS)
            x, y = left, top
        layer.alpha_composite(fitted, (x, y))

    @staticmethod
    def _draw_missing_image(
        draw: ImageDraw.ImageDraw,
        box: tuple[int, int, int, int],
    ) -> None:
        left, top, right, bottom = box
        draw.rectangle(box, fill=(69, 74, 70, 255))
        width = max(1, right - left)
        height = max(1, bottom - top)
        cx, cy = left + width // 2, top + height // 2
        icon_w = max(18, width // 4)
        icon_h = max(14, height // 5)
        draw.rounded_rectangle(
            (cx - icon_w // 2, cy - icon_h // 2, cx + icon_w // 2, cy + icon_h // 2),
            radius=max(3, icon_w // 12),
            outline=(184, 190, 183, 255),
            width=max(2, width // 90),
        )
        draw.ellipse(
            (cx - icon_w // 4, cy - icon_h // 3, cx - icon_w // 8, cy - icon_h // 6),
            fill=(184, 190, 183, 255),
        )
        draw.polygon(
            [
                (cx - icon_w // 3, cy + icon_h // 3),
                (cx - icon_w // 12, cy),
                (cx + icon_w // 12, cy + icon_h // 5),
                (cx + icon_w // 4, cy - icon_h // 8),
                (cx + icon_w // 3, cy + icon_h // 3),
            ],
            fill=(184, 190, 183, 255),
        )

    @staticmethod
    def _composite_clipped(
        canvas: Image.Image,
        layer: Image.Image,
        left: int,
        top: int,
    ) -> None:
        right = left + layer.width
        bottom = top + layer.height
        visible_left = max(0, left)
        visible_top = max(0, top)
        visible_right = min(canvas.width, right)
        visible_bottom = min(canvas.height, bottom)
        if visible_right <= visible_left or visible_bottom <= visible_top:
            return
        crop = (
            visible_left - left,
            visible_top - top,
            visible_right - left,
            visible_bottom - top,
        )
        canvas.alpha_composite(layer.crop(crop), (visible_left, visible_top))
