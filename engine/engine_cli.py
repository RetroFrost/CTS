from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import shutil
import sys
import time
import tempfile

from PIL import Image

from ccengine.assets import collect_project_assets, resolve_project_assets
from ccengine.exporter import VideoExporter
from ccengine.image_sheet_core import ImageSheetProcessor, export_sheet_crops, inset_detection
from ccengine.importers import load_spreadsheet
from ccengine.megapack import import_megapack
from ccengine.models import Card, Project, ProjectSettings
from ccengine.model_registry import (
    DEFAULT_MODEL_ID, get_model, list_models, model_manifest, normalize_model_id,
)
from ccengine.renderer import FrameRenderer
from ccengine.timing import frame_to_seconds, total_duration, total_frame_count
from ccengine.validation import normalize_project

VERSION = "1.0.6"


def write_progress_file(path: Path | None, done: int, total: int) -> None:
    if path is None:
        return
    total = max(1, int(total))
    done = max(0, min(int(done), total))
    percent = int(done * 100 / total)
    temporary = path.with_suffix(path.suffix + ".tmp")
    payload = f"{percent} {done} {total}\n"
    try:
        temporary.write_text(payload, encoding="utf-8", newline="\n")
        os.replace(temporary, path)
        return
    except OSError:
        # Win32 readers can briefly deny replacement even when the file is
        # opened only for progress polling. Progress reporting is auxiliary:
        # fall back to an in-place update and never abort the real operation.
        try:
            path.write_text(payload, encoding="utf-8", newline="\n")
        except OSError:
            pass
    finally:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass


def b64e(value: str) -> str:
    return base64.urlsafe_b64encode(value.encode("utf-8")).decode("ascii")


def b64d(value: str) -> str:
    if not value:
        return ""
    padding = "=" * ((4 - len(value) % 4) % 4)
    try:
        raw = base64.b64decode(value + padding, altchars=b"-_", validate=True)
        return raw.decode("utf-8", errors="strict")
    except Exception as exc:
        raise ValueError("Corrupted CCX base64 data.") from exc


def write_ccx(project: Project, path: str | Path) -> None:
    normalize_project(project)
    s = project.settings
    lines = ["CCX1"]

    def put(key: str, value: object) -> None:
        lines.append(f"{key}={value}")

    def puts(key: str, value: str) -> None:
        put(key, b64e(value or ""))

    puts("project.name", project.name)
    puts("project.model_id", s.model_id)
    put("project.model_revision", s.model_revision)
    put("project.width", s.width)
    put("project.height", s.height)
    put("project.fps", s.fps)
    put("project.auto_length", int(s.auto_length))
    put("project.custom_length_seconds", s.custom_length_seconds)
    put("project.credits_enabled", int(s.credits_enabled))
    put("project.show_badges", int(s.show_badges))
    for key in (
        "credits_top_text", "credits_heading", "credits_project_name",
        "credits_created_with_label", "credits_created_with_value",
        "credits_design_label", "credits_design_value", "credits_footer",
        "end_best_label", "end_newest_label", "end_credit_label", "end_credit_value",
        "soundtrack", "encoder_preset", "font_title", "font_description",
        "font_badge", "font_credits", "image_fit_mode",
    ):
        puts(f"project.{key}", str(getattr(s, key)))
    put("project.soundtrack_volume", s.soundtrack_volume)
    put("project.soundtrack_loop", int(s.soundtrack_loop))
    put("project.soundtrack_offset_seconds", s.soundtrack_offset_seconds)
    put("project.soundtrack_fade_out_seconds", s.soundtrack_fade_out_seconds)
    put("project.encoder_crf", s.encoder_crf)
    put("cards.count", len(project.cards))
    for index, card in enumerate(project.cards):
        puts(f"card.{index}.title", card.title)
        puts(f"card.{index}.value", card.value)
        puts(f"card.{index}.description", card.description)
        puts(f"card.{index}.image", card.image)
        puts(f"card.{index}.id", card.id)
        put(f"card.{index}.image_x", card.image_x)
        put(f"card.{index}.image_y", card.image_y)
        put(f"card.{index}.image_scale", card.image_scale)
        put(f"card.{index}.image_rotation", card.image_rotation)
        put(f"card.{index}.image_crop_left", card.image_crop_left)
        put(f"card.{index}.image_crop_top", card.image_crop_top)
        put(f"card.{index}.image_crop_right", card.image_crop_right)
        put(f"card.{index}.image_crop_bottom", card.image_crop_bottom)
        puts(f"card.{index}.image_layer", card.image_layer)
    # CCX is consumed by the native loader in binary mode.  Force LF here so
    # Windows does not silently translate the interchange file to CRLF.
    Path(path).write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def read_ccx(path: str | Path) -> Project:
    values: dict[str, str] = {}
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    if not lines or lines[0].strip() != "CCX1":
        raise ValueError("Not a Cubical Compare interchange file.")
    for line in lines[1:]:
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()

    def gets(key: str, default: str = "") -> str:
        return b64d(values.get(key, "")) if key in values else default

    def geti(key: str, default: int) -> int:
        try:
            return int(float(values.get(key, default)))
        except (TypeError, ValueError):
            return default

    def getf(key: str, default: float) -> float:
        try:
            return float(values.get(key, default))
        except (TypeError, ValueError):
            return default

    def getb(key: str, default: bool) -> bool:
        return bool(geti(key, int(default)))

    s = ProjectSettings()
    s.model_id = normalize_model_id(gets("project.model_id", s.model_id))
    s.model_revision = geti("project.model_revision", get_model(s.model_id).revision)
    s.width = geti("project.width", s.width)
    s.height = geti("project.height", s.height)
    s.fps = geti("project.fps", s.fps)
    s.auto_length = getb("project.auto_length", s.auto_length)
    s.custom_length_seconds = getf("project.custom_length_seconds", s.custom_length_seconds)
    s.credits_enabled = getb("project.credits_enabled", s.credits_enabled)
    s.show_badges = getb("project.show_badges", s.show_badges)
    for key in (
        "credits_top_text", "credits_heading", "credits_project_name",
        "credits_created_with_label", "credits_created_with_value",
        "credits_design_label", "credits_design_value", "credits_footer",
        "end_best_label", "end_newest_label", "end_credit_label", "end_credit_value",
        "soundtrack", "encoder_preset", "font_title", "font_description",
        "font_badge", "font_credits", "image_fit_mode",
    ):
        setattr(s, key, gets(f"project.{key}", str(getattr(s, key))))
    s.soundtrack_volume = getf("project.soundtrack_volume", s.soundtrack_volume)
    s.soundtrack_loop = getb("project.soundtrack_loop", s.soundtrack_loop)
    s.soundtrack_offset_seconds = getf("project.soundtrack_offset_seconds", s.soundtrack_offset_seconds)
    s.soundtrack_fade_out_seconds = getf("project.soundtrack_fade_out_seconds", s.soundtrack_fade_out_seconds)
    s.encoder_crf = geti("project.encoder_crf", s.encoder_crf)

    count = geti("cards.count", 0)
    if count < 0 or count > 2000:
        raise ValueError("Projects are limited to 2000 cards.")
    cards: list[Card] = []
    for index in range(count):
        cards.append(Card(
            title=gets(f"card.{index}.title"),
            value=gets(f"card.{index}.value"),
            description=gets(f"card.{index}.description"),
            image=gets(f"card.{index}.image"),
            id=gets(f"card.{index}.id") or __import__("uuid").uuid4().hex,
            image_x=getf(f"card.{index}.image_x", 0.0),
            image_y=getf(f"card.{index}.image_y", 0.0),
            image_scale=max(0.05, getf(f"card.{index}.image_scale", 1.0)),
            image_rotation=getf(f"card.{index}.image_rotation", 0.0),
            image_crop_left=max(0.0, min(0.49, getf(f"card.{index}.image_crop_left", 0.0))),
            image_crop_top=max(0.0, min(0.49, getf(f"card.{index}.image_crop_top", 0.0))),
            image_crop_right=max(0.0, min(0.49, getf(f"card.{index}.image_crop_right", 0.0))),
            image_crop_bottom=max(0.0, min(0.49, getf(f"card.{index}.image_crop_bottom", 0.0))),
            image_layer="front" if gets(f"card.{index}.image_layer", "behind").lower() == "front" else "behind",
        ))
    project = Project(name=gets("project.name", ""), cards=cards, settings=s)
    normalize_project(project)
    resolve_project_assets(project, Path(path).resolve().parent)
    return project


def make_default_project(model_id: str = DEFAULT_MODEL_ID) -> Project:
    model = get_model(model_id)
    return Project(
        name="",
        cards=[Card("Card 1", "1", "", "")],
        settings=ProjectSettings(
            model_id=model.id,
            model_revision=model.revision,
            width=model.width,
            height=model.height,
            fps=model.fps,
        ),
    )


def resolve_asset_paths(project: Project, base: Path) -> Project:
    return resolve_project_assets(project, base)


def command_new(args: argparse.Namespace) -> int:
    write_ccx(make_default_project(args.model), args.output)
    return 0


def command_list_models(args: argparse.Namespace) -> int:
    del args
    print(json.dumps([model_manifest(model) for model in list_models()], indent=2))
    return 0


def command_project_to_ccx(args: argparse.Namespace) -> int:
    project = Project.load(args.project)
    resolve_asset_paths(project, Path(args.project).resolve().parent)
    write_ccx(project, args.output)
    return 0


def command_ccx_to_project(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    portable = collect_project_assets(project, args.output) if args.collect_assets else project
    output = Path(args.output)
    if output.suffix.lower() == ".ccx":
        write_ccx(portable, output)
    else:
        portable.save(output)
    return 0


def command_save_portable(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    portable = collect_project_assets(project, args.output)
    output = Path(args.output)
    if output.suffix.lower() == ".ccx":
        write_ccx(portable, output)
    else:
        portable.save(output)
    print(f"Saved portable project to {output}")
    return 0


def command_import_data(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    cards = load_spreadsheet(args.data)
    if not cards:
        raise ValueError("No cards were found in the selected spreadsheet.")
    project.cards = cards
    project.name = Path(args.data).stem.replace("_", " ").strip() or project.name
    normalize_project(project)
    write_ccx(project, args.output)
    print(f"Imported {len(cards)} cards")
    return 0


def command_import_sheet(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    progress_file = Path(args.progress_file) if args.progress_file else None
    cancel_file = Path(args.cancel_file) if args.cancel_file else None
    def cancelled() -> bool:
        return bool(cancel_file and cancel_file.exists())
    processor = ImageSheetProcessor.from_path(args.sheet, cancel_check=cancelled)
    preferred = args.expected if args.expected > 0 else max(1, len(project.cards) - args.start)
    if args.rows > 0 and args.columns > 0:
        detection = processor.manual_grid(args.rows, args.columns, args.margin, args.gutter_x, args.gutter_y, args.inset)
    else:
        detection = processor.detect(preferred_count=preferred)
        if any((args.trim_left, args.trim_top, args.trim_right, args.trim_bottom)):
            detection = inset_detection(detection, args.trim_left, args.trim_top, args.trim_right, args.trim_bottom)

    available = max(0, len(project.cards) - args.start)
    if not args.create_extra and detection.count > available:
        rects = detection.rectangles[:available]
        detection = type(detection)(detection.rows, detection.columns, tuple(rects), detection.confidence, detection.method)

    output_dir = Path(args.assets)
    output_dir.mkdir(parents=True, exist_ok=True)
    if progress_file:
        progress_file.parent.mkdir(parents=True, exist_ok=True)
    write_progress_file(progress_file, 0, max(1, detection.count))

    def progress(done: int, total: int) -> None:
        write_progress_file(progress_file, done, total)

    project.cards, paths = export_sheet_crops(
        args.sheet,
        output_dir,
        detection,
        project.cards,
        start_card=args.start,
        create_missing=args.create_extra,
        fit_mode=args.fit,
        target_size=(args.target_width, args.target_height),
        progress=progress,
        cancel_check=cancelled,
    )
    write_progress_file(progress_file, max(1, len(paths)), max(1, len(paths)))
    write_ccx(project, args.output)
    print(json.dumps({
        "rows": detection.rows,
        "columns": detection.columns,
        "detected": detection.count,
        "assigned": len(paths),
        "start": args.start + 1,
        "fit": args.fit,
    }))
    return 0


def command_import_megapack(args: argparse.Namespace) -> int:
    progress_file = Path(args.progress_file) if args.progress_file else None
    cancel_file = Path(args.cancel_file) if args.cancel_file else None

    def progress(done: int, total: int) -> None:
        write_progress_file(progress_file, done, total)

    def cancelled() -> bool:
        return bool(cancel_file and cancel_file.exists())

    result = import_megapack(
        args.pack,
        args.assets,
        progress=progress,
        cancelled=cancelled,
    )
    write_ccx(result.project, args.output)
    write_progress_file(progress_file, 1, 1)
    summary = (
        f"MegaPack '{result.pack_name}' loaded: {len(result.project.cards)} cards and "
        f"{result.extracted_files} referenced media files."
    )
    if result.warnings:
        summary += " Warnings: " + " | ".join(result.warnings)
    print(summary)
    return 0


def command_render_preview(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    renderer = FrameRenderer()
    source_frame = int(getattr(args, "frame", -1))
    seconds = (
        frame_to_seconds(project, source_frame)
        if source_frame >= 0
        else max(0.0, args.time)
    )
    if args.width > 0 and args.height > 0:
        # Display scaling is allowed; it does not alter model output geometry.
        frame = renderer.render(project, seconds, (args.width, args.height))
    else:
        # WYSIWYG preview: use the exact output-frame path used by MP4 export.
        frame = renderer.render_output_frame(project, seconds)
    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    frame.save(args.output)
    return 0


def command_export(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    if args.fast:
        project.settings.encoder_preset = "veryfast"
    exporter = VideoExporter()
    duration = total_duration(project)
    print(f"duration={duration:.3f}")

    progress_file = Path(args.progress_file) if args.progress_file else None
    cancel_file = Path(args.cancel_file) if args.cancel_file else None
    if progress_file:
        progress_file.parent.mkdir(parents=True, exist_ok=True)
    write_progress_file(progress_file, 0, 1)

    last_progress = {"time": 0.0, "percent": -1}

    def progress(done: int, total: int) -> None:
        # The GUI polls at 10 Hz. Writing and flushing once per 1080p frame was
        # needless disk/console I/O and made Windows exports feel dramatically
        # slower. Report on percentage changes, at most ten times per second.
        now = time.monotonic()
        percent = int(done * 100 / max(1, total))
        final = done >= total
        if not final and percent == last_progress["percent"] and now - last_progress["time"] < 0.10:
            return
        if not final and now - last_progress["time"] < 0.10:
            return
        last_progress["time"] = now
        last_progress["percent"] = percent
        print(f"progress={percent} frame={done}/{total}", flush=True)
        write_progress_file(progress_file, done, total)

    def cancelled() -> bool:
        return bool(cancel_file and cancel_file.exists())

    exporter.export(project, args.output, progress, cancelled)
    output = Path(args.output).expanduser().resolve()
    if output.suffix.lower() != ".mp4":
        output = output.with_suffix(".mp4")
    if not output.is_file() or output.stat().st_size < 256:
        raise RuntimeError("Export finished without a usable MP4 file.")
    write_progress_file(progress_file, 1, 1)
    print(f"output={output}")
    return 0


def command_validate(args: argparse.Namespace) -> int:
    project = read_ccx(args.input)
    result = {
        "version": VERSION,
        "model": model_manifest(project.model),
        "cards": len(project.cards),
        "duration": total_duration(project),
        "frame_count": total_frame_count(project),
        "soundtrack": bool(project.settings.soundtrack),
        "soundtrack_loop": project.settings.soundtrack_loop,
        "resolution": [project.settings.width, project.settings.height],
        "fps": project.settings.fps,
    }
    print(json.dumps(result, indent=2))
    return 0



def command_self_test(args: argparse.Namespace) -> int:
    root = Path(args.directory)
    root.mkdir(parents=True, exist_ok=True)
    ccx = root / "self-test.ccx"
    project_json = root / "self-test.json"
    preview_png = root / "self-test.png"
    preview_bmp = root / "self-test.bmp"

    original = make_default_project()
    original.cards.extend([
        Card("Card 2", "2", "Second card", ""),
        Card("Card 3", "3", "", ""),
        Card("Card 4", "4", "Fourth card", ""),
        Card("Card 5", "5", "Continuous conveyor", ""),
    ])
    write_ccx(original, ccx)
    loaded = read_ccx(ccx)
    if len(loaded.cards) != 5 or loaded.cards[4].title != "Card 5":
        raise RuntimeError("CCX round-trip failed")

    loaded.save(project_json)
    reopened = Project.load(project_json)
    if len(reopened.cards) != 5 or reopened.cards[0].value != "1":
        raise RuntimeError("Project JSON round-trip failed")

    renderer = FrameRenderer()
    renderer.render(loaded, 0.0, (960, 540)).save(preview_png)
    renderer.render(loaded, 9.7, (800, 450)).save(preview_bmp)
    for output in (preview_png, preview_bmp):
        with Image.open(output) as image:
            image.verify()
        if output.stat().st_size < 1024:
            raise RuntimeError(f"Preview output is unexpectedly small: {output}")

    # The packaged engine is not considered healthy unless it creates a real MP4.
    video_project = make_default_project()
    video_project.settings.width = 160
    video_project.settings.height = 90
    video_project.settings.fps = 2
    video_project.settings.auto_length = True
    video_project.settings.credits_enabled = False
    video_project.settings.encoder_preset = "ultrafast"
    video_project.settings.encoder_crf = 30
    video_mp4 = root / "self-test.mp4"
    VideoExporter().export(video_project, video_mp4)
    if not video_mp4.is_file() or video_mp4.stat().st_size < 256:
        raise RuntimeError("Real MP4 export self-test failed.")
    if b"ftyp" not in video_mp4.read_bytes()[:64]:
        raise RuntimeError("MP4 self-test output has no MP4 header.")

    print(json.dumps({
        "version": VERSION,
        "ccx": str(ccx),
        "json": str(project_json),
        "png": str(preview_png),
        "bmp": str(preview_bmp),
        "mp4": str(video_mp4),
        "duration": total_duration(video_project),
        "frame_count": total_frame_count(video_project),
        "video_card_count": len(video_project.cards),
        "video_scope": "lightweight MP4 export project",
        "project_duration": total_duration(loaded),
        "project_frame_count": total_frame_count(loaded),
        "project_card_count": len(loaded.cards),
        "project_scope": "CCX and JSON round-trip project",
    }, indent=2))
    return 0

def parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="cubical-compare-engine")
    p.add_argument("--version", action="version", version=VERSION)
    sub = p.add_subparsers(dest="command", required=True)

    s = sub.add_parser("new")
    s.add_argument("output")
    s.add_argument("--model", default=DEFAULT_MODEL_ID)
    s.set_defaults(func=command_new)

    s = sub.add_parser("list-models")
    s.set_defaults(func=command_list_models)

    s = sub.add_parser("project-to-ccx")
    s.add_argument("project")
    s.add_argument("output")
    s.set_defaults(func=command_project_to_ccx)

    s = sub.add_parser("ccx-to-project")
    s.add_argument("input")
    s.add_argument("output")
    s.add_argument("--collect-assets", action="store_true")
    s.set_defaults(func=command_ccx_to_project)

    s = sub.add_parser("save-portable")
    s.add_argument("input")
    s.add_argument("output")
    s.set_defaults(func=command_save_portable)

    s = sub.add_parser("import-data")
    s.add_argument("input")
    s.add_argument("data")
    s.add_argument("output")
    s.set_defaults(func=command_import_data)

    s = sub.add_parser("import-sheet")
    s.add_argument("input")
    s.add_argument("sheet")
    s.add_argument("output")
    s.add_argument("assets")
    s.add_argument("--rows", type=int, default=0)
    s.add_argument("--columns", type=int, default=0)
    s.add_argument("--expected", type=int, default=0)
    s.add_argument("--start", type=int, default=0)
    s.add_argument("--margin", type=int, default=0)
    s.add_argument("--gutter-x", type=int, default=0)
    s.add_argument("--gutter-y", type=int, default=0)
    s.add_argument("--inset", type=int, default=0)
    s.add_argument("--trim-left", type=int, default=0)
    s.add_argument("--trim-top", type=int, default=0)
    s.add_argument("--trim-right", type=int, default=0)
    s.add_argument("--trim-bottom", type=int, default=0)
    s.add_argument("--create-extra", action="store_true")
    s.add_argument("--fit", choices=["cts_card", "cover", "original"], default="cts_card")
    s.add_argument("--target-width", type=int, default=480)
    s.add_argument("--target-height", type=int, default=830)
    s.add_argument("--progress-file", default="")
    s.add_argument("--cancel-file", default="")
    s.set_defaults(func=command_import_sheet)

    s = sub.add_parser("import-megapack")
    s.add_argument("pack")
    s.add_argument("output")
    s.add_argument("assets")
    s.add_argument("--progress-file", default="")
    s.add_argument("--cancel-file", default="")
    s.set_defaults(func=command_import_megapack)

    s = sub.add_parser("render-preview")
    s.add_argument("input")
    s.add_argument("output")
    frame_group = s.add_mutually_exclusive_group()
    frame_group.add_argument("--time", type=float, default=0.0)
    frame_group.add_argument("--frame", type=int, default=-1)
    s.add_argument("--width", type=int, default=0)
    s.add_argument("--height", type=int, default=0)
    s.set_defaults(func=command_render_preview)

    s = sub.add_parser("export")
    s.add_argument("input")
    s.add_argument("output")
    s.add_argument("--fast", action="store_true")
    s.add_argument("--progress-file", default="")
    s.add_argument("--cancel-file", default="")
    s.set_defaults(func=command_export)

    s = sub.add_parser("validate")
    s.add_argument("input")
    s.set_defaults(func=command_validate)

    s = sub.add_parser("self-test")
    s.add_argument("--directory", required=True)
    s.set_defaults(func=command_self_test)
    return p


def main() -> int:
    try:
        args = parser().parse_args()
        return int(args.func(args))
    except Exception as exc:
        print(f"error={exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
