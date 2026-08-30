from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:220]!r}")
    p.write_text(text.replace(old, new, 1))


def replace_count(path: str, old: str, new: str, expected: int) -> None:
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"expected {expected} occurrences in {path}, found {count}: {old[:180]!r}")
    p.write_text(text.replace(old, new))


root = Path("android/app/src/main/java/io/github/retrofrost/cts/android")

# Central resolver: renderer defaults remain byte-for-byte behaviour unless the project opts in.
(root / "ProjectFontResolver.kt").write_text('''package io.github.retrofrost.cts.android

import android.graphics.Typeface
import java.io.File
import java.util.LinkedHashMap

/** Resolves the optional project-wide comparison font without changing renderer-owned typography. */
object ProjectFontResolver {
    private const val MAX_CACHE = 8
    private val fileCache = object : LinkedHashMap<String, Typeface>(MAX_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Typeface>?): Boolean = size > MAX_CACHE
    }

    @Synchronized
    fun resolve(project: StudioProject, fallback: Typeface, style: Int): Typeface {
        val customPath = project.fontFile.trim()
        if (customPath.isNotEmpty()) {
            val file = File(customPath)
            if (file.isFile) {
                val key = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                val base = fileCache[key] ?: runCatching { Typeface.createFromFile(file) }.getOrNull()?.also {
                    fileCache[key] = it
                }
                if (base != null) return if (style == Typeface.NORMAL) base else Typeface.create(base, style)
            }
        }

        val family = project.fontFamily.trim()
        if (family.isNotEmpty()) return Typeface.create(family, style)
        return fallback
    }

    fun displayName(project: StudioProject): String = when {
        project.fontFile.isNotBlank() -> File(project.fontFile).nameWithoutExtension.ifBlank { "Custom font" }
        project.fontFamily.isNotBlank() -> project.fontFamily
        else -> "Renderer default"
    }

    fun isUsable(file: File): Boolean = file.isFile && runCatching { Typeface.createFromFile(file) }.getOrNull() != null
}
''')

# Persist font selection in projects. Old v4 projects continue to load with renderer defaults.
studio = str(root / "StudioProject.kt")
replace_once(
    studio,
    '''    val customLengthSeconds: Double = 90.0,\n    val encoderPreference: EncoderPreference = EncoderPreference.AUTO,\n) {''',
    '''    val customLengthSeconds: Double = 90.0,\n    val encoderPreference: EncoderPreference = EncoderPreference.AUTO,\n    val fontFamily: String = "",\n    val fontFile: String = "",\n) {''',
)
replace_once(
    studio,
    '''            .put("encoder_preference", encoderPreference.wireName)\n            .put("encoder_preset", "faster")''',
    '''            .put("encoder_preference", encoderPreference.wireName)\n            .put("font_family", fontFamily)\n            .put("font_file", fontFile)\n            .put("encoder_preset", "faster")''',
)
replace_once(studio, '.put("version", 4)', '.put("version", 5)')
replace_once(
    studio,
    '''        introMode = previous.introMode,\n        introVideo = previous.introVideo,\n    )''',
    '''        introMode = previous.introMode,\n        introVideo = previous.introVideo,\n        fontFamily = previous.fontFamily,\n        fontFile = previous.fontFile,\n    )''',
)
replace_once(
    studio,
    '''                customLengthSeconds = settings.optDouble("custom_length_seconds", 90.0),\n                encoderPreference = EncoderPreference.fromWireName(settings.optString("encoder_preference", "auto")),\n            )''',
    '''                customLengthSeconds = settings.optDouble("custom_length_seconds", 90.0),\n                encoderPreference = EncoderPreference.fromWireName(settings.optString("encoder_preference", "auto")),\n                fontFamily = settings.optString("font_family", ""),\n                fontFile = settings.optString("font_file", ""),\n            )''',
)

# UI: preset picker + custom TTF/OTF file picker.
main = str(root / "MainActivity.kt")
replace_once(
    main,
    '''private data class AccuracyState(val label: String, val detail: String, val exact: Boolean)\n''',
    '''private data class AccuracyState(val label: String, val detail: String, val exact: Boolean)\nprivate data class FontChoice(val label: String, val family: String)\n\nprivate val ProjectFontChoices = listOf(\n    FontChoice("Renderer default", ""),\n    FontChoice("Sans", "sans-serif"),\n    FontChoice("Condensed", "sans-serif-condensed"),\n    FontChoice("Serif", "serif"),\n    FontChoice("Mono", "monospace"),\n)\n''',
)
replace_once(
    main,
    '''    if (!project.autoLength) issues += "duration"\n    if (issues.isNotEmpty())''',
    '''    if (!project.autoLength) issues += "duration"\n    if (project.fontFamily.isNotBlank() || project.fontFile.isNotBlank()) issues += "font"\n    if (issues.isNotEmpty())''',
)
replace_once(
    main,
    '''    val chooseSoundtrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n        if (uri != null) {\n            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }\n            applyProject(project.copy(soundtrack = uri.toString()))\n        }\n    }\n\n    var pendingExport''',
    '''    val chooseSoundtrack = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n        if (uri != null) {\n            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }\n            applyProject(project.copy(soundtrack = uri.toString()))\n        }\n    }\n\n    val chooseFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n        if (uri != null) scope.launch {\n            runCatching {\n                withContext(Dispatchers.IO) {\n                    val file = RendererBridge.materialize(context, uri, "font")\n                    require(ProjectFontResolver.isUsable(file)) { "The selected file is not a readable font." }\n                    file\n                }\n            }.onSuccess { file ->\n                applyProject(project.copy(fontFamily = "", fontFile = file.absolutePath))\n            }.onFailure(::report)\n        }\n    }\n\n    var pendingExport''',
)
replace_once(
    main,
    '''                    onChooseIntro = { chooseIntro.launch(arrayOf("video/mp4", "video/*")) },\n                    onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) },\n                    modifier = Modifier.padding(padding),''',
    '''                    onChooseIntro = { chooseIntro.launch(arrayOf("video/mp4", "video/*")) },\n                    onChooseSoundtrack = { chooseSoundtrack.launch(arrayOf("audio/*")) },\n                    onChooseFont = { chooseFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream")) },\n                    modifier = Modifier.padding(padding),''',
)
replace_once(
    main,
    '''    onProjectChange: (StudioProject) -> Unit,\n    onChooseIntro: () -> Unit,\n    onChooseSoundtrack: () -> Unit,\n    modifier: Modifier = Modifier,''',
    '''    onProjectChange: (StudioProject) -> Unit,\n    onChooseIntro: () -> Unit,\n    onChooseSoundtrack: () -> Unit,\n    onChooseFont: () -> Unit,\n    modifier: Modifier = Modifier,''',
)
replace_once(
    main,
    '''        item {\n            SectionCard("Audio") {''',
    '''        item {\n            SectionCard("Typography") {\n                SettingRow("Comparison font", ProjectFontResolver.displayName(project))\n                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                    itemsIndexed(ProjectFontChoices) { _, choice ->\n                        FilterChip(\n                            selected = project.fontFile.isBlank() && project.fontFamily == choice.family,\n                            onClick = { onProjectChange(project.copy(fontFamily = choice.family, fontFile = "")) },\n                            label = { Text(choice.label) },\n                        )\n                    }\n                }\n                Button(onClick = onChooseFont, modifier = Modifier.fillMaxWidth()) {\n                    Text(if (project.fontFile.isBlank()) "Choose TTF / OTF" else "Replace custom font")\n                }\n                if (project.fontFile.isNotBlank()) {\n                    Text(project.fontFile.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)\n                    OutlinedButton(\n                        onClick = { onProjectChange(project.copy(fontFamily = "", fontFile = "")) },\n                        modifier = Modifier.fillMaxWidth(),\n                    ) { Text("Use renderer default") }\n                }\n                Text(\n                    "Applies to card titles, descriptions and badge text. Renderer-owned intro, credits and outro typography stay unchanged.",\n                    style = MaterialTheme.typography.bodySmall,\n                    color = MaterialTheme.colorScheme.onSurfaceVariant,\n                )\n            }\n        }\n        item {\n            SectionCard("Audio") {''',
)

# Native renderer: project font owns comparison content only.
native = str(root / "NativeFrameRenderer.kt")
replace_count(
    native,
    'typeface = Typeface.create("sans-serif", Typeface.BOLD),',
    'typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD),',
    2,
)
replace_once(native, 'drawBadge(canvas, card, localFrame, spec)', 'drawBadge(canvas, project, card, localFrame, spec)')
replace_once(
    native,
    'private fun drawBadge(canvas: Canvas, card: StudioCard, localFrame: Int, spec: RendererSpec) {',
    'private fun drawBadge(canvas: Canvas, project: StudioProject, card: StudioCard, localFrame: Int, spec: RendererSpec) {',
)
replace_once(
    native,
    'textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)',
    'textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.BOLD), Typeface.BOLD)',
)

# Legacy relationships renderer.
legacy = str(root / "RelationshipsFrameRenderer.kt")
replace_once(
    legacy,
    'positions.forEach { (index, x) -> drawCardBody(canvas, project.cards[index], x, spec, frame, index) }',
    'positions.forEach { (index, x) -> drawCardBody(canvas, project, project.cards[index], x, spec, frame, index) }',
)
replace_once(
    legacy,
    'private fun drawCardBody(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, frame: Int, index: Int) {',
    'private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec, frame: Int, index: Int) {',
)
replace_once(
    legacy,
    'drawFitted(canvas, card.title, RectF(left + 10f, cursor + 1f, right - 10f, cursor + titleHeight - 1f), spec.titleTextColor, spec.titleTextSize, true, 1)',
    'drawFitted(canvas, card.title, RectF(left + 10f, cursor + 1f, right - 10f, cursor + titleHeight - 1f), spec.titleTextColor, spec.titleTextSize, true, 1, project)',
)
replace_once(
    legacy,
    'drawFitted(canvas, card.description, RectF(left + 11f, cursor + 4f, right - 11f, 1076f), spec.descriptionTextColor, spec.descriptionTextSize, false, 4)',
    'drawFitted(canvas, card.description, RectF(left + 11f, cursor + 4f, right - 11f, 1076f), spec.descriptionTextColor, spec.descriptionTextSize, false, 4, project)',
)
replace_once(legacy, 'drawBadgeText(canvas, card, spec)', 'drawBadgeText(canvas, project, card, spec)')
replace_once(
    legacy,
    'private fun drawBadgeText(canvas: Canvas, card: StudioCard, spec: RendererSpec) {',
    'private fun drawBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, spec: RendererSpec) {',
)
replace_once(
    legacy,
    'textPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)',
    'textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", Typeface.NORMAL), Typeface.NORMAL)',
)
replace_once(
    legacy,
    'drawCardBody(canvas, last, cardX, spec, frame, project.cards.lastIndex)',
    'drawCardBody(canvas, project, last, cardX, spec, frame, project.cards.lastIndex)',
)
replace_once(
    legacy,
    'private fun drawFitted(canvas: Canvas, text: String, box: RectF, color: Int, preferred: Float, bold: Boolean, maxLines: Int) {\n        textPaint.color = color; textPaint.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL); textPaint.textAlign = Paint.Align.CENTER',
    'private fun drawFitted(canvas: Canvas, text: String, box: RectF, color: Int, preferred: Float, bold: Boolean, maxLines: Int, project: StudioProject) {\n        val style = if (bold) Typeface.BOLD else Typeface.NORMAL\n        textPaint.color = color; textPaint.typeface = ProjectFontResolver.resolve(project, Typeface.create("sans-serif", style), style); textPaint.textAlign = Paint.Align.CENTER',
)

# Ribbon renderer: card content uses the project font; credits/action UI remain renderer-owned.
ribbon = str(root / "RibbonFrameRenderer.kt")
replace_once(
    ribbon,
    'drawCardBody(canvas, project.cards[index], positions.getValue(index), spec)',
    'drawCardBody(canvas, project, project.cards[index], positions.getValue(index), spec)',
)
replace_once(
    ribbon,
    'private fun drawCardBody(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec) {',
    'private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec) {',
)
replace_count(ribbon, '                true,\n            )', '                true,\n                project,\n            )', 1)
replace_count(ribbon, '                false,\n            )', '                false,\n                project,\n            )', 1)
replace_once(
    ribbon,
    '''        maxLines: Int,\n        bold: Boolean,\n    ) {''',
    '''        maxLines: Int,\n        bold: Boolean,\n        project: StudioProject,\n    ) {''',
)
replace_once(
    ribbon,
    '''        while (true) {\n            textPaint.typeface = if (bold) boldTypeface else regularTypeface\n            textPaint.textSize = size''',
    '''        while (true) {\n            val style = if (bold) Typeface.BOLD else Typeface.NORMAL\n            val fallback = if (bold) boldTypeface else regularTypeface\n            textPaint.typeface = ProjectFontResolver.resolve(project, fallback, style)\n            textPaint.textSize = size''',
)
replace_once(ribbon, 'drawBadgeSource(canvas, card, age, spec, index, local)', 'drawBadgeSource(canvas, project, card, age, spec, index, local)')
replace_once(
    ribbon,
    '''    private fun drawBadgeSource(\n        canvas: Canvas,\n        card: StudioCard,''',
    '''    private fun drawBadgeSource(\n        canvas: Canvas,\n        project: StudioProject,\n        card: StudioCard,''',
)
replace_once(ribbon, 'drawRibbonBadgeText(canvas, card, age, spec)', 'drawRibbonBadgeText(canvas, project, card, age, spec)')
replace_once(
    ribbon,
    'private fun drawRibbonBadgeText(canvas: Canvas, card: StudioCard, age: Float, spec: RendererSpec) {',
    'private fun drawRibbonBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, age: Float, spec: RendererSpec) {',
)
replace_once(
    ribbon,
    '            textPaint.typeface = boldTypeface\n            textPaint.textAlign = Paint.Align.CENTER',
    '            textPaint.typeface = ProjectFontResolver.resolve(project, boldTypeface, Typeface.BOLD)\n            textPaint.textAlign = Paint.Align.CENTER',
)

# Exact relationships renderer: preserve embedded renderer fonts unless the user explicitly picks one.
precision = str(root / "RelationshipsPrecisionFrameRenderer.kt")
replace_once(
    precision,
    'drawCardBody(canvas, project.cards[index], x, spec, cfg, frame, index)',
    'drawCardBody(canvas, project, project.cards[index], x, spec, cfg, frame, index)',
)
replace_once(
    precision,
    'private fun drawCardBody(canvas: Canvas, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig, frame: Int, index: Int) {',
    'private fun drawCardBody(canvas: Canvas, project: StudioProject, card: StudioCard, slotX: Float, spec: RendererSpec, cfg: ExactConfig, frame: Int, index: Int) {',
)
replace_once(
    precision,
    'typeface(spec, cfg, "title", "sans-serif", Typeface.BOLD),',
    'ProjectFontResolver.resolve(project, typeface(spec, cfg, "title", "sans-serif", Typeface.BOLD), Typeface.BOLD),',
)
replace_once(
    precision,
    'typeface(spec, cfg, "description", "sans-serif", Typeface.NORMAL),',
    'ProjectFontResolver.resolve(project, typeface(spec, cfg, "description", "sans-serif", Typeface.NORMAL), Typeface.NORMAL),',
)
replace_once(precision, 'drawBadgeText(canvas, card, spec, cfg, textAlpha)', 'drawBadgeText(canvas, project, card, spec, cfg, textAlpha)')
replace_once(
    precision,
    'private fun drawBadgeText(canvas: Canvas, card: StudioCard, spec: RendererSpec, cfg: ExactConfig, alpha: Float = 1f) {',
    'private fun drawBadgeText(canvas: Canvas, project: StudioProject, card: StudioCard, spec: RendererSpec, cfg: ExactConfig, alpha: Float = 1f) {',
)
replace_once(
    precision,
    'textPaint.typeface = typeface(spec, cfg, "badgeHeader", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)',
    'textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeHeader", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)',
)
replace_once(
    precision,
    'textPaint.typeface = typeface(spec, cfg, "badgeValue", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)',
    'textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeValue", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)',
)
replace_once(
    precision,
    'textPaint.typeface = typeface(spec, cfg, "badgeUnit", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL)',
    'textPaint.typeface = ProjectFontResolver.resolve(project, typeface(spec, cfg, "badgeUnit", cfg.string("font.badge.family", "sans-serif"), Typeface.NORMAL), Typeface.NORMAL)',
)
replace_once(
    precision,
    'ledger.once("card.$lastIndex.body") { drawCardBody(canvas, last, cardX, spec, cfg, frame, lastIndex) }',
    'ledger.once("card.$lastIndex.body") { drawCardBody(canvas, project, last, cardX, spec, cfg, frame, lastIndex) }',
)

# Compile-time guard: the selected font must remain project-owned rather than renderer-global.
for path in (studio, main, native, legacy, ribbon, precision):
    text = Path(path).read_text()
    if path != studio and path != main and "ProjectFontResolver" not in text:
        raise SystemExit(f"font resolver was not wired into {path}")

print("Project font picker patch applied")
