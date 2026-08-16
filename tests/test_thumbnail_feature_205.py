from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_android_exports_to_a_folder_and_writes_sibling_thumbnails() -> None:
    studio = read("android/app/src/main/java/io/github/retrofrost/cts/android/FinalStudioApp.kt")
    service = read("android/app/src/main/java/io/github/retrofrost/cts/android/FinalExportService.kt")
    generator = read("android/app/src/main/java/io/github/retrofrost/cts/android/ThumbnailGenerator.kt")
    assert "ActivityResultContracts.OpenDocumentTree()" in studio
    assert "DocumentFile.fromTreeUri" in service
    assert 'replaceDocument(folder, "video/mp4"' in service
    assert 'replaceDocument(folder, "image/jpeg"' in service
    assert "MP4 + ${thumbnails.size} thumbnails saved" in service
    assert "private const val WIDTH = 1280" in generator
    assert "private const val HEIGHT = 720" in generator
    assert 'doubleArrayOf(0.16, 0.48, 0.78)' in generator
    assert 'Bitmap.CompressFormat.JPEG, 94' in generator
    assert 'Typeface.createFromAsset(context.assets, "fonts/Poppins-Bold.ttf")' in generator


def test_windows_creates_three_jpgs_beside_the_mp4() -> None:
    source = read("native/windows/main.cpp")
    assert 'const double fractions[] = {0.16, 0.48, 0.78};' in source
    assert 'L" - Thumbnail " + std::to_wstring(index + 1) + L".jpg"' in source
    assert '"--width", "1280", "--height", "720"' in source
    assert 'thumbnails saved beside' in source


def test_205_keeps_the_verified_animation_renderer_untouched() -> None:
    desktop = ROOT / "engine" / "ccengine"
    android = ROOT / "android" / "app" / "src" / "main" / "python" / "ccengine"
    desktop_files = sorted(p.relative_to(desktop) for p in desktop.rglob("*.py") if p.is_file())
    android_files = sorted(p.relative_to(android) for p in android.rglob("*.py") if p.is_file())
    assert desktop_files == android_files
    for relative in desktop_files:
        assert (desktop / relative).read_bytes() == (android / relative).read_bytes(), relative
