from __future__ import annotations

import sys
import traceback

from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .premiere_ui import PREMIERE_STYLE
from . import update_system as update_system_module

# Keep the update dialog's text-format enum available without adding another
# permanent UI dependency or duplicating the updater implementation.
update_system_module.Qt = Qt
UpdateAwareMainWindow = update_system_module.UpdateAwareMainWindow


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
    window = UpdateAwareMainWindow()
    window.show()
    return app.exec()
