"""CLI for Comparison Timeline Studio.

The supported Windows renderer is the same locked, measured ccengine renderer
used by Android. The old comparison_engine compositor remains in the source
tree only for compatibility; it is not used for 1:1 reference exports.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
import time

# Source-tree runs need the Android Python source directory on sys.path. In the
# PyInstaller build ccengine is bundled directly and this path is unnecessary.
try:
    import ccengine  # type: ignore
except ModuleNotFoundError:
    repo_root = Path(__file__).resolve().parents[1]
    engine_root = repo_root / "android" / "app" / "src" / "main" / "python"
    if engine_root.is_dir():
        sys.path.insert(0, str(engine_root))
    import ccengine  # type: ignore

from ccengine import FrameRenderer
from ccengine.exporter import VideoExporter
from ccengine.models import Card, Project, ProjectSettings
from ccengine.timing import total_duration, total_frame_count

from .models import ComparisonItem, CreditsInfo, TimelineProject, VideoConfig
from .sample_data import get_evolution_of_language_project


LOCKED_FPS = 60
LOCKED_WIDTH = 1920
LOCKED_HEIGHT = 1080


def load_legacy_project_from_json(json_path: str) -> TimelineProject:
    with open(json_path, "r", encoding="utf-8") as handle:
        data = json.load(handle)

    items = [
        ComparisonItem(
            badge_value=str(item.get("badge_value", "")),
            badge_unit=str(item.get("badge_unit", "")),
            title=str(item.get("title", "")),
            description=str(item.get("description", "")),
            image_path=item.get("image_path"),
        )
        for item in data.get("items", [])
    ]

    config_data = data.get("config", {})
    config = VideoConfig(
        width=LOCKED_WIDTH,
        height=LOCKED_HEIGHT,
        fps=LOCKED_FPS,
        output_path=config_data.get("output_path", "output/comparison.mp4"),
    )

    credits_data = data.get("credits", {})
    defaults = CreditsInfo()
    credits = CreditsInfo(
        intro_explanation=credits_data.get("intro_explanation", defaults.intro_explanation),
        lead_research=credits_data.get("lead_research", defaults.lead_research),
        fact_check=credits_data.get("fact_check", defaults.fact_check),
        lead_designer=credits_data.get("lead_designer", defaults.lead_designer),
        edit_post=credits_data.get("edit_post", defaults.edit_post),
        thumbnail_designer=credits_data.get("thumbnail_designer", defaults.thumbnail_designer),
        video_idea=credits_data.get("video_idea", defaults.video_idea),
    )

    return TimelineProject(
        title=data.get("title", "Comparison Timeline"),
        items=items,
        credits=credits,
        config=config,
    )


def legacy_to_exact(project: TimelineProject) -> Project:
    """Map the old package schema onto the locked reference renderer schema."""
    cards = []
    for item in project.items:
        value = " ".join(
            part.strip()
            for part in (str(item.badge_value), str(item.badge_unit))
            if part and part.strip()
        )
        cards.append(
            Card(
                title=str(item.title or ""),
                value=value,
                description=str(item.description or ""),
                image=str(item.image_path or ""),
            )
        )

    settings = ProjectSettings(
        width=LOCKED_WIDTH,
        height=LOCKED_HEIGHT,
        fps=LOCKED_FPS,
        # Content is editable; geometry, cadence and animation are model-locked.
        credits_top_text=str(project.credits.intro_explanation or ""),
    )
    return Project(name=project.title, cards=cards, settings=settings)


def load_exact_project(path: str | None) -> Project:
    if path and os.path.exists(path):
        with open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
        # Native ccengine project files use a `cards` array. Older package JSON
        # uses `items`; support both without changing the rendering contract.
        if isinstance(data, dict) and "cards" in data:
            project = Project.from_dict(data)
            project.path = str(Path(path).resolve())
            return project
        return legacy_to_exact(load_legacy_project_from_json(path))

    return legacy_to_exact(get_evolution_of_language_project())


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Comparison Timeline Studio - locked 1:1 reference renderer"
    )
    parser.add_argument(
        "--input",
        "-i",
        type=str,
        help="Path to native Cubical Compare or legacy timeline JSON",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=str,
        default="output/render.mp4",
        help="Output PNG/MP4 path",
    )
    parser.add_argument("--preview", action="store_true", help="Render one exact output frame")
    parser.add_argument(
        "--time",
        "-t",
        type=float,
        default=4.5,
        help="Preview timestamp in seconds",
    )
    # Kept only so old scripts don't break. A 1:1 model cannot legally alter
    # these values, so both flags are intentionally ignored.
    parser.add_argument("--fps", type=int, default=LOCKED_FPS, help=argparse.SUPPRESS)
    parser.add_argument("--speed", type=float, default=None, help=argparse.SUPPRESS)

    args = parser.parse_args()
    project = load_exact_project(args.input)
    project.settings.width = LOCKED_WIDTH
    project.settings.height = LOCKED_HEIGHT
    project.settings.fps = LOCKED_FPS

    if args.fps != LOCKED_FPS or args.speed is not None:
        print(
            "1:1 reference mode locks 1920x1080 at 60 FPS and the measured "
            "source cadence; --fps/--speed overrides are ignored."
        )

    renderer = FrameRenderer()

    if args.preview:
        output = Path(args.output)
        if output.suffix.lower() != ".png":
            output = Path("output/preview_frame.png")
        output.parent.mkdir(parents=True, exist_ok=True)
        frame = renderer.render_output_frame(project, max(0.0, float(args.time)))
        frame.save(output, format="PNG")
        print(f"Rendered strict reference frame {int(args.time * LOCKED_FPS)} -> {output}")
        return

    frames = total_frame_count(project)
    duration = total_duration(project)
    print(
        f"Strict reference export: {frames} frames, {duration:.3f}s, "
        f"{LOCKED_WIDTH}x{LOCKED_HEIGHT} @ {LOCKED_FPS} FPS"
    )

    exporter = VideoExporter(renderer)
    started = time.monotonic()

    def on_progress(current: int, total: int) -> None:
        elapsed = max(1e-6, time.monotonic() - started)
        actual_fps = current / elapsed
        percent = current * 100.0 / max(1, total)
        print(
            f"Rendering: {current}/{total} ({percent:.1f}%) | "
            f"{actual_fps:.1f} FPS | Elapsed: {elapsed:.1f}s"
        )

    exporter.export(project, args.output, progress=on_progress)
    print(f"Video exported successfully to: {Path(args.output).resolve()}")


if __name__ == "__main__":
    main()
