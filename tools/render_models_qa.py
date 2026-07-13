#!/usr/bin/env python3
"""Render all visual models with populated and completely blank cards."""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from comparison_studio.data import (
    MODEL_CLASSIC,
    MODEL_ILLUSTRATED,
    MODEL_REFERENCE,
    CardData,
    ProjectSettings,
)
from comparison_studio.renderer import TimelineRenderer


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    output = root / "qa"
    assets = output / "model-assets"
    assets.mkdir(parents=True, exist_ok=True)
    palette = [(49, 178, 215), (245, 180, 69), (132, 206, 92), (173, 104, 204)]
    cards: list[CardData] = []
    for index, color in enumerate(palette, start=1):
        image = Image.new("RGB", (640, 720), color)
        draw = ImageDraw.Draw(image)
        draw.ellipse((160, 150, 480, 470), fill=tuple(min(255, part + 45) for part in color), outline=(25, 32, 45), width=10)
        draw.rectangle((110, 490, 530, 650), fill=(35, 43, 57))
        path = assets / f"card-{index}.png"
        image.save(path)
        cards.append(CardData(str(index * 20), f"Example {index}", "Optional descriptive text.", str(path), "METERS"))

    renderer = TimelineRenderer()
    models = [MODEL_REFERENCE, MODEL_ILLUSTRATED, MODEL_CLASSIC]
    timestamps = [8.0, 6.0, 8.0]
    populated = [
        renderer.render(cards, timestamp, ProjectSettings(model_id=model), size=(960, 540))
        for model, timestamp in zip(models, timestamps)
    ]
    blank = [renderer.render([CardData() for _ in range(4)], 8.0, ProjectSettings(model_id=model), size=(960, 540)) for model in models]
    sheet = Image.new("RGB", (1920, 1620), (21, 25, 33))
    for index, frame in enumerate(populated):
        sheet.paste(frame, ((index % 2) * 960, (index // 2) * 540))
    for index, frame in enumerate(blank):
        slot = index + 3
        sheet.paste(frame, ((slot % 2) * 960, (slot // 2) * 540))
    sheet.save(output / "models_and_blank_styles.png")
    docs = root / "docs"
    docs.mkdir(exist_ok=True)
    names = ["REFERENCE DETAIL", "ILLUSTRATED CARDS", "CLASSIC COMPACT"]
    showcase = Image.new("RGB", (1200, 275), (13, 17, 25))
    showcase_draw = ImageDraw.Draw(showcase)
    for index, (name, frame) in enumerate(zip(names, populated)):
        x = index * 400
        showcase_draw.text((x + 18, 16), name, fill=(226, 232, 240))
        showcase.paste(frame.resize((400, 225), Image.Resampling.LANCZOS), (x, 50))
    showcase.save(docs / "models-preview.png", optimize=True)
    print(output / "models_and_blank_styles.png")


if __name__ == "__main__":
    main()
