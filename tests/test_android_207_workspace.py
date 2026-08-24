from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_android_207_has_tools_faq_and_material_you() -> None:
    source = read("android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt")
    assert 'TOOLS("Tools"' in source
    assert 'FAQ("FAQ"' in source
    assert "dynamicDarkColorScheme(context)" in source
    assert "dynamicLightColorScheme(context)" in source
    cards_body = source[source.index("private fun CardsTab("):source.index("private fun ToolsTab(")]
    assert "Open project" not in cards_body
    assert "Save project" not in cards_body
    assert "Import MegaPack" not in cards_body


def test_android_207_exports_only_mp4_and_uses_new_icon() -> None:
    source = read("android/app/src/main/java/io/github/retrofrost/cts/android/MainActivity.kt")
    manifest = read("android/app/src/main/AndroidManifest.xml")
    assert 'ActivityResultContracts.CreateDocument("video/mp4")' in source
    assert "Thumbnail" not in source
    assert 'android:icon="@drawable/icon"' in manifest
    assert (ROOT / "android/app/src/main/res/drawable-nodpi/icon.png").is_file()
