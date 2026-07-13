#!/usr/bin/env python3
"""Exercise the renderer-to-FFmpeg byte pipeline without starting the GUI."""

from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from comparison_studio.data import CardData, ProjectSettings
from comparison_studio.renderer import TimelineRenderer


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    output = root / "qa" / "export_smoke_test.mp4"
    output.parent.mkdir(exist_ok=True)
    assets = Path(tempfile.mkdtemp(prefix="comparison-studio-export-"))
    cards: list[CardData] = []
    for index in range(6):
        image_path = assets / f"{index}.png"
        Image.new("RGB", (640, 480), (45 + index * 25, 90, 145)).save(image_path)
        cards.append(
            CardData(
                f"{23 + index} April 2005",
                f"Card {index + 1}",
                "FFmpeg export smoke test.",
                str(image_path),
            )
        )
    settings = ProjectSettings(width=640, height=360, fps=30, custom_duration=3.0)
    renderer = TimelineRenderer()
    command = [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-y",
        "-f",
        "rawvideo",
        "-pixel_format",
        "rgb24",
        "-video_size",
        "640x360",
        "-framerate",
        "30",
        "-i",
        "-",
        "-an",
        "-c:v",
        "libx264",
        "-pix_fmt",
        "yuv420p",
        str(output),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    assert process.stdin is not None
    for frame in range(90):
        process.stdin.write(renderer.render(cards, frame / 30, settings).tobytes())
    process.stdin.close()
    if process.wait() != 0:
        raise SystemExit("FFmpeg smoke export failed")
    print(output)


if __name__ == "__main__":
    main()
