from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_android_exports_with_the_gpu_service() -> None:
    studio = read("android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt")
    service = read("android/app/src/main/java/io/github/retrofrost/cts/android/ExportService.kt")
    exporter = read("android/app/src/main/java/io/github/retrofrost/cts/android/HardwareVideoExporter.kt")
    assert 'ActivityResultContracts.CreateDocument("video/mp4")' in studio
    assert "HardwareVideoExporter(" in service
    assert "MediaCodec.createByCodecName" in exporter
    assert "EGL14.eglCreateContext" in exporter
    assert "RendererBridge.renderRgba(project, frame" in exporter


def test_windows_creates_three_jpgs_beside_the_mp4() -> None:
    source = read("native/windows/main.cpp")
    assert 'const double fractions[] = {0.16, 0.48, 0.78};' in source
    assert 'L" - Thumbnail " + std::to_wstring(index + 1) + L".jpg"' in source
    assert '"--width", "1280", "--height", "720"' in source
    assert 'thumbnails saved beside' in source


def test_207_keeps_android_and_desktop_renderers_in_sync() -> None:
    desktop = ROOT / "engine" / "ccengine"
    android = ROOT / "android" / "app" / "src" / "main" / "python" / "ccengine"
    desktop_files = sorted(p.relative_to(desktop) for p in desktop.rglob("*.py") if p.is_file())
    android_files = sorted(p.relative_to(android) for p in android.rglob("*.py") if p.is_file())
    assert desktop_files == android_files
    for relative in desktop_files:
        assert (desktop / relative).read_bytes() == (android / relative).read_bytes(), relative
