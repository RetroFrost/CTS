from __future__ import annotations

import json
from pathlib import Path

from ccengine.importers import load_spreadsheet
from ccengine.megapack import import_megapack
from ccengine.models import Project
from ccengine.renderer import FrameRenderer
from ccengine.timing import total_duration, total_frame_count

_renderer = FrameRenderer()

def _project(text: str) -> Project:
    return Project.from_dict(json.loads(text))

def metadata(project_json: str) -> str:
    project = _project(project_json)
    return json.dumps({"frame_count": total_frame_count(project), "duration": total_duration(project), "fps": project.settings.fps})

def render_rgba(project_json: str, frame_index: int, width: int, height: int) -> bytes:
    project = _project(project_json)
    seconds = max(0, int(frame_index)) / max(1, project.settings.fps)
    image = _renderer.render(project, seconds, (max(2, int(width)), max(2, int(height))))
    return image.convert("RGBA").tobytes("raw", "RGBA")

def import_data(project_json: str, data_path: str) -> str:
    project = _project(project_json)
    cards = load_spreadsheet(data_path)
    if not cards: raise ValueError("No cards were found in the selected file.")
    project.cards = cards
    project.name = Path(data_path).stem.replace("_", " ").strip() or project.name
    return json.dumps(project.to_dict())

def import_pack(pack_path: str, asset_dir: str) -> str:
    return json.dumps(import_megapack(pack_path, asset_dir).project.to_dict())
