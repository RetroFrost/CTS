from __future__ import annotations

import time
from pathlib import Path

from PySide6.QtCore import QSignalBlocker, Qt, QTimer
from PySide6.QtWidgets import (
    QAbstractItemView,
    QApplication,
    QDialog,
    QFileDialog,
    QLabel,
    QPushButton,
    QVBoxLayout,
)

from .data import FriendlyError
from .easy_ui import EASY_STYLE, EasyMainWindow, InsertDataDialog
from .exporter import ExportWorker
from .shared_contract import (
    MODEL_ID,
    MODEL_LABEL,
    VISIBLE_CARDS,
    editing_time_for_card,
)
from .ui import ExportProgressDialog, PreviewWidget, show_error


class CsvTextDialog(InsertDataDialog):
    """Focused setup dialog for pasting or typing CSV text directly."""

    def __init__(self, clipboard_text: str = "", parent=None) -> None:
        super().__init__(clipboard_text, parent, existing=False)
        self.setWindowTitle("CSV text")
        self.heading.setText("PASTE CSV TEXT")
        self.editor.setPlaceholderText(
            "Badge Value,Badge Label,Title,Description,Artwork\n"
            "84,PERCENT,Example card,Optional description,https://example.com/image.png"
        )

        for label in self.findChildren(QLabel):
            if label is self.heading:
                continue
            if label.text().startswith("Paste a table below"):
                label.setText(
                    "Paste or type CSV text below. The first row contains the field names, "
                    "and every following row becomes one card. CTS detects the cards as you type."
                )
                break

        # File selection belongs outside the requested CSV-text workflow.
        for button in self.findChildren(QPushButton):
            if "Import CSV" in button.text():
                button.hide()

        self._refresh_detection()
        self.editor.setFocus(Qt.FocusReason.OtherFocusReason)

    def _refresh_detection(self) -> None:
        super()._refresh_detection()
        if not self.editor.toPlainText().strip():
            self.status.setText("Paste a CSV header row and at least one card row.")
            self.status.setStyleSheet("")


class ForegroundExportDialog(ExportProgressDialog):
    """Keep export visible and show the same frames CTS is currently rendering."""

    def __init__(self, worker: ExportWorker, cards, settings, parent=None) -> None:
        super().__init__(worker, parent)
        self.setWindowTitle("Exporting video — CTS")
        self.setWindowModality(Qt.WindowModality.ApplicationModal)
        self.setMinimumSize(760, 590)
        self.resize(860, 650)

        self._cards = list(cards)
        self._settings = settings
        self._main_window = parent
        self._last_preview_update = 0.0

        self.export_preview = PreviewWidget(self)
        self.export_preview.setMinimumSize(640, 360)
        self.export_preview.setCursor(Qt.CursorShape.ArrowCursor)
        self.export_preview.setToolTip("Live view of the frame CTS is exporting")
        self.export_preview.set_empty_message("Preparing the first exported frame…")

        layout = self.layout()
        if isinstance(layout, QVBoxLayout):
            layout.insertWidget(2, self.export_preview, 1)

        self.detail_label.setText(
            "CTS stays in the foreground and displays the frames being written to the MP4."
        )
        worker.progress_changed.connect(self._render_progress_preview)
        worker.stage_changed.connect(self._render_export_stage)
        QTimer.singleShot(0, lambda: self._render_preview_at(0.0, force=True))

    def _render_export_stage(self, stage: str, _detail: str) -> None:
        if stage in {"Encoding", "Soundtrack", "Finalizing"}:
            duration = self._settings.duration(len(self._cards))
            final_time = max(0.0, duration - 1.0 / max(1, self._settings.fps))
            self._render_preview_at(final_time, force=True)

    def _render_progress_preview(self, current: int, total: int, _eta: float) -> None:
        if self.stage_label.text() != "Rendering":
            return
        frame_index = max(0, min(max(0, total - 1), current - 1))
        frame_time = frame_index / max(1, self._settings.fps)
        self._render_preview_at(frame_time, force=current >= total)

    def _render_preview_at(self, frame_time: float, *, force: bool = False) -> None:
        now = time.monotonic()
        if not force and now - self._last_preview_update < 0.35:
            return
        self._last_preview_update = now
        try:
            image = self._main_window.renderer.render(
                self._cards,
                frame_time,
                self._settings,
                size=(960, 540),
            )
        except Exception:
            # A live preview must never interrupt the actual worker export.
            return
        self.export_preview.set_pil_image(image)
        if hasattr(self._main_window, "preview"):
            self._main_window.preview.set_pil_image(image)


class CsvTextEasyMainWindow(EasyMainWindow):
    """CTS setup wizard backed by the shared Android-desktop contract."""

    def __init__(self) -> None:
        super().__init__()
        self._install_exact_card_deletion()
        self._lock_shared_design()
        self._apply_csv_text_copy()
        self.statusBar().showMessage("Video setup · Step 1 of 5 · Paste CSV text")

    def _install_exact_card_deletion(self) -> None:
        """Make card deletion row-based so blank/untitled cards stay distinguishable."""
        self._last_card_row = self.table.currentRow()
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.currentCellChanged.connect(self._remember_card_row)

        # The original button was connected directly to SpreadsheetTable's generic
        # removal method while the table still selected individual cells. Reconnect it
        # to a card-aware removal path that captures the exact row before focus changes.
        for button in self.fix_panel.findChildren(QPushButton):
            if button.text().strip() != "Delete":
                continue
            try:
                button.clicked.disconnect()
            except (RuntimeError, TypeError):
                pass
            button.clicked.connect(self._delete_selected_cards)

    def _remember_card_row(
        self,
        current_row: int,
        _current_column: int,
        _previous_row: int,
        _previous_column: int,
    ) -> None:
        if 0 <= current_row < self.table.rowCount():
            self._last_card_row = current_row

    def _delete_selected_cards(self) -> None:
        inline_row = -1
        if hasattr(self, "preview") and self.preview.is_inline_editing:
            inline_row = int(getattr(self.preview, "_editor_card", -1))

        selection_model = self.table.selectionModel()
        rows = {
            index.row()
            for index in selection_model.selectedRows()
            if 0 <= index.row() < self.table.rowCount()
        }
        if not rows:
            rows = {
                index.row()
                for index in self.table.selectedIndexes()
                if 0 <= index.row() < self.table.rowCount()
            }
        if inline_row >= 0 and not rows:
            rows = {inline_row}
        if not rows:
            current_row = self.table.currentRow()
            if 0 <= current_row < self.table.rowCount():
                rows = {current_row}
            elif 0 <= self._last_card_row < self.table.rowCount():
                rows = {self._last_card_row}
        if not rows:
            return

        if hasattr(self, "preview") and self.preview.is_inline_editing:
            self.preview.cancel_inline_edit()

        ordered_rows = sorted(rows, reverse=True)
        blocker = QSignalBlocker(self.table)
        for row in ordered_rows:
            self.table.removeRow(row)
        del blocker

        remaining = self.table.rowCount()
        if remaining:
            next_row = min(min(rows), remaining - 1)
            self.table.setCurrentCell(next_row, 0)
            self._last_card_row = next_row
        else:
            self._last_card_row = -1
        self.table.data_edited.emit()
        card_word = "card" if len(rows) == 1 else "cards"
        self.statusBar().showMessage(f"Deleted {len(rows)} {card_word}", 3500)

    def _lock_shared_design(self) -> None:
        """Prevent the desktop UI from drifting back to legacy-only styles."""
        if hasattr(self, "model_combo"):
            index = self.model_combo.findData(MODEL_ID)
            if index >= 0:
                self.model_combo.setCurrentIndex(index)
            self.model_combo.setEnabled(False)
            self.model_combo.setToolTip(
                "This design is shared with CTS Android. Edit shared/cts_contract.json "
                "to change both platforms."
            )
        if hasattr(self, "default_visible"):
            self.default_visible.setChecked(True)
            self.default_visible.setEnabled(False)
        if hasattr(self, "easy_style_button"):
            self.easy_style_button.setText(f"{MODEL_LABEL.upper()} · ANDROID SYNC")
            self.easy_style_button.setToolTip(
                "Desktop and Android use this same generated layout and timing contract"
            )

    def _choose_spreadsheet_file(self) -> None:
        """Compatibility method used by the existing first-step button connection."""
        dialog = CsvTextDialog(QApplication.clipboard().text(), self)
        if dialog.exec() != QDialog.DialogCode.Accepted or dialog.selected_data is None:
            return
        self._apply_inserted_data(dialog.selected_data, dialog.warnings, advance=True)

    def _open_style_sheet(self) -> None:
        """The style step confirms the shared template instead of offering legacy models."""
        self._lock_shared_design()
        self.statusBar().showMessage(
            f"{MODEL_LABEL} is synchronized with CTS Android · setup step 3: choose music",
            5000,
        )
        self._set_wizard_step(2)

    def _refresh_style_button_text(self) -> None:
        if not hasattr(self, "easy_style_button"):
            return
        compact = getattr(self, "_compact_mode", False)
        self.easy_style_button.setText(
            "ANDROID SYNC" if compact else f"{MODEL_LABEL.upper()} · ANDROID SYNC"
        )
        self.easy_style_button.setToolTip(
            "The canonical CTS design is generated for both Android and desktop"
        )

    def project_settings(self):
        settings = super().project_settings()
        settings.model_id = MODEL_ID
        settings.visible_cards = VISIBLE_CARDS
        settings.hexagons_bounce = True
        return settings

    def _editing_time_for_card(self, card_index: int) -> float:
        cards = self.cards()
        if not cards:
            return 0.0
        settings = self.project_settings()
        return editing_time_for_card(
            len(cards),
            card_index,
            getattr(settings, "custom_duration", None),
        )

    def _set_wizard_step(self, step: int, *, focus: bool = True) -> None:
        super()._set_wizard_step(step, focus=focus)
        self._lock_shared_design()
        self._apply_csv_text_copy()
        if focus and getattr(self, "_wizard_step", 0) == 0:
            self.statusBar().showMessage("Video setup · Step 1 of 5 · Paste CSV text", 4000)

    def _apply_responsive_layout(self) -> None:
        super()._apply_responsive_layout()
        self._lock_shared_design()
        self._apply_csv_text_copy()

    def _update_android_summary(self, *_args) -> None:
        super()._update_android_summary(*_args)
        self._lock_shared_design()
        self._apply_csv_text_copy()

    def _refresh_duration_labels(self) -> None:
        super()._refresh_duration_labels()
        if hasattr(self, "table") and not self.cards():
            detail = "Paste CSV text to begin · music and custom length are optional"
            self.duration_info.setText(detail)
            if hasattr(self, "android_duration"):
                self.android_duration.setText(detail)

    def update_preview(self) -> None:
        super().update_preview()
        if hasattr(self, "preview") and hasattr(self, "table") and not self.cards():
            self.preview.set_empty_message("Paste CSV text to create your first comparison")

    def export_video(self) -> None:
        cards = self.cards()
        if not cards:
            show_error(
                self,
                "There are no cards to export.",
                "Add at least one spreadsheet row; its cells may be empty.",
            )
            return
        try:
            settings = self.project_settings()
            tracks = self.soundtrack_table.tracks()
            for track in tracks:
                track.validate()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
            return

        path, _ = QFileDialog.getSaveFileName(
            self,
            "Export MP4",
            "comparison-video.mp4",
            "MP4 video (*.mp4)",
        )
        if not path:
            return
        if not path.lower().endswith(".mp4"):
            path += ".mp4"

        self.pause_playback()
        self._export_worker = ExportWorker(cards, settings, path, tracks, self)
        dialog = ForegroundExportDialog(self._export_worker, cards, settings, self)
        dialog.export_finished.connect(self._export_success)
        dialog.start()
        self.update_preview()

    def _apply_csv_text_copy(self) -> None:
        if hasattr(self, "insert_data_button"):
            self.insert_data_button.setText("PASTE CSV TEXT")
            self.insert_data_button.setToolTip(
                "Paste or type comma-separated text; the first row is the field names"
            )

        if hasattr(self, "wizard_heading") and getattr(self, "_wizard_step", 0) == 0:
            self.wizard_heading.setText("CSV text")
            self.wizard_detail.setText(
                "Paste or type comma-separated rows to create the cards."
            )

        if hasattr(self, "wizard_trail"):
            self.wizard_trail.setText(
                self.wizard_trail.text().replace("Spreadsheet", "CSV text")
            )

        if hasattr(self, "cards_helper"):
            self.cards_helper.setText(
                "Edit individual cards here, or use Paste / edit table for a faster bulk "
                "change. The normal workflow starts with CSV text."
            )

        if hasattr(self, "easy_export_button") and not self.cards():
            self.easy_export_button.setToolTip("Paste CSV text first")


__all__ = [
    "EASY_STYLE",
    "CsvTextDialog",
    "ForegroundExportDialog",
    "CsvTextEasyMainWindow",
]
