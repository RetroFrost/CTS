import io
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from PIL import Image, ImageDraw

from comparison_studio.data import MODEL_CLASSIC, MODEL_ILLUSTRATED, CardData, ProjectSettings
from comparison_studio.illustrated_video_profile import install_illustrated_video_profile
from comparison_studio.renderer import (
    BACKGROUND,
    AssetCache,
    TimelineRenderer,
    _fit_text,
    normalize_image_source,
)


install_illustrated_video_profile()


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
    def test_browser_image_url_is_normalized_and_loaded(self) -> None:
        payload = io.BytesIO()
        Image.new("RGB", (12, 8), (21, 99, 177)).save(payload, "PNG")
        image_bytes = payload.getvalue()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802
                self.send_response(200)
                self.send_header("Content-Type", "image/png")
                self.send_header("Content-Length", str(len(image_bytes)))
                self.end_headers()
                self.wfile.write(image_bytes)

            def log_message(self, _format: str, *_args) -> None:
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            pasted = f'  "http://127.0.0.1:{server.server_port}/picture?id=42"  '
            loaded = AssetCache().load(pasted)
            self.assertIsNotNone(loaded)
            self.assertEqual(loaded.size, (12, 8))
            self.assertEqual(loaded.getpixel((0, 0)), (21, 99, 177))
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=2)

        self.assertEqual(
            normalize_image_source("www.example.com/image.png"),
            "https://www.example.com/image.png",
        )

    def test_compact_long_text_shrinks_and_wraps_without_early_ellipsis(self) -> None:
        draw = ImageDraw.Draw(Image.new("RGB", (320, 180)))
        text = "A compact model title with several words that should remain readable"
        font, lines, size = _fit_text(draw, text, (0, 0, 200, 100), 40, 8, 3, True)
        self.assertLess(size, 40)
        self.assertEqual(len(lines), 3)
        self.assertFalse(lines[-1].endswith("…"))
        self.assertTrue(all(draw.textbbox((0, 0), line, font=font)[2] <= 200 for line in lines))

        card = CardData(
            uploaded="1234567890123456789012345678901234567890",
            badge_label="EXTREMELY LONG UNIT LABEL",
            title=text,
        )
        frame = TimelineRenderer().render(
            [card, card, card, card],
            8.0,
            ProjectSettings(model_id=MODEL_CLASSIC),
            size=(640, 360),
        )
        self.assertEqual(frame.size, (640, 360))

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

    def test_custom_duration_preserves_reveals_and_only_changes_scroll(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            tmp_path = Path(directory)
            cards = _cards(tmp_path)
            automatic = ProjectSettings()
            auto_duration = automatic.duration(len(cards))
            custom = ProjectSettings(custom_duration=auto_duration * 2)
            renderer = TimelineRenderer()

            normal_reveal = renderer.render(cards, 4.0, automatic, size=(320, 180))
            custom_reveal = renderer.render(cards, 4.0, custom, size=(320, 180))
            self.assertEqual(normal_reveal.tobytes(), custom_reveal.tobytes())

            normal_scroll = renderer.render(cards, 10.0, automatic, size=(320, 180))
            slower_scroll = renderer.render(cards, 10.0, custom, size=(320, 180))
            self.assertNotEqual(normal_scroll.tobytes(), slower_scroll.tobytes())

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

    def test_in_place_editor_regions_follow_visible_cards(self) -> None:
        cards = [CardData() for _ in range(5)]
        renderer = TimelineRenderer()
        settings = ProjectSettings()
        title = renderer.editor_region(cards, 8.0, settings, 0, "title")
        self.assertIsNotNone(title)
        self.assertAlmostEqual(title[1], 0.445)
        self.assertGreater(title[2], 0.20)
        scrolled = renderer.editor_region(cards, 8.0 + 10.0 / 3.0, settings, 1, "title")
        self.assertIsNotNone(scrolled)
        self.assertLess(scrolled[0], 0.02)

    def test_hexagon_bounce_can_be_disabled(self) -> None:
        renderer = TimelineRenderer()
        placements = renderer._placements(4, 8.0, 4, 640.0, False)
        self.assertEqual(len(placements), 4)
        self.assertTrue(all(scale == 1.0 for _index, _x, _alpha, scale in placements))
        bouncing = renderer._placements(4, 8.0, 4, 640.0, True)
        self.assertTrue(any(scale != 1.0 for _index, _x, _alpha, scale in bouncing))


if __name__ == "__main__":
    unittest.main()
