from pathlib import Path


def replace(path: str, old: str, new: str, count: int = 1) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"expected text not found in {path}: {old[:80]!r}")
    p.write_text(text.replace(old, new, count), encoding="utf-8")


replace(
    "android/app/build.gradle.kts",
    '''        watchDataFonts.forEach { (filename, source) ->
            val destination = fontDir.resolve(filename)
            val expected = source.second
            if (destination.isFile && gitBlobSha1(destination.readBytes()) == expected) {
                return@forEach
            }
            val payload = URI(source.first).toURL().openStream().use { it.readBytes() }
            val actual = gitBlobSha1(payload)
            check(actual == expected) {
                "Official Poppins font verification failed for $filename: $actual"
            }
            destination.writeBytes(payload)
        }
''',
    '''        watchDataFonts.forEach { (filename, source) ->
            val destination = fontDir.resolve(filename)
            val expected = source.second
            if (destination.isFile && gitBlobSha1(destination.readBytes()) == expected) {
                return@forEach
            }
            val payload = URI(source.first).toURL().openStream().use { it.readBytes() }
            val actual = gitBlobSha1(payload)
            check(actual == expected) {
                "Official Poppins font verification failed for $filename: $actual"
            }
            destination.writeBytes(payload)
        }
        val thumbnailFontDir = layout.projectDirectory.dir("src/main/assets/fonts").asFile
        thumbnailFontDir.mkdirs()
        fontDir.resolve("Poppins-Bold.ttf").copyTo(
            thumbnailFontDir.resolve("Poppins-Bold.ttf"),
            overwrite = true,
        )
''',
)

path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ThumbnailGenerator.kt")
text = path.read_text(encoding="utf-8")
text = text.replace("import android.graphics.Bitmap\n", "import android.content.Context\nimport android.graphics.Bitmap\n", 1)
text = text.replace(
    "    fun create(project: StudioProject, baseName: String): List<Thumbnail> {",
    "    fun create(context: Context, project: StudioProject, baseName: String): List<Thumbnail> {",
    1,
)
text = text.replace(
    "                val composed = compose(source, card?.title.orEmpty(), card?.value.orEmpty(), project.name)",
    "                val composed = compose(context, source, card?.title.orEmpty(), card?.value.orEmpty(), project.name)",
    1,
)
text = text.replace(
    "    private fun compose(source: Bitmap, cardTitle: String, value: String, projectName: String): Bitmap {",
    "    private fun compose(context: Context, source: Bitmap, cardTitle: String, value: String, projectName: String): Bitmap {",
    1,
)
text = text.replace('            typeface = Typeface.create("sans-serif", Typeface.BOLD)\n', '            typeface = thumbnailTypeface(context)\n', 2)
needle = "    private fun shortHeadline(value: String): String {\n"
helper = '''    private fun thumbnailTypeface(context: Context): Typeface = runCatching {
        Typeface.createFromAsset(context.assets, "fonts/Poppins-Bold.ttf")
    }.getOrElse {
        Typeface.create("sans-serif", Typeface.BOLD)
    }

'''
if needle not in text:
    raise SystemExit("thumbnail helper insertion point not found")
text = text.replace(needle, helper + needle, 1)
path.write_text(text, encoding="utf-8")

replace(
    "android/app/src/main/java/io/github/retrofrost/cts/android/FinalExportService.kt",
    "val thumbnails = ThumbnailGenerator.create(project, baseName)",
    "val thumbnails = ThumbnailGenerator.create(applicationContext, project, baseName)",
)

# Strengthen the static contract test.
replace(
    "tests/test_thumbnail_feature_205.py",
    '    assert \'Bitmap.CompressFormat.JPEG, 94\' in generator\n',
    '    assert \'Bitmap.CompressFormat.JPEG, 94\' in generator\n    assert \'Typeface.createFromAsset(context.assets, "fonts/Poppins-Bold.ttf")\' in generator\n',
)
