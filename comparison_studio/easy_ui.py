from __future__ import annotations

import csv
import io
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QFontMetrics, QKeySequence, QShortcut
from PySide6.QtWidgets import (
    QApplication,
    QCheckBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPlainTextEdit,
    QPushButton,
    QSizePolicy,
    QStackedWidget,
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from .data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    MODEL_SCHEMAS,
    REFERENCE_REVEAL_SECONDS,
    AudioTrack,
    FriendlyError,
    SpreadsheetData,
    format_duration,
    load_xlsx_table,
    parse_duration,
    table_from_matrix,
)
from .easy_timing import timeline_parts, with_easy_timing
from .premiere_ui import PREMIERE_STYLE
from .reference_illustrated import ReferenceIllustratedMainWindow
from .ui import MODEL_INFO, SpreadsheetTable, show_error


EASY_STYLE = PREMIERE_STYLE + """
QFrame#androidTopBar {
    background:#202024;
    border:0;
    border-bottom:1px solid #37373d;
}
QFrame#androidSheet {
    background:#27272d;
    border:1px solid #45454d;
    border-radius:10px;
}
QFrame#fixSheet {
    background:#202024;
    border:1px solid #3f3f47;
    border-radius:8px;
}
QFrame#styleChoice {
    background:#27272d;
    border:1px solid #45454d;
    border-radius:7px;
}
QFrame#wizardStepPage {
    background:#202024;
    border:1px solid #3d3d45;
    border-radius:7px;
}
QLabel#androidTitle {
    color:#f5f5f7;
    font-size:13px;
    font-weight:900;
}
QLabel#wizardHeading {
    color:#f5f5f7;
    font-size:14px;
    font-weight:900;
}
QLabel#wizardTrail {
    color:#90909a;
    background:#202024;
    border:1px solid #393940;
    border-radius:5px;
    padding:5px 8px;
}
QLabel#androidSummary {
    color:#a8a8b2;
    font-size:11px;
}
QPushButton#androidPrimary {
    background:#7057e8;
    border:1px solid #9a88f7;
    color:white;
    min-height:40px;
    font-size:12px;
    font-weight:900;
    letter-spacing:0.4px;
}
QPushButton#androidPrimary:hover {
    background:#806bee;
    border-color:#b0a4fa;
}
QPushButton#androidAction {
    min-height:40px;
    font-weight:800;
}
QPushButton#androidExport {
    background:#315c86;
    border:1px solid #5a8dbc;
    color:white;
    min-height:40px;
    font-weight:900;
}
QPushButton#androidExport:hover {
    background:#3a6d9e;
    border-color:#75a9d5;
}
QPushButton#androidExport:disabled {
    background:#252529;
    border-color:#37373d;
    color:#777780;
}
QPushButton#fixAction:checked {
    background:#704f2f;
    border-color:#a8794a;
    color:white;
}
QPushButton#fixAction {
    min-height:40px;
    font-weight:800;
}
QDialog#androidDialog {
    background:#202024;
}
QPlainTextEdit {
    background:#151519;
    color:#ededf1;
    border:1px solid #414149;
    border-radius:5px;
    selection-background-color:#7057e8;
    font-family:"JetBrains Mono","DejaVu Sans Mono",monospace;
    font-size:12px;
}
"""


def _parse_text_table(text: str) -> SpreadsheetData:
    if not text.strip():
        return SpreadsheetData()
    sample = text[:4096]
    if "\t" in sample:
        delimiter = "\t"
    elif sample.count(";") > sample.count(","):
        delimiter = ";"
    else:
        delimiter = ","
    rows = list(csv.reader(io.StringIO(text), delimiter=delimiter))
    return table_from_matrix(rows, first_row_is_header=True)


def _table_as_text(data: SpreadsheetData) -> str:
    normalized = data.normalized()
    output = io.StringIO()
    writer = csv.writer(output, delimiter="\t", lineterminator="\n")
    writer.writerow(normalized.headers)
    writer.writerows(normalized.rows)
    return output.getvalue()


def load_table_file(path: str | Path) -> tuple[SpreadsheetData, list[str]]:
    """Load the single Android-style Import file action's supported formats."""
    target = Path(path)
    suffix = target.suffix.lower()
    if suffix == ".xlsx":
        result = load_xlsx_table(target)
        return result.data, result.warnings
    if suffix in {".csv", ".tsv", ".txt"}:
        try:
            text = target.read_text(encoding="utf-8-sig")
        except UnicodeDecodeError:
            text = target.read_text(encoding="latin-1")
        data = _parse_text_table(text)
        if not data.headers:
            raise FriendlyError(
                "The selected file does not contain a readable table.",
                "Use CSV, TSV, TXT, or XLSX with field names in the first row.",
            )
        return data, []
    raise FriendlyError(
        f"Unsupported data file: {target.name}",
        "Choose a CSV, TSV, TXT, or XLSX spreadsheet.",
    )


class InsertDataDialog(QDialog):
    """The same single data sheet used by the Android workflow."""

    def __init__(
        self,
        clipboard_text: str = "",
        parent=None,
        *,
        existing: bool = False,
    ) -> None:
        super().__init__(parent)
        self.setObjectName("androidDialog")
        self.setWindowTitle("Insert data")
        self.setModal(True)
        self.resize(720, 470)
        self.selected_data: SpreadsheetData | None = None
        self.warnings: list[str] = []
        self._action_verb = "Update" if existing else "Create"

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)

        self.heading = QLabel("EDIT ALL DATA" if existing else "CLICK TO INSERT DATA")
        self.heading.setObjectName("androidTitle")
        layout.addWidget(self.heading)

        description = QLabel(
            "Paste a table below, or import CSV/XLSX. CTS detects the fields and card count "
            "as you type. The first row contains field names; each remaining row becomes a card."
        )
        description.setWordWrap(True)
        description.setObjectName("androidSummary")
        layout.addWidget(description)

        self.editor = QPlainTextEdit()
        self.editor.setPlaceholderText(
            "Badge Value\tBadge Label\tTitle\tArtwork\n"
            "84\tPERCENT\tExample card\thttps://example.com/image.png"
        )
        if clipboard_text.strip() and any(
            delimiter in clipboard_text for delimiter in ("\n", "\t", ",", ";")
        ):
            self.editor.setPlainText(clipboard_text)
        layout.addWidget(self.editor, 1)

        self.status = QLabel("Paste spreadsheet cells or import a file.")
        self.status.setObjectName("androidSummary")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)

        buttons = QHBoxLayout()
        import_button = QPushButton("Import CSV / XLSX")
        import_button.setObjectName("androidAction")
        import_button.clicked.connect(self._import_file)
        buttons.addWidget(import_button)
        buttons.addStretch()

        dialog_buttons = QDialogButtonBox()
        cancel = dialog_buttons.addButton(QDialogButtonBox.StandardButton.Cancel)
        self.use_data = dialog_buttons.addButton(
            "Create cards", QDialogButtonBox.ButtonRole.AcceptRole
        )
        self.use_data.setObjectName("androidPrimary")
        self.use_data.setDefault(True)
        cancel.clicked.connect(self.reject)
        self.use_data.clicked.connect(self._accept_pasted)
        buttons.addWidget(dialog_buttons)
        layout.addLayout(buttons)

        self._detected_data = SpreadsheetData()
        self.editor.textChanged.connect(self._refresh_detection)
        self._accept_shortcuts = [
            QShortcut(QKeySequence("Ctrl+Return"), self),
            QShortcut(QKeySequence("Ctrl+Enter"), self),
        ]
        for shortcut in self._accept_shortcuts:
            shortcut.activated.connect(self._accept_pasted)
        self._refresh_detection()

    def _refresh_detection(self) -> None:
        try:
            data = _parse_text_table(self.editor.toPlainText())
        except (csv.Error, ValueError):
            data = SpreadsheetData()
            self.status.setText("Could not read those rows · check separators and quotes")
            self.status.setStyleSheet("color:#ff9ca8;")
        else:
            if data.headers and data.row_count:
                card_word = "card" if data.row_count == 1 else "cards"
                field_word = "field" if len(data.headers) == 1 else "fields"
                self.status.setText(
                    f"Ready · {data.row_count} {card_word} · "
                    f"{len(data.headers)} {field_word} detected automatically"
                )
                self.status.setStyleSheet("color:#8ed39c;")
            elif data.headers:
                self.status.setText(
                    "Field names detected · add at least one card row underneath"
                )
                self.status.setStyleSheet("color:#e7bf72;")
            else:
                self.status.setText("Paste spreadsheet cells or import a file.")
                self.status.setStyleSheet("")
        self._detected_data = data
        ready = bool(data.headers and data.row_count)
        self.use_data.setEnabled(ready)
        if ready:
            card_word = "card" if data.row_count == 1 else "cards"
            self.use_data.setText(
                f"{self._action_verb} {data.row_count} {card_word}"
            )
        else:
            self.use_data.setText(f"{self._action_verb} cards")

    def _import_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Import comparison data",
            "",
            "Spreadsheet data (*.csv *.tsv *.txt *.xlsx)",
        )
        if not path:
            return
        try:
            self.selected_data, self.warnings = load_table_file(path)
            self.status.setText(
                f"{Path(path).name} · {self.selected_data.row_count} cards · importing"
            )
            self.accept()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            show_error(
                self,
                "Could not import that data file.",
                "Check that the file is readable and try again.",
                str(exc),
            )

    def _accept_pasted(self) -> None:
        self._refresh_detection()
        if not (self._detected_data.headers and self._detected_data.row_count):
            return
        self.selected_data = self._detected_data
        self.accept()


class TimingDialog(QDialog):
    def __init__(self, automatic: bool, value: str, parent=None) -> None:
        super().__init__(parent)
        self.setObjectName("androidDialog")
        self.setWindowTitle("Video length")
        self.setModal(True)
        self.setMinimumWidth(390)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)

        title = QLabel("TARGET VIDEO LENGTH")
        title.setObjectName("androidTitle")
        layout.addWidget(title)
        detail = QLabel(
            "CTS changes only how long the cards take to scroll. Entrances, the ending hold, "
            "and the fade keep their normal speed."
        )
        detail.setWordWrap(True)
        detail.setObjectName("androidSummary")
        layout.addWidget(detail)

        self.automatic = QCheckBox("Automatic length")
        self.automatic.setChecked(automatic)
        layout.addWidget(self.automatic)

        row = QHBoxLayout()
        row.addWidget(QLabel("Target"))
        self.target = QLineEdit(value)
        self.target.setPlaceholderText("MM:SS or HH:MM:SS")
        self.target.setEnabled(not automatic)
        self.automatic.toggled.connect(lambda checked: self.target.setEnabled(not checked))
        row.addWidget(self.target, 1)
        layout.addLayout(row)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Cancel | QDialogButtonBox.StandardButton.Ok
        )
        buttons.rejected.connect(self.reject)
        buttons.accepted.connect(self._validate)
        layout.addWidget(buttons)

    def _validate(self) -> None:
        if not self.automatic.isChecked():
            try:
                parse_duration(self.target.text())
            except FriendlyError as exc:
                show_error(self, exc.summary, exc.suggestion, exc.details)
                return
        self.accept()


class StyleDialog(QDialog):
    """One-click visual model chooser for the normal creation workflow."""

    def __init__(self, current_model: str, parent=None) -> None:
        super().__init__(parent)
        self.setObjectName("androidDialog")
        self.setWindowTitle("Choose style")
        self.setModal(True)
        self.setMinimumWidth(520)
        self.selected_model = current_model

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)

        title = QLabel("CHOOSE A VIDEO STYLE")
        title.setObjectName("androidTitle")
        layout.addWidget(title)
        detail = QLabel(
            "Pick one style. CTS remaps the spreadsheet and refreshes the preview automatically."
        )
        detail.setWordWrap(True)
        detail.setObjectName("androidSummary")
        layout.addWidget(detail)

        order = (MODEL_ILLUSTRATED, MODEL_REFERENCE, MODEL_CLASSIC)
        for model_id in order:
            name, description = MODEL_INFO[model_id]
            card = QFrame()
            card.setObjectName("styleChoice")
            row = QHBoxLayout(card)
            row.setContentsMargins(9, 8, 9, 8)
            copy = QVBoxLayout()
            heading = QLabel(name)
            heading.setObjectName("androidTitle")
            if model_id == MODEL_ILLUSTRATED:
                heading.setText(f"{name} · Recommended")
            summary = QLabel(description)
            summary.setObjectName("androidSummary")
            summary.setWordWrap(True)
            copy.addWidget(heading)
            copy.addWidget(summary)
            choose = QPushButton("Current" if model_id == current_model else "Use style")
            choose.setObjectName("androidPrimary" if model_id == current_model else "androidAction")
            choose.clicked.connect(
                lambda _checked=False, selected=model_id: self._choose(selected)
            )
            row.addLayout(copy, 1)
            row.addWidget(choose)
            layout.addWidget(card)

        cancel = QDialogButtonBox(QDialogButtonBox.StandardButton.Cancel)
        cancel.rejected.connect(self.reject)
        layout.addWidget(cancel)

    def _choose(self, model_id: str) -> None:
        self.selected_model = model_id
        self.accept()


class EasyMainWindow(ReferenceIllustratedMainWindow):
    """Desktop CTS using a monitor-first, step-by-step setup workflow."""

    def __init__(self) -> None:
        self._fix_visible = False
        self._wizard_step = 0
        super().__init__()
        self.setWindowTitle("CTS — Comparison Timeline Studio")
        self.subtitle_label.setText("CREATE")
        self._prepare_android_defaults()
        self._connect_android_status()
        self._update_android_summary()
        self._set_wizard_step(0)
        self.statusBar().showMessage(
            "Video setup · Step 1 of 5 · Select a spreadsheet"
        )

    def _build_header(self) -> QWidget:
        bar = QFrame()
        bar.setObjectName("androidTopBar")
        bar.setFixedHeight(48)
        row = QHBoxLayout(bar)
        row.setContentsMargins(9, 6, 9, 6)
        row.setSpacing(8)

        mark = QLabel("CTS")
        mark.setObjectName("appMark")
        mark.setAlignment(Qt.AlignmentFlag.AlignCenter)
        row.addWidget(mark)

        self.title_label = QLabel("Comparison Timeline Studio")
        self.title_label.setStyleSheet("font-size:14px; font-weight:850;")
        row.addWidget(self.title_label)

        self.subtitle_label = QLabel("CREATE")
        self.subtitle_label.setObjectName("workspaceName")
        row.addWidget(self.subtitle_label)
        row.addStretch()

        self.open_button = QPushButton("Open project")
        self.save_button = QPushButton("Save project")
        self.open_button.setObjectName("toolbar")
        self.save_button.setObjectName("toolbar")
        self.open_button.clicked.connect(self.open_project)
        self.save_button.clicked.connect(self.save_project)
        row.addWidget(self.open_button)
        row.addWidget(self.save_button)
        return bar

    def _build_content(self) -> QWidget:
        shell = QWidget()
        layout = QVBoxLayout(shell)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(6)

        # Build the hidden editor first so Audio, Models, and the spreadsheet exist before
        # the Android-style action sheet reads their state.
        self.fix_panel = self._build_editor_panel()
        self.fix_panel.setObjectName("fixSheet")
        self.fix_panel.setVisible(False)

        self.monitor_panel = self._build_preview_panel()
        # Card creation belongs to the spreadsheet step; keep the playback bar focused.
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
            self.monitor_hint.setText("Preview · open Manual editor for detailed changes")

        layout.addWidget(self.monitor_panel, 1)
        self.android_sheet = self._build_android_sheet()
        layout.addWidget(self.android_sheet)
        layout.addWidget(self.fix_panel)
        return shell

    def _build_editor_panel(self) -> QWidget:
        panel = QFrame()
        self.editor_layout = QVBoxLayout(panel)
        self.editor_layout.setContentsMargins(0, 0, 0, 0)
        self.editor_layout.setSpacing(0)

        header = QFrame()
        header.setObjectName("panelHeader")
        header_row = QHBoxLayout(header)
        header_row.setContentsMargins(10, 6, 8, 6)
        title = QLabel("MANUAL EDITOR")
        title.setObjectName("panelTitle")
        hint = QLabel("Data · design · detailed audio")
        hint.setObjectName("muted")
        close = QPushButton("Done")
        close.clicked.connect(lambda: self.fix_button.setChecked(False))
        header_row.addWidget(title)
        header_row.addWidget(hint)
        header_row.addStretch()
        header_row.addWidget(close)
        self.editor_layout.addWidget(header)

        body = QWidget()
        body_layout = QVBoxLayout(body)
        body_layout.setContentsMargins(7, 7, 7, 7)
        self.tabs = QTabWidget()
        self.tabs.setDocumentMode(True)
        self.tabs.addTab(self._build_spreadsheet_tab(), "Cards")
        self.tabs.addTab(self._build_models_tab(), "Design")
        self.tabs.addTab(self._build_soundtrack_tab(), "Audio")
        body_layout.addWidget(self.tabs)
        self.editor_layout.addWidget(body, 1)
        return panel

    def _build_spreadsheet_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(7, 7, 7, 7)
        layout.setSpacing(6)

        self.cards_helper = QLabel(
            "Edit individual cards here, or use Paste / edit table for a faster bulk change. "
            "The normal workflow starts with Select spreadsheet."
        )
        self.cards_helper.setWordWrap(True)
        self.cards_helper.setObjectName("muted")
        layout.addWidget(self.cards_helper)

        self.field_guide = QLabel()
        self.field_guide.setWordWrap(True)
        self.field_guide.setTextFormat(Qt.TextFormat.PlainText)
        self.field_guide.setMaximumHeight(66)
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

        row = QHBoxLayout()
        paste_table = QPushButton("Paste / edit table")
        add_card = QPushButton("＋ Card")
        duplicate = QPushButton("Duplicate")
        delete = QPushButton("Delete")
        choose_image = QPushButton("Card image")
        image_strip = QPushButton("Image strip")
        paste_table.clicked.connect(self._open_insert_data_sheet)
        add_card.clicked.connect(lambda: self.table.append_row())
        duplicate.clicked.connect(self._duplicate_rows)
        delete.clicked.connect(self.table.remove_selected_rows)
        choose_image.clicked.connect(self.choose_row_image)
        image_strip.clicked.connect(self.import_image_strip)
        row.addWidget(paste_table)
        row.addWidget(add_card)
        row.addWidget(duplicate)
        row.addWidget(delete)
        row.addStretch()
        row.addWidget(choose_image)
        row.addWidget(image_strip)
        layout.addLayout(row)
        return page

    def _build_android_sheet(self) -> QWidget:
        sheet = QFrame()
        sheet.setObjectName("androidSheet")
        layout = QVBoxLayout(sheet)
        layout.setContentsMargins(10, 8, 10, 9)
        layout.setSpacing(6)

        top = QHBoxLayout()
        title = QLabel("CREATE VIDEO SETUP")
        title.setObjectName("androidTitle")
        self.android_summary = QLabel("No cards · No music")
        self.android_summary.setObjectName("androidSummary")
        self.android_summary.setAlignment(
            Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
        )
        top.addWidget(title)
        top.addStretch()
        top.addWidget(self.android_summary)
        layout.addLayout(top)

        self.wizard_trail = QLabel()
        self.wizard_trail.setObjectName("wizardTrail")
        self.wizard_trail.setTextFormat(Qt.TextFormat.RichText)
        layout.addWidget(self.wizard_trail)

        introduction = QHBoxLayout()
        introduction.setSpacing(10)
        self.wizard_heading = QLabel()
        self.wizard_heading.setObjectName("wizardHeading")
        self.wizard_detail = QLabel()
        self.wizard_detail.setObjectName("androidSummary")
        self.wizard_detail.setWordWrap(True)
        self.wizard_detail.setAlignment(
            Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
        )
        introduction.addWidget(self.wizard_heading)
        introduction.addStretch()
        introduction.addWidget(self.wizard_detail, 2)
        layout.addLayout(introduction)

        self.insert_data_button = QPushButton("SELECT SPREADSHEET")
        self.insert_data_button.setObjectName("androidPrimary")
        self.insert_data_button.setToolTip("Choose CSV, TSV, TXT, or XLSX comparison data")
        self.insert_data_button.clicked.connect(self._choose_spreadsheet_file)

        self.easy_style_button = QPushButton("CHOOSE STYLE")
        self.easy_style_button.setObjectName("androidPrimary")
        style_policy = self.easy_style_button.sizePolicy()
        style_policy.setHorizontalPolicy(QSizePolicy.Policy.Ignored)
        self.easy_style_button.setSizePolicy(style_policy)
        self.easy_style_button.clicked.connect(self._open_style_sheet)

        self.easy_music_button = QPushButton("CHOOSE MUSIC (OPTIONAL)")
        self.easy_music_button.setObjectName("androidPrimary")
        self.easy_music_button.setToolTip("Optional · add one soundtrack that loops automatically")
        music_policy = self.easy_music_button.sizePolicy()
        music_policy.setHorizontalPolicy(QSizePolicy.Policy.Ignored)
        self.easy_music_button.setSizePolicy(music_policy)
        self.easy_music_button.clicked.connect(self._choose_easy_music)

        self.easy_timing_button = QPushButton("SET VIDEO LENGTH")
        self.easy_timing_button.setObjectName("androidPrimary")
        self.easy_timing_button.setToolTip(
            "Optional · automatic timing is already selected"
        )
        self.easy_timing_button.clicked.connect(self._open_timing_sheet)

        self.easy_export_button = QPushButton("EXPORT MP4")
        self.easy_export_button.setObjectName("androidExport")
        self.easy_export_button.setToolTip("Select a spreadsheet first")
        self.easy_export_button.clicked.connect(self.export_video)
        self.export_button = self.easy_export_button

        self.fix_button = QPushButton("Manual editor")
        self.fix_button.setObjectName("fixAction")
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
            page.setObjectName("wizardStepPage")
            row = QHBoxLayout(page)
            row.setContentsMargins(8, 5, 8, 5)
            row.setSpacing(7)
            row.addStretch()
            for button in buttons:
                row.addWidget(button, 1)
            row.addStretch()
            self.wizard_stack.addWidget(page)
        layout.addWidget(self.wizard_stack)

        navigation = QHBoxLayout()
        navigation.setSpacing(7)
        self.android_duration = QLabel()
        self.android_duration.setObjectName("androidSummary")
        self.android_duration.setWordWrap(True)
        navigation.addWidget(self.android_duration, 1)
        self.wizard_progress = QLabel("STEP 1 OF 5")
        self.wizard_progress.setObjectName("androidSummary")
        navigation.addWidget(self.wizard_progress)
        self.wizard_back = QPushButton("‹ Back")
        self.wizard_back.setObjectName("androidAction")
        self.wizard_back.clicked.connect(self._wizard_previous)
        navigation.addWidget(self.wizard_back)
        self.wizard_next = QPushButton("Next ›")
        self.wizard_next.setObjectName("androidAction")
        self.wizard_next.clicked.connect(self._wizard_next_step)
        navigation.addWidget(self.wizard_next)
        layout.addLayout(navigation)
        return sheet

    def _wizard_previous(self) -> None:
        self._set_wizard_step(self._wizard_step - 1)

    def _wizard_next_step(self) -> None:
        if self._wizard_step == 0 and not self.cards():
            return
        self._set_wizard_step(self._wizard_step + 1)

    def _set_wizard_step(self, step: int, *, focus: bool = True) -> None:
        if not hasattr(self, "wizard_stack"):
            return
        if step > 0 and not self.cards():
            step = 0
        step = max(0, min(step, self.wizard_stack.count() - 1))
        self._wizard_step = step
        self.wizard_stack.setCurrentIndex(step)

        steps = (
            ("Spreadsheet", "Select your spreadsheet file to create the cards."),
            ("Style", "Choose the visual style used for every card."),
            ("Music", "Choose a soundtrack, or continue without music."),
            ("Video length", "Use automatic timing or enter a target duration."),
            ("Ready to export", "Export now, or open Manual editor for further changes."),
        )
        self.wizard_heading.setText(steps[step][0])
        self.wizard_detail.setText(steps[step][1])
        self.wizard_progress.setText(f"STEP {step + 1} OF {len(steps)}")

        trail: list[str] = []
        for index, (label, _detail) in enumerate(steps):
            if index < step:
                marker, color, weight = "✓", "#8ed39c", "700"
            elif index == step:
                marker, color, weight = str(index + 1), "#ffffff", "900"
            else:
                marker, color, weight = str(index + 1), "#777780", "600"
            trail.append(
                f'<span style="color:{color}; font-weight:{weight};">{marker} {label}</span>'
            )
        self.wizard_trail.setText("&nbsp;&nbsp; › &nbsp;&nbsp;".join(trail))

        self.wizard_back.setEnabled(step > 0)
        self.wizard_next.setVisible(step < len(steps) - 1)
        self.wizard_next.setEnabled(step > 0 or bool(self.cards()))
        if step == 2 and not self.soundtrack_table.rowCount():
            self.wizard_next.setText("Skip ›")
        elif step == 3:
            self.wizard_next.setText("Review ›")
        else:
            self.wizard_next.setText("Next ›")

        if self.monitor_hint is not None and not self._fix_visible:
            self.monitor_hint.setText(
                "Preview · choose Export or Manual editor"
                if step == len(steps) - 1
                else f"Preview · video setup step {step + 1} of {len(steps)}"
            )
        if focus:
            current_actions = (
                self.insert_data_button,
                self.easy_style_button,
                self.easy_music_button,
                self.easy_timing_button,
                self.easy_export_button,
            )
            current_actions[step].setFocus(Qt.FocusReason.OtherFocusReason)
            if step == len(steps) - 1:
                self.statusBar().showMessage(
                    "Video setup complete · Export MP4 or open Manual editor", 5000
                )
            else:
                self.statusBar().showMessage(
                    f"Video setup · Step {step + 1} of {len(steps)} · {steps[step][0]}",
                    4000,
                )

    def _prepare_android_defaults(self) -> None:
        illustrated = self.model_combo.findData(MODEL_ILLUSTRATED)
        if illustrated >= 0:
            self.model_combo.setCurrentIndex(illustrated)
        self.default_visible.setChecked(True)

        # Setup starts empty and keeps every later step behind spreadsheet selection.
        headers = [header for header, _role in MODEL_SCHEMAS[MODEL_ILLUSTRATED]]
        self.table.set_data(SpreadsheetData(headers, []))
        self._auto_map_fields()
        self.position_seconds = 0.0
        self.update_preview()
        self.insert_data_button.setFocus(Qt.FocusReason.OtherFocusReason)

    def _connect_android_status(self) -> None:
        self.table.data_edited.connect(self._update_android_summary)
        self.soundtrack_table.data_edited.connect(self._update_android_summary)
        self.master_volume.valueChanged.connect(self._update_android_summary)
        self.auto_length.toggled.connect(self._update_android_summary)
        self.custom_length.editingFinished.connect(self._update_android_summary)
        self.model_combo.currentIndexChanged.connect(self._update_android_summary)

    def _set_fix_visible(self, visible: bool) -> None:
        self._fix_visible = visible
        self.fix_panel.setVisible(visible)
        self.android_sheet.setVisible(not visible)
        self.fix_button.setText("Close editor" if visible else "Manual editor")
        self.subtitle_label.setText("MANUAL" if visible else "CREATE")
        if self.monitor_hint is not None:
            self.monitor_hint.setText(
                "Click a field in the preview to edit it"
                if visible
                else "Preview · open Manual editor for detailed changes"
            )
        if visible:
            self.statusBar().showMessage(
                "Manual editor · edit cards in the table or click fields in the Program Monitor",
                5000,
            )
        else:
            self.tabs.setCurrentIndex(0)
            self.statusBar().showMessage(
                f"Video setup · returned to step {self._wizard_step + 1}", 3500
            )
            self._set_wizard_step(self._wizard_step, focus=False)
        self._apply_responsive_layout()

    def _preview_field_clicked(self, normalized_x: float, normalized_y: float) -> None:
        if not self._fix_visible:
            self.statusBar().showMessage(
                "Open Manual editor to edit fields directly in the Program Monitor.", 4000
            )
            return
        super()._preview_field_clicked(normalized_x, normalized_y)

    def _choose_spreadsheet_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Select comparison spreadsheet",
            "",
            "Spreadsheet data (*.csv *.tsv *.txt *.xlsx)",
        )
        if not path:
            return
        try:
            data, warnings = load_table_file(path)
            self._apply_inserted_data(data, warnings)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            show_error(
                self,
                "Could not open that spreadsheet.",
                "Check that the file is readable and try again.",
                str(exc),
            )

    def _open_insert_data_sheet(self) -> None:
        current = self.spreadsheet_data()
        existing = current.row_count > 0
        initial_text = _table_as_text(current) if existing else QApplication.clipboard().text()
        dialog = InsertDataDialog(initial_text, self, existing=existing)
        if dialog.exec() != QDialog.DialogCode.Accepted or dialog.selected_data is None:
            return
        self._apply_inserted_data(dialog.selected_data, dialog.warnings, advance=False)

    def _apply_inserted_data(
        self,
        data: SpreadsheetData,
        warnings: list[str] | None = None,
        *,
        advance: bool = True,
    ) -> None:
        try:
            self.table.set_data(data)
            self._auto_map_fields()
            self._apply_model_schema(self.model_combo.currentData() or MODEL_ILLUSTRATED)
            cards = self.cards()
            if cards:
                visible = self.project_settings().effective_visible_cards()
                self.position_seconds = self._editing_time_for_card(
                    min(len(cards), visible) - 1
                )
            else:
                self.position_seconds = 0.0
            self.update_preview()
            self._update_android_summary()
            if advance:
                self._set_wizard_step(1)
                self.statusBar().showMessage(
                    f"Imported {data.row_count} cards · setup step 2: choose a style",
                    6000,
                )
            else:
                self.statusBar().showMessage(
                    f"Updated {data.row_count} cards in Manual editor", 5000
                )
            if warnings:
                box = QMessageBox(self)
                box.setIcon(QMessageBox.Icon.Warning)
                box.setWindowTitle("Data imported with warnings")
                box.setText("The cards were imported, with readable warnings.")
                box.setDetailedText("\n".join(warnings))
                box.exec()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def import_xlsx(self) -> None:
        """Compatibility entry point; desktop and Android now share one file picker."""
        self._choose_spreadsheet_file()

    def _open_style_sheet(self) -> None:
        current = self.model_combo.currentData() or MODEL_ILLUSTRATED
        dialog = StyleDialog(current, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        self._apply_style(dialog.selected_model)

    def _apply_style(self, model_id: str) -> None:
        index = self.model_combo.findData(model_id)
        if index < 0:
            return
        self.model_combo.setCurrentIndex(index)
        cards = self.cards()
        if cards:
            visible = self.project_settings().effective_visible_cards()
            self.position_seconds = self._editing_time_for_card(
                min(len(cards), visible) - 1
            )
        self.update_preview()
        self._update_android_summary()
        self._set_wizard_step(2)
        self.statusBar().showMessage(
            f"Style selected · {MODEL_INFO[model_id][0]} · setup step 3: choose music or skip",
            5000,
        )

    def _choose_easy_music(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose soundtrack",
            "",
            "Audio (*.mp3 *.wav *.m4a *.aac *.flac *.ogg *.opus *.wma);;All files (*)",
        )
        if not path:
            return
        self.soundtrack_table.set_tracks(
            [AudioTrack(path=str(Path(path).resolve()), loop=True, fade_out=0.8)]
        )
        self.statusBar().showMessage(
            f"Music selected · {Path(path).name} · setup step 4: set video length",
            5000,
        )
        self._update_android_summary()
        self._set_wizard_step(3)

    def _open_timing_sheet(self) -> None:
        current = self.custom_length.text().strip()
        if not current:
            current = format_duration(self.project_settings().auto_duration(len(self.cards())))
        dialog = TimingDialog(self.auto_length.isChecked(), current, self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        self.auto_length.setChecked(dialog.automatic.isChecked())
        if not dialog.automatic.isChecked():
            self.custom_length.setText(dialog.target.text().strip())
        self._custom_duration_changed()
        self._update_android_summary()
        self._set_wizard_step(4)

    def open_project(self) -> None:
        super().open_project()
        self._update_android_summary()
        if self.cards():
            self._set_wizard_step(4)

    def _update_android_summary(self, *_args) -> None:
        if not hasattr(self, "android_summary") or not hasattr(self, "table"):
            return
        cards = self.cards()
        card_count = len(cards)
        track_count = self.soundtrack_table.rowCount()
        card_text = f"{card_count} card" if card_count == 1 else f"{card_count} cards"
        style_text = MODEL_INFO[self.model_combo.currentData() or MODEL_ILLUSTRATED][0]
        music_text = "No music" if track_count == 0 else "Music ready"
        self.android_summary.setText(f"{card_text} · {style_text} · {music_text}")

        if track_count:
            item = self.soundtrack_table.item(0, 0)
            name = Path(item.text()).name if item and item.text().strip() else "Music ready"
            self._music_display_name = name
            self.easy_music_button.setToolTip(name)
        else:
            self._music_display_name = ""
            self.easy_music_button.setToolTip(
                "Optional · add one soundtrack that loops automatically"
            )
        ready = card_count > 0
        self.easy_export_button.setEnabled(ready)
        self.easy_export_button.setToolTip(
            "Export the finished MP4" if ready else "Select a spreadsheet first"
        )
        self.easy_timing_button.setEnabled(ready)
        self._refresh_style_button_text()
        self._refresh_music_button_text()
        self._refresh_duration_labels()
        self._set_wizard_step(self._wizard_step, focus=False)

    def _refresh_style_button_text(self) -> None:
        if not hasattr(self, "easy_style_button"):
            return
        model_id = self.model_combo.currentData() or MODEL_ILLUSTRATED
        name, description = MODEL_INFO[model_id]
        compact = getattr(self, "_compact_mode", False)
        self.easy_style_button.setText(
            "CHOOSE STYLE" if compact else f"CHOOSE STYLE · {name.upper()}"
        )
        self.easy_style_button.setToolTip(description)

    def _refresh_music_button_text(self) -> None:
        if not hasattr(self, "easy_music_button"):
            return
        name = getattr(self, "_music_display_name", "")
        if not name:
            compact = getattr(self, "_compact_mode", False)
            self.easy_music_button.setText(
                "CHOOSE MUSIC" if compact else "CHOOSE MUSIC (OPTIONAL)"
            )
            return
        available = max(52, self.easy_music_button.contentsRect().width() - 14)
        self.easy_music_button.setText(
            QFontMetrics(self.easy_music_button.font()).elidedText(
                name,
                Qt.TextElideMode.ElideMiddle,
                available,
            )
        )

    def project_settings(self):
        return with_easy_timing(super().project_settings())

    def update_preview(self) -> None:
        super().update_preview()
        if hasattr(self, "preview") and hasattr(self, "table") and not self.cards():
            self.preview.set_empty_message(
                "Select a spreadsheet to create your first comparison"
            )

    def _refresh_duration_labels(self) -> None:
        if not hasattr(self, "table"):
            return
        cards = self.cards()
        if not cards:
            detail = "Select a spreadsheet to begin · music and custom length are optional"
            self.duration_info.setText(detail)
            if hasattr(self, "android_duration"):
                self.android_duration.setText(detail)
            if hasattr(self, "easy_timing_button"):
                self.easy_timing_button.setText(
                    "SET LENGTH"
                    if getattr(self, "_compact_mode", False)
                    else "SET LENGTH · AUTOMATIC"
                )
            self.time_label.setText("00:00 / 00:00")
            return
        try:
            settings = self.project_settings()
            duration = settings.duration(len(cards))
            _intro, scroll_steps, _automatic_scroll, _fixed_tail = timeline_parts(
                settings, len(cards)
            )
            per_card = settings.seconds_per_card(len(cards))
            if self.auto_length.isChecked():
                detail = f"Ready to export · Automatic length · {format_duration(duration)}"
                button = (
                    "SET LENGTH"
                    if getattr(self, "_compact_mode", False)
                    else "SET LENGTH · AUTOMATIC"
                )
            elif scroll_steps:
                detail = (
                    f"Target {format_duration(duration)} · cards scroll every {per_card:.2f}s · "
                    "entrances stay normal"
                )
                button = f"SET LENGTH · {format_duration(duration)}"
            else:
                detail = (
                    f"{format_duration(duration)} · all cards fit, so no horizontal scroll needs retiming"
                )
                button = f"SET LENGTH · {format_duration(duration)}"
            self.duration_info.setText(detail)
            if hasattr(self, "android_duration"):
                self.android_duration.setText(detail)
            if hasattr(self, "easy_timing_button"):
                self.easy_timing_button.setText(button)
            self.time_label.setText(
                f"{format_duration(self.position_seconds)} / {format_duration(duration)}"
            )
        except FriendlyError:
            self.duration_info.setText("Enter a valid target length")
            if hasattr(self, "android_duration"):
                self.android_duration.setText("Enter a valid target such as 01:30")

    def _editing_time_for_card(self, card_index: int) -> float:
        cards = self.cards()
        if not cards:
            return 0.0
        settings = self.project_settings()
        visible = settings.effective_visible_cards()
        card_index = max(0, min(card_index, len(cards) - 1))
        intro = min(len(cards), visible) * REFERENCE_REVEAL_SECONDS
        if card_index < visible:
            return min(settings.duration(len(cards)), intro)
        scroll_step = card_index - visible + 1
        return min(
            settings.duration(len(cards)),
            intro + scroll_step * settings.seconds_per_card(len(cards)),
        )

    def _apply_responsive_layout(self) -> None:
        if not hasattr(self, "root_layout"):
            return
        compact = self.width() < 1120 or self.height() < 760
        fix_only = self._fix_visible and self.height() < 680
        self._compact_mode = compact
        self.root_layout.setContentsMargins(5, 5, 5, 5)
        self.root_layout.setSpacing(5)
        self.title_label.setStyleSheet(
            "font-size:13px; font-weight:850;" if compact
            else "font-size:14px; font-weight:850;"
        )
        self.subtitle_label.setVisible(self.width() >= 900)
        self.open_button.setText("Open" if compact else "Open project")
        self.save_button.setText("Save" if compact else "Save project")
        self.monitor_panel.setVisible(not fix_only)
        show_card_guidance = not (self._fix_visible and compact)
        self.cards_helper.setVisible(show_card_guidance)
        self.field_guide.setVisible(show_card_guidance)
        if self._fix_visible:
            self.preview.setMinimumSize(320, 180)
        else:
            self.preview.setMinimumSize(480, 270)
        if fix_only:
            self.fix_panel.setMaximumHeight(16777215)
        elif self._fix_visible:
            self.fix_panel.setMaximumHeight(320 if compact else 360)
        else:
            self.fix_panel.setMaximumHeight(270 if compact else 360)

        if hasattr(self, "insert_data_button"):
            self.insert_data_button.setText(
                "SPREADSHEET" if compact else "SELECT SPREADSHEET"
            )
            self.easy_export_button.setText("EXPORT MP4")
            if not self._fix_visible:
                self.fix_button.setText("Manual editor")
            self._refresh_style_button_text()
            self._refresh_music_button_text()
            self._refresh_duration_labels()
            self._set_wizard_step(self._wizard_step, focus=False)
