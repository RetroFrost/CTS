"""CLI for Comparison Timeline Studio."""

import argparse
import json
import sys
import os
from .models import ComparisonItem, TimelineProject, VideoConfig, CreditsInfo
from .timeline_renderer import TimelineCompositor
from .video_exporter import VideoExporter
from .sample_data import get_evolution_of_language_project

def load_project_from_json(json_path: str) -> TimelineProject:
    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)
        
    items = []
    for it in data.get("items", []):
        items.append(ComparisonItem(
            badge_value=str(it.get("badge_value", "")),
            badge_unit=str(it.get("badge_unit", "")),
            title=str(it.get("title", "")),
            description=str(it.get("description", "")),
            image_path=it.get("image_path")
        ))
        
    cfg = data.get("config", {})
    config = VideoConfig(
        width=cfg.get("width", 1920),
        height=cfg.get("height", 1080),
        fps=cfg.get("fps", 60),
        scroll_speed_px_per_sec=cfg.get("scroll_speed_px_per_sec", 160.0),
        intro_duration_sec=cfg.get("intro_duration_sec", 3.0),
        outro_duration_sec=cfg.get("outro_duration_sec", 3.5),
        output_path=cfg.get("output_path", "output/comparison.mp4")
    )
    
    cred = data.get("credits", {})
    credits_info = CreditsInfo(
        intro_explanation=cred.get("intro_explanation", CreditsInfo.intro_explanation),
        lead_research=cred.get("lead_research", CreditsInfo.lead_research),
        fact_check=cred.get("fact_check", CreditsInfo.fact_check),
        lead_designer=cred.get("lead_designer", CreditsInfo.lead_designer),
        edit_post=cred.get("edit_post", CreditsInfo.edit_post),
        thumbnail_designer=cred.get("thumbnail_designer", CreditsInfo.thumbnail_designer),
        video_idea=cred.get("video_idea", CreditsInfo.video_idea),
    )
    
    return TimelineProject(
        title=data.get("title", "Comparison Timeline"),
        items=items,
        credits=credits_info,
        config=config
    )

def main():
    parser = argparse.ArgumentParser(description="Comparison Timeline Studio - High-Fidelity Video Engine")
    parser.add_argument("--input", "-i", type=str, help="Path to input JSON dataset (defaults to Evolution of Language)")
    parser.add_argument("--output", "-o", type=str, default="output/render.mp4", help="Output file path (PNG or MP4)")
    parser.add_argument("--preview", action="store_true", help="Render a single frame preview as PNG")
    parser.add_argument("--time", "-t", type=float, default=4.5, help="Timestamp in seconds for preview snapshot")
    parser.add_argument("--fps", type=int, default=60, help="Framerate (default: 60)")
    parser.add_argument("--speed", type=float, default=160.0, help="Scroll speed in pixels/sec (default: 160.0)")

    args = parser.parse_args()

    if args.input and os.path.exists(args.input):
        print(f"Loading project from {args.input}...")
        project = load_project_from_json(args.input)
    else:
        print("Using default Evolution of Language timeline dataset...")
        project = get_evolution_of_language_project()

    project.config.fps = args.fps
    project.config.scroll_speed_px_per_sec = args.speed
    if args.output:
        project.config.output_path = args.output

    print(f"Initializing timeline compositor with {len(project.items)} items...")
    compositor = TimelineCompositor(project)
    exporter = VideoExporter(compositor)

    if args.preview:
        preview_path = args.output if args.output.endswith(".png") else "output/preview_frame.png"
        print(f"Rendering frame preview at t={args.time}s -> {preview_path}...")
        exporter.export_preview_image(args.time, preview_path)
        print("Preview rendered successfully!")
    else:
        print(f"Total Video Duration: {compositor.total_duration:.2f}s ({compositor.total_frames} frames @ {args.fps} FPS)")
        
        def on_progress(cur, total, elapsed):
            fps_act = cur / elapsed if elapsed > 0 else 0
            pct = (cur / total) * 100
            print(f"Rendering: {cur}/{total} frames ({pct:.1f}%) | {fps_act:.1f} FPS | Elapsed: {elapsed:.1f}s")
            
        out_vid = exporter.export_video(args.output, progress_callback=on_progress)
        print(f"Video exported successfully to: {out_vid}")

if __name__ == "__main__":
    main()
