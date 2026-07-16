from __future__ import annotations

import time
from pathlib import Path

from PIL.ImageQt import ImageQt
from PySide6.QtCore import QSignalBlocker, Qt, QTimer, Signal
from PySide6.QtGui import QAction, QCloseEvent, QPainter, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QComboBox,
    QFileDialog,
    QFormLayout,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSlider,
    QSpinBox,
    QSplitter,
    QTableWidget,
    QTableWidgetItem,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from . import __version__
from .exporter import ExportWorker
from .model import (
    MODEL_IDS,
    MODEL_LABELS,
    AudioSettings,
    Card,
    Project,
    ProjectError,
    import_csv,
    import_xlsx,
    load_project,
    parse_table,
    save_project,
)
from .render import Renderer
from .timing import Timeline


STYLE = """
QWidget {
    background: #17181d;
    color: #ececf1;
    font-family: "Inter", "Noto Sans", sans-serif;
    font-size: 12px;
}
QMainWindow { background: #101116; }
QFrame#topBar {
    background: #22242b;
    border-bottom: 1px solid #363944;
}
QLabel#brand {
    background: #7358e8;
    color: white;
    border-radius: 6px;
    padding: 8px 11px;
    font-weight: 900;
}
QLabel#title { font-size: 15px; font-weight: 800; }
QLabel#muted { color: #a3a6b2; }
QLabel#section { color: #c7c9d2; font-weight: 800; letter-spacing: 1px; }
QFrame#panel {
    background: #202127;
    border: 1px solid #343741;
    border-radius: 8px;
}
QFrame#monitor {
    background: #07080c;
    border: 1px solid #3b3e49;
    border-radius: 8px;
}
QPushButton {
    background: #30323b;
    color: #f0f0f4;
    border: 1px solid #4a4d59;
    border-radius: 6px;
    padding: 7px 10px;
    font-weight: 650;
}
QPushButton:hover { background: #3b3e49; }
QPushButton:pressed { background: #282a31; }
QPushButton#primary {
    background: #7057e8;
    border-color: #8b78ef;
    color: white;
}
QPushButton#primary:hover { background: #7d67ee; }
QPushButton#insert {
    background: #2e5e87;
    border-color: #4d83b4;
    min-height: 38px;
    font-weight: 850;
}
QPushButton#danger { color: #ffabb4; }
QLineEdit, QComboBox, QSpinBox, QTableWidget {
    background: #14151a;
    border: 1px solid #454852;
    border-radius: 5px;
    padding: 5px;
    selection-background-color: #5747b4;
}
QTableWidget {
    gridline-color: #353842;
    alternate-background-color: #1b1c22;
}
QHeaderView::section {
    background: #292b33;
    color: #d9dae1;
    border: 0;
    border-right: 1px solid #3d404a;
    border-bottom: 1px solid #3d404a;
    padding: 6px;
    font-weight: 750;
}
QTabWidget::pane { border: 0; }
QTabBar::tab {
    background: #1c1d23;
    color: #aeb0bb;
    padding: 8px 16px;
    border-bottom: 2px solid transparent;
}
QTabBar::tab:selected {
    color: white;
    border-bottom-color: #765de8;
}
QSlider::groove:horizontal {
    height: 5px;
    background: #343741;
    border-radius: 2px;
}
QSlider::sub-page:horizontal { background: #765de8; border-radius: 2px; }
QSlider::handle:horizontal {
    background: #d8d9df;
    width: 14px;
    margin: -5px 0;
    border-radius: 7px;
}
QProgressBar {
    background: #17181d;
    border: 1px solid #434650;
    border-radius: 5px;
    text-align: center;
    min-height: 20px;
}
QProgressBar::chunk { background: #7057e8; }
QSplitter::handle { background: #0f1014; width: 5px; }
QStatusBar { background: #202127; color: #aeb0bb; }
"""


def format_time(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    minutes, secs = divmod(total, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}" if hours else f"{minutes:02d}:{secs:02d}"


def parse_time(text: str) -> float:
    value = text.strip()
    if not value:
        raise ProjectError("Enter a video length such as 2:05.")
    if value.replace(".", "", 1).isdigit() and ":" not in value:
        seconds = float(value)
    else:
        parts = value.split(":")
        if len(parts) not in (2, 3) or any(not part.isdigit() for part in parts):
            raise ProjectError("Use seconds, MM:SS, or HH:MM:SS.")
        numbers = [int(part) for part in parts]
        if len(numbers) == 2:
            minutes, secs = numbers
            if secs >= 60:
                raise ProjectError("Seconds must be below 60.")
            seconds = minutes * 60 + secs
        else:
            hours, minutes, secs = numbers
            if minutes >= 60 or secs >= 60:
                raise ProjectError("Minutes and seconds must be below 60.")
            seconds = hours * 3600 + minutes * 60 + secs
    if seconds <= 0:
        raise ProjectError("Video length must be greater than zero.")
    return seconds


class PreviewCanvas(QWidget):
    card_clicked = Signal(int)

    def __init__(self, parent=None) -> None:
        super().__init__(parent)
        self.setMinimumSize(480, 270)
        self.setMouseTracking(True)
        self._pixmap = QPixmap()
        self._video_rect = None
        self._project: Project | None = None
        self._time = 0.0

    def set_frame(self, project: Project, output_time: float, pixmap: QPixmap) -> None:
        self._project = project
        self._time = output_time
        self._pixmap = pixmap
        self.update()

    def paintEvent(self, event) -> None:  # noqa: N802
        painter = QPainter(self)
        painter.fillRect(self.rect(), Qt.GlobalColor.black)
        if self._pixmap.isNull():
            return
        scaled = self._pixmap.scaled(
            self.size(),
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
        x = (self.width() - scaled.width()) // 2
        y = (self.height() - scaled.height()) // 2
        self._video_rect = (x, y, scaled.width(), scaled.height())
        painter.drawPixmap(x, y, scaled)

    def mousePressEvent(self, event) -> None:  # noqa: N802
        if event.button() != Qt.MouseButton.LeftButton or self._video_rect is None:
            return
        x, y, width, height = self._video_rect
        point = event.position()
        if not (x <= point.x() <= x + width and y <= point.y() <= y + height):
            return
        project = self._project
        if project is None:
            return
        local_x = (point.x() - x) / max(1, width)
        timeline = Timeline(project, len(project.content_cards()))
        click_x = local_x * width
        for placement in reversed(timeline.placements(self._time, float(width))):
            card_width = width / timeline.visible_cards
            if placement.x <= click_x < placement.x + card_width:
                self.card_clicked.emit(placement.index)
                return


class MainWindow(QMainWindow):
    def __init__(self) -> None:
        super().__init__()
        self.project = Project()
        self.renderer = Renderer()
        self.current_time = 0.0
        self._last_tick = time.monotonic()
        self._export_worker: ExportWorker | None = None
        self._table_loading = False

        self.setWindowTitle(f"CTS {__version__} — Comparison Timeline Studio")
        self.resize(1366, 768)
        self.setMinimumSize(1040, 650)
        self._build_ui()
        self._build_menu()

        self.playback_timer = QTimer(self)
        self.playback_timer.setInterval(33)
        self.playback_timer.timeout.connect(self._playback_tick)
        self._load_project_into_ui()
        self.statusBar().showMessage("Ready — clean desktop rewrite")

    def _build_menu(self) -> None:
        file_menu = self.menuBar().addMenu("File")
        actions = (
            ("New project", self.new_project, "Ctrl+N"),
            ("Open project…", self.open_project, "Ctrl+O"),
            ("Save project", self.save_project, "Ctrl+S"),
            ("Save project as…", self.save_project_as, "Ctrl+Shift+S"),
        )
        for label, slot, shortcut in actions:
            action = QAction(label, self)
            action.setShortcut(shortcut)
            action.triggered.connect(slot)
            file_menu.addAction(action)
        file_menu.addSeparator()
        quit_action = QAction("Quit", self)
        quit_action.setShortcut("Ctrl+Q")
        quit_action.triggered.connect(self.close)
        file_menu.addAction(quit_action)

    def _build_ui(self) -> None:
        root = QWidget()
        root_layout = QVBoxLayout(root)
        root_layout.setContentsMargins(0, 0, 0, 0)
        root_layout.setSpacing(0)
        root_layout.addWidget(self._build_top_bar())

        splitter = QSplitter(Qt.Orientation.Horizontal)
        splitter.setChildrenCollapsible(False)
        splitter.addWidget(self._build_left_panel())
        splitter.addWidget(self._build_monitor_panel())
        splitter.setSizes([410, 950])
        splitter.setStretchFactor(0, 0)
        splitter.setStretchFactor(1, 1)
        root_layout.addWidget(splitter, 1)
        self.setCentralWidget(root)

    def _build_top_bar(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("topBar")
        bar.setFixedHeight(58)
        layout = QHBoxLayout(bar)
        layout.setContentsMargins(12, 8, 12, 8)
        layout.setSpacing(9)
        brand = QLabel("CTS")
        brand.setObjectName("brand")
        layout.addWidget(brand)
        title = QLabel("Comparison Timeline Studio")
        title.setObjectName("title")
        layout.addWidget(title)
        version = QLabel(__version__)
        version.setObjectName("muted")
        layout.addWidget(version)
        layout.addStretch()
        open_button = QPushButton("Open")
        save_button = QPushButton("Save")
        export_button = QPushButton("Export MP4")
        export_button.setObjectName("primary")
        open_button.clicked.connect(self.open_project)
        save_button.clicked.connect(self.save_project)
        export_button.clicked.connect(self.start_export)
        layout.addWidget(open_button)
        layout.addWidget(save_button)
        layout.addWidget(export_button)
        return bar

    def _build_left_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        panel.setMinimumWidth(350)
        panel.setMaximumWidth(520)
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(7)
        section = QLabel("PROJECT")
        section.setObjectName("section")
        layout.addWidget(section)
        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_data_tab(), "Data")
        self.tabs.addTab(self._build_style_tab(), "Style")
        self.tabs.addTab(self._build_audio_tab(), "Audio")
        self.tabs.addTab(self._build_export_tab(), "Export")
        layout.addWidget(self.tabs, 1)
        return panel

    def _build_data_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(5, 8, 5, 5)
        layout.setSpacing(6)
        helper = QLabel("One row is one card. Paste TSV/CSV data or edit cells directly.")
        helper.setWordWrap(True)
        helper.setObjectName("muted")
        layout.addWidget(helper)

        paste_button = QPushButton("＋  PASTE DATA")
        paste_button.setObjectName("insert")
        paste_button.clicked.connect(self.paste_data)
        layout.addWidget(paste_button)

        import_row = QHBoxLayout()
        import_button = QPushButton("Import CSV/XLSX")
        choose_image = QPushButton("Choose artwork")
        import_button.clicked.connect(self.import_data)
        choose_image.clicked.connect(self.choose_selected_image)
        import_row.addWidget(import_button)
        import_row.addWidget(choose_image)
        layout.addLayout(import_row)

        self.table = QTableWidget(0, 5)
        self.table.setHorizontalHeaderLabels(("Value", "Label", "Title", "Description", "Image"))
        self.table.setAlternatingRowColors(True)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.table.verticalHeader().setDefaultSectionSize(27)
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.setColumnWidth(0, 85)
        self.table.setColumnWidth(1, 105)
        self.table.setColumnWidth(2, 115)
        self.table.setColumnWidth(3, 190)
        self.table.cellChanged.connect(self._table_changed)
        self.table.itemSelectionChanged.connect(self._selection_changed)
        layout.addWidget(self.table, 1)

        row = QHBoxLayout()
        add_button = QPushButton("＋ Card")
        duplicate_button = QPushButton("Duplicate")
        delete_button = QPushButton("Delete")
        delete_button.setObjectName("danger")
        add_button.clicked.connect(self.add_card)
        duplicate_button.clicked.connect(self.duplicate_card)
        delete_button.clicked.connect(self.delete_card)
        row.addWidget(add_button)
        row.addWidget(duplicate_button)
        row.addWidget(delete_button)
        layout.addLayout(row)
        self.card_count_label = QLabel()
        self.card_count_label.setObjectName("muted")
        layout.addWidget(self.card_count_label)
        return page

    def _build_style_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(8, 12, 8, 8)
        form = QFormLayout()
        form.setSpacing(10)
        self.model_combo = QComboBox()
        for model_id in MODEL_IDS:
            self.model_combo.addItem(MODEL_LABELS[model_id], model_id)
        self.model_combo.currentIndexChanged.connect(self._style_changed)
        form.addRow("Visual model", self.model_combo)

        self.resolution_combo = QComboBox()
        self.resolution_combo.addItem("1280 × 720", (1280, 720))
        self.resolution_combo.addItem("1920 × 1080", (1920, 1080))
        self.resolution_combo.addItem("2560 × 1440", (2560, 1440))
        self.resolution_combo.currentIndexChanged.connect(self._style_changed)
        form.addRow("Resolution", self.resolution_combo)

        self.fps_spin = QSpinBox()
        self.fps_spin.setRange(1, 60)
        self.fps_spin.setValue(30)
        self.fps_spin.valueChanged.connect(self._style_changed)
        form.addRow("Frame rate", self.fps_spin)

        self.badge_bounce = QCheckBox("Animate badge scale")
        self.badge_bounce.setChecked(True)
        self.badge_bounce.toggled.connect(self._style_changed)
        form.addRow("Badge motion", self.badge_bounce)
        layout.addLayout(form)

        note = QLabel(
            "Illustrated Cards uses the measured reference-video geometry: four cards, "
            "73% artwork, white title strip, orange separator and dark description band."
        )
        note.setWordWrap(True)
        note.setObjectName("muted")
        layout.addWidget(note)
        layout.addStretch()
        return page

    def _build_audio_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(8, 12, 8, 8)
        self.audio_path = QLineEdit()
        self.audio_path.setPlaceholderText("No soundtrack selected")
        self.audio_path.editingFinished.connect(self._audio_changed)
        choose = QPushButton("Choose soundtrack")
        clear = QPushButton("Clear")
        choose.clicked.connect(self.choose_audio)
        clear.clicked.connect(self.clear_audio)
        layout.addWidget(self.audio_path)
        buttons = QHBoxLayout()
        buttons.addWidget(choose)
        buttons.addWidget(clear)
        layout.addLayout(buttons)

        self.audio_volume = QSlider(Qt.Orientation.Horizontal)
        self.audio_volume.setRange(0, 200)
        self.audio_volume.setValue(100)
        self.audio_volume.valueChanged.connect(self._audio_changed)
        self.audio_volume_label = QLabel("100%")
        volume_row = QHBoxLayout()
        volume_row.addWidget(QLabel("Volume"))
        volume_row.addWidget(self.audio_volume, 1)
        volume_row.addWidget(self.audio_volume_label)
        layout.addLayout(volume_row)

        self.audio_loop = QCheckBox("Loop soundtrack to video length")
        self.audio_loop.toggled.connect(self._audio_changed)
        layout.addWidget(self.audio_loop)
        layout.addStretch()
        return page

    def _build_export_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(8, 12, 8, 8)
        self.export_summary = QLabel()
        self.export_summary.setWordWrap(True)
        layout.addWidget(self.export_summary)
        self.export_stage = QLabel("Ready")
        self.export_stage.setObjectName("muted")
        layout.addWidget(self.export_stage)
        self.export_progress = QProgressBar()
        self.export_progress.setRange(0, 100)
        layout.addWidget(self.export_progress)
        self.export_button = QPushButton("Choose location and export")
        self.export_button.setObjectName("primary")
        self.export_button.clicked.connect(self.start_export)
        self.cancel_export_button = QPushButton("Cancel export")
        self.cancel_export_button.setEnabled(False)
        self.cancel_export_button.clicked.connect(self.cancel_export)
        layout.addWidget(self.export_button)
        layout.addWidget(self.cancel_export_button)
        layout.addStretch()
        return page

    def _build_monitor_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        layout = QVBoxLayout(panel)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(7)
        heading = QHBoxLayout()
        section = QLabel("PROGRAM MONITOR")
        section.setObjectName("section")
        self.monitor_hint = QLabel("Click a visible card to select its row")
        self.monitor_hint.setObjectName("muted")
        heading.addWidget(section)
        heading.addStretch()
        heading.addWidget(self.monitor_hint)
        layout.addLayout(heading)

        monitor = QFrame()
        monitor.setObjectName("monitor")
        monitor_layout = QVBoxLayout(monitor)
        monitor_layout.setContentsMargins(6, 6, 6, 6)
        self.preview = PreviewCanvas()
        self.preview.card_clicked.connect(self._preview_card_clicked)
        monitor_layout.addWidget(self.preview, 1)
        layout.addWidget(monitor, 1)

        playback = QHBoxLayout()
        self.play_button = QPushButton("▶ Play")
        self.play_button.clicked.connect(self.toggle_playback)
        self.timeline_slider = QSlider(Qt.Orientation.Horizontal)
        self.timeline_slider.setRange(0, 10000)
        self.timeline_slider.sliderMoved.connect(self._seek_slider)
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setMinimumWidth(105)
        self.time_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        playback.addWidget(self.play_button)
        playback.addWidget(self.timeline_slider, 1)
        playback.addWidget(self.time_label)
        layout.addLayout(playback)

        duration_frame = QFrame()
        duration_frame.setObjectName("panel")
        duration_layout = QGridLayout(duration_frame)
        duration_layout.setContentsMargins(10, 8, 10, 8)
        duration_layout.setHorizontalSpacing(8)
        self.auto_length = QCheckBox("Automatic length")
        self.auto_length.setChecked(True)
        self.auto_length.toggled.connect(self._duration_mode_changed)
        self.custom_length = QLineEdit()
        self.custom_length.setPlaceholderText("MM:SS")
        self.custom_length.setMaximumWidth(110)
        self.custom_length.setEnabled(False)
        self.custom_length.editingFinished.connect(self._custom_length_changed)
        self.duration_info = QLabel()
        self.duration_info.setObjectName("muted")
        duration_layout.addWidget(self.auto_length, 0, 0)
        duration_layout.addWidget(self.custom_length, 0, 1)
        duration_layout.addWidget(self.duration_info, 0, 2, 1, 2)
        layout.addWidget(duration_frame)
        return panel

    def _load_project_into_ui(self) -> None:
        self.project.normalize()
        self._table_loading = True
        try:
            self.table.setRowCount(len(self.project.cards))
            for row, card in enumerate(self.project.cards):
                for column, value in enumerate(
                    (card.value, card.label, card.title, card.description, card.image)
                ):
                    self.table.setItem(row, column, QTableWidgetItem(value))
        finally:
            self._table_loading = False

        with QSignalBlocker(self.model_combo):
            index = self.model_combo.findData(self.project.model_id)
            self.model_combo.setCurrentIndex(max(0, index))
        with QSignalBlocker(self.resolution_combo):
            index = self.resolution_combo.findData((self.project.width, self.project.height))
            self.resolution_combo.setCurrentIndex(index if index >= 0 else 1)
        with QSignalBlocker(self.fps_spin):
            self.fps_spin.setValue(self.project.fps)
        with QSignalBlocker(self.badge_bounce):
            self.badge_bounce.setChecked(self.project.badge_bounce)
        with QSignalBlocker(self.auto_length):
            self.auto_length.setChecked(self.project.custom_duration is None)
        self.custom_length.setEnabled(self.project.custom_duration is not None)
        self.custom_length.setText(
            "" if self.project.custom_duration is None else format_time(self.project.custom_duration)
        )
        with QSignalBlocker(self.audio_path):
            self.audio_path.setText(self.project.audio.path)
        with QSignalBlocker(self.audio_volume):
            self.audio_volume.setValue(round(self.project.audio.volume * 100))
        with QSignalBlocker(self.audio_loop):
            self.audio_loop.setChecked(self.project.audio.loop)
        self.current_time = 0.0
        self.timeline_slider.setValue(0)
        if self.project.cards:
            self.table.selectRow(0)
        self._refresh_all()

    def _sync_project_from_table(self) -> None:
        cards: list[Card] = []
        for row in range(self.table.rowCount()):
            values = []
            for column in range(5):
                item = self.table.item(row, column)
                values.append(item.text().strip() if item else "")
            cards.append(Card(*values))
        self.project.cards = cards or [Card()]
        self.project.dirty = True

    def _refresh_all(self) -> None:
        cards = self.project.content_cards()
        timeline = Timeline(self.project, len(cards))
        self.current_time = min(self.current_time, max(0.0, timeline.output_duration))
        preview_size = (960, 540)
        image = self.renderer.render(self.project, self.current_time, preview_size)
        qimage = ImageQt(image)
        pixmap = QPixmap.fromImage(qimage)
        self.preview.set_frame(self.project, self.current_time, pixmap)
        duration = timeline.output_duration
        slider_value = round((self.current_time / duration) * 10000) if duration > 0 else 0
        with QSignalBlocker(self.timeline_slider):
            self.timeline_slider.setValue(max(0, min(10000, slider_value)))
        self.time_label.setText(f"{format_time(self.current_time)} / {format_time(duration)}")
        automatic_text = format_time(timeline.automatic_duration)
        pace = f"{timeline.seconds_per_card:.2f}s/card" if timeline.scroll_steps else "no scrolling"
        self.duration_info.setText(f"Auto {automatic_text} · {pace}")
        self.card_count_label.setText(f"{len(self.project.cards)} cards")
        self.export_summary.setText(
            f"{self.project.width} × {self.project.height} · {self.project.fps} FPS · "
            f"{format_time(duration)} · {MODEL_LABELS[self.project.model_id]}"
        )
        self.audio_volume_label.setText(f"{self.audio_volume.value()}%")

    def _table_changed(self, _row: int, _column: int) -> None:
        if self._table_loading:
            return
        self._sync_project_from_table()
        self.renderer.assets.clear()
        self._refresh_all()

    def _selection_changed(self) -> None:
        row = self.table.currentRow()
        self.monitor_hint.setText(
            f"Selected card {row + 1}" if row >= 0 else "Click a visible card to select its row"
        )

    def _preview_card_clicked(self, index: int) -> None:
        if 0 <= index < self.table.rowCount():
            self.table.selectRow(index)
            self.tabs.setCurrentIndex(0)

    def paste_data(self) -> None:
        try:
            cards = parse_table(QApplication.clipboard().text())
            if not cards:
                raise ProjectError("The clipboard does not contain a table.")
            self.project.cards = cards
            self.project.dirty = True
            self.renderer.assets.clear()
            self._load_project_into_ui()
            self.statusBar().showMessage(f"Pasted {len(cards)} cards", 4000)
        except ProjectError as exc:
            self._error(str(exc))

    def import_data(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Import comparison data",
            "",
            "Data files (*.csv *.xlsx);;CSV files (*.csv);;Excel files (*.xlsx)",
        )
        if not path:
            return
        try:
            cards = import_xlsx(path) if path.lower().endswith(".xlsx") else import_csv(path)
            if not cards:
                raise ProjectError("The selected file contains no cards.")
            self.project.cards = cards
            self.project.dirty = True
            self.renderer.assets.clear()
            self._load_project_into_ui()
            self.statusBar().showMessage(f"Imported {len(cards)} cards", 4000)
        except ProjectError as exc:
            self._error(str(exc))

    def choose_selected_image(self) -> None:
        row = max(0, self.table.currentRow())
        if row >= self.table.rowCount():
            self.add_card()
            row = self.table.rowCount() - 1
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose artwork",
            "",
            "Images (*.png *.jpg *.jpeg *.webp *.bmp *.gif)",
        )
        if not path:
            return
        self.table.setItem(row, 4, QTableWidgetItem(path))

    def add_card(self) -> None:
        row = self.table.rowCount()
        self.table.insertRow(row)
        for column in range(5):
            self.table.setItem(row, column, QTableWidgetItem(""))
        self.table.selectRow(row)
        self._sync_project_from_table()
        self._refresh_all()

    def duplicate_card(self) -> None:
        source_row = self.table.currentRow()
        if source_row < 0:
            return
        row = source_row + 1
        self.table.insertRow(row)
        for column in range(5):
            source = self.table.item(source_row, column)
            self.table.setItem(row, column, QTableWidgetItem(source.text() if source else ""))
        self.table.selectRow(row)
        self._sync_project_from_table()
        self._refresh_all()

    def delete_card(self) -> None:
        row = self.table.currentRow()
        if row < 0:
            return
        self.table.removeRow(row)
        if self.table.rowCount() == 0:
            self.add_card()
        else:
            self._sync_project_from_table()
            self._refresh_all()

    def _style_changed(self) -> None:
        self.project.model_id = str(self.model_combo.currentData())
        resolution = self.resolution_combo.currentData()
        if resolution:
            self.project.width, self.project.height = resolution
        self.project.fps = self.fps_spin.value()
        self.project.badge_bounce = self.badge_bounce.isChecked()
        self.project.dirty = True
        self._refresh_all()

    def _duration_mode_changed(self, automatic: bool) -> None:
        self.custom_length.setEnabled(not automatic)
        if automatic:
            self.project.custom_duration = None
        else:
            timeline = Timeline(self.project, len(self.project.content_cards()))
            self.project.custom_duration = timeline.automatic_duration
            self.custom_length.setText(format_time(self.project.custom_duration))
        self.project.dirty = True
        self._refresh_all()

    def _custom_length_changed(self) -> None:
        if self.auto_length.isChecked():
            return
        try:
            self.project.custom_duration = parse_time(self.custom_length.text())
            self.project.dirty = True
            self._refresh_all()
        except ProjectError as exc:
            self._error(str(exc))
            self.custom_length.setFocus()

    def choose_audio(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose soundtrack",
            "",
            "Audio files (*.mp3 *.wav *.m4a *.aac *.flac *.ogg)",
        )
        if not path:
            return
        self.audio_path.setText(path)
        self._audio_changed()

    def clear_audio(self) -> None:
        self.audio_path.clear()
        self._audio_changed()

    def _audio_changed(self) -> None:
        self.project.audio = AudioSettings(
            path=self.audio_path.text().strip(),
            volume=self.audio_volume.value() / 100.0,
            loop=self.audio_loop.isChecked(),
        )
        self.project.dirty = True
        self._refresh_all()

    def toggle_playback(self) -> None:
        if self.playback_timer.isActive():
            self.playback_timer.stop()
            self.play_button.setText("▶ Play")
            return
        duration = Timeline(self.project, len(self.project.content_cards())).output_duration
        if self.current_time >= duration:
            self.current_time = 0.0
        self._last_tick = time.monotonic()
        self.playback_timer.start()
        self.play_button.setText("❚❚ Pause")

    def _playback_tick(self) -> None:
        now = time.monotonic()
        self.current_time += now - self._last_tick
        self._last_tick = now
        duration = Timeline(self.project, len(self.project.content_cards())).output_duration
        if self.current_time >= duration:
            self.current_time = duration
            self.playback_timer.stop()
            self.play_button.setText("▶ Play")
        self._refresh_all()

    def _seek_slider(self, value: int) -> None:
        duration = Timeline(self.project, len(self.project.content_cards())).output_duration
        self.current_time = duration * (value / 10000.0)
        self._refresh_all()

    def new_project(self) -> None:
        if not self._confirm_discard():
            return
        self.project = Project()
        self.renderer.assets.clear()
        self._load_project_into_ui()

    def open_project(self) -> None:
        if not self._confirm_discard():
            return
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Open CTS project",
            "",
            "CTS projects (*.cts.json *.json)",
        )
        if not path:
            return
        try:
            self.project = load_project(path)
            self.renderer.assets.clear()
            self._load_project_into_ui()
            self.statusBar().showMessage(f"Opened {Path(path).name}", 4000)
        except ProjectError as exc:
            self._error(str(exc))

    def save_project(self) -> None:
        if not self.project.project_path:
            self.save_project_as()
            return
        self._save_to(self.project.project_path)

    def save_project_as(self) -> None:
        path, _ = QFileDialog.getSaveFileName(
            self,
            "Save CTS project",
            self.project.project_path or "comparison.cts.json",
            "CTS projects (*.cts.json)",
        )
        if path:
            if not path.lower().endswith(".json"):
                path += ".cts.json"
            self._save_to(path)

    def _save_to(self, path: str) -> None:
        try:
            self._sync_project_from_table()
            saved = save_project(self.project, path)
            self.statusBar().showMessage(f"Saved {Path(saved).name}", 4000)
        except ProjectError as exc:
            self._error(str(exc))

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
        worker = ExportWorker(self.project, path, self)
        self._export_worker = worker
        worker.stage_changed.connect(self.export_stage.setText)
        worker.progress_changed.connect(self._export_progress_changed)
        worker.completed.connect(self._export_completed)
        worker.failed.connect(self._export_failed)
        worker.canceled.connect(self._export_canceled)
        worker.finished.connect(worker.deleteLater)
        self.export_button.setEnabled(False)
        self.cancel_export_button.setEnabled(True)
        self.tabs.setCurrentIndex(3)
        self.export_progress.setValue(0)
        worker.start()

    def _export_progress_changed(self, completed: int, total: int, eta: float) -> None:
        percent = round((completed / max(1, total)) * 100)
        self.export_progress.setValue(percent)
        self.export_stage.setText(f"Rendering {completed:,}/{total:,} frames · ETA {format_time(eta)}")

    def _export_completed(self, path: str) -> None:
        self._finish_export_ui()
        self.export_progress.setValue(100)
        self.export_stage.setText(f"Finished: {path}")
        QMessageBox.information(self, "Export complete", f"Video saved to:\n{path}")

    def _export_failed(self, message: str) -> None:
        self._finish_export_ui()
        self.export_stage.setText("Export failed")
        self._error(message)

    def _export_canceled(self) -> None:
        self._finish_export_ui()
        self.export_stage.setText("Export canceled")

    def cancel_export(self) -> None:
        if self._export_worker is not None:
            self._export_worker.request_cancel()

    def _finish_export_ui(self) -> None:
        self._export_worker = None
        self.export_button.setEnabled(True)
        self.cancel_export_button.setEnabled(False)

    def _confirm_discard(self) -> bool:
        if not self.project.dirty:
            return True
        answer = QMessageBox.question(
            self,
            "Unsaved changes",
            "Discard the current unsaved changes?",
            QMessageBox.StandardButton.Discard | QMessageBox.StandardButton.Cancel,
            QMessageBox.StandardButton.Cancel,
        )
        return answer == QMessageBox.StandardButton.Discard

    def _error(self, message: str) -> None:
        QMessageBox.critical(self, "CTS", message)

    def closeEvent(self, event: QCloseEvent) -> None:  # noqa: N802
        if self._export_worker is not None:
            QMessageBox.warning(self, "CTS", "Cancel the current export before closing CTS.")
            event.ignore()
            return
        if self._confirm_discard():
            event.accept()
        else:
            event.ignore()
