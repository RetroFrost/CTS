from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QSignalBlocker, Qt
from PySide6.QtWidgets import (
    QApplication,
    QFileDialog,
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPushButton,
    QScrollArea,
    QTabWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from .premiere_workspace import PremiereWorkspaceWindow


class ConvenientPremiereWindow(PremiereWorkspaceWindow):
    """Premiere-shaped workspace with the direct mobile CTS editing workflow."""

    def __init__(self) -> None:
        self._quick_loading = False
        self._card_buttons: list[QPushButton] = []
        super().__init__()
        self.statusBar().showMessage("Ready — Premiere workspace with mobile quick editing")

    def _build_application_bar(self) -> QWidget:
        bar = super()._build_application_bar()
        layout = bar.layout()

        self.quick_paste_button = QPushButton("Paste Data")
        self.quick_paste_button.setObjectName("quickAction")
        self.quick_paste_button.setToolTip("Paste a complete TSV/CSV table from the clipboard")
        self.quick_paste_button.clicked.connect(self.paste_data)

        self.quick_import_button = QPushButton("Import")
        self.quick_import_button.setObjectName("quickAction")
        self.quick_import_button.setToolTip("Import CSV or XLSX data")
        self.quick_import_button.clicked.connect(self.import_data)

        self.batch_artwork_button = QPushButton("Batch Artwork")
        self.batch_artwork_button.setObjectName("quickAction")
        self.batch_artwork_button.setToolTip(
            "Choose several images and assign them in order from the selected card"
        )
        self.batch_artwork_button.clicked.connect(self.choose_batch_artwork)

        # Insert before the stretch and the Open/Save/Export group.
        layout.insertWidget(3, self.quick_paste_button)
        layout.insertWidget(4, self.quick_import_button)
        layout.insertWidget(5, self.batch_artwork_button)
        return bar

    def _build_project_panel(self) -> QWidget:
        content = QWidget()
        layout = QVBoxLayout(content)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        chip_frame = QFrame()
        chip_frame.setObjectName("cardStrip")
        chip_layout = QVBoxLayout(chip_frame)
        chip_layout.setContentsMargins(6, 5, 6, 5)
        chip_layout.setSpacing(4)
        chip_layout.addWidget(QLabel("CARDS"))

        self.card_scroll = QScrollArea()
        self.card_scroll.setWidgetResizable(True)
        self.card_scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAsNeeded)
        self.card_scroll.setVerticalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.card_scroll.setFixedHeight(48)
        self.card_scroll.setFrameShape(QFrame.Shape.NoFrame)
        self.card_strip_widget = QWidget()
        self.card_strip_layout = QHBoxLayout(self.card_strip_widget)
        self.card_strip_layout.setContentsMargins(0, 0, 0, 0)
        self.card_strip_layout.setSpacing(4)
        self.card_strip_layout.addStretch()
        self.card_scroll.setWidget(self.card_strip_widget)
        chip_layout.addWidget(self.card_scroll)
        layout.addWidget(chip_frame)

        self.project_tabs = QTabWidget()
        self.project_tabs.addTab(self._build_data_tab(), "Project: CTS")
        media = QWidget()
        media_layout = QVBoxLayout(media)
        media_layout.addWidget(
            QLabel("Artwork and soundtrack files remain linked to their card rows and project.")
        )
        media_layout.addStretch()
        self.project_tabs.addTab(media, "Media Browser")
        self.project_tabs.setMinimumWidth(320)
        layout.addWidget(self.project_tabs, 1)
        return self._wrap_panel("PROJECT", content)

    def _build_inspector_panel(self) -> QWidget:
        self.tabs = QTabWidget()
        self.tabs.addTab(self._build_quick_edit_tab(), "Edit")
        self.tabs.addTab(self._build_style_tab(), "Style")
        self.tabs.addTab(self._build_audio_tab(), "Audio")
        self.tabs.addTab(self._build_export_tab(), "Export")
        self.tabs.setMinimumWidth(310)
        return self._wrap_panel("QUICK CONTROLS", self.tabs)

    def _build_quick_edit_tab(self) -> QWidget:
        page = QWidget()
        layout = QVBoxLayout(page)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(7)

        self.quick_card_label = QLabel("Card 1")
        self.quick_card_label.setStyleSheet("font-size:14px; font-weight:700;")
        layout.addWidget(self.quick_card_label)

        value_label_grid = QGridLayout()
        value_label_grid.setHorizontalSpacing(6)
        value_label_grid.addWidget(QLabel("Value"), 0, 0)
        value_label_grid.addWidget(QLabel("Label"), 0, 1)
        self.quick_value = QLineEdit()
        self.quick_label = QLineEdit()
        value_label_grid.addWidget(self.quick_value, 1, 0)
        value_label_grid.addWidget(self.quick_label, 1, 1)
        layout.addLayout(value_label_grid)

        layout.addWidget(QLabel("Title"))
        self.quick_title = QLineEdit()
        layout.addWidget(self.quick_title)

        layout.addWidget(QLabel("Description"))
        self.quick_description = QTextEdit()
        self.quick_description.setAcceptRichText(False)
        self.quick_description.setMinimumHeight(86)
        layout.addWidget(self.quick_description)

        layout.addWidget(QLabel("Artwork / image URL"))
        self.quick_image = QLineEdit()
        self.quick_image.setPlaceholderText("Local path, raw image URL, or Google image URL")
        layout.addWidget(self.quick_image)

        image_buttons = QGridLayout()
        choose = QPushButton("Choose artwork")
        paste = QPushButton("Paste artwork")
        batch = QPushButton("Choose multiple")
        clear = QPushButton("Clear")
        choose.clicked.connect(self.choose_selected_image)
        paste.clicked.connect(self.paste_selected_artwork)
        batch.clicked.connect(self.choose_batch_artwork)
        clear.clicked.connect(self.clear_selected_artwork)
        image_buttons.addWidget(choose, 0, 0)
        image_buttons.addWidget(paste, 0, 1)
        image_buttons.addWidget(batch, 1, 0)
        image_buttons.addWidget(clear, 1, 1)
        layout.addLayout(image_buttons)

        self.quick_value.editingFinished.connect(self._commit_quick_editor)
        self.quick_label.editingFinished.connect(self._commit_quick_editor)
        self.quick_title.editingFinished.connect(self._commit_quick_editor)
        self.quick_description.textChanged.connect(self._commit_quick_editor)
        self.quick_image.editingFinished.connect(self._commit_quick_editor)
        layout.addStretch()
        return page

    def _selected_row(self) -> int:
        row = self.table.currentRow()
        if row < 0 and self.table.rowCount():
            row = 0
            self.table.selectRow(row)
        return row

    def _set_table_value(self, row: int, column: int, value: str) -> None:
        item = self.table.item(row, column)
        if item is None:
            item = QTableWidgetItem()
            self.table.setItem(row, column, item)
        item.setText(value)

    def _load_quick_editor(self) -> None:
        if not hasattr(self, "quick_value"):
            return
        row = self._selected_row()
        enabled = row >= 0
        fields = (
            self.quick_value,
            self.quick_label,
            self.quick_title,
            self.quick_description,
            self.quick_image,
        )
        for field in fields:
            field.setEnabled(enabled)
        if not enabled:
            return

        self._quick_loading = True
        try:
            values = [
                self.table.item(row, column).text() if self.table.item(row, column) else ""
                for column in range(5)
            ]
            with QSignalBlocker(self.quick_value):
                self.quick_value.setText(values[0])
            with QSignalBlocker(self.quick_label):
                self.quick_label.setText(values[1])
            with QSignalBlocker(self.quick_title):
                self.quick_title.setText(values[2])
            with QSignalBlocker(self.quick_description):
                self.quick_description.setPlainText(values[3])
            with QSignalBlocker(self.quick_image):
                self.quick_image.setText(values[4])
            self.quick_card_label.setText(f"Card {row + 1} of {self.table.rowCount()}")
        finally:
            self._quick_loading = False

    def _commit_quick_editor(self) -> None:
        if self._quick_loading or not hasattr(self, "quick_value"):
            return
        row = self._selected_row()
        if row < 0:
            return
        values = (
            self.quick_value.text().strip(),
            self.quick_label.text().strip(),
            self.quick_title.text().strip(),
            self.quick_description.toPlainText().strip(),
            self.quick_image.text().strip(),
        )
        with QSignalBlocker(self.table):
            for column, value in enumerate(values):
                self._set_table_value(row, column, value)
        self._sync_project_from_table()
        self.renderer.assets.clear()
        self._refresh_all()
        self._refresh_card_strip()

    def _refresh_card_strip(self) -> None:
        if not hasattr(self, "card_strip_layout"):
            return
        while self.card_strip_layout.count():
            item = self.card_strip_layout.takeAt(0)
            widget = item.widget()
            if widget is not None:
                widget.deleteLater()
        self._card_buttons.clear()
        selected = self.table.currentRow()
        for row in range(self.table.rowCount()):
            title_item = self.table.item(row, 2)
            title = title_item.text().strip() if title_item else ""
            button = QPushButton(f"{row + 1}  {title or 'Untitled'}")
            button.setObjectName("cardChipActive" if row == selected else "cardChip")
            button.setCheckable(True)
            button.setChecked(row == selected)
            button.clicked.connect(lambda _checked=False, index=row: self._select_card(index))
            self.card_strip_layout.addWidget(button)
            self._card_buttons.append(button)
        self.card_strip_layout.addStretch()

    def _select_card(self, row: int) -> None:
        if 0 <= row < self.table.rowCount():
            self.table.selectRow(row)
            self.tabs.setCurrentIndex(0)
            self._load_quick_editor()
            self._refresh_card_strip()

    def _selection_changed(self) -> None:
        super()._selection_changed()
        self._load_quick_editor()
        self._refresh_card_strip()

    def _load_project_into_ui(self) -> None:
        super()._load_project_into_ui()
        self._load_quick_editor()
        self._refresh_card_strip()

    def _table_changed(self, row: int, column: int) -> None:
        super()._table_changed(row, column)
        self._load_quick_editor()
        self._refresh_card_strip()

    def add_card(self) -> None:
        super().add_card()
        self._load_quick_editor()
        self._refresh_card_strip()

    def duplicate_card(self) -> None:
        super().duplicate_card()
        self._load_quick_editor()
        self._refresh_card_strip()

    def delete_card(self) -> None:
        super().delete_card()
        self._load_quick_editor()
        self._refresh_card_strip()

    def choose_selected_image(self) -> None:
        super().choose_selected_image()
        self._load_quick_editor()
        self._refresh_card_strip()

    def paste_selected_artwork(self) -> None:
        row = self._selected_row()
        if row < 0:
            return
        source = QApplication.clipboard().text().strip().strip('"')
        if not source:
            self._error("Copy an image URL or local image path first.")
            return
        with QSignalBlocker(self.table):
            self._set_table_value(row, 4, source)
        self._sync_project_from_table()
        self.renderer.assets.clear()
        self._load_quick_editor()
        self._refresh_all()
        self.statusBar().showMessage(f"Artwork pasted into card {row + 1}", 3500)

    def clear_selected_artwork(self) -> None:
        row = self._selected_row()
        if row < 0:
            return
        with QSignalBlocker(self.table):
            self._set_table_value(row, 4, "")
        self._sync_project_from_table()
        self.renderer.assets.clear()
        self._load_quick_editor()
        self._refresh_all()

    def choose_batch_artwork(self) -> None:
        start = self._selected_row()
        if start < 0:
            return
        paths, _ = QFileDialog.getOpenFileNames(
            self,
            "Choose artwork in card order",
            str(Path.home()),
            "Images (*.png *.jpg *.jpeg *.webp *.bmp *.gif)",
        )
        if not paths:
            return
        available = max(0, self.table.rowCount() - start)
        assigned = min(len(paths), available)
        with QSignalBlocker(self.table):
            for offset, path in enumerate(paths[:assigned]):
                self._set_table_value(start + offset, 4, path)
        self._sync_project_from_table()
        self.renderer.assets.clear()
        self._load_quick_editor()
        self._refresh_all()
        self._refresh_card_strip()
        suffix = "" if assigned == len(paths) else f"; {len(paths) - assigned} extra files ignored"
        self.statusBar().showMessage(
            f"Assigned {assigned} artwork files from card {start + 1}{suffix}",
            5000,
        )
