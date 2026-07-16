from __future__ import annotations

import sys
import traceback

from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .mobile_convenience import ConvenientPremiereWindow
from .premiere_workspace import PREMIERE_STYLE


CONVENIENCE_STYLE = PREMIERE_STYLE + """
QPushButton#quickAction {
    background: #303030;
    border-color: #555555;
    font-weight: 700;
    padding-left: 11px;
    padding-right: 11px;
}
QPushButton#quickAction:hover { background: #3b3b3b; border-color: #6b9dca; }
QFrame#cardStrip {
    background: #1b1b1b;
    border-bottom: 1px solid #080808;
}
QPushButton#cardChip, QPushButton#cardChipActive {
    min-width: 92px;
    max-width: 150px;
    padding: 5px 9px;
    text-align: left;
}
QPushButton#cardChip {
    background: #252525;
    color: #b8b8b8;
    border-color: #3b3b3b;
}
QPushButton#cardChipActive {
    background: #315f89;
    color: white;
    border-color: #6b9dca;
}
QTextEdit {
    background: #171717;
    border: 1px solid #414141;
    color: #dedede;
    selection-background-color: #315f89;
}
"""


def _exception_hook(exc_type, exc_value, exc_traceback) -> None:
    details = "".join(traceback.format_exception(exc_type, exc_value, exc_traceback))
    box = QMessageBox()
    box.setIcon(QMessageBox.Icon.Critical)
    box.setWindowTitle("Comparison Timeline Studio")
    box.setText("CTS encountered an unexpected error.")
    box.setInformativeText("The rewrite stopped the operation instead of silently corrupting the project.")
    box.setDetailedText(details)
    box.exec()


def main() -> int:
    app = QApplication(sys.argv)
    app.setApplicationName("Comparison Timeline Studio")
    app.setApplicationVersion(__version__)
    app.setOrganizationName("RetroFrost")
    app.setStyle("Fusion")
    app.setStyleSheet(CONVENIENCE_STYLE)
    sys.excepthook = _exception_hook
    window = ConvenientPremiereWindow()
    window.show()
    return app.exec()
