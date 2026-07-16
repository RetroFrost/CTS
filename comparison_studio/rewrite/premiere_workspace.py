from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import Qt, QRectF, Signal
from PySide6.QtGui import QColor, QFont, QPainter, QPen
from PySide6.QtWidgets import (
    QCheckBox,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QSlider,
    QSplitter,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from . import __version__
from .model import MODEL_LABELS, Project
from .timing import Timeline
from .window import MainWindow, PreviewCanvas, format_time


PREMIERE_STYLE = """
QWidget {
    background: #191919;
    color: #d6d6d6;
    font-family: "Inter", "Noto Sans", sans-serif;
    font-size: 12px;
}
QMainWindow, QMenuBar, QStatusBar { background: #181818; }
QMenuBar { border-bottom: 1px solid #090909; }
QMenuBar::item:selected { background: #333333; }
QFrame#applicationBar {
    background: #202020;
    border-bottom: 1px solid #080808;
}
QFrame#workspaceBar {
    background: #151515;
    border-bottom: 1px solid #343434;
}
QLabel#appIcon {
    background: #00005b;
    color: #9999ff;
    border: 1px solid #4d4dcc;
    padding: 4px 7px;
    font-weight: 900;
    font-size: 13px;
}
QLabel#projectName { color: #efefef; font-weight: 700; }
QLabel#panelTitle {
    color: #d8d8d8;
    background: #232323;
    border-bottom: 1px solid #090909;
    padding: 5px 8px;
    font-weight: 700;
}
QLabel#muted { color: #969696; }
QFrame#panel {
    background: #202020;
    border: 1px solid #080808;
}
QFrame#monitorWell {
    background: #080808;
    border: 1px solid #050505;
}
QFrame#transportBar, QFrame#sequenceHeader {
    background: #242424;
    border-top: 1px solid #353535;
}
QPushButton {
    background: #2d2d2d;
    color: #d7d7d7;
    border: 1px solid #444444;
    border-radius: 2px;
    padding: 5px 9px;
}
QPushButton:hover { background: #3a3a3a; border-color: #606060; }
QPushButton:pressed { background: #252525; }
QPushButton#workspace {
    background: transparent;
    border: 0;
    border-bottom: 2px solid transparent;
    padding: 5px 9px 4px 9px;
    color: #a8a8a8;
}
QPushButton#workspace:hover { color: #ffffff; }
QPushButton#workspaceActive {
    background: transparent;
    border: 0;
    border-bottom: 2px solid #67a8e4;
    padding: 5px 9px 4px 9px;
    color: #ffffff;
    font-weight: 700;
}
QPushButton#transport {
    background: transparent;
    border: 0;
    min-width: 26px;
    padding: 4px;
    font-size: 14px;
}
QPushButton#transport:hover { background: #393939; }
QPushButton#export {
    background: #2d66a0;
    border-color: #4c84bc;
    color: white;
    font-weight: 700;
}
QPushButton#export:hover { background: #3777b7; }
QLineEdit, QComboBox, QSpinBox, QTableWidget {
    background: #171717;
    border: 1px solid #414141;
    border-radius: 0;
    color: #dedede;
    selection-background-color: #315f89;
}
QTableWidget { gridline-color: #333333; alternate-background-color: #1d1d1d; }
QHeaderView::section {
    background: #2a2a2a;
    color: #cfcfcf;
    border: 0;
    border-right: 1px solid #404040;
    border-bottom: 1px solid #111111;
    padding: 5px;
}
QTabWidget::pane { border: 1px solid #080808; background: #202020; }
QTabBar::tab {
    background: #242424;
    color: #a8a8a8;
    border-right: 1px solid #111111;
    border-bottom: 1px solid #111111;
    padding: 6px 11px;
}
QTabBar::tab:selected {
    background: #202020;
    color: #f2f2f2;
    border-top: 2px solid #67a8e4;
}
QSlider::groove:horizontal { height: 4px; background: #3d3d3d; }
QSlider::sub-page:horizontal { background: #6b9dca; }
QSlider::handle:horizontal {
    background: #d0d0d0;
    width: 10px;
    margin: -4px 0;
    border-radius: 5px;
}
QProgressBar {
    background: #181818;
    border: 1px solid #444444;
    border-radius: 0;
    text-align: center;
}
QProgressBar::chunk { background: #4f86b8; }
QSplitter::handle { background: #080808; }
QSplitter::handle:horizontal { width: 4px; }
QSplitter::handle:vertical { height: 4px; }
QStatusBar { color: #9d9d9d; border-top: 1px solid #080808; }
"""


@dataclass(slots=True)
class TimelineGeometry:
    ruler_height: int = 24
    track_height: int = 42
    label_width: int = 74


class SequenceTimelineWidget(QWidget):
    time_requested = Signal(float)
    card_requested = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setMinimumHeight(150)
        self.setMouseTracking(True)
        self.geometry_info = TimelineGeometry()
        self.project = Project()
        self.current_time = 0.0
        self.selected_card = 0

    def set_sequence(self, project: Project, current_time: float, selected_card: int) -> None:
        self.project = project
        self.current_time = current_time
        self.selected_card = max(0, selected_card)
        self.update()

    def _timeline(self) -> Timeline:
        return Timeline(self.project, len(self.project.content_cards()))

    def _content_rect(self) -> QRectF:
        g = self.geometry_info
        return QRectF(g.label_width, g.ruler_height, max(1, self.width() - g.label_width), g.track_height * 2)

    def paintEvent(self, _event) -> None:  # noqa: N802
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing, False)
        painter.fillRect(self.rect(), QColor("#191919"))
        g = self.geometry_info
        timeline = self._timeline()
        duration = max(0.001, timeline.output_duration)
        content = self._content_rect()

        painter.fillRect(0, 0, self.width(), g.ruler_height, QColor("#202020"))
        painter.fillRect(0, g.ruler_height, g.label_width, g.track_height * 2, QColor("#252525"))
        painter.fillRect(content, QColor("#171717"))
        painter.setPen(QPen(QColor("#3b3b3b"), 1))
        painter.drawLine(g.label_width, 0, g.label_width, g.ruler_height + g.track_height * 2)
        painter.drawLine(0, g.ruler_height + g.track_height, self.width(), g.ruler_height + g.track_height)

        painter.setFont(QFont("Inter", 8))
        painter.setPen(QColor("#9f9f9f"))
        painter.drawText(8, g.ruler_height + 25, "V1")
        painter.drawText(8, g.ruler_height + g.track_height + 25, "A1")

        major = 5.0 if duration > 40 else 2.0
        tick = 0.0
        while tick <= duration + 0.0001:
            x = content.left() + (tick / duration) * content.width()
            painter.setPen(QPen(QColor("#696969"), 1))
            painter.drawLine(int(x), 0, int(x), 7)
            painter.drawText(int(x + 3), 14, format_time(tick))
            tick += major

        cards = self.project.content_cards()
        if cards:
            clip_width = content.width() / len(cards)
            for index, card in enumerate(cards):
                left = content.left() + index * clip_width
                rect = QRectF(left + 1, g.ruler_height + 4, max(2, clip_width - 2), g.track_height - 8)
                fill = QColor("#3c77a5") if index != self.selected_card else QColor("#5a9bd0")
                painter.fillRect(rect, fill)
                painter.setPen(QColor("#0e2f48"))
                painter.drawRect(rect)
                painter.setPen(QColor("#f0f0f0"))
                label = card.title.strip() or f"Card {index + 1}"
                painter.drawText(rect.adjusted(5, 0, -3, 0), Qt.AlignmentFlag.AlignVCenter, label)

        if self.project.audio.path:
            audio_rect = QRectF(
                content.left() + 1,
                g.ruler_height + g.track_height + 4,
                content.width() - 2,
                g.track_height - 8,
            )
            painter.fillRect(audio_rect, QColor("#3f795b"))
            painter.setPen(QColor("#9ad4ad"))
            painter.drawText(audio_rect.adjusted(6, 0, -4, 0), Qt.AlignmentFlag.AlignVCenter, "Soundtrack")

        playhead_x = content.left() + (min(self.current_time, duration) / duration) * content.width()
        painter.setPen(QPen(QColor("#e04c4c"), 2))
        painter.drawLine(int(playhead_x), 0, int(playhead_x), g.ruler_height + g.track_height * 2)
        painter.setBrush(QColor("#e04c4c"))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawPolygon(
            [
                self._point(playhead_x - 5, 0),
                self._point(playhead_x + 5, 0),
                self._point(playhead_x, 7),
            ]
        )

    @staticmethod
    def _point(x: float, y: float):
        from PySide6.QtCore import QPointF

        return QPointF(x, y)

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.MouseButton.LeftButton:
            return
        g = self.geometry_info
        content = self._content_rect()
        point = event.position()
        if point.x() < content.left() or point.x() > content.right():
            return
        timeline = self._timeline()
        duration = max(0.001, timeline.output_duration)
        requested = ((point.x() - content.left()) / content.width()) * duration
        if g.ruler_height <= point.y() < g.ruler_height + g.track_height:
            cards = self.project.content_cards()
            if cards:
                index = min(len(cards) - 1, int(((point.x() - content.left()) / content.width()) * len(cards)))
                self.card_requested.emit(index)
        self.time_requested.emit(requested)


class PremiereWorkspaceWindow(MainWindow):
    """Clean rewrite engine inside a Premiere-style four-zone editing workspace."""

    def _build_ui(self) -> None:
        root = QWidget()
        outer = QVBoxLayout(root)
        outer.setContentsMargins(0, 0, 0, 0)
        outer.setSpacing(0)
        outer.addWidget(self._build_application_bar())
        outer.addWidget(self._build_workspace_bar())

        vertical = QSplitter(Qt.Orientation.Vertical)
        vertical.setChildrenCollapsible(False)
        upper = QSplitter(Qt.Orientation.Horizontal)
        upper.setChildrenCollapsible(False)
        upper.addWidget(self._build_project_panel())
        upper.addWidget(self._build_program_panel())
        upper.addWidget(self._build_inspector_panel())
        upper.setSizes([345, 720, 310])
        upper.setStretchFactor(0, 0)
        upper.setStretchFactor(1, 1)
        upper.setStretchFactor(2, 0)
        vertical.addWidget(upper)
        vertical.addWidget(self._build_sequence_panel())
        vertical.setSizes([520, 225])
        vertical.setStretchFactor(0, 1)
        vertical.setStretchFactor(1, 0)
        outer.addWidget(vertical, 1)
        self.setCentralWidget(root)

    def _build_application_bar(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("applicationBar")
        bar.setFixedHeight(42)
        row = QHBoxLayout(bar)
        row.setContentsMargins(8, 5, 8, 5)
        row.setSpacing(8)
        icon = QLabel("CTS")
        icon.setObjectName("appIcon")
        row.addWidget(icon)
        name = QLabel("Comparison Timeline Studio")
        name.setObjectName("projectName")
        row.addWidget(name)
        version = QLabel(f"{__version__} · desktop rewrite")
        version.setObjectName("muted")
        row.addWidget(version)
        row.addStretch()
        open_button = QPushButton("Open Project")
        save_button = QPushButton("Save")
        export_button = QPushButton("Export")
        export_button.setObjectName("export")
        open_button.clicked.connect(self.open_project)
        save_button.clicked.connect(self.save_project)
        export_button.clicked.connect(self.start_export)
        row.addWidget(open_button)
        row.addWidget(save_button)
        row.addWidget(export_button)
        return bar

    def _build_workspace_bar(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("workspaceBar")
        bar.setFixedHeight(32)
        row = QHBoxLayout(bar)
        row.setContentsMargins(6, 0, 6, 0)
        row.setSpacing(0)
        for label in ("Learning", "Assembly", "Editing", "Color", "Effects", "Audio", "Graphics", "Captions"):
            button = QPushButton(label)
            button.setObjectName("workspaceActive" if label == "Editing" else "workspace")
            button.setEnabled(label == "Editing")
            row.addWidget(button)
        row.addStretch()
        return bar

    def _wrap_panel(self, title: str, content: QWidget) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        heading = QLabel(title)
        heading.setObjectName("panelTitle")
        layout.addWidget(heading)
        layout.addWidget(content, 1)
        return panel

    def _build_project_panel(self) -> QWidget:
        self.project_tabs = QTabWidget()
        self.project_tabs.addTab(self._build_data_tab(), "Project: CTS")
        media = QWidget()
        media_layout = QVBoxLayout(media)
        media_layout.addWidget(QLabel("Imported artwork and soundtrack files appear in the Project table."))
        media_layout.addStretch()
        self.project_tabs.addTab(media, "Media Browser")
        self.project_tabs.setMinimumWidth(300)
        return self._wrap_panel("PROJECT", self.project_tabs)

    def _build_program_panel(self) -> QWidget:
        tabs = QTabWidget()
        source = QWidget()
        source_layout = QVBoxLayout(source)
        source_label = QLabel("No source clip selected")
        source_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        source_label.setObjectName("muted")
        source_layout.addWidget(source_label)

        program = QWidget()
        program_layout = QVBoxLayout(program)
        program_layout.setContentsMargins(0, 0, 0, 0)
        program_layout.setSpacing(0)
        well = QFrame()
        well.setObjectName("monitorWell")
        well_layout = QVBoxLayout(well)
        well_layout.setContentsMargins(8, 8, 8, 8)
        self.preview = PreviewCanvas()
        self.preview.card_clicked.connect(self._preview_card_clicked)
        well_layout.addWidget(self.preview, 1)
        program_layout.addWidget(well, 1)

        scrub = QSlider(Qt.Orientation.Horizontal)
        scrub.setRange(0, 10000)
        scrub.sliderMoved.connect(self._seek_slider)
        self.timeline_slider = scrub
        program_layout.addWidget(scrub)

        transport = QFrame()
        transport.setObjectName("transportBar")
        transport_row = QHBoxLayout(transport)
        transport_row.setContentsMargins(8, 3, 8, 3)
        transport_row.setSpacing(3)
        back = QPushButton("◀│")
        back.setObjectName("transport")
        back.clicked.connect(lambda: self._step_time(-1.0 / max(1, self.project.fps)))
        self.play_button = QPushButton("▶")
        self.play_button.setObjectName("transport")
        self.play_button.clicked.connect(self.toggle_playback)
        forward = QPushButton("│▶")
        forward.setObjectName("transport")
        forward.clicked.connect(lambda: self._step_time(1.0 / max(1, self.project.fps)))
        transport_row.addStretch()
        transport_row.addWidget(back)
        transport_row.addWidget(self.play_button)
        transport_row.addWidget(forward)
        transport_row.addStretch()
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setMinimumWidth(112)
        self.time_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        transport_row.addWidget(self.time_label)
        program_layout.addWidget(transport)

        tabs.addTab(source, "Source: (no clip)")
        tabs.addTab(program, "Program: Sequence 01")
        tabs.setCurrentIndex(1)
        return self._wrap_panel("PROGRAM MONITOR", tabs)

    def _build_inspector_panel(self) -> QWidget:
        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_style_tab(), "Effect Controls")

        graphics = QWidget()
        graphics_layout = QVBoxLayout(graphics)
        graphics_layout.setContentsMargins(8, 10, 8, 8)
        heading = QLabel("Essential Graphics")
        heading.setStyleSheet("font-weight:700; font-size:13px;")
        graphics_layout.addWidget(heading)
        self.graphics_summary = QLabel("Select a card in the Project panel or Program Monitor.")
        self.graphics_summary.setWordWrap(True)
        self.graphics_summary.setObjectName("muted")
        graphics_layout.addWidget(self.graphics_summary)
        graphics_layout.addStretch()
        self.tabs.addTab(graphics, "Essential Graphics")
        self.tabs.addTab(self._build_audio_tab(), "Audio")
        self.tabs.addTab(self._build_export_tab(), "Export")
        self.tabs.setMinimumWidth(280)
        return self._wrap_panel("EFFECT CONTROLS", self.tabs)

    def _build_sequence_panel(self) -> QWidget:
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        header = QFrame()
        header.setObjectName("sequenceHeader")
        row = QHBoxLayout(header)
        row.setContentsMargins(8, 4, 8, 4)
        row.addWidget(QLabel("Sequence 01"))
        row.addStretch()
        self.auto_length = QCheckBox("Auto")
        self.auto_length.setChecked(True)
        self.auto_length.toggled.connect(self._duration_mode_changed)
        self.custom_length = QLineEdit()
        self.custom_length.setPlaceholderText("MM:SS")
        self.custom_length.setMaximumWidth(88)
        self.custom_length.setEnabled(False)
        self.custom_length.editingFinished.connect(self._custom_length_changed)
        self.duration_info = QLabel()
        self.duration_info.setObjectName("muted")
        row.addWidget(QLabel("Duration"))
        row.addWidget(self.auto_length)
        row.addWidget(self.custom_length)
        row.addWidget(self.duration_info)
        layout.addWidget(header)

        self.sequence_view = SequenceTimelineWidget()
        self.sequence_view.time_requested.connect(self._seek_time)
        self.sequence_view.card_requested.connect(self._sequence_card_clicked)
        layout.addWidget(self.sequence_view, 1)
        return self._wrap_panel("TIMELINE: SEQUENCE 01", content)

    def _step_time(self, delta: float) -> None:
        duration = Timeline(self.project, len(self.project.content_cards())).output_duration
        self.current_time = max(0.0, min(duration, self.current_time + delta))
        self._refresh_all()

    def _seek_time(self, seconds: float) -> None:
        self.current_time = max(0.0, seconds)
        self._refresh_all()

    def _sequence_card_clicked(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self.table.selectRow(index)
            self.project_tabs.setCurrentIndex(0)

    def _preview_card_clicked(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self.table.selectRow(index)
            self.tabs.setCurrentIndex(1)

    def _selection_changed(self) -> None:
        row = self.table.currentRow()
        if hasattr(self, "graphics_summary"):
            if 0 <= row < len(self.project.cards):
                card = self.project.cards[row]
                self.graphics_summary.setText(
                    f"Card {row + 1}\n\n"
                    f"Title: {card.title or '—'}\n"
                    f"Badge: {card.value or '—'} {card.label or ''}\n"
                    f"Artwork: {card.image or '—'}"
                )
            else:
                self.graphics_summary.setText("Select a card in the Project panel or Program Monitor.")
        if hasattr(self, "sequence_view"):
            self.sequence_view.set_sequence(self.project, self.current_time, max(0, row))

    def _refresh_all(self) -> None:
        super()._refresh_all()
        if hasattr(self, "sequence_view"):
            self.sequence_view.set_sequence(self.project, self.current_time, max(0, self.table.currentRow()))

    def toggle_playback(self) -> None:
        super().toggle_playback()
        self.play_button.setText("❚❚" if self.playback_timer.isActive() else "▶")
