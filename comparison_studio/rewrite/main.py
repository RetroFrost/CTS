from __future__ import annotations

import sys
import traceback

from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .practical_workspace import PRACTICAL_STYLE, PracticalWorkspaceWindow


def _exception_hook(exc_type, exc_value, exc_traceback) -> None:
    details = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    box = QMessageBox()
    box.setIcon(QMessageBox.Icon.Critical)
    box.setWindowTitle("Comparison Timeline Studio")
    box.setText("CTS encountered an unexpected error.")
    box.setInformativeText(
        "The rewrite stopped the operation instead of silently corrupting the project."
    )
    box.setDetailedText(details)
    box.exec()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Comparison Timeline Studio")
    app.setApplicationVersion(__version__)
    app.setOrganizationName("RetroFrost")
    app.setStyle("Fusion")
    app.setStyleSheet(PRACTICAL_STYLE)
    sys.excepthook = _exception_hook
    window = PracticalWorkspaceWindow()
    window.show()
    return app.exec()
