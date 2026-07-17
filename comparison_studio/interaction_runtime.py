from __future__ import annotations

import csv
import json
import shutil
from pathlib import Path

from PySide6.QtWidgets import QFileDialog, QMessageBox

from . import exporter as exporter_module
from .data import FriendlyError, SpreadsheetData, load_xlsx_table, save_project_json
from .direct_transform import TransformTimelineRenderer
from .studio_ui import StudioAssetCache, _load_studio_document
from .transform_layout_hotfix import TransformLayoutFixedMainWindow
from .ui import show_error


class RuntimeTransformRenderer(TransformTimelineRenderer):
    ACTIVE_TRANSFORMS = {}

    def __init__(self, asset_cache=None, transforms=None) -> None:
        super().__init__(asset_cache or StudioAssetCache(), transforms if transforms is not None else self.ACTIVE_TRANSFORMS)


exporter_module.TimelineRenderer = RuntimeTransformRenderer


class InteractionMainWindow(TransformLayoutFixedMainWindow):
    transform_space = "screen_absolute_v1"

    def __init__(self) -> None:
        super().__init__()
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        self.renderer = RuntimeTransformRenderer(StudioAssetCache(), self.transform_overrides)
        self.statusBar().showMessage("Ready · Import file handles CSV/XLSX · left-click edits · right-click transforms")
        self.update_preview()

    def _new_renderer(self) -> RuntimeTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        return RuntimeTransformRenderer(StudioAssetCache(), self.transform_overrides)

    @staticmethod
    def _encoded_transforms(transforms) -> dict[str, list[float]]:
        return {f"{card_index}:{role}": [float(value) for value in box] for (card_index, role), box in transforms.items()}

    @staticmethod
    def _decoded_transforms(payload: object) -> dict[tuple[int, str], tuple[float, float, float, float]]:
        result = {}
        if not isinstance(payload, dict):
            return result
        for key, value in payload.items():
            try:
                card_text, role = str(key).split(":", 1)
                values = tuple(float(item) for item in value)
                if len(values) == 4:
                    result[(int(card_text), role)] = values
            except (TypeError, ValueError):
                continue
        return result

    def import_data_file(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Import comparison data", "", "Data files (*.csv *.xlsx);;CSV files (*.csv);;Excel workbooks (*.xlsx)")
        if not path:
            return
        try:
            suffix = Path(path).suffix.lower()
            warnings: list[str] = []
            if suffix == ".xlsx":
                imported = load_xlsx_table(path)
                data = imported.data
                warnings = imported.warnings
            elif suffix == ".csv":
                with open(path, "r", encoding="utf-8-sig", newline="") as handle:
                    rows = list(csv.reader(handle))
                if not rows:
                    raise FriendlyError("The CSV file is empty.", "Add a header row and at least one data row.")
                width = max(len(row) for row in rows)
                headers = [(rows[0][index].strip() if index < len(rows[0]) else "") or f"Column {index + 1}" for index in range(width)]
                body = [[row[index].strip() if index < len(row) else "" for index in range(width)] for row in rows[1:]]
                data = SpreadsheetData(headers, body).normalized()
            else:
                raise FriendlyError("Unsupported data file.", "Choose a .csv or .xlsx file.")
            self.table.set_data(data)
            self._auto_map_fields()
            self._apply_model_schema(self.model_combo.currentData())
            self.position_seconds = 0.0
            self.update_preview()
            self.statusBar().showMessage(f"Imported {data.row_count} rows and {len(data.headers)} columns from {Path(path).name}", 6000)
            if warnings:
                box = QMessageBox(self)
                box.setIcon(QMessageBox.Icon.Warning)
                box.setWindowTitle("Data imported with warnings")
                box.setText("The file was imported, with readable warnings.")
                box.setDetailedText("\n".join(warnings))
                box.exec()
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            show_error(self, "Could not import that data file.", "Choose a valid UTF-8 CSV or XLSX workbook.", str(exc))

    def save_project(self) -> None:
        path, _ = QFileDialog.getSaveFileName(self, "Save project", "comparison-project.cts.json", "Comparison Studio projects (*.cts.json)")
        if not path:
            return
        if not path.endswith(".cts.json"):
            path += ".cts.json"
        try:
            data = self.spreadsheet_data()
            project_path = Path(path).resolve()
            asset_directory = project_path.with_name(project_path.name.removesuffix(".cts.json") + "_assets")
            for row_index, row in enumerate(data.rows, start=1):
                for column_index, value in enumerate(row):
                    source = Path(value).expanduser() if value and not value.lower().startswith(("http://", "https://")) else None
                    if source is None or not source.is_file() or not source.parent.name.startswith("comparison-studio-"):
                        continue
                    asset_directory.mkdir(parents=True, exist_ok=True)
                    destination = asset_directory / f"asset_{row_index:03d}_{column_index + 1:02d}{source.suffix.lower() or '.bin'}"
                    shutil.copy2(source, destination)
                    row[column_index] = str(destination)
            save_project_json(project_path, data, self.project_settings(), self.soundtrack_table.tracks())
            payload = json.loads(project_path.read_text(encoding="utf-8"))
            payload["transform_overrides"] = self._encoded_transforms(self.transform_overrides)
            payload["transform_space"] = str(getattr(self, "transform_space", "screen_absolute_v1"))
            project_path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
            self.table.set_data(data)
            self.statusBar().showMessage(f"Saved {project_path.name} with direct-layout transforms", 5000)
        except FriendlyError as exc:
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            show_error(self, "Could not save the project.", "Choose another destination.", str(exc))

    def open_project(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Open project", "", "Comparison Studio projects (*.cts.json)")
        if not path:
            return
        try:
            payload = json.loads(Path(path).read_text(encoding="utf-8"))
            document, extras = _load_studio_document(path)
            self._suspend_model_schema = True
            self.table.set_data(document.data)
            self.model_combo.setCurrentIndex(max(0, self.model_combo.findData(document.settings.model_id)))
            self.default_visible.setChecked(document.settings.visible_cards == 0)
            if document.settings.visible_cards:
                self.visible_cards.setValue(document.settings.visible_cards)
            for role, combo in self.mapping_combos.items():
                combo.setCurrentIndex(max(0, combo.findData(document.settings.field_mapping.get(role, ""))))
            if document.settings.custom_duration is None:
                self.auto_length.setChecked(True)
            else:
                from .data import format_duration
                self.auto_length.setChecked(False)
                self.custom_length.setText(format_duration(document.settings.custom_duration))
            self.master_volume.setValue(round(document.settings.soundtrack_master_volume * 100))
            self.hexagons_bounce.setChecked(document.settings.hexagons_bounce)
            self.soundtrack_table.set_tracks(document.audio_tracks)
            self.font_combo.setCurrentText(str(extras["font_family"]))
            self.background_combo.setCurrentIndex(max(0, self.background_combo.findData(extras["illustrated_background"])))
            self.image_scale_slider.setValue(round(float(extras["image_scale"]) * 100))
            self.badge_scale_slider.setValue(round(float(extras["illustrated_badge_scale"]) * 100))
            self.illustrated_auto_size.setChecked(bool(extras["illustrated_auto_size"]))
            if hasattr(self, "show_hexagons"):
                stored_settings = payload.get("settings", {})
                self.show_hexagons.setChecked(bool(stored_settings.get("show_hexagons", True)))
            decoded = self._decoded_transforms(payload.get("transform_overrides", {}))
            normalizer = getattr(self, "_normalize_loaded_transforms", None)
            if callable(normalizer):
                decoded = normalizer(decoded, str(payload.get("transform_space", "screen_absolute_v1")))
            self.transform_overrides.clear()
            self.transform_overrides.update(decoded)
            RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
            self.renderer = self._new_renderer()
            self._suspend_model_schema = False
            self._refresh_field_guide(document.settings.model_id)
            self._update_visual_control_state()
            cards = self.cards()
            if cards:
                visible = self.project_settings().effective_visible_cards()
                self.position_seconds = self._editing_time_for_card(
                    min(len(cards), visible) - 1
                )
            else:
                self.position_seconds = 0.0
            self.preview.clear_transform()
            self.update_preview()
            self.statusBar().showMessage(f"Opened {Path(path).name}", 5000)
        except FriendlyError as exc:
            self._suspend_model_schema = False
            show_error(self, exc.summary, exc.suggestion, exc.details)
        except Exception as exc:
            self._suspend_model_schema = False
            show_error(self, "Could not finish opening the project.", "Your current project was not intentionally changed.", str(exc))
