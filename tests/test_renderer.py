import tempfile
import unittest
from pathlib import Path

from PIL import Image

from comparison_studio.data import MODEL_CLASSIC, MODEL_ILLUSTRATED, CardData, ProjectSettings
from comparison_studio.renderer import BACKGROUND, TimelineRenderer


def _cards(tmp_path: Path, count: int = 6) -> list[CardData]:
    cards = []
    for index in range(count):
        path = tmp_path / f"{index}.png"
        Image.new("RGB", (320, 240), (40 + index * 20, 80, 150)).save(path)
        cards.append(
            CardData(
                uploaded=f"{23 + index} April 2005",
                title=f"Card {index + 1}",
                description="A readable description for this comparison card.",
                image=str(path),
            )
        )
    return cards


class RendererTests(unittest.TestCase):
    def test_first_frame_is_black(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            renderer = TimelineRenderer()
            image = renderer.render(_cards(tmp_path), 0.0, ProjectSettings(), size=(640, 360))
            self.assertEqual(image.size, (640, 360))
            self.assertEqual(image.getpixel((10, 10)), BACKGROUND)

    def test_four_cards_fill_reference_viewport(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            renderer = TimelineRenderer()
            image = renderer.render(_cards(tmp_path), 8.0, ProjectSettings(), size=(640, 360))
            self.assertNotEqual(image.getpixel((80, 330)), BACKGROUND)
            self.assertNotEqual(image.getpixel((560, 330)), BACKGROUND)

    def test_custom_duration_maps_to_same_model_frame(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            cards = _cards(tmp_path)
            automatic = ProjectSettings()
            auto_duration = automatic.duration(len(cards))
            custom = ProjectSettings(custom_duration=auto_duration * 2)
            renderer = TimelineRenderer()
            normal_frame = renderer.render(cards, 5.0, automatic, size=(320, 180))
            slow_frame = renderer.render(cards, 10.0, custom, size=(320, 180))
            self.assertEqual(normal_frame.tobytes(), slow_frame.tobytes())

    def test_all_models_render_without_any_mapped_data(self) -> None:
        renderer = TimelineRenderer()
        cards = [CardData() for _ in range(4)]
        for model in (MODEL_ILLUSTRATED, MODEL_CLASSIC):
            image = renderer.render(cards, 8.0, ProjectSettings(model_id=model), size=(640, 360))
            self.assertEqual(image.size, (640, 360))
            self.assertNotEqual(image.getpixel((320, 330)), BACKGROUND)

    def test_reference_direct_edit_hit_regions(self) -> None:
        cards = [CardData() for _ in range(4)]
        renderer = TimelineRenderer()
        settings = ProjectSettings()
        self.assertEqual(renderer.hit_test(cards, 8.0, settings, 0.125, 0.20), (0, "badge_primary"))
        self.assertEqual(renderer.hit_test(cards, 8.0, settings, 0.125, 0.48), (0, "title"))
        self.assertEqual(renderer.hit_test(cards, 8.0, settings, 0.125, 0.60), (0, "description"))
        self.assertEqual(renderer.hit_test(cards, 8.0, settings, 0.125, 0.82), (0, "image"))

    def test_other_models_direct_edit_hit_regions(self) -> None:
        cards = [CardData() for _ in range(4)]
        renderer = TimelineRenderer()
        illustrated = ProjectSettings(model_id=MODEL_ILLUSTRATED)
        self.assertEqual(renderer.hit_test(cards, 6.0, illustrated, 1 / 6, 0.12), (0, "badge_primary"))
        self.assertEqual(renderer.hit_test(cards, 6.0, illustrated, 1 / 6, 0.27), (0, "badge_secondary"))
        self.assertEqual(renderer.hit_test(cards, 6.0, illustrated, 1 / 6, 0.55), (0, "image"))
        self.assertEqual(renderer.hit_test(cards, 6.0, illustrated, 1 / 6, 0.94), (0, "title"))
        classic = ProjectSettings(model_id=MODEL_CLASSIC)
        self.assertEqual(renderer.hit_test(cards, 8.0, classic, 0.125, 0.15), (0, "badge_primary"))
        self.assertEqual(renderer.hit_test(cards, 8.0, classic, 0.125, 0.30), (0, "badge_secondary"))
        self.assertEqual(renderer.hit_test(cards, 8.0, classic, 0.125, 0.44), (0, "title"))
        self.assertEqual(renderer.hit_test(cards, 8.0, classic, 0.125, 0.70), (0, "image"))

    def test_hit_test_tracks_scrolled_card_positions(self) -> None:
        cards = [CardData() for _ in range(5)]
        renderer = TimelineRenderer()
        settings = ProjectSettings()
        self.assertEqual(renderer.hit_test(cards, 8.0 + 10.0 / 3.0, settings, 0.125, 0.48), (1, "title"))


if __name__ == "__main__":
    unittest.main()
