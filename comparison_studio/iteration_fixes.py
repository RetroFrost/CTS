from __future__ import annotations

from PIL import Image, ImageDraw
from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QComboBox,
    QFormLayout,
    QFrame,
    QLabel,
    QScrollArea,
    QSizePolicy,
    QWidget,
)

from . import exporter as exporter_module
from . import renderer as renderer_module
from .data import MODEL_REFERENCE
from .studio_ui import (
    EnhancedPremiereMainWindow,
    StudioAssetCache,
    StudioTimelineRenderer,
    _clamp,
    _draw_text_box,
)


class ResponsiveStudioTimelineRenderer(StudioTimelineRenderer):
    """Studio renderer with text-measured Illustrated badge auto-sizing."""

    @staticmethod
    def _balanced_line_width(draw: ImageDraw.ImageDraw, text: str, font, lines: int) -> float:
        text = text.strip()
        if not text:
            return 0.0
        words = text.split()
        if len(words) <= 1:
            width = draw.textbbox((0, 0), text, font=font)[2]
            return width / max(1, lines)

        total = sum(draw.textbbox((0, 0), word, font=font)[2] for word in words)
        spaces = max(0, len(words) - 1) * draw.textbbox((0, 0), " ", font=font)[2]
        widest_word = max(draw.textbbox((0, 0), word, font=font)[2] for word in words)
        return max(widest_word, (total + spaces) / max(1, lines))

    def _required_illustrated_badge_scale(self, card, width: int, top_height: int) -> float:
        """Return a minimum badge scale that keeps typed values visibly readable.

        Unlike the first implementation, this is a minimum final scale. It is not
        multiplied by the focus/bounce scale, so a long value cannot become tiny merely
        because its card is away from the current animation focus.
        """
        primary = card.uploaded.strip()
        secondary = card.badge_label.strip()
        if not primary and not secondary:
            return 0.0

        probe = Image.new("RGB", (4, 4))
        draw = ImageDraw.Draw(probe)
        primary_font = renderer_module._font(max(10, round(top_height * 0.25)), True)
        secondary_font = renderer_module._font(max(8, round(top_height * 0.13)), True)

        primary_lines = 1 if len(primary) <= 8 else 2 if len(primary) <= 24 else 3
        secondary_lines = 1 if len(secondary) <= 15 else 2
        primary_width = self._balanced_line_width(draw, primary, primary_font, primary_lines)
        secondary_width = self._balanced_line_width(draw, secondary, secondary_font, secondary_lines)

        # _render_badge reserves roughly 86% of the polygon width for text.
        base_inner_width = max(1.0, width * 0.69 * 0.86)
        measured = max(primary_width, secondary_width) / base_inner_width

        # Character count supplements font measurement for very long wrapped phrases.
        length_floor = 0.94 + max(0, len(primary) - 5) * 0.018
        if secondary:
            length_floor += max(0, len(secondary) - 10) * 0.007
        return _clamp(max(0.96, measured * 1.06, length_floor), 0.96, 1.48)

    def _render_illustrated_card(self, card, width: int, height: int, badge_scale: float) -> Image.Image:
        layer = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        draw = ImageDraw.Draw(layer)
        title_height = round(height * 0.12)
        title_top = height - title_height
        divider = max(2, round(width * 0.008))

        background_id = getattr(self._studio_settings, "illustrated_background", "beach")
        self._draw_background(draw, (divider, 0, width - divider, title_top), background_id)

        manual_image_scale = self._image_scale
        manual_badge_scale = _clamp(
            float(getattr(self._studio_settings, "illustrated_badge_scale", 1.0)),
            0.45,
            2.0,
        )
        auto_enabled = bool(getattr(self._studio_settings, "illustrated_auto_size", False))
        top_height = round(height * 0.37)
        required_scale = (
            self._required_illustrated_badge_scale(card, width, top_height)
            if auto_enabled
            else 0.0
        )

        animated_scale = badge_scale * 0.87
        final_badge_scale = max(animated_scale, required_scale) * manual_badge_scale

        # Give a large auto-sized badge a little more breathing room without making
        # artwork disappear. Manual image scale remains fully respected.
        image_auto_factor = 1.0
        if auto_enabled and required_scale > 1.0:
            image_auto_factor = _clamp(1.0 - (required_scale - 1.0) * 0.18, 0.91, 1.0)

        source = self.assets.load(card.image)
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

        badge = self._render_badge(
            card.uploaded,
            card.badge_label,
            width,
            top_height,
            final_badge_scale,
            primary_max_lines=3 if auto_enabled else 2,
            secondary_max_lines=2,
            minimum_text_scale=0.115 if auto_enabled else 0.10,
        )
        badge_x = (width - badge.width) // 2
        badge_y = max(3, round(height * 0.025))
        layer.alpha_composite(badge, (badge_x, badge_y))
        return layer


# Export must use exactly the same renderer as the Program Monitor.
exporter_module.TimelineRenderer = ResponsiveStudioTimelineRenderer


class IteratedStudioMainWindow(EnhancedPremiereMainWindow):
    """0.4.0 test window with a width-safe Models panel and fixed auto-sizing."""

    def __init__(self) -> None:
        super().__init__()
        self.renderer = ResponsiveStudioTimelineRenderer(StudioAssetCache())
        self.statusBar().showMessage("Ready · badge auto-size fixed · laptop layout widened")
        self.update_preview()

    def _build_models_tab(self) -> QWidget:
        scroll = super()._build_models_tab()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        scroll.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)

        page = scroll.widget()
        if page is not None:
            page.setMinimumWidth(0)
            page.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Preferred)
            layout = page.layout()
            if layout is not None:
                layout.setContentsMargins(9, 8, 9, 8)
                layout.setSpacing(7)

            # Let form fields shrink or wrap instead of making the hidden horizontal
            # scrollbar move the entire page and cut off the beginnings of labels.
            for group in page.findChildren(QFrame):
                form = group.layout()
                if isinstance(form, QFormLayout):
                    form.setFieldGrowthPolicy(QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow)
                    form.setRowWrapPolicy(QFormLayout.RowWrapPolicy.WrapLongRows)
                    form.setHorizontalSpacing(8)
                    form.setVerticalSpacing(6)
                    for row in range(form.rowCount()):
                        label = form.itemAt(row, QFormLayout.ItemRole.LabelRole)
                        if label is not None and isinstance(label.widget(), QLabel):
                            label.widget().setWordWrap(True)
                            label.widget().setMinimumWidth(0)

            for combo in page.findChildren(QComboBox):
                combo.setMinimumWidth(0)
                combo.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)

        bar = scroll.horizontalScrollBar()
        bar.setValue(0)
        bar.rangeChanged.connect(lambda _minimum, _maximum, target=bar: target.setValue(0))
        return scroll

    def _build_editor_panel(self) -> QWidget:
        panel = super()._build_editor_panel()
        panel.setMinimumWidth(430)
        return panel

    def _apply_responsive_layout(self) -> None:
        super()._apply_responsive_layout()
        if not hasattr(self, "content_splitter"):
            return
        compact = self.width() < 1450 or self.height() < 850
        project_width = 450 if compact else 485
        remaining = max(560, self.content_splitter.width() - project_width - 5)
        self.content_splitter.setSizes([project_width, remaining])
        if hasattr(self, "tabs") and self.tabs.currentWidget() is not None:
            current = self.tabs.currentWidget()
            for scroll in current.findChildren(QScrollArea):
                scroll.horizontalScrollBar().setValue(0)

    def _data_changed(self) -> None:
        super()._data_changed()
        if getattr(self, "_ui_ready", False):
            self.renderer = ResponsiveStudioTimelineRenderer(StudioAssetCache())
            if hasattr(self, "preview_debounce"):
                self.preview_debounce.start()
