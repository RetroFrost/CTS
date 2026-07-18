from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QDialog, QLabel, QPushButton

from .easy_ui import EASY_STYLE, EasyMainWindow, InsertDataDialog


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


class CsvTextEasyMainWindow(EasyMainWindow):
    """CTS setup wizard whose first step consumes CSV text instead of a file."""

    def __init__(self) -> None:
        super().__init__()
        self._apply_csv_text_copy()
        self.statusBar().showMessage("Video setup · Step 1 of 5 · Paste CSV text")

    def _choose_spreadsheet_file(self) -> None:
        """Compatibility method used by the existing first-step button connection."""
        dialog = CsvTextDialog(QApplication.clipboard().text(), self)
        if dialog.exec() != QDialog.DialogCode.Accepted or dialog.selected_data is None:
            return
        self._apply_inserted_data(dialog.selected_data, dialog.warnings, advance=True)

    def _set_wizard_step(self, step: int, *, focus: bool = True) -> None:
        super()._set_wizard_step(step, focus=focus)
        self._apply_csv_text_copy()
        if focus and getattr(self, "_wizard_step", 0) == 0:
            self.statusBar().showMessage("Video setup · Step 1 of 5 · Paste CSV text", 4000)

    def _apply_responsive_layout(self) -> None:
        super()._apply_responsive_layout()
        self._apply_csv_text_copy()

    def _update_android_summary(self, *_args) -> None:
        super()._update_android_summary(*_args)
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

    def _apply_csv_text_copy(self) -> None:
        if hasattr(self, "insert_data_button"):
            compact = getattr(self, "_compact_mode", False)
            self.insert_data_button.setText("CSV TEXT" if compact else "PASTE CSV TEXT")
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


__all__ = ["EASY_STYLE", "CsvTextDialog", "CsvTextEasyMainWindow"]
