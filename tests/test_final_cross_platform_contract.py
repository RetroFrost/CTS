from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DESKTOP = ROOT / "engine" / "ccengine"
ANDROID = ROOT / "android" / "app" / "src" / "main" / "python" / "ccengine"


def _files(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)).replace("\\", "/"): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"
    }


def test_android_embeds_the_desktop_renderer_byte_for_byte() -> None:
    desktop = _files(DESKTOP)
    android = _files(ANDROID)
    assert desktop
    assert desktop.keys() == android.keys()
    for name, content in desktop.items():
        assert android[name] == content, f"Android renderer drifted from desktop: {name}"


def test_old_android_renderer_is_completely_removed() -> None:
    java = ROOT / "android" / "app" / "src" / "main" / "java"
    forbidden = {
        "ReferenceFrameRenderer.kt",
        "ReferenceOverlayRenderer.kt",
        "ReferenceBadgePainter.kt",
        "ReferenceCardPainter.kt",
        "ReferenceScene.kt",
        "TimelineEngine.kt",
        "ExactReferenceFrames.kt",
    }
    present = {path.name for path in java.rglob("*.kt")}
    assert forbidden.isdisjoint(present)


def test_android_preview_and_export_call_shared_renderer() -> None:
    bridge = (ROOT / "android" / "app" / "src" / "main" / "java" / "io" / "github" / "retrofrost" / "cts" / "android" / "SharedRenderer.kt").read_text(encoding="utf-8")
    exporter = (ROOT / "android" / "app" / "src" / "main" / "java" / "io" / "github" / "retrofrost" / "cts" / "android" / "FinalExportEngine.kt").read_text(encoding="utf-8")
    python_bridge = (ROOT / "android" / "app" / "src" / "main" / "python" / "cts_android_bridge.py").read_text(encoding="utf-8")
    assert 'getModule("cts_android_bridge")' in bridge
    assert "SharedRenderer.render(project, frame" in exporter
    assert "FrameRenderer" in python_bridge
    assert "_renderer.render(" in python_bridge
