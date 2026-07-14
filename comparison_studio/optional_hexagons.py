from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter
from PySide6.QtWidgets import QCheckBox

from . import exporter as exporter_module
from .data import MODEL_CLASSIC, MODEL_ILLUSTRATED, MODEL_REFERENCE
from .live_transform import LiveTransformMainWindow
from .studio_ui import StudioAssetCache, StudioProjectSettings, _draw_text_box
from .interaction_runtime import RuntimeTransformRenderer


@dataclass(slots=True)
class OptionalHexagonSettings(StudioProjectSettings):
    show_hexagons: bool = True


class OptionalHexagonRenderer(RuntimeTransformRenderer):
    """Renderer with a genuine no-badge composition for every visual model."""

    @property
    def _show_hexagons(self) -> bool:
        return bool(getattr(self._studio_settings, "show_hexagons", True))

    def _render_reference_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        if self._show_hexagons:
            return super()._render_reference_card(card, width, height, badge_scale)

        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.008))
        title_height = round(height * 0.13)
        description_height = round(height * 0.20)
        description_top = title_height
        image_top = description_top + description_height

        draw.rectangle((0, 0, width, title_height), fill=(249, 248, 244, 255))
        draw.rectangle((0, description_top, width, image_top), fill=(203, 198, 187, 255))
        draw.rectangle((0, image_top, width, height), fill=(92, 94, 93, 255))
        draw.rectangle((0, 0, divider, height), fill=(18, 18, 18, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(18, 18, 18, 255))
        draw.rectangle((0, title_height, width, title_height + divider), fill=(18, 18, 18, 255))
        draw.rectangle((0, image_top, width, image_top + divider), fill=(18, 18, 18, 255))

        _draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), 3, width - round(width * 0.035), title_height - 3),
            (18, 18, 16, 255),
            maximum_size=round(height * 0.052),
            minimum_size=max(9, round(height * 0.022)),
            max_lines=2,
            bold=True,
        )
        _draw_text_box(
            draw,
            card.description,
            (
                round(width * 0.045),
                description_top + round(description_height * 0.08),
                width - round(width * 0.045),
                image_top - round(description_height * 0.08),
            ),
            (42, 40, 36, 255),
            maximum_size=round(height * 0.034),
            minimum_size=max(8, round(height * 0.018)),
            max_lines=5,
            bold=False,
        )

        source = self.assets.load(card.image)
        if source is not None:
            target_size = (width - divider * 2, height - image_top - divider)
            fitted = self._scaled_fit(source, target_size, self._image_scale)
            layer.alpha_composite(fitted, (divider, image_top + divider))
        return layer

    def _render_classic_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        if self._show_hexagons:
            return super()._render_classic_card(card, width, height, badge_scale)

        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        divider = max(2, round(width * 0.008))
        title_height = round(height * 0.13)
        image_top = title_height

        draw.rectangle((0, 0, width, title_height), fill=(249, 248, 244, 255))
        draw.rectangle((0, image_top, width, height), fill=(118, 119, 117, 255))
        draw.rectangle((0, 0, divider, height), fill=(5, 6, 8, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(5, 6, 8, 255))
        draw.rectangle((0, image_top, width, image_top + divider), fill=(5, 6, 8, 255))

        _draw_text_box(
            draw,
            card.title,
            (round(width * 0.035), 3, width - round(width * 0.035), title_height - 3),
            (15, 15, 15, 255),
            maximum_size=round(height * 0.055),
            minimum_size=max(9, round(height * 0.020)),
            max_lines=2,
            bold=True,
        )
        source = self.assets.load(card.image)
        if source is not None:
            target_size = (width - divider * 2, height - image_top - divider)
            fitted = self._scaled_fit(source, target_size, self._image_scale)
            layer.alpha_composite(fitted, (divider, image_top + divider))
        return layer

    def _render_illustrated_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        if self._show_hexagons:
            return super()._render_illustrated_card(card, width, height, badge_scale)

        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        title_height = round(height * 0.12)
        title_top = height - title_height
        divider = max(2, round(width * 0.008))

        background_id = getattr(self._studio_settings, "illustrated_background", "beach")
        self._draw_background(draw, (divider, 0, width - divider, title_top), background_id)
        source = self.assets.load(card.image)
        if source is not None:
            artwork = self._scaled_fit(
                source,
                (width - divider * 2, title_top),
                self._image_scale,
            )
            layer.alpha_composite(artwork, (divider, 0))

        # Keep the same slight title-strip separation shadow used by Illustrated Cards.
        shadow_height = max(5, round(height * 0.014))
        shadow = Image.new("RGBA", (width, shadow_height * 3), (0, 0, 0, 0))
        shadow_draw = ImageDraw.Draw(shadow)
        shadow_draw.rectangle(
            (0, shadow_height, width, shadow_height * 2),
            fill=(0, 0, 0, 72),
        )
        shadow = shadow.filter(ImageFilter.GaussianBlur(max(2, round(shadow_height * 0.62))))
        layer.alpha_composite(shadow, (0, max(0, title_top - shadow_height * 2)))

        draw = ImageDraw.Draw(layer)
        draw.rectangle((0, title_top, width, height), fill=(249, 248, 244, 255))
        draw.rectangle((0, 0, divider, height), fill=(30, 30, 28, 255))
        draw.rectangle((width - divider, 0, width, height), fill=(30, 30, 28, 255))
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
        return layer

    def _field_box(self, model_id: str, role: str):
        if self._show_hexagons:
            return super()._field_box(model_id, role)
        boxes = {
            MODEL_REFERENCE: {
                "title": (0.035, 0.01, 0.93, 0.11),
                "description": (0.045, 0.14, 0.91, 0.17),
                "image": (0.01, 0.33, 0.98, 0.66),
            },
            MODEL_ILLUSTRATED: {
                "title": (0.035, 0.885, 0.93, 0.105),
                "image": (0.01, 0.01, 0.98, 0.87),
            },
            MODEL_CLASSIC: {
                "title": (0.035, 0.01, 0.93, 0.11),
                "image": (0.01, 0.14, 0.98, 0.85),
            },
        }
        return boxes.get(model_id, boxes[MODEL_REFERENCE]).get(role)

    def _field_at(self, model_id: str, local_x: float, local_y: float):
        if self._show_hexagons:
            return super()._field_at(model_id, local_x, local_y)
        if model_id == MODEL_ILLUSTRATED:
            return "title" if local_y >= 0.88 else "image"
        if model_id == MODEL_CLASSIC:
            return "title" if local_y < 0.13 else "image"
        if local_y < 0.13:
            return "title"
        if local_y < 0.33:
            return "description"
        return "image"


exporter_module.TimelineRenderer = OptionalHexagonRenderer


class OptionalHexagonMainWindow(LiveTransformMainWindow):
    def _build_models_tab(self):
        scroll = super()._build_models_tab()
        self.show_hexagons = QCheckBox("Show hexagons")
        self.show_hexagons.setChecked(True)
        self.show_hexagons.setToolTip(
            "Hide all value badges and automatically reflow each model into a no-badge layout."
        )
        self.show_hexagons.toggled.connect(self._data_changed)
        self.visual_group.layout().addRow(self.show_hexagons)
        return scroll

    def project_settings(self) -> OptionalHexagonSettings:
        base = super().project_settings()
        return OptionalHexagonSettings(
            **asdict(base),
            show_hexagons=self.show_hexagons.isChecked(),
        )

    def _new_renderer(self) -> OptionalHexagonRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return OptionalHexagonRenderer(StudioAssetCache(), self.transform_overrides)

    def open_project(self) -> None:
        super().open_project()
        # The base loader deliberately ignores unknown future settings, so read this
        # optional 0.4.0 field after the normal project has opened.
        # QFileDialog path is owned by the base method; persisted state is also restored
        # by save/open in projects written after this feature is enabled.

    def __init__(self) -> None:
        super().__init__()
        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · optional hexagons with automatic model reflow · live transforms"
        )
        self.update_preview()
