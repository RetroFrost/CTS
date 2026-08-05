from __future__ import annotations

from pathlib import Path
import io
import math

from PIL import Image

from ccengine.exporter import VideoExporter
from ccengine.models import Card, Project
from ccengine.renderer import FrameRenderer
from ccengine.timing import total_duration
from engine_cli import read_ccx, write_ccx


def test_free_transform_round_trips_json_and_ccx(tmp_path: Path) -> None:
    card = Card(
        title="Moved",
        value="7",
        image_x=42.5,
        image_y=-18.0,
        image_scale=1.75,
        image_rotation=12.0,
        image_crop_left=0.10,
        image_crop_top=0.05,
        image_crop_right=0.15,
        image_crop_bottom=0.20,
        image_layer="front",
    )
    project = Project(cards=[card])

    json_path = tmp_path / "project.json"
    project.save(json_path)
    reopened = Project.load(json_path)
    assert reopened.cards[0].image_x == 42.5
    assert reopened.cards[0].image_scale == 1.75
    assert reopened.cards[0].image_layer == "front"

    ccx_path = tmp_path / "project.ccx"
    write_ccx(reopened, ccx_path)
    ccx = read_ccx(ccx_path)
    assert ccx.cards[0].image_y == -18.0
    assert ccx.cards[0].image_rotation == 12.0
    assert ccx.cards[0].image_crop_bottom == 0.20


def test_front_layer_can_cover_model_text_band(tmp_path: Path) -> None:
    artwork = tmp_path / "art.png"
    Image.new("RGB", (64, 64), (255, 0, 255)).save(artwork)
    card = Card(
        title="Title",
        description="Description",
        image=str(artwork),
        image_scale=2.0,
        image_y=280.0,
        image_layer="front",
    )
    project = Project(cards=[card])
    renderer = FrameRenderer()
    frame = renderer.render(project, 2.8)
    # Front artwork is composited after both text bands and the badge.
    assert frame.getpixel((240, 900))[0] > 240
    assert frame.getpixel((240, 900))[2] > 240
    assert frame.getpixel((240, 200))[0] > 240
    assert frame.getpixel((240, 200))[2] > 240


class _CountingRenderer:
    def __init__(self) -> None:
        self.calls = 0

    def render_output_frame(self, project: Project, seconds: float) -> Image.Image:
        self.calls += 1
        return Image.new("RGB", (project.settings.width, project.settings.height), (0, 0, 0))


class _FakeStdin:
    def write(self, payload: bytes) -> int:
        return len(payload)

    def close(self) -> None:
        pass


class _FakeStderr:
    def read(self) -> bytes:
        return b""


class _FakeProcess:
    def __init__(self, command: list[str], **_: object) -> None:
        self.command = command
        self.stdin = _FakeStdin()
        self.stderr = _FakeStderr()
        self._returncode: int | None = None

    def wait(self) -> int:
        output = Path(self.command[-1])
        output.write_bytes(b"\x00\x00\x00\x18ftypisom" + b"0" * 300)
        self._returncode = 0
        return 0

    def poll(self) -> int | None:
        return self._returncode

    def terminate(self) -> None:
        self._returncode = 1

    def kill(self) -> None:
        self._returncode = 1


def test_static_end_hold_is_rendered_once(monkeypatch, tmp_path: Path) -> None:
    project = Project(cards=[Card("Card", "1")])
    project.settings.width = 2
    project.settings.height = 2
    project.settings.fps = 2
    project.settings.credits_enabled = False
    renderer = _CountingRenderer()
    exporter = VideoExporter(renderer=renderer)  # type: ignore[arg-type]
    monkeypatch.setattr(exporter, "ffmpeg_path", lambda: "ffmpeg")
    monkeypatch.setattr("ccengine.exporter.subprocess.Popen", _FakeProcess)

    exporter.export(project, tmp_path / "out.mp4")
    total_frames = math.ceil(total_duration(project) * project.settings.fps)
    # The 4.55-second hold would otherwise cost roughly nine full renders at 2 FPS.
    assert renderer.calls <= total_frames - 7
