from __future__ import annotations

import math
import re
import threading
import urllib.request
from datetime import datetime
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps

from .data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    REFERENCE_END_HOLD_SECONDS,
    REFERENCE_FADE_SECONDS,
    REFERENCE_REVEAL_SECONDS,
    REFERENCE_SCROLL_SECONDS,
    CardData,
    ProjectSettings,
)


BACKGROUND = (5, 6, 15)
CARD_BODY = (127, 133, 119)
TITLE_BACKGROUND = (239, 239, 241)
DIVIDER = (8, 9, 12)
DESCRIPTION_TEXT = (235, 236, 230)


def _clamp(value: float, minimum: float = 0.0, maximum: float = 1.0) -> float:
    return max(minimum, min(maximum, value))


def _smoothstep(value: float) -> float:
    value = _clamp(value)
    return value * value * (3.0 - 2.0 * value)


def _ease_out_back(value: float) -> float:
    value = _clamp(value)
    c1 = 1.70158
    c3 = c1 + 1
    return 1 + c3 * (value - 1) ** 3 + c1 * (value - 1) ** 2


def _find_font(bold: bool = False) -> str:
    candidates = (
        [
            "/usr/share/fonts/opentype/urw-base35/URWGothic-Demi.otf",
            "/usr/share/fonts/truetype/msttcorefonts/Arial_Bold.ttf",
            "C:/Windows/Fonts/GOTHICB.TTF",
            "/System/Library/Fonts/Supplemental/Century Gothic Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        ]
        if bold
        else [
            "/usr/share/fonts/opentype/urw-base35/URWGothic-Book.otf",
            "/usr/share/fonts/truetype/msttcorefonts/Arial.ttf",
            "C:/Windows/Fonts/GOTHIC.TTF",
            "/System/Library/Fonts/Supplemental/Century Gothic.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        ]
    )
    for candidate in candidates:
        if Path(candidate).is_file():
            return candidate
    return candidates[-1]


REGULAR_FONT = _find_font(False)
BOLD_FONT = _find_font(True)


class AssetCache:
    def __init__(self) -> None:
        self._images: dict[str, Image.Image] = {}
        self._errors: dict[str, str] = {}
        self._lock = threading.Lock()

    @property
    def errors(self) -> dict[str, str]:
        return dict(self._errors)

    def load(self, source: str) -> Image.Image | None:
        source = source.strip()
        if not source:
            return None
        with self._lock:
            if source in self._images:
                return self._images[source]
            if source in self._errors:
                return None
        try:
            if re.match(r"^https?://", source, flags=re.IGNORECASE):
                request = urllib.request.Request(
                    source,
                    headers={"User-Agent": "ComparisonTimelineStudio/0.2"},
                )
                with urllib.request.urlopen(request, timeout=15) as response:
                    from io import BytesIO

                    image = Image.open(BytesIO(response.read())).convert("RGB")
            else:
                image = Image.open(Path(source).expanduser()).convert("RGB")
            image.load()
            with self._lock:
                self._images[source] = image
            return image
        except Exception as exc:
            with self._lock:
                self._errors[source] = str(exc)
            return None

    def preload(self, cards: Iterable[CardData]) -> list[str]:
        errors: list[str] = []
        for index, card in enumerate(cards, start=1):
            if not card.image.strip():
                continue
            if self.load(card.image) is None:
                reason = self._errors.get(card.image, "unknown image error")
                errors.append(f"Card {index} ({card.title or 'untitled'}): {reason}")
        return errors


def _font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(BOLD_FONT if bold else REGULAR_FONT, max(5, int(size)))


def _wrap_lines(
    draw: ImageDraw.ImageDraw,
    text: str,
    font: ImageFont.FreeTypeFont,
    max_width: int,
    max_lines: int,
) -> list[str]:
    words = re.split(r"\s+", text.strip()) if text.strip() else []
    if not words:
        return []
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if draw.textbbox((0, 0), candidate, font=font)[2] <= max_width:
            current = candidate
            continue
        if current:
            lines.append(current)
        current = word
        if len(lines) >= max_lines:
            break
    if current and len(lines) < max_lines:
        lines.append(current)
    if len(lines) == max_lines and words:
        joined = " ".join(lines)
        if len(joined) < len(" ".join(words)):
            while lines[-1] and draw.textbbox((0, 0), lines[-1] + "…", font=font)[2] > max_width:
                lines[-1] = lines[-1][:-1]
            lines[-1] = lines[-1].rstrip() + "…"
    return lines


def _fit_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    maximum_size: int,
    minimum_size: int,
    max_lines: int,
    bold: bool,
) -> tuple[ImageFont.FreeTypeFont, list[str], int]:
    left, top, right, bottom = box
    available_width = max(1, right - left)
    available_height = max(1, bottom - top)
    for size in range(maximum_size, minimum_size - 1, -1):
        font = _font(size, bold)
        lines = _wrap_lines(draw, text, font, available_width, max_lines)
        if not lines:
            return font, [], size
        line_height = draw.textbbox((0, 0), "Ag", font=font)[3]
        spacing = max(2, round(size * 0.10))
        total_height = len(lines) * line_height + (len(lines) - 1) * spacing
        if total_height <= available_height:
            return font, lines, size
    font = _font(minimum_size, bold)
    return font, _wrap_lines(draw, text, font, available_width, max_lines), minimum_size


def _draw_text_box(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    fill: tuple[int, int, int] | tuple[int, int, int, int],
    maximum_size: int,
    minimum_size: int,
    max_lines: int,
    bold: bool = False,
    align: str = "center",
) -> None:
    left, top, right, bottom = box
    font, lines, size = _fit_text(
        draw, text, box, maximum_size, minimum_size, max_lines, bold
    )
    if not lines:
        return
    line_height = draw.textbbox((0, 0), "Ag", font=font)[3]
    spacing = max(2, round(size * 0.10))
    total_height = len(lines) * line_height + (len(lines) - 1) * spacing
    y = top + (bottom - top - total_height) / 2
    for line in lines:
        width = draw.textbbox((0, 0), line, font=font)[2]
        if align == "left":
            x = left
        else:
            x = left + (right - left - width) / 2
        draw.text((round(x), round(y)), line, font=font, fill=fill)
        y += line_height + spacing


MONTH_FORMATS = (
    "%Y-%m-%d",
    "%d/%m/%Y",
    "%m/%d/%Y",
    "%d %B %Y",
    "%d %b %Y",
    "%B %d %Y",
    "%B %d, %Y",
    "%b %d, %Y",
)


def date_lines(value: str) -> tuple[str, str]:
    text = value.strip()
    for date_format in MONTH_FORMATS:
        try:
            parsed = datetime.strptime(text, date_format)
            return f"{parsed.day} {parsed.strftime('%B')}", str(parsed.year)
        except ValueError:
            pass
    match = re.match(r"^(.*?)[,\s]+((?:19|20)\d{2})$", text)
    if match:
        return match.group(1).strip(), match.group(2)
    if re.fullmatch(r"(?:19|20)\d{2}", text):
        return text, ""
    return text, ""


class TimelineRenderer:
    """Deterministic renderer for the built-in comparison-video models."""

    def __init__(self, asset_cache: AssetCache | None = None) -> None:
        self.assets = asset_cache or AssetCache()

    def render(
        self,
        cards: list[CardData],
        output_time: float,
        settings: ProjectSettings,
        size: tuple[int, int] | None = None,
    ) -> Image.Image:
        width, height = size or (settings.width, settings.height)
        frame = Image.new("RGB", (width, height), BACKGROUND)
        if not cards:
            return frame

        model_time = settings.model_time(output_time, len(cards))
        automatic_duration = settings.auto_duration(len(cards))
        if model_time >= automatic_duration:
            return frame

        visible_cards = settings.effective_visible_cards()
        card_width = width / visible_cards
        placements = self._placements(len(cards), model_time, visible_cards, width)

        for index, x, alpha, badge_scale in placements:
            card_image = self._render_card(
                cards[index],
                max(1, round(card_width)),
                height,
                badge_scale,
                alpha,
                settings.model_id,
            )
            y_offset = round((1.0 - alpha) * height * 0.018)
            frame.paste(card_image, (round(x), y_offset), card_image)

        fade_start = automatic_duration - REFERENCE_FADE_SECONDS
        if model_time > fade_start:
            fade = _smoothstep((model_time - fade_start) / REFERENCE_FADE_SECONDS)
            overlay = Image.new("RGB", frame.size, BACKGROUND)
            frame = Image.blend(frame, overlay, fade)
        return frame

    def hit_test(
        self,
        cards: list[CardData],
        output_time: float,
        settings: ProjectSettings,
        normalized_x: float,
        normalized_y: float,
    ) -> tuple[int, str] | None:
        """Return the card row and semantic field beneath a preview click."""
        if not cards or not (0.0 <= normalized_x <= 1.0 and 0.0 <= normalized_y <= 1.0):
            return None
        model_time = settings.model_time(output_time, len(cards))
        if model_time >= settings.auto_duration(len(cards)):
            return None
        virtual_width = 1000.0
        visible_cards = settings.effective_visible_cards()
        card_width = virtual_width / visible_cards
        click_x = normalized_x * virtual_width
        placements = self._placements(len(cards), model_time, visible_cards, virtual_width)
        for index, x, alpha, _badge_scale in reversed(placements):
            if alpha < 0.08 or not (x <= click_x < x + card_width):
                continue
            local_x = (click_x - x) / card_width
            role = self._field_at(settings.model_id, local_x, normalized_y)
            return (index, role) if role else None
        return None

    def _placements(
        self,
        card_count: int,
        model_time: float,
        visible_cards: int,
        width: float,
    ) -> list[tuple[int, float, float, float]]:
        card_width = width / visible_cards
        initial_count = min(card_count, visible_cards)
        intro_duration = initial_count * REFERENCE_REVEAL_SECONDS
        placements: list[tuple[int, float, float, float]] = []
        if model_time < intro_duration:
            latest_visible = max(0, min(initial_count - 1, int(model_time // REFERENCE_REVEAL_SECONDS)))
            focus_center = (latest_visible + 0.5) * card_width
            for index in range(initial_count):
                local = model_time - index * REFERENCE_REVEAL_SECONDS
                if local < 0:
                    continue
                progress = _smoothstep(local / 0.62)
                x = index * card_width
                center = x + card_width / 2
                badge_scale = self._badge_scale(center, focus_center, card_width)
                entrance_scale = min(1.0, _ease_out_back(local / 0.58))
                placements.append((index, x, progress, badge_scale * entrance_scale))
            return placements

        scroll_elapsed = max(0.0, model_time - intro_duration)
        shift = min(
            max(0, card_count - visible_cards),
            scroll_elapsed / REFERENCE_SCROLL_SECONDS,
        ) * card_width
        focus_center = (visible_cards - 0.5) * card_width
        for index in range(card_count):
            x = index * card_width - shift
            if x >= width or x + card_width <= 0:
                continue
            center = x + card_width / 2
            placements.append((index, x, 1.0, self._badge_scale(center, focus_center, card_width)))
        return placements

    @staticmethod
    def _field_at(model_id: str, local_x: float, local_y: float) -> str | None:
        if model_id == MODEL_ILLUSTRATED:
            if local_y >= 0.88:
                return "title"
            # The badge floats over the artwork; clicks outside its footprint edit artwork.
            if 0.12 <= local_x <= 0.88 and local_y <= 0.32:
                return "badge_primary" if local_y <= 0.21 else "badge_secondary"
            return "image"
        if model_id == MODEL_CLASSIC:
            if local_y < 0.39:
                return "badge_primary" if local_y < 0.25 else "badge_secondary"
            if local_y < 0.495:
                return "title"
            return "image"
        if local_y < 0.44:
            return "badge_primary"
        if local_y < 0.538:
            return "title"
        if local_y < 0.67:
            return "description"
        return "image"

    @staticmethod
    def _badge_scale(center: float, focus: float, card_width: float) -> float:
        distance = (center - focus) / max(1.0, card_width * 0.60)
        return 0.72 + 0.44 * math.exp(-0.5 * distance * distance)

    def _render_card(
        self,
        card: CardData,
        width: int,
        height: int,
        badge_scale: float,
        alpha: float,
        model_id: str,
    ) -> Image.Image:
        if model_id == MODEL_ILLUSTRATED:
            layer = self._render_illustrated_card(card, width, height, badge_scale)
        elif model_id == MODEL_CLASSIC:
            layer = self._render_classic_card(card, width, height, badge_scale)
        else:
            layer = self._render_reference_card(card, width, height, badge_scale)
        if alpha < 0.999:
            channel = layer.getchannel("A").point(lambda value: round(value * alpha))
            layer.putalpha(channel)
        return layer

    def _render_reference_card(
        self,
        card: CardData,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        top_height = round(height * 0.44)
        title_height = round(height * 0.098)
        title_top = top_height
        body_top = title_top + title_height
        divider_width = max(2, round(width * 0.008))

        draw.rectangle((0, title_top, width, title_top + title_height), fill=TITLE_BACKGROUND + (255,))
        draw.rectangle((0, body_top, width, height), fill=CARD_BODY + (255,))
        draw.rectangle((0, title_top, divider_width, height), fill=DIVIDER + (255,))
        draw.rectangle((width - divider_width, title_top, width, height), fill=DIVIDER + (255,))
        draw.rectangle((0, title_top, width, title_top + max(2, divider_width // 2)), fill=DIVIDER + (255,))
        draw.rectangle((0, body_top, width, body_top + max(2, divider_width // 2)), fill=DIVIDER + (255,))

        title_padding = round(width * 0.045)
        _draw_text_box(
            draw,
            card.title,
            (title_padding, title_top + 5, width - title_padding, body_top - 5),
            (15, 15, 17, 255),
            maximum_size=round(height * 0.047),
            minimum_size=round(height * 0.025),
            max_lines=2,
            bold=True,
        )

        body_height = height - body_top
        image_top = body_top + round(body_height * 0.29)
        image_margin = round(width * 0.085)
        image_box = (
            image_margin,
            image_top,
            width - image_margin,
            height - max(5, divider_width),
        )
        source_image = self.assets.load(card.image)
        if source_image is not None:
            target_size = (image_box[2] - image_box[0], image_box[3] - image_box[1])
            fitted = ImageOps.fit(source_image, target_size, Image.Resampling.LANCZOS)
            layer.paste(fitted, (image_box[0], image_box[1]))
            draw.rectangle(image_box, outline=(24, 25, 23, 255), width=max(2, divider_width // 2))

        description_padding = round(width * 0.045)
        _draw_text_box(
            draw,
            card.description,
            (
                description_padding,
                body_top + round(body_height * 0.035),
                width - description_padding,
                image_top - round(body_height * 0.025),
            ),
            DESCRIPTION_TEXT + (255,),
            maximum_size=round(height * 0.031),
            minimum_size=round(height * 0.018),
            max_lines=4,
            bold=False,
        )

        primary, secondary = card.uploaded, card.badge_label
        if primary and not secondary:
            primary, secondary = date_lines(primary)
        badge = self._render_badge(primary, secondary, width, top_height, badge_scale)
        badge_x = (width - badge.width) // 2
        badge_y = max(4, round((top_height - badge.height) * 0.52))
        layer.alpha_composite(badge, (badge_x, badge_y))
        return layer

    def _render_classic_card(
        self,
        card: CardData,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        top_height = round(height * 0.39)
        title_height = round(height * 0.105)
        title_top = top_height
        image_top = title_top + title_height
        divider = max(2, round(width * 0.008))
        draw.rectangle((0, 0, width, top_height), fill=(16, 17, 19, 255))
        draw.rectangle((0, title_top, width, image_top), fill=(239, 239, 239, 255))
        draw.rectangle((0, image_top, width, height), fill=(118, 119, 117, 255))
        draw.rectangle((0, 0, divider, height), fill=(5, 6, 8, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(5, 6, 8, 255))
        draw.rectangle((0, title_top, width, title_top + divider), fill=(5, 6, 8, 255))
        draw.rectangle((0, image_top, width, image_top + divider), fill=(5, 6, 8, 255))

        _draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), title_top + 3, width - round(width * 0.035), image_top - 3),
            (15, 15, 15, 255),
            maximum_size=round(height * 0.047),
            minimum_size=round(height * 0.022),
            max_lines=2,
            bold=True,
        )
        source = self.assets.load(card.image)
        if source is not None:
            fitted = ImageOps.fit(source, (width - divider * 2, height - image_top - divider), Image.Resampling.LANCZOS)
            layer.paste(fitted, (divider, image_top + divider))

        badge = self._render_badge(card.uploaded, card.badge_label, width, top_height, badge_scale * 0.97)
        layer.alpha_composite(badge, ((width - badge.width) // 2, max(2, round((top_height - badge.height) * 0.50))))
        return layer

    def _render_illustrated_card(
        self,
        card: CardData,
        width: int,
        height: int,
        badge_scale: float,
    ) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        title_height = round(height * 0.12)
        title_top = height - title_height
        divider = max(2, round(width * 0.008))
        source = self.assets.load(card.image)
        if source is not None:
            fitted = ImageOps.fit(source, (width - divider * 2, title_top), Image.Resampling.LANCZOS)
            layer.paste(fitted, (divider, 0))
        else:
            # An intentionally empty illustrated set: only the model's sky/floor styling.
            draw.rectangle((divider, 0, width - divider, round(title_top * 0.64)), fill=(70, 204, 226, 255))
            draw.rectangle((divider, round(title_top * 0.64), width - divider, title_top), fill=(242, 198, 111, 255))
            horizon = round(title_top * 0.64)
            draw.line((divider, horizon, width - divider, horizon), fill=(43, 122, 143, 255), width=max(2, divider))
        draw.rectangle((0, title_top, width, height), fill=(249, 248, 244, 255))
        draw.rectangle((0, 0, divider, height), fill=(30, 30, 28, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(30, 30, 28, 255))
        draw.rectangle((0, title_top, width, title_top + divider), fill=(30, 30, 28, 255))
        _draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), title_top + 3, width - round(width * 0.035), height - 3),
            (18, 18, 16, 255),
            maximum_size=round(height * 0.048),
            minimum_size=round(height * 0.022),
            max_lines=2,
            bold=True,
        )
        top_height = round(height * 0.37)
        badge = self._render_badge(card.uploaded, card.badge_label, width, top_height, badge_scale * 0.87)
        layer.alpha_composite(badge, ((width - badge.width) // 2, max(3, round(height * 0.025))))
        return layer

    @staticmethod
    def _draw_image_placeholder(
        draw: ImageDraw.ImageDraw,
        box: tuple[int, int, int, int],
        height: int,
    ) -> None:
        left, top, right, bottom = box
        draw.rectangle(box, fill=(63, 67, 62, 255), outline=(38, 41, 38, 255), width=max(2, round(height * 0.002)))
        cx, cy = (left + right) // 2, (top + bottom) // 2
        icon_width = max(20, (right - left) // 4)
        icon_height = max(16, (bottom - top) // 5)
        draw.rounded_rectangle(
            (cx - icon_width // 2, cy - icon_height // 2, cx + icon_width // 2, cy + icon_height // 2),
            radius=max(3, icon_width // 14),
            outline=(166, 171, 160, 255),
            width=max(2, round(height * 0.003)),
        )
        draw.ellipse(
            (cx - icon_width // 4, cy - icon_height // 3, cx - icon_width // 8, cy - icon_height // 6),
            fill=(166, 171, 160, 255),
        )
        draw.polygon(
            [
                (cx - icon_width // 3, cy + icon_height // 3),
                (cx - icon_width // 12, cy),
                (cx + icon_width // 12, cy + icon_height // 5),
                (cx + icon_width // 4, cy - icon_height // 8),
                (cx + icon_width // 3, cy + icon_height // 3),
            ],
            fill=(166, 171, 160, 255),
        )

    @staticmethod
    def _render_badge(
        primary: str,
        secondary: str,
        card_width: int,
        top_height: int,
        scale: float,
    ) -> Image.Image:
        badge_width = max(20, round(card_width * 0.69 * scale))
        badge_height = max(24, round(top_height * 0.73 * scale))
        padding = max(8, round(badge_width * 0.08))
        canvas = Image.new(
            "RGBA",
            (badge_width + padding * 2, badge_height + padding * 2),
            (0, 0, 0, 0),
        )
        mask = Image.new("L", canvas.size, 0)
        mask_draw = ImageDraw.Draw(mask)
        x0, y0 = padding, padding
        x1, y1 = padding + badge_width, padding + badge_height
        points = [
            ((x0 + x1) // 2, y0),
            (x1, y0 + round(badge_height * 0.20)),
            (x1, y0 + round(badge_height * 0.78)),
            ((x0 + x1) // 2, y1),
            (x0, y0 + round(badge_height * 0.78)),
            (x0, y0 + round(badge_height * 0.20)),
        ]
        mask_draw.polygon(points, fill=255)

        shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        shadow_mask = mask.filter(ImageFilter.GaussianBlur(max(3, round(padding * 0.55))))
        shadow.putalpha(shadow_mask.point(lambda value: round(value * 0.72)))
        canvas.alpha_composite(shadow, (max(1, padding // 6), max(2, padding // 3)))

        gradient = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        gradient_draw = ImageDraw.Draw(gradient)
        for y in range(y0, y1 + 1):
            position = (y - y0) / max(1, badge_height)
            center_light = 1.0 - abs(position - 0.38) * 1.3
            red = round(205 + 43 * _clamp(center_light))
            green = round(2 + 12 * _clamp(center_light))
            blue = round(8 + 7 * _clamp(center_light))
            gradient_draw.line((x0, y, x1, y), fill=(red, green, blue, 255))
        gradient.putalpha(mask)
        canvas.alpha_composite(gradient)

        border = ImageDraw.Draw(canvas)
        border.line(points + [points[0]], fill=(125, 0, 6, 235), width=max(1, round(badge_width * 0.012)), joint="curve")

        text_draw = ImageDraw.Draw(canvas)
        inner_left = x0 + round(badge_width * 0.07)
        inner_right = x1 - round(badge_width * 0.07)
        main_top = y0 + round(badge_height * (0.22 if secondary else 0.17))
        main_bottom = y0 + round(badge_height * (0.68 if secondary else 0.83))
        _draw_text_box(
            text_draw,
            primary,
            (inner_left, main_top, inner_right, main_bottom),
            (255, 250, 244, 255),
            maximum_size=round(badge_height * 0.25),
            minimum_size=max(9, round(badge_height * 0.10)),
            max_lines=2,
            bold=True,
        )
        if secondary:
            _draw_text_box(
                text_draw,
                secondary,
                (inner_left, y0 + round(badge_height * 0.67), inner_right, y0 + round(badge_height * 0.88)),
                (255, 248, 240, 255),
                maximum_size=round(badge_height * 0.13),
                minimum_size=max(8, round(badge_height * 0.09)),
                max_lines=2,
                bold=True,
            )
        return canvas
