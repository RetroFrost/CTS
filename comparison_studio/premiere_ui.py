from __future__ import annotations

from PySide6.QtCore import Qt
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

from .ui import MainWindow, PreviewWidget, SpreadsheetTable


PREMIERE_STYLE = """
QWidget {
    background:#1b1b1f;
    color:#dedee3;
    font-family:"Inter","Noto Sans",sans-serif;
    font-size:12px;
}
QMainWindow { background:#151518; }
QToolTip {
    background:#2b2b31;
    color:#f4f4f6;
    border:1px solid #50505a;
    padding:5px;
}
QFrame#premiereTopBar {
    background:#242428;
    border:0;
    border-bottom:1px solid #35353b;
}
QLabel#appMark {
    background:#7057e8;
    color:white;
    border-radius:3px;
    padding:5px 8px;
    font-size:13px;
    font-weight:900;
}
QLabel#workspaceName {
    color:#b8b8c0;
    font-size:11px;
    font-weight:700;
    padding:4px 8px;
    border-left:1px solid #3b3b42;
}
QLabel#panelTitle {
    color:#f0f0f3;
    font-size:11px;
    font-weight:800;
    letter-spacing:0.8px;
}
QLabel#eyebrow {
    color:#a3a3ad;
    font-size:10px;
    font-weight:800;
    letter-spacing:0.7px;
}
QLabel#muted { color:#9696a0; }
QFrame#panel {
    background:#202024;
    border:1px solid #34343a;
    border-radius:2px;
}
QFrame#panelHeader {
    background:#29292e;
    border:0;
    border-bottom:1px solid #3a3a41;
}
QFrame#previewFrame {
    background:#08080a;
    border:1px solid #3c3c43;
    border-radius:1px;
}
QFrame#controlBar,
QFrame#settingsBar {
    background:#26262b;
    border:1px solid #38383f;
    border-radius:2px;
}
QPushButton {
    background:#303036;
    color:#e4e4e8;
    border:1px solid #484850;
    border-radius:3px;
    padding:6px 10px;
    font-weight:650;
}
QPushButton:hover {
    background:#3a3a42;
    border-color:#666672;
}
QPushButton:pressed { background:#28282e; }
QPushButton:disabled {
    color:#777780;
    background:#252529;
    border-color:#37373d;
}
QPushButton#toolbar {
    background:#2b2b30;
    border-color:#414149;
    padding:6px 11px;
}
QPushButton#toolbar:hover { background:#383840; }
QPushButton#primary {
    background:#7057e8;
    border-color:#8975f0;
    color:white;
    padding-left:15px;
    padding-right:15px;
}
QPushButton#primary:hover {
    background:#7d67ee;
    border-color:#a091f6;
}
QPushButton#insertData {
    background:#315c86;
    border:1px solid #4f82b5;
    color:#ffffff;
    min-height:32px;
    font-size:12px;
    font-weight:850;
    letter-spacing:0.4px;
}
QPushButton#insertData:hover {
    background:#3a6d9e;
    border-color:#6da1d1;
}
QPushButton#danger { color:#ff9ca8; }
QLineEdit,
QTableWidget,
QComboBox,
QSpinBox,
QDoubleSpinBox {
    background:#17171b;
    color:#e5e5e8;
    border:1px solid #404047;
    border-radius:2px;
    selection-background-color:#7057e8;
    selection-color:white;
}
QLineEdit,
QComboBox,
QSpinBox,
QDoubleSpinBox { padding:5px 7px; }
QLineEdit:focus,
QComboBox:focus,
QSpinBox:focus,
QDoubleSpinBox:focus { border-color:#7057e8; }
QTableWidget {
    gridline-color:#323238;
    alternate-background-color:#1d1d21;
}
QTableWidget::item { padding:3px; }
QTableWidget::item:selected {
    background:#5142a6;
    color:white;
}
QHeaderView::section {
    background:#2b2b30;
    color:#d2d2d8;
    border:0;
    border-right:1px solid #3d3d44;
    border-bottom:1px solid #414148;
    padding:6px;
    font-weight:700;
}
QTabWidget::pane {
    border:1px solid #393940;
    background:#202024;
    top:-1px;
}
QTabBar::tab {
    background:#242428;
    color:#a8a8b1;
    padding:7px 13px;
    border:1px solid #37373d;
    border-bottom:0;
    min-width:64px;
}
QTabBar::tab:selected {
    background:#202024;
    color:#ffffff;
    border-top:2px solid #7057e8;
    padding-top:6px;
}
QTabBar::tab:hover:!selected { background:#2d2d32; }
QGroupBox {
    border:1px solid #3a3a41;
    border-radius:2px;
    margin-top:11px;
    padding-top:10px;
    font-weight:700;
}
QGroupBox::title {
    subcontrol-origin:margin;
    left:8px;
    padding:0 4px;
    color:#c8c8cf;
}
QSlider::groove:horizontal {
    height:4px;
    background:#3a3a41;
    border-radius:2px;
}
QSlider::handle:horizontal {
    background:#d7d7dc;
    border:1px solid #f0f0f2;
    width:10px;
    margin:-4px 0;
    border-radius:5px;
}
QSlider::sub-page:horizontal {
    background:#7057e8;
    border-radius:2px;
}
QProgressBar {
    background:#222227;
    border:1px solid #44444c;
    border-radius:2px;
    text-align:center;
    min-height:19px;
}
QProgressBar::chunk { background:#7057e8; }
QCheckBox { spacing:7px; }
QCheckBox::indicator { width:15px; height:15px; }
QSplitter::handle { background:#101012; }
QSplitter::handle:horizontal { width:5px; }
QSplitter::handle:vertical { height:5px; }
QScrollArea { border:0; }
QMenu {
    background:#29292e;
    border:1px solid #484850;
    padding:4px;
}
QMenu::item { padding:6px 28px 6px 10px; }
QMenu::item:selected { background:#5142a6; }
QStatusBar {
    background:#202024;
    color:#a5a5ae;
    border-top:1px solid #37373d;
}
"""


class PremiereMainWindow(MainWindow):
    """CTS 0.3.5 behavior inside a denser, editing-suite-inspired shell."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CTS 0.4.0 — Comparison Timeline Studio")
        self.statusBar().showMessage("Ready · 0.3.5 workflow · Editing workspace")

    def _build_header(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("premiereTopBar")
        bar.setFixedHeight(48)
        row = QHBoxLayout(bar)
        row.setContentsMargins(8, 6, 8, 6)
        row.setSpacing(8)

        mark = QLabel("CTS")
        mark.setObjectName("appMark")
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row.addWidget(mark)

        self.title_label = QLabel("Comparison Timeline Studio")
        self.title_label.setStyleSheet("font-size:14px; font-weight:800;")
        row.addWidget(self.title_label)

        self.subtitle_label = QLabel("EDITING")
        self.subtitle_label.setObjectName("workspaceName")
        row.addWidget(self.subtitle_label)
        row.addStretch()

        self.open_button = QPushButton("Open project")
        self.save_button = QPushButton("Save project")
        self.export_button = QPushButton("Export MP4")
        self.open_button.setObjectName("toolbar")
        self.save_button.setObjectName("toolbar")
        self.export_button.setObjectName("primary")
        self.open_button.clicked.connect(self.open_project)
        self.save_button.clicked.connect(self.save_project)
        self.export_button.clicked.connect(self.export_video)
        row.addWidget(self.open_button)
        row.addWidget(self.save_button)
        row.addWidget(self.export_button)
        return bar

    def _build_content(self) -> QSplitter:
        self.content_splitter = QSplitter(Qt.Orientation.Horizontal)
        self.content_splitter.setChildrenCollapsible(False)
        self.content_splitter.setHandleWidth(5)
        self.content_splitter.addWidget(self._build_editor_panel())
        self.content_splitter.addWidget(self._build_preview_panel())
        self.content_splitter.setStretchFactor(0, 0)
        self.content_splitter.setStretchFactor(1, 1)
        self.content_splitter.setSizes([440, 900])
        return self.content_splitter

    def _panel_header(self, title: str, detail: str) -> QFrame:
        header = QFrame()
        header.setObjectName("panelHeader")
        row = QHBoxLayout(header)
        row.setContentsMargins(9, 6, 9, 6)
        row.setSpacing(8)
        title_label = QLabel(title)
        title_label.setObjectName("panelTitle")
        detail_label = QLabel(detail)
        detail_label.setObjectName("muted")
        row.addWidget(title_label)
        row.addStretch()
        row.addWidget(detail_label)
        return header

    def _build_editor_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        panel.setMinimumWidth(340)
        self.editor_layout = QVBoxLayout(panel)
        self.editor_layout.setContentsMargins(0, 0, 0, 0)
        self.editor_layout.setSpacing(0)
        self.editor_layout.addWidget(self._panel_header("PROJECT", "Data · Models · Audio"))

        body = QWidget()
        body_layout = QVBoxLayout(body)
        body_layout.setContentsMargins(7, 7, 7, 7)
        body_layout.setSpacing(6)
        self.tabs = QTabWidget()
        self.tabs.setDocumentMode(True)
        self.tabs.addTab(self._build_spreadsheet_tab(), "Data")
        self.tabs.addTab(self._build_models_tab(), "Models")
        self.tabs.addTab(self._build_soundtrack_tab(), "Audio")
        body_layout.addWidget(self.tabs)
        self.editor_layout.addWidget(body, 1)
        return panel

    def _build_spreadsheet_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(7, 7, 7, 7)
        layout.setSpacing(6)

        helper = QLabel(
            "One row is one card. Paste a table, type directly, or import a spreadsheet."
        )
        helper.setWordWrap(True)
        helper.setObjectName("muted")
        layout.addWidget(helper)

        self.insert_data_button = QPushButton("＋  CLICK TO INSERT DATA")
        self.insert_data_button.setObjectName("insertData")
        self.insert_data_button.setToolTip(
            "Paste a complete table from the clipboard. The first row becomes the field names."
        )
        self.insert_data_button.clicked.connect(self.paste_data)
        layout.addWidget(self.insert_data_button)

        import_buttons = QGridLayout()
        import_buttons.setHorizontalSpacing(5)
        import_buttons.setVerticalSpacing(5)
        import_button = QPushButton("Import XLSX")
        strip_button = QPushButton("Import image strip")
        image_button = QPushButton("Choose row image")
        import_button.clicked.connect(self.import_xlsx)
        strip_button.clicked.connect(self.import_image_strip)
        image_button.clicked.connect(self.choose_row_image)
        import_buttons.addWidget(import_button, 0, 0)
        import_buttons.addWidget(strip_button, 0, 1)
        import_buttons.addWidget(image_button, 1, 0, 1, 2)
        layout.addLayout(import_buttons)

        self.field_guide = QLabel()
        self.field_guide.setWordWrap(True)
        self.field_guide.setTextFormat(Qt.TextFormat.PlainText)
        self.field_guide.setMaximumHeight(82)
        self.field_guide.setStyleSheet(
            "background:#19191d; border:1px solid #393940; padding:7px; color:#bdbdc5;"
        )
        layout.addWidget(self.field_guide)

        self.table = SpreadsheetTable()
        self.table.data_edited.connect(self._data_changed)
        self.table.headers_changed.connect(self._headers_changed)
        self.table.horizontalHeader().sectionDoubleClicked.connect(self._rename_column_at)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._spreadsheet_context_menu)
        self.table.horizontalHeader().setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.horizontalHeader().customContextMenuRequested.connect(self._header_context_menu)
        layout.addWidget(self.table, 1)

        self.table_status = QLabel("0 cards · 0 fields")
        self.table_status.setObjectName("muted")
        layout.addWidget(self.table_status)

        row_buttons = QHBoxLayout()
        row_buttons.setSpacing(5)
        add_row = QPushButton("＋ Card")
        duplicate_row = QPushButton("Duplicate")
        remove_row = QPushButton("Delete")
        add_row.clicked.connect(lambda: self.table.append_row())
        duplicate_row.clicked.connect(self._duplicate_rows)
        remove_row.clicked.connect(self.table.remove_selected_rows)
        row_buttons.addWidget(add_row)
        row_buttons.addWidget(duplicate_row)
        row_buttons.addWidget(remove_row)
        layout.addLayout(row_buttons)

        field_buttons = QHBoxLayout()
        field_buttons.setSpacing(5)
        add_column = QPushButton("＋ Field")
        rename_column = QPushButton("Rename")
        remove_column = QPushButton("Delete field")
        reset = QPushButton("Blank")
        add_column.clicked.connect(lambda: self.table.add_column())
        rename_column.clicked.connect(self._rename_column)
        remove_column.clicked.connect(self._remove_column)
        reset.clicked.connect(self.clear_all)
        field_buttons.addWidget(add_column)
        field_buttons.addWidget(rename_column)
        field_buttons.addWidget(remove_column)
        field_buttons.addStretch()
        field_buttons.addWidget(reset)
        layout.addLayout(field_buttons)
        return page

    def _build_preview_panel(self) -> QWidget:
        panel = QFrame()
        panel.setObjectName("panel")
        self.preview_layout = QVBoxLayout(panel)
        self.preview_layout.setContentsMargins(0, 0, 0, 0)
        self.preview_layout.setSpacing(0)
        self.preview_layout.addWidget(
            self._panel_header("PROGRAM MONITOR", "Click a field in the preview to edit it")
        )

        monitor_body = QWidget()
        monitor_layout = QVBoxLayout(monitor_body)
        monitor_layout.setContentsMargins(9, 9, 9, 8)
        monitor_layout.setSpacing(7)

        self.preview = PreviewWidget()
        self.preview.field_clicked.connect(self._preview_field_clicked)
        self.preview.inline_committed.connect(self._commit_direct_edit)
        self.preview.inline_canceled.connect(self.update_preview)
        monitor_layout.addWidget(self.preview, 1)

        control_bar = QFrame()
        control_bar.setObjectName("controlBar")
        playback = QHBoxLayout(control_bar)
        playback.setContentsMargins(7, 5, 7, 5)
        playback.setSpacing(6)
        self.play_button = QPushButton("▶ Play")
        self.play_button.setMinimumWidth(72)
        self.play_button.setToolTip("Play or pause the preview")
        self.play_button.clicked.connect(self.toggle_playback)
        add_card_button = QPushButton("＋ Add card")
        add_card_button.clicked.connect(self._add_card_from_preview)
        self.slider = QSlider(Qt.Orientation.Horizontal)
        self.slider.setRange(0, 10000)
        self.slider.sliderMoved.connect(self._slider_moved)
        self.time_label = QLabel("00:00 / 00:00")
        self.time_label.setMinimumWidth(106)
        self.time_label.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
        playback.addWidget(self.play_button)
        playback.addWidget(add_card_button)
        playback.addWidget(self.slider, 1)
        playback.addWidget(self.time_label)
        monitor_layout.addWidget(control_bar)

        settings_bar = QFrame()
        settings_bar.setObjectName("settingsBar")
        duration_row = QHBoxLayout(settings_bar)
        duration_row.setContentsMargins(8, 5, 8, 5)
        duration_row.setSpacing(7)
        sequence_label = QLabel("SEQUENCE")
        sequence_label.setObjectName("eyebrow")
        duration_row.addWidget(sequence_label)

        self.auto_length = QCheckBox("Auto length")
        self.auto_length.setChecked(True)
        self.custom_length = QLineEdit()
        self.custom_length.setPlaceholderText("MM:SS")
        self.custom_length.setMaximumWidth(105)
        self.custom_length.setEnabled(False)
        self.duration_info = QLabel()
        self.duration_info.setObjectName("muted")
        self.auto_length.toggled.connect(self._duration_mode_changed)
        self.custom_length.editingFinished.connect(self._custom_duration_changed)
        duration_row.addWidget(self.auto_length)
        duration_row.addWidget(self.custom_length)
        duration_row.addWidget(self.duration_info, 1)

        self.hexagons_bounce = QCheckBox("Badge reveal")
        self.hexagons_bounce.setChecked(True)
        self.hexagons_bounce.setToolTip(
            "Fade each red value badge in after its card slides into place, like the reference video."
        )
        self.hexagons_bounce.toggled.connect(self._data_changed)
        duration_row.addWidget(self.hexagons_bounce)
        monitor_layout.addWidget(settings_bar)

        self.preview_layout.addWidget(monitor_body, 1)
        return panel

    def _apply_responsive_layout(self) -> None:
        """Keep the dense editing workspace usable on 1366×768 displays."""
        compact = self.width() < 1450 or self.height() < 850
        changed = compact != self._compact_mode
        self._compact_mode = compact

        if compact:
            self.root_layout.setContentsMargins(5, 5, 5, 5)
            self.root_layout.setSpacing(5)
            self.title_label.setStyleSheet("font-size:13px; font-weight:800;")
            self.field_guide.setMaximumHeight(66)
        else:
            self.root_layout.setContentsMargins(9, 8, 9, 9)
            self.root_layout.setSpacing(7)
            self.title_label.setStyleSheet("font-size:14px; font-weight:800;")
            self.field_guide.setMaximumHeight(82)

        self.subtitle_label.setVisible(self.width() >= 1120)
        short_header = self.width() < 1180
        self.open_button.setText("Open" if short_header else "Open project")
        self.save_button.setText("Save" if short_header else "Save project")
        self.export_button.setText("Export" if self.width() < 1050 else "Export MP4")

        if changed:
            editor_width = 390 if compact else 455
            remaining = max(520, self.content_splitter.width() - editor_width - 5)
            self.content_splitter.setSizes([editor_width, remaining])
