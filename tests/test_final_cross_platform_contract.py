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
    assert "fun badgeTextProgress" in timeline
    assert "fun outroCoverY" in timeline
    assert "fun outroGroupTop" in timeline


def test_native_preview_and_gpu_export_share_badge_and_outro_layers() -> None:
    canvas = (KOTLIN / "NativeSceneRenderer.kt").read_text(encoding="utf-8")
    gpu = (KOTLIN / "NativeGpuRenderer.kt").read_text(encoding="utf-8")
    for source in (canvas, gpu):
        assert "NativeArtwork.badgeShell" in source
        assert "NativeArtwork.badgeText" in source
        assert "NativeTimeline.badgeTextProgress" in source
        assert "NativeTimeline.outroActionBar" in source
        assert "NativeTimeline.outroSubscribe" in source


def test_android_export_has_runtime_fallback_and_memory_guard() -> None:
    exporter = (KOTLIN / "HardwareVideoExporter.kt").read_text(encoding="utf-8")
    service = (KOTLIN / "ExportService.kt").read_text(encoding="utf-8")
    scene = (KOTLIN / "NativeSceneRenderer.kt").read_text(encoding="utf-8")
    assert "HardwareCodecSelector.candidates" in exporter
    assert '"Encoder fallback"' in exporter
    assert "BITRATE_MODE_CBR" in exporter
    assert "EGL_RECORDABLE_ANDROID" in exporter
    assert "NativeFrameRenderer.trimCaches()" in service
    assert "fun trimCaches()" in scene


def test_scrolling_badges_can_be_settled_by_project_option() -> None:
    project = (KOTLIN / "StudioProject.kt").read_text(encoding="utf-8")
    timeline = (KOTLIN / "NativeTimeline.kt").read_text(encoding="utf-8")
    ui = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
    assert "settledScrollingBadges" in project
    assert '"settled_scrolling_badges"' in project
    assert "if (settledScrollingBadges) return 0f" in timeline
    assert "Badges already placed while scrolling" in ui


def test_android_recovers_crash_diagnostics_to_clipboard() -> None:
    manifest = (ROOT / "android/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    journal = (KOTLIN / "CrashJournal.kt").read_text(encoding="utf-8")
    activity = (KOTLIN / "MainActivity.kt").read_text(encoding="utf-8")
    service = (KOTLIN / "ExportService.kt").read_text(encoding="utf-8")

    assert 'android:name=".CubicalCompareApplication"' in manifest
    assert "Thread.setDefaultUncaughtExceptionHandler" in journal
    assert "copyPendingReportToClipboard" in journal
    assert "ClipboardManager" in journal
    assert "KEY_EXPORT_ACTIVE" in journal
    assert "copyPendingReportToClipboard(this)" in activity
    assert "CrashJournal.updateExportStage" in service
    assert '.putBoolean(KEY_EXPORT_ACTIVE, false)' in journal


def test_android_gpu_normalises_recordable_textures_for_mali() -> None:
    gpu = (KOTLIN / "NativeGpuRenderer.kt").read_text(encoding="utf-8")
    exporter = (KOTLIN / "HardwareVideoExporter.kt").read_text(encoding="utf-8")

    assert "source.copy(Bitmap.Config.ARGB_8888, false)" in gpu
    assert "uploading RGBA texture" in gpu
    assert "swapping encoder surface" in exporter
    assert "submitted to encoder" in exporter


def test_badge_text_is_visible_with_the_badge_instead_of_animating_later() -> None:
    timeline = (KOTLIN / "NativeTimeline.kt").read_text(encoding="utf-8")

    assert "if (badgeOffset(index, localFrame, settledScrollingBadges) != null) 1f else 0f" in timeline


def test_opening_badge_shine_finishes_before_the_next_card_enters() -> None:
    timeline = (KOTLIN / "NativeTimeline.kt").read_text(encoding="utf-8")

    assert "openingShineStart = 95" in timeline
    assert "openingShineEndExclusive = 120" in timeline
    assert "scrollingShineStart = 208" in timeline
    assert "scrollingShineEndExclusive = 241" in timeline
