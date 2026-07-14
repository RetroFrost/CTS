from __future__ import annotations

import atexit
import os
import shutil
import tempfile
from copy import deepcopy
from math import ceil
from pathlib import Path

from PIL import Image, ImageChops, ImageFilter
from PySide6.QtCore import QTimer

from . import exporter as exporter_module
from .direct_transform import TransformBox, TransformKey, _clamp_box
from .interaction_runtime import RuntimeTransformRenderer
from .optional_hexagons import OptionalHexagonRenderer
from .reselect_fix import ReselectAwareRenderer, ReselectFixedMainWindow
from .studio_ui import StudioAssetCache


class BakedTransformRenderer(ReselectAwareRenderer):
    """Composite transformed fields from pre-rendered PNGs instead of the moving card.

    The old transform renderer cropped the source field again on every timeline frame.
    That made the transformed object depend on the card's animated position. This
    renderer blanks the original field from the card and composites a baked alpha PNG
    at the saved Program Monitor coordinates instead.
    """

    ACTIVE_TRANSFORMS: dict[TransformKey, TransformBox] = {}
    ACTIVE_BAKED_FILES: dict[TransformKey, str] = {}

    def __init__(
        self,
        asset_cache=None,
        transforms: dict[TransformKey, TransformBox] | None = None,
        baked_files: dict[TransformKey, str] | None = None,
    ) -> None:
        active_transforms = transforms if transforms is not None else self.ACTIVE_TRANSFORMS
        super().__init__(asset_cache or StudioAssetCache(), active_transforms)
        self.baked_files = baked_files if baked_files is not None else self.ACTIVE_BAKED_FILES
        self._loaded_bakes: dict[str, tuple[int, int, Image.Image]] = {}

    def _base_render(self, cards, output_time: float, settings, size=None) -> Image.Image:
        """Render the normal model while bypassing the old live transform compositor."""
        previous = self.transforms
        self.transforms = {}
        try:
            return super().render(cards, output_time, settings, size)
        finally:
            self.transforms = previous

    def _valid_baked_path(self, key: TransformKey) -> Path | None:
        raw_path = self.baked_files.get(key, "")
        if not raw_path:
            return None
        path = Path(raw_path)
        return path if path.is_file() else None

    def _transformed_hit(
        self,
        cards,
        output_time: float,
        settings,
        normalized_x: float,
        normalized_y: float,
    ):
        del output_time
        for (card_index, role), raw_box in reversed(list(self.transforms.items())):
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            if self._valid_baked_path((card_index, role)) is None:
                continue
            x, y, width, height = _clamp_box(raw_box)
            if x <= normalized_x <= x + width and y <= normalized_y <= y + height:
                return card_index, role
        return None

    def editor_region(
        self,
        cards,
        output_time: float,
        settings,
        card_index: int,
        role: str,
    ):
        key = (card_index, role)
        transformed = self.transforms.get(key)
        if transformed is not None and self._valid_baked_path(key) is not None:
            return _clamp_box(transformed)
        return super().editor_region(
            cards,
            output_time,
            settings,
            card_index,
            role,
        )

    def _load_baked(self, path: Path) -> Image.Image | None:
        try:
            stat = path.stat()
            cache_key = str(path)
            cached = self._loaded_bakes.get(cache_key)
            if cached is not None and cached[0] == stat.st_mtime_ns and cached[1] == stat.st_size:
                return cached[2]
            with Image.open(path) as opened:
                image = opened.convert("RGBA").copy()
            self._loaded_bakes[cache_key] = (stat.st_mtime_ns, stat.st_size, image)
            return image
        except (OSError, ValueError):
            return None

    @staticmethod
    def _composite_clipped(canvas: Image.Image, layer: Image.Image, left: int, top: int) -> None:
        right = left + layer.width
        bottom = top + layer.height
        visible_left = max(0, left)
        visible_top = max(0, top)
        visible_right = min(canvas.width, right)
        visible_bottom = min(canvas.height, bottom)
        if visible_right <= visible_left or visible_bottom <= visible_top:
            return
        crop = (
            visible_left - left,
            visible_top - top,
            visible_right - left,
            visible_bottom - top,
        )
        canvas.alpha_composite(layer.crop(crop), (visible_left, visible_top))

    def render(self, cards, output_time: float, settings, size=None):
        active: list[tuple[int, str, TransformBox, Path]] = []
        for (card_index, role), target in self.transforms.items():
            if not (0 <= card_index < len(cards)):
                continue
            if not self._show_hexagons and role in {"badge_primary", "badge_secondary"}:
                continue
            path = self._valid_baked_path((card_index, role))
            if path is not None:
                active.append((card_index, role, _clamp_box(target), path))

        if not active:
            return self._base_render(cards, output_time, settings, size)

        blank_cards = [deepcopy(card) for card in cards]
        for card_index, role, _target, _path in active:
            self._blank_role(blank_cards[card_index], role)

        result = self._base_render(blank_cards, output_time, settings, size).convert("RGBA")
        frame_width, frame_height = result.size

        for _card_index, _role, target, path in active:
            layer = self._load_baked(path)
            if layer is None:
                continue
            x, y, width, height = target
            target_width = max(1, round(width * frame_width))
            target_height = max(1, round(height * frame_height))
            if layer.size != (target_width, target_height):
                layer = layer.resize((target_width, target_height), Image.Resampling.LANCZOS)
            self._composite_clipped(
                result,
                layer,
                round(x * frame_width),
                round(y * frame_height),
            )

        return result.convert("RGB")


# ExportWorker resolves this global at export time, so preview and MP4 use the
# same baked-PNG compositor.
exporter_module.TimelineRenderer = BakedTransformRenderer


class BakedTransformMainWindow(ReselectFixedMainWindow):
    """CTS transform UI backed by protected, session-local PNG files."""

    def __init__(self) -> None:
        self.baked_transform_files: dict[TransformKey, str] = {}
        self._baked_source_layers: dict[TransformKey, Image.Image] = {}
        self._bake_ready = False
        self._cache_cleaned = False
        self._baked_cache_dir = Path(tempfile.mkdtemp(prefix="cts-baked-transforms-"))
        try:
            self._baked_cache_dir.chmod(0o700)
        except OSError:
            pass

        super().__init__()

        self._rebake_timer = QTimer(self)
        self._rebake_timer.setSingleShot(True)
        self._rebake_timer.setInterval(140)
        self._rebake_timer.timeout.connect(self._rebake_all_transforms)
        self._bake_ready = True
        atexit.register(self._cleanup_transform_cache)

        self.renderer = self._new_renderer()
        self.statusBar().showMessage(
            "Ready · transformed objects are baked into protected temporary PNGs"
        )
        self.update_preview()

    def _new_renderer(self) -> BakedTransformRenderer:
        RuntimeTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        BakedTransformRenderer.ACTIVE_TRANSFORMS = self.transform_overrides
        BakedTransformRenderer.ACTIVE_BAKED_FILES = self.baked_transform_files
        return BakedTransformRenderer(
            StudioAssetCache(),
            self.transform_overrides,
            self.baked_transform_files,
        )

    def _cache_path(self, card_index: int, role: str) -> Path:
        safe_role = "".join(character if character.isalnum() else "-" for character in role)
        return self._baked_cache_dir / f"card-{card_index:05d}-{safe_role}.png"

    def _write_baked_png(self, key: TransformKey, image: Image.Image) -> Path:
        path = self._cache_path(*key)
        writing = path.with_name(path.stem + ".writing.png")
        image.convert("RGBA").save(writing, format="PNG")
        os.replace(writing, path)
        try:
            path.chmod(0o600)
        except OSError:
            pass
        self.baked_transform_files[key] = str(path)
        return path

    @staticmethod
    def _region_is_fully_visible(region: TransformBox) -> bool:
        x, y, width, height = region
        return x >= 0.0 and y >= 0.0 and x + width <= 1.0 and y + height <= 1.0

    def _find_source_time(
        self,
        renderer: OptionalHexagonRenderer,
        cards,
        settings,
        card_index: int,
        role: str,
    ) -> tuple[float, TransformBox] | None:
        duration = max(0.0, float(settings.duration(len(cards))))
        current = max(0.0, min(duration, float(self.position_seconds)))
        first_partial: tuple[float, TransformBox] | None = None

        candidates = [current]
        if duration > 0:
            steps = max(1, min(600, ceil(duration / 0.10)))
            candidates.extend(duration * index / steps for index in range(steps + 1))

        checked: set[int] = set()
        for output_time in candidates:
            marker = round(output_time * 1000)
            if marker in checked:
                continue
            checked.add(marker)
            region = renderer.editor_region(
                cards,
                output_time,
                settings,
                card_index,
                role,
            )
            if region is None:
                continue
            normalized = tuple(float(value) for value in region)
            if first_partial is None:
                first_partial = (output_time, normalized)
            if self._region_is_fully_visible(normalized):
                return output_time, normalized
        return first_partial

    def _extract_source_layer(self, key: TransformKey) -> Image.Image | None:
        cards = self.cards()
        card_index, role = key
        if not (0 <= card_index < len(cards)):
            return None

        settings = self.project_settings()
        renderer = OptionalHexagonRenderer(StudioAssetCache(), {})
        located = self._find_source_time(renderer, cards, settings, card_index, role)
        if located is None:
            return None
        output_time, source_region = located

        frame_size = (settings.width, settings.height)
        pristine = renderer.render(cards, output_time, settings, frame_size).convert("RGBA")
        blank_cards = [deepcopy(card) for card in cards]
        renderer._blank_role(blank_cards[card_index], role)
        blank = renderer.render(blank_cards, output_time, settings, frame_size).convert("RGBA")

        source_box = renderer._pixel_box(source_region, pristine.size)
        foreground = pristine.crop(source_box)
        background = blank.crop(source_box)
        difference = ImageChops.difference(foreground, background).convert("L")
        alpha = difference.point(lambda value: 255 if value > 8 else 0).filter(
            ImageFilter.GaussianBlur(0.55)
        )
        if alpha.getbbox() is None:
            return None
        foreground.putalpha(alpha)
        return foreground

    def _bake_transform(self, key: TransformKey, box: TransformBox) -> bool:
        source = self._baked_source_layers.get(key)
        if source is None:
            source = self._extract_source_layer(key)
            if source is None:
                return False
            self._baked_source_layers[key] = source

        settings = self.project_settings()
        _x, _y, width, height = _clamp_box(box)
        target_size = (
            max(1, round(width * settings.width)),
            max(1, round(height * settings.height)),
        )
        baked = source.resize(target_size, Image.Resampling.LANCZOS)
        self._write_baked_png(key, baked)
        return True

    def _delete_baked_transform(self, key: TransformKey) -> None:
        raw_path = self.baked_transform_files.pop(key, "")
        self._baked_source_layers.pop(key, None)
        if raw_path:
            try:
                Path(raw_path).unlink(missing_ok=True)
            except OSError:
                pass

    def _clear_baked_files(self) -> None:
        for key in list(self.baked_transform_files):
            self._delete_baked_transform(key)
        self._baked_source_layers.clear()

    def _transform_changed(self, card_index: int, role: str, box: object) -> None:
        if not (isinstance(box, tuple) and len(box) == 4):
            return

        key = (card_index, role)
        target = _clamp_box(tuple(float(value) for value in box))
        previous = self.transform_overrides.get(key)
        self.transform_overrides[key] = target

        if not self._bake_transform(key, target):
            if previous is None:
                self.transform_overrides.pop(key, None)
            else:
                self.transform_overrides[key] = previous
            self.statusBar().showMessage(
                "Could not bake that object. Its previous transform was preserved.",
                5000,
            )
            return

        self.renderer = self._new_renderer()
        self.update_preview()
        self.preview.begin_transform(card_index, role, target)
        self.statusBar().showMessage(
            f"Baked {role.replace('_', ' ')} into the protected CTS temp cache",
            1800,
        )

    def _transform_reset(self, card_index: int, role: str) -> None:
        self._delete_baked_transform((card_index, role))
        super()._transform_reset(card_index, role)

    def _rebake_all_transforms(self) -> None:
        if not self._bake_ready:
            return

        active_keys = set(self.transform_overrides)
        for key in list(self.baked_transform_files):
            if key not in active_keys:
                self._delete_baked_transform(key)

        self._baked_source_layers.clear()
        failed: list[TransformKey] = []
        for key, box in list(self.transform_overrides.items()):
            if not self._bake_transform(key, box):
                failed.append(key)

        self.renderer = self._new_renderer()
        self.update_preview()
        if failed:
            self.statusBar().showMessage(
                f"Could not rebuild {len(failed)} transformed object(s) into the temp cache",
                5000,
            )

    def _data_changed(self) -> None:
        super()._data_changed()
        if self._bake_ready and self.transform_overrides:
            self._rebake_timer.start()

    def open_project(self) -> None:
        self._clear_baked_files()
        super().open_project()
        self._rebake_all_transforms()

    def export_video(self) -> None:
        missing = [
            key
            for key in self.transform_overrides
            if not self.baked_transform_files.get(key)
            or not Path(self.baked_transform_files[key]).is_file()
        ]
        if missing:
            self._rebake_all_transforms()
        super().export_video()

    def _cleanup_transform_cache(self) -> None:
        if self._cache_cleaned:
            return
        self._cache_cleaned = True
        try:
            shutil.rmtree(self._baked_cache_dir, ignore_errors=True)
        except Exception:
            pass

    def closeEvent(self, event) -> None:  # noqa: N802
        super().closeEvent(event)
        if event.isAccepted():
            self._cleanup_transform_cache()
