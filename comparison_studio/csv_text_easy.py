from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QDialog, QLabel, QPushButton

from .easy_ui import EASY_STYLE, EasyMainWindow, InsertDataDialog
from .shared_contract import (
    MODEL_ID,
    MODEL_LABEL,
    VISIBLE_CARDS,
    editing_time_for_card,
)


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
    """CTS setup wizard backed by the shared Android-desktop contract."""

    def __init__(self) -> None:
        super().__init__()
        self._lock_shared_design()
        self._apply_csv_text_copy()
        self.statusBar().showMessage("Video setup · Step 1 of 5 · Paste CSV text")

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


__all__ = ["EASY_STYLE", "CsvTextDialog", "CsvTextEasyMainWindow"]
