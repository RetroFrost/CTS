from __future__ import annotations

from PySide6.QtCore import QSignalBlocker, Qt
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QSlider,
    QSplitter,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from .image_transform import (
    ImageTransform,
    TransformPreviewCanvas,
    TransformRenderer,
    format_image_reference,
    parse_image_reference,
)
from .mobile_convenience import ConvenientPremiereWindow
from .premiere_workspace import PREMIERE_STYLE
from .transform_exporter import TransformExportWorker


PRACTICAL_STYLE = PREMIERE_STYLE + """
QFrame#workflowPanel {
    background: #202020;
    border-right: 1px solid #080808;
}
QFrame#dataEntryBar {
    background: #202020;
    border-bottom: 1px solid #090909;
}
QPushButton#insertData {
    background: #315f89;
    border: 1px solid #5c8eb9;
    color: #ffffff;
    min-height: 37px;
    padding: 5px 10px;
    font-size: 12px;
    font-weight: 850;
    letter-spacing: 0.4px;
}
QPushButton#insertData:hover {
    background: #3b709f;
    border-color: #79a9d2;
}
QFrame#cardStrip {
    background: #1b1b1b;
    border-bottom: 1px solid #090909;
}
QPushButton#quickAction {
    background: #315f89;
    border-color: #4d7ca7;
    color: #ffffff;
    font-weight: 700;
}
QPushButton#quickAction:hover { background: #3b709f; }
QPushButton#cardChip,
QPushButton#cardChipActive {
    min-height: 25px;
    padding: 3px 8px;
    border-radius: 2px;
}
QPushButton#cardChip {
    background: #272727;
    border-color: #444444;
    color: #bcbcbc;
}
QPushButton#cardChipActive {
    background: #315f89;
    border-color: #6b9dca;
    color: #ffffff;
}
QFrame#practicalTransport,
QFrame#durationBar,
QFrame#imageTransformBar {
    background: #242424;
    border-top: 1px solid #353535;
}
QPushButton#transformModeActive {
    background: #315f89;
    border-color: #6b9dca;
    color: #ffffff;
}
"""


class PracticalWorkspaceWindow(ConvenientPremiereWindow):
    """CTS-first desktop layout with direct text and artwork editing."""

    def __init__(self) -> None:
        super().__init__()
        self.renderer = TransformRenderer()
        self._refresh_all()
        self._load_transform_controls()
        self.statusBar().showMessage(
            "Ready — click text to type; drag artwork to move; wheel to zoom"
        )

    def _build_ui(self) -> None:
        root = QWidget()
        outer = QVBoxLayout(root)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        outer.addWidget(self._build_application_bar())

        body = QSplitter(Qt.Orientation.Horizontal)
        body.setChildrenCollapsible(False)
        body.setHandleWidth(4)
        body.addWidget(self._build_workflow_panel())
        body.addWidget(self._build_practical_program_panel())
        body.setSizes([430, 936])
        body.setStretchFactor(0, 0)
        body.setStretchFactor(1, 1)
        outer.addWidget(body, 1)
        self.setCentralWidget(root)

    def _build_workflow_panel(self) -> QWidget:
        content = QFrame()
        content.setObjectName("workflowPanel")
        content.setMinimumWidth(360)
        content.setMaximumWidth(560)
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        entry = QFrame()
        entry.setObjectName("dataEntryBar")
        entry_layout = QVBoxLayout(entry)
        entry_layout.setContentsMargins(7, 7, 7, 7)
        entry_layout.setSpacing(0)
        self.insert_data_button = QPushButton("＋  CLICK TO INSERT DATA")
        self.insert_data_button.setObjectName("insertData")
        self.insert_data_button.setToolTip(
            "Paste copied TSV/CSV data. With an empty clipboard, open the table for typing."
        )
        self.insert_data_button.clicked.connect(self._insert_data_clicked)
        entry_layout.addWidget(self.insert_data_button)
        layout.addWidget(entry)

        strip = QFrame()
        strip.setObjectName("cardStrip")
        strip_layout = QVBoxLayout(strip)
        strip_layout.setContentsMargins(7, 5, 7, 6)
        strip_layout.setSpacing(4)
        strip_layout.addWidget(QLabel("CARDS"))

        self.card_scroll = QScrollArea()
        self.card_scroll.setWidgetResizable(True)
        self.card_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.card_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.card_scroll.setFixedHeight(46)
        self.card_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.card_strip_widget = QWidget()
        self.card_strip_layout = QHBoxLayout(self.card_strip_widget)
        self.card_strip_layout.setContentsMargins(0, 0, 0, 0)
        self.card_strip_layout.setSpacing(4)
        self.card_strip_layout.addStretch()
        self.card_scroll.setWidget(self.card_strip_widget)
        strip_layout.addWidget(self.card_scroll)
        layout.addWidget(strip)

        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_quick_edit_tab(), "Edit")
        self.tabs.addTab(self._build_data_tab(), "Data")
        self.tabs.addTab(self._build_style_tab(), "Style")
        self.tabs.addTab(self._build_audio_tab(), "Audio")
        self.tabs.addTab(self._build_export_tab(), "Export")
        self.project_tabs = self.tabs
        layout.addWidget(self.tabs, 1)
        return self._wrap_panel("CTS CONTROLS", content)

    def _insert_data_clicked(self) -> None:
        if QApplication.clipboard().text().strip():
            self.paste_data()
            self.tabs.setCurrentIndex(0)
            self._load_quick_editor()
            self._refresh_card_strip()
            return

        self.tabs.setCurrentIndex(1)
        if self.table.rowCount() == 0:
            self.add_card()
        self.table.setCurrentCell(0, 0)
        self.table.setFocus(Qt.FocusReason.ShortcutFocusReason)
        item = self.table.item(0, 0)
        if item is not None:
            self.table.editItem(item)
        self.statusBar().showMessage("Paste a table or type directly into the Data grid", 4000)

    def _build_practical_program_panel(self) -> QWidget:
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        heading = QFrame()
        heading.setObjectName("panelTitle")
        heading_layout = QHBoxLayout(heading)
        heading_layout.setContentsMargins(8, 3, 8, 3)
        heading_layout.addWidget(QLabel("PROGRAM MONITOR"))
        heading_layout.addStretch()
        self.monitor_hint = QLabel("Click text to type · drag artwork to move · wheel to zoom")
        self.monitor_hint.setObjectName("muted")
        heading_layout.addWidget(self.monitor_hint)
        layout.addWidget(heading)

        well = QFrame()
        well.setObjectName("monitorWell")
        well_layout = QVBoxLayout(well)
        well_layout.setContentsMargins(8, 8, 8, 8)
        self.preview = TransformPreviewCanvas()
        self.preview.card_clicked.connect(self._preview_card_clicked)
        self.preview.field_edit_started.connect(self._preview_field_edit_started)
        self.preview.field_committed.connect(self._preview_field_committed)
        self.preview.image_selected.connect(self._preview_image_selected)
        self.preview.image_transform_changed.connect(self._preview_image_transform_changed)
        well_layout.addWidget(self.preview, 1)
        layout.addWidget(well, 1)

        self.timeline_slider = QSlider(Qt.Orientation.Horizontal)
        self.timeline_slider.setRange(0, 10000)
        self.timeline_slider.sliderMoved.connect(self._seek_slider)
        layout.addWidget(self.timeline_slider)

        transport = QFrame()
        transport.setObjectName("practicalTransport")
        transport_row = QHBoxLayout(transport)
        transport_row.setContentsMargins(8, 4, 8, 4)
        transport_row.setSpacing(5)
        previous = QPushButton("◀│")
        previous.setObjectName("transport")
        previous.clicked.connect(lambda: self._step_time(-1.0 / max(1, self.project.fps)))
        self.play_button = QPushButton("▶ Play")
        self.play_button.clicked.connect(self.toggle_playback)
        following = QPushButton("│▶")
        following.setObjectName("transport")
        following.clicked.connect(lambda: self._step_time(1.0 / max(1, self.project.fps)))
        transport_row.addWidget(previous)
        transport_row.addWidget(self.play_button)
        transport_row.addWidget(following)
        transport_row.addStretch()
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setMinimumWidth(112)
        self.time_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        transport_row.addWidget(self.time_label)
        layout.addWidget(transport)

        transform_bar = QFrame()
        transform_bar.setObjectName("imageTransformBar")
        transform_row = QHBoxLayout(transform_bar)
        transform_row.setContentsMargins(8, 4, 8, 4)
        transform_row.setSpacing(6)
        transform_row.addWidget(QLabel("Artwork"))
        self.image_fit_button = QPushButton("Fit")
        self.image_fill_button = QPushButton("Fill")
        self.image_reset_button = QPushButton("Reset")
        self.image_fit_button.clicked.connect(lambda: self._set_image_mode("fit"))
        self.image_fill_button.clicked.connect(lambda: self._set_image_mode("fill"))
        self.image_reset_button.clicked.connect(self._reset_image_transform)
        transform_row.addWidget(self.image_fit_button)
        transform_row.addWidget(self.image_fill_button)
        transform_row.addWidget(self.image_reset_button)
        transform_row.addWidget(QLabel("Zoom"))
        self.image_zoom_slider = QSlider(Qt.Orientation.Horizontal)
        self.image_zoom_slider.setRange(25, 400)
        self.image_zoom_slider.setValue(100)
        self.image_zoom_slider.setMinimumWidth(130)
        self.image_zoom_slider.valueChanged.connect(self._zoom_slider_changed)
        transform_row.addWidget(self.image_zoom_slider, 1)
        self.image_zoom_label = QLabel("100%")
        self.image_zoom_label.setMinimumWidth(44)
        transform_row.addWidget(self.image_zoom_label)
        layout.addWidget(transform_bar)

        duration = QFrame()
        duration.setObjectName("durationBar")
        duration_row = QHBoxLayout(duration)
        duration_row.setContentsMargins(8, 5, 8, 5)
        duration_row.setSpacing(7)
        duration_row.addWidget(QLabel("Video length"))
        self.auto_length = QCheckBox("Auto")
        self.auto_length.setChecked(True)
        self.auto_length.toggled.connect(self._duration_mode_changed)
        duration_row.addWidget(self.auto_length)
        self.custom_length = QLineEdit()
        self.custom_length.setPlaceholderText("MM:SS")
        self.custom_length.setMaximumWidth(92)
        self.custom_length.setEnabled(False)
        self.custom_length.editingFinished.connect(self._custom_length_changed)
        duration_row.addWidget(self.custom_length)
        self.duration_info = QLabel()
        self.duration_info.setObjectName("muted")
        duration_row.addWidget(self.duration_info, 1)
        layout.addWidget(duration)
        return self._wrap_panel("PREVIEW", content)

    def _selected_row(self) -> int:
        row = self.table.currentRow()
        return row if 0 <= row < self.table.rowCount() else -1

    def _load_quick_editor(self) -> None:
        super()._load_quick_editor()
        if not hasattr(self, "quick_image"):
            return
        row = self._selected_row()
        if row < 0:
            return
        item = self.table.item(row, 4)
        source, _transform = parse_image_reference(item.text() if item else "")
        with QSignalBlocker(self.quick_image):
            self.quick_image.setText(source)

    def _commit_quick_editor(self) -> None:
        if getattr(self, "_quick_loading", False) or not hasattr(self, "quick_image"):
            return
        row = self._selected_row()
        if row < 0:
            return
        item = self.table.item(row, 4)
        _old_source, transform = parse_image_reference(item.text() if item else "")
        visible_source = self.quick_image.text().strip()
        encoded = format_image_reference(visible_source, transform)
        with QSignalBlocker(self.quick_image):
            self.quick_image.setText(encoded)
        super()._commit_quick_editor()
        with QSignalBlocker(self.quick_image):
            self.quick_image.setText(visible_source)
        self._load_transform_controls()

    def _selection_changed(self) -> None:
        super()._selection_changed()
        self._load_transform_controls()

    def _load_project_into_ui(self) -> None:
        super()._load_project_into_ui()
        self._load_transform_controls()

    def _preview_card_clicked(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self.table.selectRow(index)
            self.tabs.setCurrentIndex(0)
            self._load_quick_editor()
            self._refresh_card_strip()
            self._load_transform_controls()

    def _preview_field_edit_started(self, index: int, field: str) -> None:
        if self.playback_timer.isActive():
            self.playback_timer.stop()
            self.play_button.setText("▶ Play")
        self.monitor_hint.setText(
            f"Editing {field} on card {index + 1} · Enter saves · Escape cancels"
        )

    def _preview_field_committed(self, index: int, field: str, value: str) -> None:
        columns = {"value": 0, "label": 1, "title": 2, "description": 3}
        column = columns.get(field)
        if column is None or not (0 <= index < self.table.rowCount()):
            return
        with QSignalBlocker(self.table):
            self._set_table_value(index, column, value)
        self.table.selectRow(index)
        self._sync_project_from_table()
        self.project.dirty = True
        self.renderer.assets.clear()
        self.tabs.setCurrentIndex(0)
        self._load_quick_editor()
        self._refresh_card_strip()
        self._refresh_all()
        self.monitor_hint.setText("Click text to type · drag artwork to move · wheel to zoom")
        self.statusBar().showMessage(f"Updated {field} on card {index + 1}", 2500)

    def _preview_image_selected(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self.table.selectRow(index)
            self._load_transform_controls()
            self.monitor_hint.setText(
                f"Transforming artwork on card {index + 1} · drag to move · wheel to zoom"
            )

    def _preview_image_transform_changed(self, index: int, encoded: str) -> None:
        if not (0 <= index < self.table.rowCount()):
            return
        with QSignalBlocker(self.table):
            self._set_table_value(index, 4, encoded)
        self.table.selectRow(index)
        self._sync_project_from_table()
        self.project.dirty = True
        self._load_quick_editor()
        self._load_transform_controls()
        self._refresh_all()
        self.preview.select_image(index)

    def _current_image(self) -> tuple[int, str, ImageTransform]:
        row = self._selected_row()
        if row < 0:
            return -1, "", ImageTransform()
        item = self.table.item(row, 4)
        source, transform = parse_image_reference(item.text() if item else "")
        return row, source, transform

    def _write_image_transform(self, transform: ImageTransform) -> None:
        row, source, _old = self._current_image()
        if row < 0 or not source:
            return
        encoded = format_image_reference(source, transform)
        self._preview_image_transform_changed(row, encoded)

    def _load_transform_controls(self) -> None:
        if not hasattr(self, "image_zoom_slider"):
            return
        row, source, transform = self._current_image()
        enabled = row >= 0 and bool(source)
        for widget in (
            self.image_fit_button,
            self.image_fill_button,
            self.image_reset_button,
            self.image_zoom_slider,
        ):
            widget.setEnabled(enabled)
        with QSignalBlocker(self.image_zoom_slider):
            self.image_zoom_slider.setValue(round(transform.scale * 100))
        self.image_zoom_label.setText(f"{round(transform.scale * 100)}%")
        self.image_fit_button.setObjectName(
            "transformModeActive" if transform.mode == "fit" else ""
        )
        self.image_fill_button.setObjectName(
            "transformModeActive" if transform.mode == "fill" else ""
        )
        self.image_fit_button.style().unpolish(self.image_fit_button)
        self.image_fit_button.style().polish(self.image_fit_button)
        self.image_fill_button.style().unpolish(self.image_fill_button)
        self.image_fill_button.style().polish(self.image_fill_button)

    def _zoom_slider_changed(self, value: int) -> None:
        row, source, transform = self._current_image()
        self.image_zoom_label.setText(f"{value}%")
        if row < 0 or not source:
            return
        self._write_image_transform(
            ImageTransform(value / 100.0, transform.x, transform.y, transform.mode)
        )

    def _set_image_mode(self, mode: str) -> None:
        row, source, transform = self._current_image()
        if row < 0 or not source:
            return
        self._write_image_transform(
            ImageTransform(transform.scale, transform.x, transform.y, mode)
        )
        self.preview.select_image(row)

    def _reset_image_transform(self) -> None:
        row, source, _transform = self._current_image()
        if row < 0 or not source:
            return
        self._write_image_transform(ImageTransform())
        self.preview.select_image(row)

    def _step_time(self, delta: float) -> None:
        from .timing import Timeline

        duration = Timeline(self.project, len(self.project.content_cards())).output_duration
        self.current_time = max(0.0, min(duration, self.current_time + delta))
        self._refresh_all()

    def start_export(self) -> None:
        if self._export_worker is not None:
            return
        self._sync_project_from_table()
        path, _ = QFileDialog.getSaveFileName(
            self,
            "Export MP4",
            "comparison.mp4",
            "MP4 video (*.mp4)",
        )
        if not path:
            return
        if not path.lower().endswith(".mp4"):
            path += ".mp4"
        worker = TransformExportWorker(self.project, path, self)
        self._export_worker = worker
        worker.stage_changed.connect(self.export_stage.setText)
        worker.progress_changed.connect(self._export_progress_changed)
        worker.completed.connect(self._export_completed)
        worker.failed.connect(self._export_failed)
        worker.canceled.connect(self._export_canceled)
        worker.finished.connect(worker.deleteLater)
        self.export_button.setEnabled(False)
        self.cancel_export_button.setEnabled(True)
        self.tabs.setCurrentIndex(4)
        self.export_progress.setValue(0)
        worker.start()
