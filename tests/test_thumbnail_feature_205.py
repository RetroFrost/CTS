from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_android_exports_with_the_gpu_service() -> None:
    studio = read("android/app/src/main/java/dev/infinitycomparison/cc/MainActivity.kt")
    service = read("android/app/src/main/java/dev/infinitycomparison/cc/ExportService.kt")
    exporter = read("android/app/src/main/java/dev/infinitycomparison/cc/HardwareVideoExporter.kt")
    assert 'ActivityResultContracts.CreateDocument("video/mp4")' in studio
    assert "HardwareVideoExporter(" in service
    assert "MediaCodec.createByCodecName" in exporter
    assert "EGL14.eglCreateContext" in exporter
    assert "NativeGpuRenderer" in exporter
    assert "renderRgba" not in exporter


def test_windows_creates_three_jpgs_beside_the_mp4() -> None:
    source = read("native/windows/main.cpp")
    assert 'const double fractions[] = {0.16, 0.48, 0.78};' in source
    assert 'L" - Thumbnail " + std::to_wstring(index + 1) + L".jpg"' in source
    assert '"--width", "1280", "--height", "720"' in source
    assert 'thumbnails saved beside' in source


def test_207_android_renderer_is_native_and_python_free() -> None:
    android = ROOT / "android" / "app" / "src" / "main"
    assert not (android / "python").exists()
    assert (android / "java" / "dev" / "infinitycomparison" / "cc" / "NativeGpuRenderer.kt").is_file()
