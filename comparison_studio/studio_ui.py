from __future__ import annotations

import json
import math
import re
import shutil
import subprocess
import tempfile
import threading
import urllib.request
from dataclasses import asdict, dataclass
from io import BytesIO
from pathlib import Path
from urllib.parse import unquote, urlparse

from PIL import Image, ImageDraw, ImageFont, ImageOps
from PySide6.QtCore import QSignalBlocker, Qt
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QGroupBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QScrollArea,
    QSlider,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from . import exporter as exporter_module
from . import renderer as renderer_module
from .data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    AudioTrack,
    FriendlyError,
    ProjectDocument,
    ProjectSettings,
    SpreadsheetData,
    format_duration,
    load_project_document,
)
from .premiere_ui import PremiereMainWindow
from .renderer import (
    BACKGROUND,
    CARD_BODY,
    DESCRIPTION_TEXT,
    DIVIDER,
    TITLE_BACKGROUND,
    AssetCache,
    TimelineRenderer,
    _clamp,
    _draw_text_box,
    date_lines,
    is_remote_image_source,
    normalize_image_source,
)


BACKGROUND_CHOICES = (
    ("Beach", "beach"),
    ("Sunset", "sunset"),
    ("Forest", "forest"),
    ("Lavender", "lavender"),
    ("Night", "night"),
    ("Blueprint Grid", "grid"),
)

_FONT_LOCK = threading.RLock()
_FONT_PATH_CACHE: dict[tuple[str, bool], str] = {}


@dataclass(slots=True)
class StudioProjectSettings(ProjectSettings):
    """0.4.0 visual options layered on top of the stable 0.3.5 settings."""

    font_family: str = "CTS Default"
    illustrated_background: str = "beach"
    image_scale: float = 1.0
    illustrated_badge_scale: float = 1.0
    illustrated_auto_size: bool = False


def _font_path(family: str, bold: bool) -> str:
    family = (family or "CTS Default").strip()
    if family == "CTS Default":
        return renderer_module.BOLD_FONT if bold else renderer_module.REGULAR_FONT

    cache_key = (family, bold)
    cached = _FONT_PATH_CACHE.get(cache_key)
    if cached:
        return cached

    style = "Bold" if bold else "Regular"
    try:
        result = subprocess.run(
            ["fc-match", "-f", "%{file}", f"{family}:style={style}"],
            check=False,
            capture_output=True,
            text=True,
            timeout=3,
        )
        candidate = result.stdout.strip()
        if candidate and Path(candidate).is_file():
            _FONT_PATH_CACHE[cache_key] = candidate
            return candidate
    except (OSError, subprocess.SubprocessError):
        pass

    for candidate in (family, f"{family}.ttf", f"{family}.otf"):
        try:
            ImageFont.truetype(candidate, 12)
            _FONT_PATH_CACHE[cache_key] = candidate
            return candidate
        except OSError:
            continue

    fallback = renderer_module.BOLD_FONT if bold else renderer_module.REGULAR_FONT
    _FONT_PATH_CACHE[cache_key] = fallback
    return fallback


class StudioAssetCache(AssetCache):
    """Asset cache that keeps alpha so Illustrated backgrounds remain visible."""

    def load(self, source: str) -> Image.Image | None:
        source = normalize_image_source(source)
        if not source:
            return None
        with self._lock:
            if source in self._images:
                return self._images[source]
            if source in self._errors:
                return None
        try:
            if is_remote_image_source(source):
                request = urllib.request.Request(
                    source,
                    headers={
                        "User-Agent": (
                            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                            "Chrome/150.0 Safari/537.36 CTS/0.4.0"
                        ),
                        "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
                    },
                )
                with urllib.request.urlopen(request, timeout=15) as response:
                    payload = response.read(40 * 1024 * 1024 + 1)
                    if len(payload) > 40 * 1024 * 1024:
                        raise ValueError("remote image is larger than 40 MiB")
                    image = Image.open(BytesIO(payload)).convert("RGBA")
            elif source.lower().startswith("file://"):
                parsed = urlparse(source)
                image = Image.open(Path(unquote(parsed.path))).convert("RGBA")
            else:
                image = Image.open(Path(source).expanduser()).convert("RGBA")
            image.load()
            with self._lock:
                self._images[source] = image
            return image
        except Exception as exc:
            with self._lock:
                self._errors[source] = str(exc)
            return None


class StudioTimelineRenderer(TimelineRenderer):
    """0.3.5 renderer with opt-in 0.4.0 visual controls."""

    def __init__(self, asset_cache: AssetCache | None = None) -> None:
        super().__init__(asset_cache or StudioAssetCache())
        self._studio_settings = StudioProjectSettings()

    def render(
        self,
        cards,
        output_time: float,
        settings: ProjectSettings,
        size: tuple[int, int] | None = None,
    ) -> Image.Image:
        self._studio_settings = settings
        regular = _font_path(getattr(settings, "font_family", "CTS Default"), False)
        bold = _font_path(getattr(settings, "font_family", "CTS Default"), True)
        with _FONT_LOCK:
            previous_regular = renderer_module.REGULAR_FONT
            previous_bold = renderer_module.BOLD_FONT
            renderer_module.REGULAR_FONT = regular
            renderer_module.BOLD_FONT = bold
            try:
                return super().render(cards, output_time, settings, size)
            finally:
                renderer_module.REGULAR_FONT = previous_regular
                renderer_module.BOLD_FONT = previous_bold

    @property
    def _image_scale(self) -> float:
        return _clamp(float(getattr(self._studio_settings, "image_scale", 1.0)), 0.35, 2.5)

    @staticmethod
    def _scaled_fit(source: Image.Image, size: tuple[int, int], scale: float) -> Image.Image:
        target_width, target_height = max(1, size[0]), max(1, size[1])
        fitted = ImageOps.fit(source.convert("RGBA"), (target_width, target_height), Image.Resampling.LANCZOS)
        scale = _clamp(scale, 0.35, 2.5)
        if abs(scale - 1.0) < 0.001:
            return fitted
        scaled_size = (
            max(1, round(target_width * scale)),
            max(1, round(target_height * scale)),
        )
        scaled = fitted.resize(scaled_size, Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (target_width, target_height), (0, 0, 0, 0))
        x = (target_width - scaled.width) // 2
        y = (target_height - scaled.height) // 2
        canvas.alpha_composite(scaled, (x, y))
        return canvas

    @staticmethod
    def _draw_background(
        draw: ImageDraw.ImageDraw,
        box: tuple[int, int, int, int],
        background_id: str,
    ) -> None:
        left, top, right, bottom = box
        width = max(1, right - left)
        height = max(1, bottom - top)

        if background_id == "sunset":
            for row in range(height):
                t = row / max(1, height - 1)
                if t < 0.68:
                    local = t / 0.68
                    color = (
                        round(83 + 172 * local),
                        round(70 + 66 * local),
                        round(150 - 80 * local),
                        255,
                    )
                else:
                    local = (t - 0.68) / 0.32
                    color = (
                        round(238 - 40 * local),
                        round(138 + 42 * local),
                        round(82 - 28 * local),
                        255,
                    )
                draw.line((left, top + row, right, top + row), fill=color)
            sun_r = max(8, round(min(width, height) * 0.09))
            sun_x = left + round(width * 0.76)
            sun_y = top + round(height * 0.28)
            draw.ellipse((sun_x - sun_r, sun_y - sun_r, sun_x + sun_r, sun_y + sun_r), fill=(255, 225, 152, 235))
            draw.rectangle((left, top + round(height * 0.72), right, bottom), fill=(92, 54, 83, 255))
            return

        if background_id == "forest":
            draw.rectangle(box, fill=(169, 218, 180, 255))
            horizon = top + round(height * 0.68)
            draw.rectangle((left, horizon, right, bottom), fill=(91, 139, 88, 255))
            step = max(18, round(width * 0.12))
            for x in range(left - step, right + step, step):
                tree_h = round(height * (0.34 + 0.08 * ((x // step) % 3)))
                trunk_w = max(2, round(step * 0.12))
                draw.rectangle((x - trunk_w, horizon - tree_h // 4, x + trunk_w, horizon), fill=(88, 65, 47, 255))
                draw.polygon(
                    [
                        (x, horizon - tree_h),
                        (x - round(step * 0.48), horizon - round(tree_h * 0.25)),
                        (x + round(step * 0.48), horizon - round(tree_h * 0.25)),
                    ],
                    fill=(35, 108, 70, 245),
                )
            return

        if background_id == "lavender":
            for row in range(height):
                t = row / max(1, height - 1)
                color = (
                    round(190 - 44 * t),
                    round(186 - 58 * t),
                    round(236 - 18 * t),
                    255,
                )
                draw.line((left, top + row, right, top + row), fill=color)
            spacing = max(12, round(width * 0.08))
            for x in range(left, right + spacing, spacing):
                draw.line((x, bottom, x + round(spacing * 0.65), top + round(height * 0.60)), fill=(93, 75, 137, 150), width=max(1, spacing // 12))
            return

        if background_id == "night":
            draw.rectangle(box, fill=(17, 25, 54, 255))
            for index in range(28):
                x = left + (index * 79 + 31) % width
                y = top + (index * 47 + 13) % max(1, round(height * 0.66))
                radius = 1 + index % 2
                draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=(235, 240, 255, 210))
            moon_r = max(8, round(min(width, height) * 0.075))
            moon_x = left + round(width * 0.77)
            moon_y = top + round(height * 0.22)
            draw.ellipse((moon_x - moon_r, moon_y - moon_r, moon_x + moon_r, moon_y + moon_r), fill=(244, 239, 203, 255))
            draw.rectangle((left, top + round(height * 0.72), right, bottom), fill=(24, 47, 63, 255))
            return

        if background_id == "grid":
            draw.rectangle(box, fill=(34, 73, 105, 255))
            grid = max(14, round(min(width, height) * 0.08))
            for x in range(left, right + 1, grid):
                draw.line((x, top, x, bottom), fill=(104, 171, 211, 105), width=1)
            for y in range(top, bottom + 1, grid):
                draw.line((left, y, right, y), fill=(104, 171, 211, 105), width=1)
            return

        horizon = top + round(height * 0.64)
        draw.rectangle((left, top, right, horizon), fill=(70, 204, 226, 255))
        draw.rectangle((left, horizon, right, bottom), fill=(242, 198, 111, 255))
        draw.line((left, horizon, right, horizon), fill=(43, 122, 143, 255), width=max(2, round(width * 0.008)))

    def _render_reference_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
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
        image_box = (image_margin, image_top, width - image_margin, height - max(5, divider_width))
        source_image = self.assets.load(card.image)
        if source_image is not None:
            target_size = (image_box[2] - image_box[0], image_box[3] - image_box[1])
            fitted = self._scaled_fit(source_image, target_size, self._image_scale)
            layer.alpha_composite(fitted, (image_box[0], image_box[1]))
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

    def _render_classic_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
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
            minimum_size=max(8, round(height * 0.016)),
            max_lines=3,
            bold=True,
        )
        source = self.assets.load(card.image)
        if source is not None:
            target_size = (width - divider * 2, height - image_top - divider)
            fitted = self._scaled_fit(source, target_size, self._image_scale)
            layer.alpha_composite(fitted, (divider, image_top + divider))

        badge = self._render_badge(
            card.uploaded,
            card.badge_label,
            width,
            top_height,
            badge_scale * 0.97,
            primary_max_lines=3,
            secondary_max_lines=3,
            minimum_text_scale=0.07,
        )
        layer.alpha_composite(
            badge,
            ((width - badge.width) // 2, max(2, round((top_height - badge.height) * 0.50))),
        )
        return layer

    def _render_illustrated_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        title_height = round(height * 0.12)
        title_top = height - title_height
        divider = max(2, round(width * 0.008))

        background_id = getattr(self._studio_settings, "illustrated_background", "beach")
        self._draw_background(draw, (divider, 0, width - divider, title_top), background_id)

        source = self.assets.load(card.image)
        manual_image_scale = self._image_scale
        badge_manual = _clamp(
            float(getattr(self._studio_settings, "illustrated_badge_scale", 1.0)),
            0.45,
            2.0,
        )
        auto_factor = 1.0
        image_auto_factor = 1.0
        if bool(getattr(self._studio_settings, "illustrated_auto_size", False)):
            text_weight = max(len(card.uploaded.strip()), round(len(card.badge_label.strip()) * 0.72))
            auto_factor = _clamp(0.90 + max(0, text_weight - 3) * 0.033, 0.90, 1.30)
            image_auto_factor = _clamp(1.08 - (auto_factor - 0.90) * 0.42, 0.88, 1.08)

        if source is not None:
            target_size = (width - divider * 2, title_top)
            artwork = self._scaled_fit(source, target_size, manual_image_scale * image_auto_factor)
            layer.alpha_composite(artwork, (divider, 0))

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
        badge = self._render_badge(
            card.uploaded,
            card.badge_label,
            width,
            top_height,
            badge_scale * 0.87 * badge_manual * auto_factor,
        )
        layer.alpha_composite(badge, ((width - badge.width) // 2, max(3, round(height * 0.025))))
        return layer


exporter_module.AssetCache = StudioAssetCache
exporter_module.TimelineRenderer = StudioTimelineRenderer


def _load_studio_document(path: str | Path) -> tuple[ProjectDocument, dict]:
    target = Path(path)
    payload = json.loads(target.read_text(encoding="utf-8"))
    settings_payload = dict(payload.get("settings", {}))
    extras = {
        "font_family": settings_payload.pop("font_family", "CTS Default"),
        "illustrated_background": settings_payload.pop("illustrated_background", "beach"),
        "image_scale": settings_payload.pop("image_scale", 1.0),
        "illustrated_badge_scale": settings_payload.pop("illustrated_badge_scale", 1.0),
        "illustrated_auto_size": settings_payload.pop("illustrated_auto_size", False),
    }
    if int(payload.get("version", 1)) >= 2 and "spreadsheet" in payload:
        valid = {
            key: value
            for key, value in settings_payload.items()
            if key in ProjectSettings.__dataclass_fields__
        }
        document = ProjectDocument(
            SpreadsheetData(**payload.get("spreadsheet", {})).normalized(),
            ProjectSettings(**valid),
            [AudioTrack(**entry) for entry in payload.get("audio_tracks", [])],
        )
        return document, extras
    return load_project_document(path), extras


class EnhancedPremiereMainWindow(PremiereMainWindow):
    """Premiere-style CTS 0.4.0 plus the requested visual customization tools."""

    def __init__(self) -> None:
        super().__init__()
        self.renderer = StudioTimelineRenderer(StudioAssetCache())
        self.statusBar().showMessage("Ready · 0.4.0 visual controls · 0.3.5 workflow")
        self._update_visual_control_state()
        self.update_preview()

    def _build_models_tab(self) -> QWidget:
        scroll = super()._build_models_tab()
        page = scroll.widget()
        layout = page.layout()

        visual_group = QGroupBox("0.4.0 visual controls")
        form = QFormLayout(visual_group)

        self.font_combo = QComboBox()
        self.font_combo.setEditable(True)
        self.font_combo.addItem("CTS Default")
        try:
            from PySide6.QtGui import QFontDatabase

            for family in QFontDatabase.families():
                if family and self.font_combo.findText(family) < 0:
                    self.font_combo.addItem(family)
        except Exception:
            for family in ("DejaVu Sans", "Liberation Sans", "Noto Sans", "Ubuntu"):
                self.font_combo.addItem(family)
        self.font_combo.setCurrentText("CTS Default")
        self.font_combo.currentTextChanged.connect(self._visual_option_changed)
        form.addRow("Font", self.font_combo)

        self.background_combo = QComboBox()
        for label, background_id in BACKGROUND_CHOICES:
            self.background_combo.addItem(label, background_id)
        self.background_combo.currentIndexChanged.connect(self._visual_option_changed)
        form.addRow("Illustrated background", self.background_combo)

        self.image_scale_slider, self.image_scale_value, image_row = self._scale_control(50, 200, 100)
        self.image_scale_slider.valueChanged.connect(self._image_scale_changed)
        form.addRow("Image scale (all models)", image_row)

        self.badge_scale_slider, self.badge_scale_value, badge_row = self._scale_control(60, 160, 100)
        self.badge_scale_slider.valueChanged.connect(self._badge_scale_changed)
        form.addRow("Illustrated hexagon", badge_row)

        self.illustrated_auto_size = QCheckBox("Auto-size artwork and hexagon from typed value")
        self.illustrated_auto_size.setToolTip(
            "Longer badge text automatically receives more hexagon room and slightly less artwork scale."
        )
        self.illustrated_auto_size.toggled.connect(self._visual_option_changed)
        form.addRow(self.illustrated_auto_size)

        insert_at = max(0, layout.count() - 1)
        layout.insertWidget(insert_at, visual_group)
        self.visual_group = visual_group
        return scroll

    @staticmethod
    def _scale_control(minimum: int, maximum: int, value: int):
        container = QWidget()
        row = QHBoxLayout(container)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(7)
        slider = QSlider(Qt.Orientation.Horizontal)
        slider.setRange(minimum, maximum)
        slider.setValue(value)
        label = QLabel(f"{value}%")
        label.setMinimumWidth(42)
        label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        row.addWidget(slider, 1)
        row.addWidget(label)
        return slider, label, container

    def _visual_option_changed(self, *_args) -> None:
        self._update_visual_control_state()
        self._data_changed()

    def _image_scale_changed(self, value: int) -> None:
        self.image_scale_value.setText(f"{value}%")
        self._data_changed()

    def _badge_scale_changed(self, value: int) -> None:
        self.badge_scale_value.setText(f"{value}%")
        self._data_changed()

    def _update_visual_control_state(self) -> None:
        if not hasattr(self, "model_combo") or not hasattr(self, "background_combo"):
            return
        illustrated = (self.model_combo.currentData() or MODEL_REFERENCE) == MODEL_ILLUSTRATED
        self.background_combo.setEnabled(illustrated)
        self.badge_scale_slider.setEnabled(illustrated)
        self.illustrated_auto_size.setEnabled(illustrated)

    def _model_changed(self) -> None:
        super()._model_changed()
        self._update_visual_control_state()

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = StudioTimelineRenderer(StudioAssetCache())
            if hasattr(self, "preview_debounce"):
                self.preview_debounce.start()

    def project_settings(self) -> StudioProjectSettings:
        base = super().project_settings()
        return StudioProjectSettings(
            **asdict(base),
            font_family=self.font_combo.currentText().strip() or "CTS Default",
            illustrated_background=self.background_combo.currentData() or "beach",
            image_scale=self.image_scale_slider.value() / 100.0,
            illustrated_badge_scale=self.badge_scale_slider.value() / 100.0,
            illustrated_auto_size=self.illustrated_auto_size.isChecked(),
        )

    def open_project(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Open project",
            "",
            "Comparison Studio projects (*.cts.json)",
        )
        if not path:
            return
        try:
            document, extras = _load_studio_document(path)
            self._suspend_model_schema = True
            self.table.set_data(document.data)
            model_index = self.model_combo.findData(document.settings.model_id)
            self.model_combo.setCurrentIndex(max(0, model_index))
            self.default_visible.setChecked(document.settings.visible_cards == 0)
            if document.settings.visible_cards:
                self.visible_cards.setValue(document.settings.visible_cards)
            for role, combo in self.mapping_combos.items():
                combo.setCurrentIndex(
                    max(0, combo.findData(document.settings.field_mapping.get(role, "")))
                )
            if document.settings.custom_duration is None:
                self.auto_length.setChecked(True)
            else:
                self.auto_length.setChecked(False)
                self.custom_length.setText(format_duration(document.settings.custom_duration))
            self.master_volume.setValue(round(document.settings.soundtrack_master_volume * 100))
            self.hexagons_bounce.setChecked(document.settings.hexagons_bounce)
            self.soundtrack_table.set_tracks(document.audio_tracks)

            self.font_combo.setCurrentText(str(extras["font_family"]))
            background_index = self.background_combo.findData(extras["illustrated_background"])
            self.background_combo.setCurrentIndex(max(0, background_index))
            self.image_scale_slider.setValue(round(float(extras["image_scale"]) * 100))
            self.badge_scale_slider.setValue(round(float(extras["illustrated_badge_scale"]) * 100))
            self.illustrated_auto_size.setChecked(bool(extras["illustrated_auto_size"]))

            self._suspend_model_schema = False
            self._refresh_field_guide(document.settings.model_id)
            self._update_visual_control_state()
            self.position_seconds = 0.0
            self.update_preview()
            self.statusBar().showMessage(f"Opened {Path(path).name}", 5000)
        except FriendlyError as exc:
            self._suspend_model_schema = False
            from .ui import show_error

            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            self._suspend_model_schema = False
            from .ui import show_error

            show_error(
                self,
                "Could not finish opening the project.",
                "Your current project was not intentionally changed.",
                str(exc),
            )
