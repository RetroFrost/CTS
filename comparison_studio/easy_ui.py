from __future__ import annotations

import csv
import io
from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtGui import QFontMetrics
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
    QTabWidget,
    QVBoxLayout,
    QWidget,
)

from .data import (
    MODEL_ILLUSTRATED,
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
from .ui import SpreadsheetTable, show_error


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
QLabel#androidTitle {
    color:#f5f5f7;
    font-size:13px;
    font-weight:900;
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

    def __init__(self, clipboard_text: str = "", parent=None) -> None:
        super().__init__(parent)
        self.setObjectName("androidDialog")
        self.setWindowTitle("Insert data")
        self.setModal(True)
        self.resize(720, 470)
        self.selected_data: SpreadsheetData | None = None
        self.warnings: list[str] = []

        layout = QVBoxLayout(self)
        layout.setContentsMargins(18, 16, 18, 16)
        layout.setSpacing(10)

        title = QLabel("CLICK TO INSERT DATA")
        title.setObjectName("androidTitle")
        layout.addWidget(title)

        description = QLabel(
            "Paste rows below, or use the one Import file action for CSV and XLSX. "
            "The first row becomes the field names and one remaining row becomes one card."
        )
        description.setWordWrap(True)
        description.setObjectName("androidSummary")
        layout.addWidget(description)

        self.editor = QPlainTextEdit()
        self.editor.setPlaceholderText(
            "Badge Value\tBadge Label\tTitle\tArtwork\n"
            "84\tPERCENT\tExample card\thttps://example.com/image.png"
        )
        if clipboard_text.strip() and ("\n" in clipboard_text or "\t" in clipboard_text):
            self.editor.setPlainText(clipboard_text)
        layout.addWidget(self.editor, 1)

        self.status = QLabel("Paste spreadsheet cells or import a file.")
        self.status.setObjectName("androidSummary")
        layout.addWidget(self.status)

        buttons = QHBoxLayout()
        import_button = QPushButton("Import CSV / XLSX")
        import_button.setObjectName("androidAction")
        import_button.clicked.connect(self._import_file)
        buttons.addWidget(import_button)
        buttons.addStretch()

        dialog_buttons = QDialogButtonBox()
        cancel = dialog_buttons.addButton(QDialogButtonBox.StandardButton.Cancel)
        use_data = dialog_buttons.addButton("Use data", QDialogButtonBox.ButtonRole.AcceptRole)
        use_data.setObjectName("androidPrimary")
        cancel.clicked.connect(self.reject)
        use_data.clicked.connect(self._accept_pasted)
        buttons.addWidget(dialog_buttons)
        layout.addLayout(buttons)

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
        data = _parse_text_table(self.editor.toPlainText())
        if not data.headers:
            show_error(
                self,
                "No table was found.",
                "Paste copied spreadsheet cells, CSV rows, or import a CSV/XLSX file.",
            )
            return
        self.selected_data = data
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


class EasyMainWindow(ReferenceIllustratedMainWindow):
    """Desktop CTS using the Android app's monitor-first, bottom-sheet workflow."""

    def __init__(self) -> None:
        self._fix_visible = False
        super().__init__()
        self.setWindowTitle("CTS — Comparison Timeline Studio")
        self.subtitle_label.setText("CREATE")
        self._prepare_android_defaults()
        self._connect_android_status()
        self._update_android_summary()
        self.statusBar().showMessage(
            "Ready · Insert data · Add music · Set length · Export"
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
        old_sequence_bar = self.monitor_panel.findChild(QFrame, "settingsBar")
        if old_sequence_bar is not None:
            old_sequence_bar.setVisible(False)
        monitor_header = self.monitor_panel.findChild(QFrame, "panelHeader")
        self.monitor_hint = (
            monitor_header.findChild(QLabel, "muted") if monitor_header is not None else None
        )
        if self.monitor_hint is not None:
            self.monitor_hint.setText("Preview · open Fix Something to edit")

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
        title = QLabel("FIX SOMETHING")
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
            "Use Click to Insert Data for paste, CSV, or XLSX. This sheet is only for fixing "
            "individual cards after CTS has created them."
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
        add_card = QPushButton("＋ Card")
        duplicate = QPushButton("Duplicate")
        delete = QPushButton("Delete")
        choose_image = QPushButton("Card image")
        image_strip = QPushButton("Image strip")
        add_card.clicked.connect(lambda: self.table.append_row())
        duplicate.clicked.connect(self._duplicate_rows)
        delete.clicked.connect(self.table.remove_selected_rows)
        choose_image.clicked.connect(self.choose_row_image)
        image_strip.clicked.connect(self.import_image_strip)
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
        layout.setSpacing(7)

        top = QHBoxLayout()
        title = QLabel("CREATE VIDEO")
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

        actions = QHBoxLayout()
        actions.setSpacing(7)

        self.insert_data_button = QPushButton("＋  CLICK TO INSERT DATA")
        self.insert_data_button.setObjectName("androidPrimary")
        self.insert_data_button.clicked.connect(self._open_insert_data_sheet)

        self.easy_music_button = QPushButton("Music")
        self.easy_music_button.setObjectName("androidAction")
        music_policy = self.easy_music_button.sizePolicy()
        music_policy.setHorizontalPolicy(QSizePolicy.Policy.Ignored)
        self.easy_music_button.setSizePolicy(music_policy)
        self.easy_music_button.clicked.connect(self._choose_easy_music)

        self.easy_timing_button = QPushButton("Video length")
        self.easy_timing_button.setObjectName("androidAction")
        self.easy_timing_button.clicked.connect(self._open_timing_sheet)

        self.easy_export_button = QPushButton("EXPORT VIDEO")
        self.easy_export_button.setObjectName("androidExport")
        self.easy_export_button.clicked.connect(self.export_video)
        self.export_button = self.easy_export_button

        self.fix_button = QPushButton("Fix Something")
        self.fix_button.setObjectName("fixAction")
        self.fix_button.setCheckable(True)
        self.fix_button.toggled.connect(self._set_fix_visible)

        actions.addWidget(self.insert_data_button, 2)
        actions.addWidget(self.easy_music_button, 1)
        actions.addWidget(self.easy_timing_button, 1)
        actions.addWidget(self.easy_export_button, 1)
        actions.addWidget(self.fix_button, 1)
        layout.addLayout(actions)

        self.android_duration = QLabel()
        self.android_duration.setObjectName("androidSummary")
        layout.addWidget(self.android_duration)
        return sheet

    def _prepare_android_defaults(self) -> None:
        illustrated = self.model_combo.findData(MODEL_ILLUSTRATED)
        if illustrated >= 0:
            self.model_combo.setCurrentIndex(illustrated)
        self.default_visible.setChecked(True)

        # Android starts with an empty project and lets the flagship action create the cards.
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

    def _set_fix_visible(self, visible: bool) -> None:
        self._fix_visible = visible
        self.fix_panel.setVisible(visible)
        self.android_sheet.setVisible(not visible)
        self.fix_button.setText("Done Fixing" if visible else "Fix Something")
        self.subtitle_label.setText("FIX" if visible else "CREATE")
        if self.monitor_hint is not None:
            self.monitor_hint.setText(
                "Click a field in the preview to edit it"
                if visible
                else "Preview · open Fix Something to edit"
            )
        if visible:
            self.statusBar().showMessage(
                "Fix mode · edit cards in the table or click fields in the Program Monitor",
                5000,
            )
        else:
            self.tabs.setCurrentIndex(0)
            self.statusBar().showMessage("Create mode · the editor is out of the way", 3500)
        self._apply_responsive_layout()

    def _preview_field_clicked(self, normalized_x: float, normalized_y: float) -> None:
        if not self._fix_visible:
            self.statusBar().showMessage(
                "Open Fix Something to edit fields directly in the Program Monitor.", 4000
            )
            return
        super()._preview_field_clicked(normalized_x, normalized_y)

    def _open_insert_data_sheet(self) -> None:
        dialog = InsertDataDialog(QApplication.clipboard().text(), self)
        if dialog.exec() != QDialog.DialogCode.Accepted or dialog.selected_data is None:
            return
        try:
            self.table.set_data(dialog.selected_data)
            self._auto_map_fields()
            self._apply_model_schema(self.model_combo.currentData() or MODEL_ILLUSTRATED)
            self.position_seconds = 0.0
            self.update_preview()
            self._update_android_summary()
            self.statusBar().showMessage(
                f"Inserted {dialog.selected_data.row_count} cards · ready for music and export",
                6000,
            )
            if dialog.warnings:
                box = QMessageBox(self)
                box.setIcon(QMessageBox.Icon.Warning)
                box.setWindowTitle("Data imported with warnings")
                box.setText("The cards were imported, with readable warnings.")
                box.setDetailedText("\n".join(dialog.warnings))
                box.exec()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)

    def import_xlsx(self) -> None:
        """Compatibility entry point; desktop and Android now share one file picker."""
        self._open_insert_data_sheet()

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
            f"Music selected · {Path(path).name} · loops to the video length", 5000
        )
        self._update_android_summary()

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

    def _update_android_summary(self, *_args) -> None:
        if not hasattr(self, "android_summary") or not hasattr(self, "table"):
            return
        card_count = len([card for card in self.cards() if not card.is_blank()])
        track_count = self.soundtrack_table.rowCount()
        card_text = f"{card_count} card" if card_count == 1 else f"{card_count} cards"
        music_text = "No music" if track_count == 0 else "Music ready"
        self.android_summary.setText(f"{card_text} · {music_text}")

        if track_count:
            item = self.soundtrack_table.item(0, 0)
            name = Path(item.text()).name if item and item.text().strip() else "Music ready"
            self._music_display_name = name
            self.easy_music_button.setToolTip(name)
        else:
            self._music_display_name = ""
            self.easy_music_button.setToolTip("Choose a soundtrack")
        self._refresh_music_button_text()
        self._refresh_duration_labels()

    def _refresh_music_button_text(self) -> None:
        if not hasattr(self, "easy_music_button"):
            return
        name = getattr(self, "_music_display_name", "")
        if not name:
            self.easy_music_button.setText("Music")
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
                "Click Insert Data to create your first comparison"
            )

    def _refresh_duration_labels(self) -> None:
        if not hasattr(self, "table"):
            return
        cards = self.cards()
        try:
            settings = self.project_settings()
            duration = settings.duration(len(cards))
            _intro, scroll_steps, _automatic_scroll, _fixed_tail = timeline_parts(
                settings, len(cards)
            )
            per_card = settings.seconds_per_card(len(cards))
            if self.auto_length.isChecked():
                detail = f"Automatic · {format_duration(duration)}"
                button = "Video length"
            elif scroll_steps:
                detail = (
                    f"Target {format_duration(duration)} · cards scroll every {per_card:.2f}s · "
                    "entrances stay normal"
                )
                button = format_duration(duration)
            else:
                detail = (
                    f"{format_duration(duration)} · all cards fit, so no horizontal scroll needs retiming"
                )
                button = format_duration(duration)
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
                "＋  INSERT DATA" if compact else "＋  CLICK TO INSERT DATA"
            )
            if self.auto_length.isChecked():
                self.easy_timing_button.setText("Length" if compact else "Video length")
            self.easy_export_button.setText("Export" if compact else "EXPORT VIDEO")
            if not self._fix_visible:
                self.fix_button.setText("Fix" if compact else "Fix Something")
            self._refresh_music_button_text()
