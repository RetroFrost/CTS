from __future__ import annotations

import sys
import traceback

from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .premiere_ui import PREMIERE_STYLE
from .word_safe_fit import WordSafeMainWindow


def _exception_hook(exc_type, exc_value, exc_traceback) -> None:
    details = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    box = QMessageBox()
    box.setIcon(QMessageBox.Icon.Critical)
    box.setWindowTitle("Comparison Timeline Studio")
    box.setText("Something unexpected went wrong.")
    box.setInformativeText(
        "Your spreadsheet data has not been intentionally changed. "
        "You can copy the technical details below when reporting the problem."
    )
    box.setDetailedText(details)
    box.exec()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Comparison Timeline Studio")
    app.setApplicationVersion(__version__)
    app.setOrganizationName("Ethan Woods")
    app.setStyle("Fusion")
    app.setStyleSheet(PREMIERE_STYLE)
    sys.excepthook = _exception_hook
    window = WordSafeMainWindow()
    window.show()
    return app.exec()
