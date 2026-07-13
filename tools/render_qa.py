#!/usr/bin/env python3
"""Render representative timestamps for visual regression review."""

from __future__ import annotations

import tempfile
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from comparison_studio.data import CardData, ProjectSettings
from comparison_studio.renderer import TimelineRenderer


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    output = project_root / "qa"
    output.mkdir(exist_ok=True)
    assets = Path(tempfile.mkdtemp(prefix="comparison-studio-qa-"))
    colors = [
        (63, 119, 166),
        (115, 144, 81),
        (162, 92, 75),
        (97, 82, 145),
        (188, 148, 64),
        (72, 140, 136),
        (149, 74, 111),
        (88, 106, 132),
    ]
    names = [
        "First Ever Video",
        "Second Ever Video",
        "Third Ever Video",
        "First Animation",
        "First Music Video",
        "First Prank",
        "First Stopmotion",
        "First Cat Video",
    ]
    cards: list[CardData] = []
    for index, (color, name) in enumerate(zip(colors, names)):
        asset = Image.new("RGB", (640, 480), color)
        draw = ImageDraw.Draw(asset)
        draw.ellipse((190, 90, 450, 350), fill=tuple(min(255, value + 45) for value in color))
        draw.rectangle((80 + index * 4, 335, 560 - index * 4, 430), fill=(22, 27, 35))
        path = assets / f"asset_{index + 1}.png"
        asset.save(path)
        cards.append(
            CardData(
                uploaded=f"{23 + index} April 2005",
                title=name,
                description="This short description demonstrates the locked card layout and automatic text fitting.",
                image=str(path),
            )
        )

    renderer = TimelineRenderer()
    settings = ProjectSettings()
    timestamps = [0, 2, 4, 6, 8, 10, 15, 20]
    frames = [renderer.render(cards, timestamp, settings, size=(640, 360)) for timestamp in timestamps]
    sheet = Image.new("RGB", (640 * 2 + 24, 360 * 4 + 40), (238, 240, 244))
    sheet_draw = ImageDraw.Draw(sheet)
    for index, (timestamp, frame) in enumerate(zip(timestamps, frames)):
        x = 8 + (index % 2) * (640 + 8)
        y = 8 + (index // 2) * (360 + 8)
        sheet.paste(frame, (x, y))
        sheet_draw.rectangle((x + 8, y + 8, x + 84, y + 32), fill=(0, 0, 0))
        sheet_draw.text((x + 14, y + 13), f"{timestamp:02d}.0s", fill=(255, 225, 70))
    sheet.save(output / "renderer_contact_sheet.png")
    renderer.render(cards, 20, settings, size=(1920, 1080)).save(output / "renderer_1080p.png")


if __name__ == "__main__":
    main()
