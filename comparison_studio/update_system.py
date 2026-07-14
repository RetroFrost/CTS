from __future__ import annotations

import json
import shutil
import subprocess
import sys
import time
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QSettings, QThread, QTimer, QUrl, Signal
from PySide6.QtGui import QDesktopServices
from PySide6.QtWidgets import QApplication, QMessageBox

from . import __version__
from .card_relative_transform import CardRelativeTransformMainWindow


LATEST_RELEASE_API = "https://api.github.com/repos/RetroFrost/CTS/releases/latest"
UPDATE_CHECK_INTERVAL_SECONDS = 24 * 60 * 60


@dataclass(slots=True)
class ReleaseInfo:
    version: str
    name: str
    body: str
    url: str


def _version_tuple(value: str) -> tuple[int, ...]:
    clean = value.strip().lower().lstrip("v")
    numeric = clean.split("-", 1)[0].split("+", 1)[0]
    pieces: list[int] = []
    for piece in numeric.split("."):
        digits = "".join(character for character in piece if character.isdigit())
        pieces.append(int(digits or 0))
    while len(pieces) < 3:
        pieces.append(0)
    return tuple(pieces)


class UpdateCheckWorker(QThread):
    update_found = Signal(object)
    failed = Signal(str)

    def run(self) -> None:
        try:
            request = urllib.request.Request(
                LATEST_RELEASE_API,
                headers={
                    "Accept": "application/vnd.github+json",
                    "User-Agent": f"CTS/{__version__}",
                    "X-GitHub-Api-Version": "2022-11-28",
                },
            )
            with urllib.request.urlopen(request, timeout=12) as response:
                payload = json.loads(response.read().decode("utf-8"))
            tag = str(payload.get("tag_name", "")).strip()
            if not tag:
                raise ValueError("GitHub did not return a release tag")
            release = ReleaseInfo(
                version=tag.lstrip("vV"),
                name=str(payload.get("name") or tag),
                body=str(payload.get("body") or "").strip(),
                url=str(payload.get("html_url") or "https://github.com/RetroFrost/CTS/releases"),
            )
            self.update_found.emit(release)
        except Exception as exc:
            self.failed.emit(str(exc))


class SourceUpdateWorker(QThread):
    completed = Signal(bool, str)

    def __init__(self, root: Path, parent=None) -> None:
        super().__init__(parent)
        self.root = root

    def run(self) -> None:
        try:
            pull = subprocess.run(
                ["git", "-C", str(self.root), "pull", "--ff-only"],
                check=False,
                capture_output=True,
                text=True,
                timeout=120,
            )
            details = "\n".join(part for part in (pull.stdout.strip(), pull.stderr.strip()) if part)
            if pull.returncode != 0:
                self.completed.emit(False, details or "git pull failed")
                return
            self.completed.emit(True, details or "CTS source updated successfully.")
        except Exception as exc:
            self.completed.emit(False, str(exc))


class UpdateAwareMainWindow(CardRelativeTransformMainWindow):
    """CTS window with non-intrusive release checking and safe source updates."""

    def __init__(self) -> None:
        super().__init__()
        self._update_checker: UpdateCheckWorker | None = None
        self._source_updater: SourceUpdateWorker | None = None
        self._manual_update_check = False
        self._latest_release: ReleaseInfo | None = None

        help_menu = self.menuBar().addMenu("Help")
        self.check_updates_action = help_menu.addAction("Check for updates…")
        self.check_updates_action.triggered.connect(lambda: self.check_for_updates(manual=True))
        help_menu.addSeparator()
        about_action = help_menu.addAction(f"About CTS {__version__}")
        about_action.triggered.connect(self._show_about)

        QTimer.singleShot(2600, self._automatic_update_check)
        self.statusBar().showMessage(
            "Ready · transforms stay attached to cards · Help → Check for updates"
        )

    def _show_about(self) -> None:
        QMessageBox.information(
            self,
            "About Comparison Timeline Studio",
            f"Comparison Timeline Studio {__version__}\n\n"
            "Create spreadsheet-driven comparison videos with direct visual editing, "
            "soundtrack mixing, and MP4 export.",
        )

    def _automatic_update_check(self) -> None:
        settings = QSettings("Ethan Woods", "Comparison Timeline Studio")
        last_check = int(settings.value("updates/last_check", 0) or 0)
        if int(time.time()) - last_check < UPDATE_CHECK_INTERVAL_SECONDS:
            return
        self.check_for_updates(manual=False)

    def check_for_updates(self, manual: bool = True) -> None:
        if self._update_checker is not None and self._update_checker.isRunning():
            if manual:
                self.statusBar().showMessage("CTS is already checking for updates…", 3000)
            return
        self._manual_update_check = manual
        self.check_updates_action.setEnabled(False)
        if manual:
            self.statusBar().showMessage("Checking GitHub Releases for a newer CTS build…")
        worker = UpdateCheckWorker(self)
        worker.update_found.connect(self._update_check_finished)
        worker.failed.connect(self._update_check_failed)
        worker.finished.connect(self._update_worker_finished)
        self._update_checker = worker
        worker.start()

    def _update_worker_finished(self) -> None:
        self.check_updates_action.setEnabled(True)
        QSettings("Ethan Woods", "Comparison Timeline Studio").setValue(
            "updates/last_check",
            int(time.time()),
        )

    def _update_check_failed(self, details: str) -> None:
        if self._manual_update_check:
            QMessageBox.warning(
                self,
                "Could not check for updates",
                "CTS could not reach GitHub Releases.\n\n"
                f"Technical details: {details}",
            )
        else:
            self.statusBar().showMessage("Automatic update check could not reach GitHub", 3500)

    def _update_check_finished(self, release: ReleaseInfo) -> None:
        self._latest_release = release
        if _version_tuple(release.version) <= _version_tuple(__version__):
            if self._manual_update_check:
                QMessageBox.information(
                    self,
                    "CTS is up to date",
                    f"You are running CTS {__version__}.\n\n"
                    f"Latest GitHub release: {release.name}",
                )
            else:
                self.statusBar().showMessage(f"CTS {__version__} is up to date", 3000)
            return
        self._show_update_available(release)

    @staticmethod
    def _source_checkout_root() -> Path | None:
        for candidate in Path(__file__).resolve().parents:
            if (candidate / ".git").is_dir() and (candidate / "pyproject.toml").is_file():
                return candidate
        return None

    @staticmethod
    def _source_is_clean(root: Path) -> tuple[bool, str]:
        if shutil.which("git") is None:
            return False, "Git is not installed or is not available in PATH."
        try:
            result = subprocess.run(
                ["git", "-C", str(root), "status", "--porcelain"],
                check=False,
                capture_output=True,
                text=True,
                timeout=8,
            )
            if result.returncode != 0:
                return False, result.stderr.strip() or "Could not inspect the Git checkout."
            if result.stdout.strip():
                return False, "The CTS checkout has local changes. Commit or stash them before using Update now."
            return True, ""
        except Exception as exc:
            return False, str(exc)

    @staticmethod
    def _package_update_command() -> str:
        location = str(Path(__file__).resolve())
        if "dist-packages" in location:
            return "sudo apt update && sudo apt install --only-upgrade comparison-timeline-studio"
        return f'"{sys.executable}" -m pip install --upgrade comparison-timeline-studio'

    def _show_update_available(self, release: ReleaseInfo) -> None:
        notes = release.body.strip()
        if len(notes) > 900:
            notes = notes[:897].rstrip() + "…"
        message = (
            f"CTS {release.version} is available.\n"
            f"You are running CTS {__version__}."
        )
        if notes:
            message += f"\n\n{notes}"

        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Information)
        box.setWindowTitle("CTS update available")
        box.setText(message)
        box.setTextFormat(Qt.TextFormat.PlainText)

        root = self._source_checkout_root()
        update_now = None
        if root is not None:
            clean, reason = self._source_is_clean(root)
            if clean:
                update_now = box.addButton("Update source now", QMessageBox.ButtonRole.AcceptRole)
            else:
                box.setInformativeText(reason)
        copy_command = box.addButton("Copy update command", QMessageBox.ButtonRole.ActionRole)
        open_release = box.addButton("Open GitHub release", QMessageBox.ButtonRole.ActionRole)
        box.addButton("Later", QMessageBox.ButtonRole.RejectRole)
        box.exec()

        clicked = box.clickedButton()
        if update_now is not None and clicked is update_now:
            self._start_source_update(root)
        elif clicked is copy_command:
            command = (
                f"git -C {root} pull --ff-only"
                if root is not None
                else self._package_update_command()
            )
            QApplication.clipboard().setText(command)
            self.statusBar().showMessage("Update command copied to the clipboard", 4500)
        elif clicked is open_release:
            QDesktopServices.openUrl(QUrl(release.url))

    def _start_source_update(self, root: Path) -> None:
        if self._source_updater is not None and self._source_updater.isRunning():
            return
        self.check_updates_action.setEnabled(False)
        self.statusBar().showMessage("Updating CTS source with git pull --ff-only…")
        worker = SourceUpdateWorker(root, self)
        worker.completed.connect(lambda success, details: self._source_update_finished(root, success, details))
        worker.finished.connect(lambda: self.check_updates_action.setEnabled(True))
        self._source_updater = worker
        worker.start()

    def _source_update_finished(self, root: Path, success: bool, details: str) -> None:
        if not success:
            QMessageBox.critical(
                self,
                "CTS update failed",
                "The source checkout was not changed.\n\n" + details,
            )
            return

        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Information)
        box.setWindowTitle("CTS source updated")
        box.setText("The CTS source was updated successfully.")
        box.setInformativeText("Restart now to launch the updated build?")
        restart = box.addButton("Restart CTS", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Later", QMessageBox.ButtonRole.RejectRole)
        box.setDetailedText(details)
        box.exec()
        if box.clickedButton() is restart:
            run_script = root / "run.py"
            if run_script.is_file():
                from PySide6.QtCore import QProcess

                QProcess.startDetached(sys.executable, [str(run_script)], str(root))
                QApplication.quit()
