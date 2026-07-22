from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Expected block not found in {path}")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")


path = "comparison_studio/reference_illustrated.py"
replace_once(
    path,
    '''def _rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


class ReferenceIllustratedRenderer''',
    '''def _rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


def _content_frames(card) -> tuple[tuple[float, float, float, float], tuple[float, float, float, float] | None, tuple[float, float, float, float] | None]:
    """Collapse blank text rows and give their height to the artwork."""
    left, image_top, width, _image_height = IMAGE_FRAME
    content_bottom = DESCRIPTION_FRAME[1] + DESCRIPTION_FRAME[3]
    cursor = content_bottom
    description = None
    if str(getattr(card, "description", "")).strip():
        cursor -= DESCRIPTION_FRAME[3]
        description = (left, cursor, width, DESCRIPTION_FRAME[3])
    title = None
    if str(getattr(card, "title", "")).strip():
        cursor -= TITLE_FRAME[3]
        title = (left, cursor, width, TITLE_FRAME[3])
    image = (left, image_top, width, max(0.0, cursor - image_top))
    return image, title, description


class ReferenceIllustratedRenderer''',
)
replace_once(
    path,
    '''        image_left = round(width * IMAGE_FRAME[0])
        image_top = round(height * IMAGE_FRAME[1])
        image_right = round(width * (IMAGE_FRAME[0] + IMAGE_FRAME[2]))
        image_bottom = round(height * (IMAGE_FRAME[1] + IMAGE_FRAME[3]))
        image_box = (image_left, image_top, image_right, image_bottom)
''',
    '''        image_frame, title_frame, description_frame = _content_frames(card)
        image_left = round(width * image_frame[0])
        image_top = round(height * image_frame[1])
        image_right = round(width * (image_frame[0] + image_frame[2]))
        image_bottom = round(height * (image_frame[1] + image_frame[3]))
        image_box = (image_left, image_top, image_right, image_bottom)
''',
)
replace_once(
    path,
    '''        title_left = round(width * TITLE_FRAME[0])
        title_top = round(height * TITLE_FRAME[1])
        title_right = round(width * (TITLE_FRAME[0] + TITLE_FRAME[2]))
        title_bottom = round(height * (TITLE_FRAME[1] + TITLE_FRAME[3]))
        description_left = round(width * DESCRIPTION_FRAME[0])
        description_top = round(height * DESCRIPTION_FRAME[1])
        description_right = round(width * (DESCRIPTION_FRAME[0] + DESCRIPTION_FRAME[2]))
        description_bottom = round(height * (DESCRIPTION_FRAME[1] + DESCRIPTION_FRAME[3]))

        draw.rectangle(
            (title_left, title_top, title_right, title_bottom),
            fill=_rgb(COLORS["title_background"]) + (255,),
        )
        draw.rectangle(
            (description_left, description_top, description_right, description_bottom),
            fill=_rgb(COLORS["description_background"]) + (255,),
        )
        divider_color = _rgb(COLORS["divider"]) + (255,)
        draw.rectangle((0, 0, divider, height), fill=divider_color)
        draw.rectangle((width - divider, 0, width, height), fill=divider_color)
        draw.rectangle((0, title_top, width, title_top + divider), fill=divider_color)
        draw.rectangle((0, description_top, width, description_top + divider), fill=divider_color)
        draw.rectangle((0, height - divider, width, height), fill=divider_color)

        padding = round(width * 0.035)
        _draw_text_box(
            draw,
            card.title,
            (title_left + padding, title_top + 2, title_right - padding, title_bottom - 2),
            _rgb(COLORS["title_text"]) + (255,),
            maximum_size=max(12, round(height * 0.043)),
            minimum_size=max(8, round(height * 0.018)),
            max_lines=2,
            bold=True,
        )
        _draw_text_box(
            draw,
            card.description,
            (
                description_left + padding,
                description_top + 2,
                description_right - padding,
                description_bottom - 2,
            ),
            _rgb(COLORS["description_text"]) + (255,),
            maximum_size=max(10, round(height * 0.027)),
            minimum_size=max(7, round(height * 0.014)),
            max_lines=3,
            bold=True,
        )
''',
    '''        title_box = None
        if title_frame is not None:
            title_left = round(width * title_frame[0])
            title_top = round(height * title_frame[1])
            title_right = round(width * (title_frame[0] + title_frame[2]))
            title_bottom = round(height * (title_frame[1] + title_frame[3]))
            title_box = (title_left, title_top, title_right, title_bottom)
            draw.rectangle(title_box, fill=_rgb(COLORS["title_background"]) + (255,))

        description_box = None
        if description_frame is not None:
            description_left = round(width * description_frame[0])
            description_top = round(height * description_frame[1])
            description_right = round(width * (description_frame[0] + description_frame[2]))
            description_bottom = round(height * (description_frame[1] + description_frame[3]))
            description_box = (
                description_left,
                description_top,
                description_right,
                description_bottom,
            )
            draw.rectangle(
                description_box,
                fill=_rgb(COLORS["description_background"]) + (255,),
            )

        divider_color = _rgb(COLORS["divider"]) + (255,)
        draw.rectangle((0, 0, divider, height), fill=divider_color)
        draw.rectangle((width - divider, 0, width, height), fill=divider_color)
        if title_box is not None:
            draw.rectangle((0, title_box[1], width, title_box[1] + divider), fill=divider_color)
        if description_box is not None:
            draw.rectangle(
                (0, description_box[1], width, description_box[1] + divider),
                fill=divider_color,
            )
        draw.rectangle((0, height - divider, width, height), fill=divider_color)

        padding = round(width * 0.035)
        if title_box is not None:
            _draw_text_box(
                draw,
                card.title,
                (
                    title_box[0] + padding,
                    title_box[1] + 2,
                    title_box[2] - padding,
                    title_box[3] - 2,
                ),
                _rgb(COLORS["title_text"]) + (255,),
                maximum_size=max(12, round(height * 0.043)),
                minimum_size=max(8, round(height * 0.018)),
                max_lines=2,
                bold=True,
            )
        if description_box is not None:
            _draw_text_box(
                draw,
                card.description,
                (
                    description_box[0] + padding,
                    description_box[1] + 2,
                    description_box[2] - padding,
                    description_box[3] - 2,
                ),
                _rgb(COLORS["description_text"]) + (255,),
                maximum_size=max(10, round(height * 0.027)),
                minimum_size=max(7, round(height * 0.014)),
                max_lines=3,
                bold=True,
            )
''',
)

(ROOT / "tests/test_reference_illustrated.py").write_text(
    '''import unittest

from comparison_studio.data import CardData
from comparison_studio.reference_illustrated import ReferenceIllustratedRenderer
from comparison_studio.shared_contract import COLORS


def rgb(value: str) -> tuple[int, int, int]:
    value = value.lstrip("#")
    return tuple(int(value[index : index + 2], 16) for index in (0, 2, 4))


class ReferenceIllustratedTests(unittest.TestCase):
    def setUp(self) -> None:
        self.renderer = ReferenceIllustratedRenderer()

    def test_reference_bands_match_android_vertical_layout(self) -> None:
        card = CardData("1 in 4", "Online Only", "Optional description", "", "People")
        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        self.assertEqual(image.getpixel((20, 500))[:3], rgb(COLORS["image_top"]))
        self.assertEqual(image.getpixel((20, 850))[:3], rgb(COLORS["title_background"]))
        self.assertEqual(image.getpixel((20, 950))[:3], rgb(COLORS["description_background"]))

    def test_empty_description_gives_its_band_to_artwork(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")
        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        self.assertEqual(image.size, (400, 1000))
        self.assertEqual(image.getpixel((20, 850))[:3], rgb(COLORS["image_bottom"]))
        self.assertEqual(image.getpixel((20, 950))[:3], rgb(COLORS["title_background"]))

    def test_empty_title_gives_its_band_to_artwork(self) -> None:
        card = CardData("1 in 5", "", "Description", "", "People")
        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        self.assertEqual(image.getpixel((20, 850))[:3], rgb(COLORS["image_bottom"]))
        self.assertEqual(image.getpixel((20, 950))[:3], rgb(COLORS["description_background"]))

    def test_empty_text_lets_artwork_fill_the_card(self) -> None:
        card = CardData("1 in 5", "", "", "", "People")
        image = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        self.assertEqual(image.getpixel((20, 950))[:3], rgb(COLORS["image_bottom"]))

    def test_historical_render_methods_resolve_to_the_same_card(self) -> None:
        card = CardData("1 in 5", "School Crush", "", "", "People")
        illustrated = self.renderer._render_illustrated_card(card, 400, 1000, 1.0)
        reference = self.renderer._render_reference_card(card, 400, 1000, 1.0)
        classic = self.renderer._render_classic_card(card, 400, 1000, 1.0)
        self.assertEqual(reference.tobytes(), illustrated.tobytes())
        self.assertEqual(classic.tobytes(), illustrated.tobytes())


if __name__ == "__main__":
    unittest.main()
''',
    encoding="utf-8",
)

print("Applied desktop adaptive card layout")
