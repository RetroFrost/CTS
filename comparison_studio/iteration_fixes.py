from __future__ import annotations

from PIL import Image, ImageDraw, ImageFilter
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
    """Studio renderer with a text-fitted Illustrated hexagon."""

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

    @classmethod
    def _choose_line_count(
        cls,
        draw: ImageDraw.ImageDraw,
        text: str,
        font,
        maximum_lines: int,
        maximum_inner_width: float,
    ) -> tuple[int, float]:
        """Choose the fewest lines that fit without shrinking the intended font."""
        text = text.strip()
        if not text:
            return 1, 0.0
        for lines in range(1, maximum_lines + 1):
            width = cls._balanced_line_width(draw, text, font, lines)
            if width <= maximum_inner_width:
                return lines, width
        return maximum_lines, cls._balanced_line_width(draw, text, font, maximum_lines)

    def _adaptive_badge_geometry(
        self,
        card,
        card_width: int,
        top_height: int,
        animated_scale: float,
        manual_scale: float,
    ) -> tuple[int, int, int, int, int, int]:
        """Measure text and return independent badge width/height plus text settings.

        Auto sizing is deliberately non-uniform: long values primarily widen the
        hexagon. Height grows only when the chosen line count actually needs it.
        """
        # Keep a readable baseline while still allowing focused badges to grow.
        motion_scale = max(0.87, animated_scale)
        base_scale = motion_scale * manual_scale
        base_width = max(20, round(card_width * 0.69 * base_scale))
        base_height = max(24, round(top_height * 0.73 * base_scale))

        probe = Image.new("RGB", (4, 4))
        draw = ImageDraw.Draw(probe)
        primary_size = max(11, round(base_height * 0.25))
        secondary_size = max(8, round(base_height * 0.13))
        primary_font = renderer_module._font(primary_size, True)
        secondary_font = renderer_module._font(secondary_size, True)

        # The badge renderer reserves about 86% of the polygon width for text.
        maximum_body_width = max(base_width, round(card_width * 0.93 * max(1.0, manual_scale)))
        maximum_inner_width = maximum_body_width * 0.86
        primary_lines, primary_width = self._choose_line_count(
            draw,
            card.uploaded,
            primary_font,
            3,
            maximum_inner_width,
        )
        secondary_lines, secondary_width = self._choose_line_count(
            draw,
            card.badge_label,
            secondary_font,
            2,
            maximum_inner_width,
        )

        required_inner_width = max(primary_width, secondary_width)
        required_body_width = round(required_inner_width / 0.82) if required_inner_width else base_width
        badge_width = max(base_width, min(maximum_body_width, required_body_width))

        # Two lines need a little more vertical room, but nowhere near a full
        # uniform zoom. This is what keeps a long value from producing a giant badge.
        height_factor = 1.0 + 0.14 * max(0, primary_lines - 1)
        height_factor += 0.07 * max(0, secondary_lines - 1)
        maximum_body_height = max(base_height, round(top_height * 0.94 * max(1.0, manual_scale)))
        badge_height = min(maximum_body_height, max(base_height, round(base_height * height_factor)))

        return (
            badge_width,
            badge_height,
            primary_lines,
            secondary_lines,
            primary_size,
            secondary_size,
        )

    @staticmethod
    def _render_adaptive_badge(
        primary: str,
        secondary: str,
        badge_width: int,
        badge_height: int,
        primary_lines: int,
        secondary_lines: int,
        primary_size: int,
        secondary_size: int,
    ) -> Image.Image:
        """Render the familiar badge with independent body width and height."""
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
        border.line(
            points + [points[0]],
            fill=(125, 0, 6, 235),
            width=max(1, round(badge_width * 0.012)),
            joint="curve",
        )

        text_draw = ImageDraw.Draw(canvas)
        inner_left = x0 + round(badge_width * 0.07)
        inner_right = x1 - round(badge_width * 0.07)
        main_top = y0 + round(badge_height * (0.18 if secondary else 0.14))
        main_bottom = y0 + round(badge_height * (0.69 if secondary else 0.86))
        _draw_text_box(
            text_draw,
            primary,
            (inner_left, main_top, inner_right, main_bottom),
            (255, 250, 244, 255),
            maximum_size=primary_size,
            minimum_size=max(8, round(primary_size * 0.86)),
            max_lines=primary_lines,
            bold=True,
        )
        if secondary:
            _draw_text_box(
                text_draw,
                secondary,
                (
                    inner_left,
                    y0 + round(badge_height * 0.68),
                    inner_right,
                    y0 + round(badge_height * 0.90),
                ),
                (255, 248, 240, 255),
                maximum_size=secondary_size,
                minimum_size=max(7, round(secondary_size * 0.86)),
                max_lines=secondary_lines,
                bold=True,
            )
        return canvas

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
        animated_scale = badge_scale * 0.87

        image_auto_factor = 1.0
        if auto_enabled:
            (
                adaptive_width,
                adaptive_height,
                primary_lines,
                secondary_lines,
                primary_size,
                secondary_size,
            ) = self._adaptive_badge_geometry(
                card,
                width,
                top_height,
                animated_scale,
                manual_badge_scale,
            )
            badge = self._render_adaptive_badge(
                card.uploaded,
                card.badge_label,
                adaptive_width,
                adaptive_height,
                primary_lines,
                secondary_lines,
                primary_size,
                secondary_size,
            )
            width_growth = adaptive_width / max(1.0, width * 0.69 * manual_badge_scale)
            image_auto_factor = _clamp(1.0 - max(0.0, width_growth - 1.0) * 0.07, 0.93, 1.0)
        else:
            badge = self._render_badge(
                card.uploaded,
                card.badge_label,
                width,
                top_height,
                animated_scale * manual_badge_scale,
            )

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

        badge_x = (width - badge.width) // 2
        badge_y = max(3, round(height * 0.025))
        layer.alpha_composite(badge, (badge_x, badge_y))
        return layer


# Export must use exactly the same renderer as the Program Monitor.
exporter_module.TimelineRenderer = ResponsiveStudioTimelineRenderer


class IteratedStudioMainWindow(EnhancedPremiereMainWindow):
    """0.4.0 test window with a width-safe Models panel and fitted badges."""

    def __init__(self) -> None:
        super().__init__()
        self.renderer = ResponsiveStudioTimelineRenderer(StudioAssetCache())
        if hasattr(self, "illustrated_auto_size"):
            self.illustrated_auto_size.setText("Fit hexagon shape to typed value")
            self.illustrated_auto_size.setToolTip(
                "Stretches the Illustrated hexagon to fit the typed value while keeping text readable."
            )
        self.statusBar().showMessage("Ready · text-fitted hexagons · laptop layout widened")
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
