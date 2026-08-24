from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = ROOT / "android" / "app" / "src" / "main" / "java" / "dev" / "infinitycomparison" / "cc"


def _files(root: Path) -> dict[str, bytes]:
    return {
        str(path.relative_to(root)).replace("\\", "/"): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc"
    }


def test_android_contains_no_python_runtime_or_sources() -> None:
    android = ROOT / "android"
    assert not (android / "app" / "src" / "main" / "python").exists()
    sources = "\n".join(
        path.read_text(encoding="utf-8", errors="ignore")
        for path in android.rglob("*")
        if path.is_file() and "build" not in path.parts
    ).lower()
    assert "chaquopy" not in sources
    assert "com.chaquo" not in sources


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
    bridge = (KOTLIN / "RendererBridge.kt").read_text(encoding="utf-8")
    exporter = (KOTLIN / "HardwareVideoExporter.kt").read_text(encoding="utf-8")
    assert "NativeFrameRenderer.render" in bridge
    assert "NativeGpuRenderer" in exporter
    assert "MediaCodec.createByCodecName" in exporter
    assert "renderRgba" not in exporter


def test_android_uses_new_package_and_fixed_badge_geometry() -> None:
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    artwork = (KOTLIN / "NativeSceneRenderer.kt").read_text(encoding="utf-8")
    assert 'namespace = "dev.infinitycomparison.cc"' in gradle
    assert 'applicationId = "dev.infinitycomparison.cc"' in gradle
    assert "const val badgeWidth = 325" in artwork
    assert "const val badgeHeight = 375" in artwork
    timeline = (KOTLIN / "NativeTimeline.kt").read_text(encoding="utf-8")
    assert "fun badgeShineProgress" in timeline
