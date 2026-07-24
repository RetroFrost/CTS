from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSizePolicy,
    QSplitter,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from .csv_text_easy import EASY_STYLE, CsvTextEasyMainWindow
from .shared_contract import MODEL_LABEL


DESKTOP_STYLE = EASY_STYLE + """
QFrame#desktopTopBar {
    background:#1d1d22;
    border:1px solid #34343c;
    border-radius:8px;
}
QFrame#desktopRail {
    background:#202027;
    border:1px solid #3b3b44;
    border-radius:8px;
}
QFrame#desktopWorkspace {
    background:#17171b;
    border:0;
}
QFrame#desktopStepPage {
    background:#18181d;
    border:1px solid #393942;
    border-radius:7px;
}
QLabel#desktopProduct {
    color:#ffffff;
    font-size:15px;
    font-weight:900;
}
QLabel#desktopVersion {
    color:#9f9faa;
    font-size:10px;
    font-weight:700;
    letter-spacing:0.8px;
}
QLabel#desktopSection {
    color:#b8b8c2;
    font-size:10px;
    font-weight:900;
    letter-spacing:1px;
}
QLabel#desktopSummary {
    color:#a7a7b1;
    font-size:11px;
}
QPushButton#desktopPrimary {
    background:#7057e8;
    border:1px solid #9a88f7;
    color:white;
    min-height:42px;
    font-size:12px;
    font-weight:900;
}
QPushButton#desktopPrimary:hover {
    background:#806bee;
    border-color:#b0a4fa;
}
QPushButton#desktopSecondary {
    min-height:38px;
    font-weight:800;
}
QPushButton#desktopExport {
    background:#315c86;
    border:1px solid #5a8dbc;
    color:white;
    min-height:44px;
    font-weight:900;
}
QPushButton#desktopExport:hover {
    background:#3a6d9e;
    border-color:#75a9d5;
}
QPushButton#desktopExport:disabled {
    background:#252529;
    border-color:#37373d;
    color:#777780;
}
"""


class DesktopMainWindow(CsvTextEasyMainWindow):
    """Desktop-native CTS shell backed by the shared Android renderer contract."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CTS Desktop — Comparison Timeline Studio")
        self.subtitle_label.setText("DESKTOP WORKSPACE")
        self.statusBar().showMessage("CTS Desktop · Paste data to create a comparison")

    def _build_header(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("desktopTopBar")
        bar.setFixedHeight(54)
        row = QHBoxLayout(bar)
        row.setContentsMargins(10, 7, 10, 7)
        row.setSpacing(9)

        mark = QLabel("CTS")
        mark.setObjectName("appMark")
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row.addWidget(mark)

        title_box = QVBoxLayout()
        title_box.setSpacing(0)
        self.title_label = QLabel("Comparison Timeline Studio")
        self.title_label.setObjectName("desktopProduct")
        self.subtitle_label = QLabel("DESKTOP WORKSPACE")
        self.subtitle_label.setObjectName("desktopVersion")
        title_box.addWidget(self.title_label)
        title_box.addWidget(self.subtitle_label)
        row.addLayout(title_box)
        row.addStretch()

        self.open_button = QPushButton("Open project")
        self.save_button = QPushButton("Save project")
        self.header_export_button = QPushButton("Export MP4")
        self.open_button.setObjectName("toolbar")
        self.save_button.setObjectName("toolbar")
        self.header_export_button.setObjectName("primary")
        self.open_button.clicked.connect(self.open_project)
        self.save_button.clicked.connect(self.save_project)
        self.header_export_button.clicked.connect(self.export_video)
        row.addWidget(self.open_button)
        row.addWidget(self.save_button)
        row.addWidget(self.header_export_button)
        return bar

    def _build_content(self) -> QWidget:
        # Build the workflow rail first because the manual editor's close button
        # references fix_button while the inherited editor panel is being created.
        self.workflow_panel = self._build_workflow_panel()
        self.android_sheet = self.workflow_panel

        self.fix_panel = self._build_editor_panel()
        self.fix_panel.setObjectName("fixSheet")
        self.fix_panel.setVisible(False)

        self.monitor_panel = self._build_preview_panel()
        for button in self.monitor_panel.findChildren(QPushButton):
            if button.text().endswith("Add card"):
                button.setVisible(False)
        old_sequence_bar = self.monitor_panel.findChild(QFrame, "settingsBar")
        if old_sequence_bar is not None:
            old_sequence_bar.setVisible(False)
        monitor_header = self.monitor_panel.findChild(QFrame, "panelHeader")
        self.monitor_hint = (
            monitor_header.findChild(QLabel, "muted") if monitor_header is not None else None
        )
        if self.monitor_hint is not None:
            self.monitor_hint.setText("Preview · open the editor for precise changes")

        workspace = QFrame()
        workspace.setObjectName("desktopWorkspace")
        workspace_layout = QVBoxLayout(workspace)
        workspace_layout.setContentsMargins(0, 0, 0, 0)
        workspace_layout.setSpacing(0)

        self.workspace_splitter = QSplitter(Qt.Orientation.Vertical)
        self.workspace_splitter.setChildrenCollapsible(False)
        self.workspace_splitter.setHandleWidth(5)
        self.workspace_splitter.addWidget(self.monitor_panel)
        self.workspace_splitter.addWidget(self.fix_panel)
        self.workspace_splitter.setStretchFactor(0, 1)
        self.workspace_splitter.setStretchFactor(1, 0)
        self.workspace_splitter.setSizes([720, 0])
        workspace_layout.addWidget(self.workspace_splitter)

        self.content_splitter = QSplitter(Qt.Orientation.Horizontal)
        self.content_splitter.setChildrenCollapsible(False)
        self.content_splitter.setHandleWidth(5)
        self.content_splitter.addWidget(self.workflow_panel)
        self.content_splitter.addWidget(workspace)
        self.content_splitter.setStretchFactor(0, 0)
        self.content_splitter.setStretchFactor(1, 1)
        self.content_splitter.setSizes([310, 1050])
        return self.content_splitter

    def _build_workflow_panel(self) -> QWidget:
        rail = QFrame()
        rail.setObjectName("desktopRail")
        rail.setMinimumWidth(270)
        rail.setMaximumWidth(380)
        layout = QVBoxLayout(rail)
        layout.setContentsMargins(12, 12, 12, 12)
        layout.setSpacing(8)

        section = QLabel("CREATE")
        section.setObjectName("desktopSection")
        layout.addWidget(section)

        heading = QLabel("Build the video in five steps")
        heading.setObjectName("wizardHeading")
        heading.setWordWrap(True)
        layout.addWidget(heading)

        self.android_summary = QLabel("0 cards · No music")
        self.android_summary.setObjectName("desktopSummary")
        self.android_summary.setWordWrap(True)
        layout.addWidget(self.android_summary)

        self.wizard_trail = QLabel()
        self.wizard_trail.setObjectName("wizardTrail")
        self.wizard_trail.setTextFormat(Qt.TextFormat.RichText)
        self.wizard_trail.setWordWrap(True)
        layout.addWidget(self.wizard_trail)

        step_copy = QFrame()
        step_copy.setObjectName("desktopStepPage")
        step_copy_layout = QVBoxLayout(step_copy)
        step_copy_layout.setContentsMargins(10, 8, 10, 8)
        step_copy_layout.setSpacing(3)
        self.wizard_heading = QLabel()
        self.wizard_heading.setObjectName("wizardHeading")
        self.wizard_detail = QLabel()
        self.wizard_detail.setObjectName("androidSummary")
        self.wizard_detail.setWordWrap(True)
        step_copy_layout.addWidget(self.wizard_heading)
        step_copy_layout.addWidget(self.wizard_detail)
        layout.addWidget(step_copy)

        self.insert_data_button = QPushButton("PASTE CSV TEXT")
        self.insert_data_button.setObjectName("desktopPrimary")
        self.insert_data_button.clicked.connect(self._choose_spreadsheet_file)

        self.easy_style_button = QPushButton(f"{MODEL_LABEL.upper()} · ANDROID SYNC")
        self.easy_style_button.setObjectName("desktopPrimary")
        self.easy_style_button.clicked.connect(self._open_style_sheet)

        self.easy_music_button = QPushButton("CHOOSE MUSIC (OPTIONAL)")
        self.easy_music_button.setObjectName("desktopPrimary")
        self.easy_music_button.clicked.connect(self._choose_easy_music)

        self.easy_timing_button = QPushButton("SET VIDEO LENGTH")
        self.easy_timing_button.setObjectName("desktopPrimary")
        self.easy_timing_button.clicked.connect(self._open_timing_sheet)

        self.easy_export_button = QPushButton("EXPORT MP4")
        self.easy_export_button.setObjectName("desktopExport")
        self.easy_export_button.clicked.connect(self.export_video)
        self.export_button = self.easy_export_button

        self.fix_button = QPushButton("Open detailed editor")
        self.fix_button.setObjectName("desktopSecondary")
        self.fix_button.setCheckable(True)
        self.fix_button.toggled.connect(self._set_fix_visible)

        self.wizard_stack = QStackedWidget()
        for buttons in (
            (self.insert_data_button,),
            (self.easy_style_button,),
            (self.easy_music_button,),
            (self.easy_timing_button,),
            (self.easy_export_button, self.fix_button),
        ):
            page = QFrame()
            page.setObjectName("desktopStepPage")
            page_layout = QVBoxLayout(page)
            page_layout.setContentsMargins(8, 7, 8, 7)
            page_layout.setSpacing(7)
            for button in buttons:
                policy = button.sizePolicy()
                policy.setHorizontalPolicy(QSizePolicy.Policy.Expanding)
                button.setSizePolicy(policy)
                page_layout.addWidget(button)
            self.wizard_stack.addWidget(page)
        layout.addWidget(self.wizard_stack)

        self.android_duration = QLabel()
        self.android_duration.setObjectName("desktopSummary")
        self.android_duration.setWordWrap(True)
        layout.addWidget(self.android_duration)
        layout.addStretch()

        footer = QHBoxLayout()
        footer.setSpacing(6)
        self.wizard_progress = QLabel("STEP 1 OF 5")
        self.wizard_progress.setObjectName("desktopSummary")
        footer.addWidget(self.wizard_progress)
        footer.addStretch()
        self.wizard_back = QPushButton("‹ Back")
        self.wizard_back.setObjectName("desktopSecondary")
        self.wizard_back.clicked.connect(self._wizard_previous)
        self.wizard_next = QPushButton("Next ›")
        self.wizard_next.setObjectName("desktopSecondary")
        self.wizard_next.clicked.connect(self._wizard_next_step)
        footer.addWidget(self.wizard_back)
        footer.addWidget(self.wizard_next)
        layout.addLayout(footer)
        return rail

    def _update_android_summary(self, *_args) -> None:
        super()._update_android_summary(*_args)
        if not hasattr(self, "header_export_button") or not hasattr(self, "table"):
            return
        ready = bool(self.cards())
        self.header_export_button.setEnabled(ready)
        self.header_export_button.setToolTip(
            "Export the finished MP4" if ready else "Paste CSV text first"
        )

    def _set_fix_visible(self, visible: bool) -> None:
        self._fix_visible = visible
        self.fix_panel.setVisible(visible)
        self.fix_button.setText("Close detailed editor" if visible else "Open detailed editor")
        self.subtitle_label.setText("MANUAL EDITING" if visible else "DESKTOP WORKSPACE")
        if self.monitor_hint is not None:
            self.monitor_hint.setText(
                "Click a field in the preview to edit it"
                if visible
                else "Preview · open the editor for precise changes"
            )
        if visible:
            self.workspace_splitter.setSizes([430, 300])
            self.statusBar().showMessage(
                "Detailed editor · cards, design mapping, and soundtrack controls", 5000
            )
        else:
            self.tabs.setCurrentIndex(0)
            self.workspace_splitter.setSizes([720, 0])
            self.statusBar().showMessage(
                f"Create workflow · step {self._wizard_step + 1} of 5", 3500
            )
            self._set_wizard_step(self._wizard_step, focus=False)
        self._apply_responsive_layout()

    def _apply_responsive_layout(self) -> None:
        if not hasattr(self, "root_layout"):
            return
        compact = self.width() < 1180 or self.height() < 760
        very_compact = self.width() < 980
        self._compact_mode = compact

        self.root_layout.setContentsMargins(5, 5, 5, 5)
        self.root_layout.setSpacing(5)
        self.title_label.setStyleSheet(
            "font-size:13px; font-weight:900;"
            if compact
            else "font-size:15px; font-weight:900;"
        )
        self.subtitle_label.setVisible(self.width() >= 920)
        self.open_button.setText("Open" if compact else "Open project")
        self.save_button.setText("Save" if compact else "Save project")
        self.header_export_button.setText("Export" if compact else "Export MP4")

        rail_width = 250 if very_compact else (285 if compact else 320)
        self.workflow_panel.setMinimumWidth(235 if very_compact else 270)
        self.workflow_panel.setMaximumWidth(330 if compact else 380)
        remaining = max(520, self.content_splitter.width() - rail_width - 5)
        self.content_splitter.setSizes([rail_width, remaining])

        self.preview.setMinimumSize(420 if compact else 560, 236 if compact else 315)
        show_guidance = not (self._fix_visible and compact)
        self.cards_helper.setVisible(show_guidance)
        self.field_guide.setVisible(show_guidance)
        if self._fix_visible:
            editor_height = 250 if compact else 330
            monitor_height = max(280, self.workspace_splitter.height() - editor_height - 5)
            self.workspace_splitter.setSizes([monitor_height, editor_height])
        else:
            self.workspace_splitter.setSizes([max(420, self.workspace_splitter.height()), 0])

        self._refresh_style_button_text()
        self._refresh_music_button_text()
        self._refresh_duration_labels()
        self._set_wizard_step(self._wizard_step, focus=False)


__all__ = ["DESKTOP_STYLE", "DesktopMainWindow"]
