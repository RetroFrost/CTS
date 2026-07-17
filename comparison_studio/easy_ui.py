from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSlider,
    QVBoxLayout,
)

from .data import (
    MODEL_ILLUSTRATED,
    REFERENCE_REVEAL_SECONDS,
    AudioTrack,
    format_duration,
)
from .easy_timing import timeline_parts, with_easy_timing
from .premiere_ui import PREMIERE_STYLE
from .reference_illustrated import ReferenceIllustratedMainWindow


EASY_STYLE = PREMIERE_STYLE + """
QFrame#easyWorkflow {
    background:#24242a;
    border:1px solid #454550;
    border-radius:5px;
}
QLabel#easyStep {
    background:#7057e8;
    color:white;
    border-radius:9px;
    min-width:18px;
    min-height:18px;
    max-width:18px;
    max-height:18px;
    font-size:10px;
    font-weight:900;
}
QLabel#easyHeading {
    color:#f4f4f7;
    font-size:12px;
    font-weight:850;
}
QLabel#easySummary {
    color:#a9a9b4;
    font-size:11px;
}
QPushButton#easyMusic {
    text-align:left;
    min-height:30px;
}
QPushButton#easyExport {
    background:#7057e8;
    border:1px solid #9a88f7;
    color:white;
    min-height:35px;
    font-size:12px;
    font-weight:900;
    letter-spacing:0.5px;
}
QPushButton#easyExport:hover {
    background:#7f6aef;
    border-color:#b0a4fa;
}
"""


class EasyMainWindow(ReferenceIllustratedMainWindow):
    """The data-first CTS workflow with advanced editing kept optional."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("CTS Easy — Comparison Timeline Studio")
        self.subtitle_label.setText("EASY")
        self._advanced_visible = False
        self._install_easy_workflow()
        self._prepare_easy_defaults()
        self._connect_easy_status()
        self._update_easy_summary()
        self.statusBar().showMessage(
            "CTS Easy ready · Insert data · Choose music · Set length · Export"
        )

    @staticmethod
    def _step(number: int) -> QLabel:
        badge = QLabel(str(number))
        badge.setObjectName("easyStep")
        badge.setAlignment(Qt.AlignmentFlag.AlignCenter)
        return badge

    def _install_easy_workflow(self) -> None:
        workflow = QFrame()
        workflow.setObjectName("easyWorkflow")
        workflow_layout = QVBoxLayout(workflow)
        workflow_layout.setContentsMargins(9, 8, 9, 9)
        workflow_layout.setSpacing(7)

        heading = QHBoxLayout()
        title = QLabel("FAST WORKFLOW")
        title.setObjectName("easyHeading")
        self.easy_project_summary = QLabel()
        self.easy_project_summary.setObjectName("easySummary")
        self.easy_project_summary.setAlignment(
            Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter
        )
        heading.addWidget(title)
        heading.addStretch()
        heading.addWidget(self.easy_project_summary)
        workflow_layout.addLayout(heading)

        data_row = QHBoxLayout()
        data_row.setSpacing(6)
        data_row.addWidget(self._step(1))
        self.insert_data_button.setText("＋  CLICK TO INSERT DATA")
        self.insert_data_button.setMinimumHeight(38)
        data_row.addWidget(self.insert_data_button, 1)
        workflow_layout.addLayout(data_row)

        music_row = QHBoxLayout()
        music_row.setSpacing(6)
        music_row.addWidget(self._step(2))
        self.easy_music_button = QPushButton("Choose music…")
        self.easy_music_button.setObjectName("easyMusic")
        self.easy_music_button.clicked.connect(self._choose_easy_music)
        self.easy_clear_music = QPushButton("Clear")
        self.easy_clear_music.clicked.connect(self._clear_easy_music)
        music_row.addWidget(self.easy_music_button, 1)
        music_row.addWidget(self.easy_clear_music)
        workflow_layout.addLayout(music_row)

        volume_row = QHBoxLayout()
        volume_row.setContentsMargins(24, 0, 0, 0)
        volume_row.setSpacing(6)
        volume_row.addWidget(QLabel("Volume"))
        self.easy_volume = QSlider(Qt.Orientation.Horizontal)
        self.easy_volume.setRange(0, 200)
        self.easy_volume.setValue(self.master_volume.value())
        self.easy_volume.setToolTip("Soundtrack master volume")
        self.easy_volume_label = QLabel(f"{self.master_volume.value()}%")
        self.easy_volume_label.setMinimumWidth(38)
        self.easy_volume.valueChanged.connect(self.master_volume.setValue)
        self.easy_volume.valueChanged.connect(
            lambda value: self.easy_volume_label.setText(f"{value}%")
        )
        self.master_volume.valueChanged.connect(self.easy_volume.setValue)
        volume_row.addWidget(self.easy_volume, 1)
        volume_row.addWidget(self.easy_volume_label)
        workflow_layout.addLayout(volume_row)

        length_row = QHBoxLayout()
        length_row.setSpacing(6)
        length_row.addWidget(self._step(3))
        length_label = QLabel("Target video length")
        length_label.setObjectName("easyHeading")
        self.auto_length.setText("Auto")
        self.custom_length.setPlaceholderText("MM:SS")
        length_row.addWidget(length_label)
        length_row.addStretch()
        length_row.addWidget(self.auto_length)
        length_row.addWidget(self.custom_length)
        workflow_layout.addLayout(length_row)

        self.easy_duration_summary = QLabel()
        self.easy_duration_summary.setObjectName("easySummary")
        self.easy_duration_summary.setWordWrap(True)
        duration_indent = QHBoxLayout()
        duration_indent.setContentsMargins(24, 0, 0, 0)
        duration_indent.addWidget(self.easy_duration_summary)
        workflow_layout.addLayout(duration_indent)

        action_row = QHBoxLayout()
        action_row.setSpacing(6)
        action_row.addWidget(self._step(4))
        self.easy_export_button = QPushButton("EXPORT VIDEO")
        self.easy_export_button.setObjectName("easyExport")
        self.easy_export_button.clicked.connect(self.export_video)
        self.advanced_button = QPushButton("Advanced")
        self.advanced_button.setCheckable(True)
        self.advanced_button.toggled.connect(self._set_advanced_visible)
        action_row.addWidget(self.easy_export_button, 1)
        action_row.addWidget(self.advanced_button)
        workflow_layout.addLayout(action_row)

        # Put the complete normal path above the project tabs. The spreadsheet remains
        # available below; Models and Audio are revealed only when Advanced is requested.
        self.editor_layout.insertWidget(1, workflow)
        self.duration_info.setVisible(False)
        self.field_guide.setVisible(False)
        self.hexagons_bounce.setVisible(False)
        old_sequence_bar = self.findChild(QFrame, "settingsBar")
        if old_sequence_bar is not None:
            old_sequence_bar.setVisible(False)
        self.tabs.setCurrentIndex(0)
        self.tabs.setTabVisible(1, False)
        self.tabs.setTabVisible(2, False)

    def _prepare_easy_defaults(self) -> None:
        # Illustrated Cards is the flagship CTS Easy template, while existing projects can
        # still restore any saved model through Open project.
        illustrated = self.model_combo.findData(MODEL_ILLUSTRATED)
        if illustrated >= 0:
            self.model_combo.setCurrentIndex(illustrated)
        self.default_visible.setChecked(True)
        self.insert_data_button.setFocus(Qt.FocusReason.OtherFocusReason)

    def _connect_easy_status(self) -> None:
        self.table.data_edited.connect(self._update_easy_summary)
        self.soundtrack_table.data_edited.connect(self._update_easy_summary)
        self.master_volume.valueChanged.connect(self._update_easy_summary)
        self.auto_length.toggled.connect(self._update_easy_summary)
        self.custom_length.editingFinished.connect(self._update_easy_summary)

    def _set_advanced_visible(self, visible: bool) -> None:
        self._advanced_visible = visible
        self.tabs.setTabVisible(1, visible)
        self.tabs.setTabVisible(2, visible)
        self.field_guide.setVisible(visible)
        self.hexagons_bounce.setVisible(visible)
        self.advanced_button.setText("Hide advanced" if visible else "Advanced")
        if not visible:
            self.tabs.setCurrentIndex(0)

    def _choose_easy_music(self) -> None:
        path, _ = QFileDialog.getOpenFileName(
            self,
            "Choose soundtrack",
            "",
            "Audio (*.mp3 *.wav *.m4a *.aac *.flac *.ogg *.opus *.wma);;All files (*)",
        )
        if not path:
            return
        # Easy mode owns one looping soundtrack. The full Audio tab remains available for
        # layering, trims, delays, and fades when Advanced is enabled.
        self.soundtrack_table.set_tracks(
            [AudioTrack(path=str(Path(path).resolve()), loop=True, fade_out=0.8)]
        )
        self.statusBar().showMessage(
            f"Music selected · {Path(path).name} · loops to video length", 5000
        )
        self._update_easy_summary()

    def _clear_easy_music(self) -> None:
        self.soundtrack_table.set_tracks([])
        self.statusBar().showMessage("Soundtrack removed", 3500)
        self._update_easy_summary()

    def _update_easy_summary(self, *_args) -> None:
        if not hasattr(self, "easy_project_summary"):
            return
        card_count = len(self.cards())
        track_count = self.soundtrack_table.rowCount()
        music = "no music" if track_count == 0 else "music ready"
        self.easy_project_summary.setText(f"{card_count} cards · {music}")
        if track_count:
            first = self.soundtrack_table.item(0, 0)
            name = Path(first.text()).name if first and first.text().strip() else "Music selected"
            if track_count > 1:
                name = f"{name} + {track_count - 1} more"
            self.easy_music_button.setText(name)
        else:
            self.easy_music_button.setText("Choose music…")
        self.easy_clear_music.setEnabled(track_count > 0)
        self._refresh_duration_labels()

    def project_settings(self):
        """Keep all current visual settings, but use CTS Easy's segment-only timing."""
        return with_easy_timing(super().project_settings())

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
                detail = f"{format_duration(duration)} automatic"
            elif scroll_steps:
                detail = (
                    f"{format_duration(duration)} target · horizontal scroll "
                    f"{per_card:.2f}s/card · entrances stay normal"
                )
            else:
                detail = (
                    f"{format_duration(duration)} · all cards already fit on screen, "
                    "so there is no horizontal scroll to retime"
                )
            self.duration_info.setText(detail)
            if hasattr(self, "easy_duration_summary"):
                self.easy_duration_summary.setText(detail)
            self.time_label.setText(
                f"{format_duration(self.position_seconds)} / {format_duration(duration)}"
            )
        except Exception:
            self.duration_info.setText("Enter a valid duration")
            if hasattr(self, "easy_duration_summary"):
                self.easy_duration_summary.setText("Enter a valid target such as 01:30")

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
