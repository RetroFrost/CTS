#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android/app/src/main/java/io/github/retrofrost/cts/android"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


# ---------------------------------------------------------------------------
# Expose exact renderer file fingerprints so repository/catalog UI can tell an
# old active copy from a corrected same-id replacement.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
if "fun activeSha256(): String?" not in text:
    text = replace_once(
        text,
        '''    fun listInstalled(): List<InstalledRenderer> {
''',
        '''    fun activeSha256(): String? = activeFile.takeIf(File::isFile)?.readBytes()?.let(::sha256)

    fun installedSha256(id: String): String? = File(libraryDir, "$id.renderer")
        .takeIf(File::isFile)
        ?.readBytes()
        ?.let(::sha256)

    fun listInstalled(): List<InstalledRenderer> {
''',
        "renderer sha accessors",
    )
path.write_text(text)


# ---------------------------------------------------------------------------
# Official repository UI: ID is a logical name, SHA is the installed/active
# version identity. Updated same-id renderers must remain downloadable/usable.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererRepositoryActivity.kt"
text = path.read_text()
text = replace_once(
    text,
    '''    private var installedIds by mutableStateOf<Set<String>>(emptySet())
    private var activeId by mutableStateOf(RendererRuntime.active.id)
''',
    '''    private var installedShaById by mutableStateOf<Map<String, String>>(emptyMap())
    private var activeSha256 by mutableStateOf("")
''',
    "repository sha state",
)
text = replace_once(
    text,
    '''                        items(entries, key = { it.id }) { entry ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
''',
    '''                        items(entries, key = { it.id }) { entry ->
                            val exactActive = entry.sha256.equals(activeSha256, ignoreCase = true)
                            val exactInstalled = entry.sha256.equals(installedShaById[entry.id], ignoreCase = true)
                            OutlinedCard(Modifier.fillMaxWidth()) {
''',
    "repository exact state per entry",
)
text = text.replace('if (entry.id == activeId) {', 'if (exactActive) {')
text = text.replace('enabled = downloadingId == null && entry.id != activeId,', 'enabled = downloadingId == null && !exactActive,')
text = text.replace('if (entry.id in installedIds) activateInstalled(entry)', 'if (exactInstalled) activateInstalled(entry)')
text = text.replace('entry.id == activeId -> "Active"', 'exactActive -> "Active"')
text = text.replace('entry.id in installedIds -> "Use"', 'exactInstalled -> "Use"')
text = replace_once(
    text,
    '''    private fun refreshLocal() {
        val active = store.active().also(RendererBridge::setRuntimeActive)
        activeId = active.id
        installedIds = store.listInstalled().map { it.spec.id }.toSet()
    }
''',
    '''    private fun refreshLocal() {
        store.active().also(RendererBridge::setRuntimeActive)
        activeSha256 = store.activeSha256().orEmpty()
        installedShaById = store.listInstalled().associate { item ->
            item.spec.id to store.installedSha256(item.spec.id).orEmpty()
        }
    }
''',
    "repository exact local refresh",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# Renderer-owned artwork bounds. imageHeight is canonical. Blank title and/or
# description bands are the only app-side reason to reclaim extra image space.
# ---------------------------------------------------------------------------
path = ANDROID / "RibbonFrameRenderer.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        val canonicalDescriptionHeight = REFERENCE_HEIGHT - spec.descriptionTop
        val descriptionHeight = if (hasDescription) canonicalDescriptionHeight else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)
''',
    '''        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)
''',
    "ribbon renderer-owned artwork height",
)
text = replace_once(
    text,
    '''        val descriptionHeight = if (hasDescription) REFERENCE_HEIGHT - spec.descriptionTop else 0f
        val titleHeight = if (hasTitle) spec.titleHeight else 0f
        val imageBottom = (REFERENCE_HEIGHT - descriptionHeight - titleHeight).coerceAtLeast(1f)
''',
    '''        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)
''',
    "ribbon front artwork height",
)
path.write_text(text)

path = ANDROID / "NativeFrameRenderer.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        val timeMs = frame * 1000 / project.fps.coerceAtLeast(1)

        if (frame < contentEnd) {
            drawCards(canvas, project, frame, timeMs, spec)
''',
    '''        val trackTime = rendererTrackTime(project, spec, frame)

        if (frame < contentEnd) {
            drawCards(canvas, project, frame, trackTime, spec)
''',
    "native renderer timeline unit",
)
text = replace_once(
    text,
    '''        drawCards(canvas, project, (contentEnd - 1).coerceAtLeast(0), timeMs, spec)
''',
    '''        drawCards(canvas, project, (contentEnd - 1).coerceAtLeast(0), trackTime, spec)
''',
    "native outro track time",
)
text = replace_once(
    text,
    '''        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val descriptionHeight = if (card.description.isBlank()) 0f else REFERENCE_HEIGHT - spec.descriptionTop
        val imageBottom = (REFERENCE_HEIGHT - titleHeight - descriptionHeight).coerceAtLeast(1f)
''',
    '''        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)
''',
    "native renderer-owned artwork height",
)
marker = '''    private fun sampleLaterBadgeY(localFrame: Int, spec: RendererSpec): Float {'''
helper = '''    private fun rendererTrackTime(project: StudioProject, spec: RendererSpec, frame: Int): Int = when (spec.timelineUnit) {
        "milliseconds" -> (frame.toLong() * 1000L / project.fps.coerceAtLeast(1)).toInt()
        "normalized" -> {
            val frames = (spec.canonicalFrameCount.takeIf { it > 1 }
                ?: NativeTimeline.totalFrameCount(project, spec).coerceAtLeast(2))
            (frame.toLong() * 1000L / (frames - 1).coerceAtLeast(1)).toInt().coerceIn(0, 1000)
        }
        else -> frame
    }

'''
if helper not in text:
    if text.count(marker) != 1:
        raise SystemExit("native track time insertion marker changed")
    text = text.replace(marker, helper + marker, 1)
text = replace_once(
    text,
    '''        if (count == 57 && spec.continuousStartFrame == 528 && spec.continuousStepFrames == 214) return 11_858
        return spec.continuousStartFrame + (count - 4) * spec.continuousStepFrames
''',
    '''        return spec.continuousStartFrame + (count - 4) * spec.continuousStepFrames
''',
    "remove archived 57-card timeline exception",
)
path.write_text(text)

path = ANDROID / "RelationshipsFrameRenderer.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        val descriptionHeight = if (card.description.isBlank()) 0f else 115f
        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val imageBottom = 1080f - descriptionHeight - titleHeight
''',
    '''        val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
        val imageBottom = RendererArtworkLayout.imageBottom(card, spec)
''',
    "relationships renderer-owned artwork height",
)
text = replace_once(
    text,
    '''        val desc = if (card.description.isBlank()) 0f else 115f
        val title = if (card.title.isBlank()) 0f else spec.titleHeight
        drawArtwork(canvas, card, RectF(slotX + spec.bodyInset, 0f, slotX + spec.bodyInset + spec.bodyWidth, 1080f - desc - title))
''',
    '''        drawArtwork(
            canvas,
            card,
            RectF(
                slotX + spec.bodyInset,
                0f,
                slotX + spec.bodyInset + spec.bodyWidth,
                RendererArtworkLayout.imageBottom(card, spec),
            ),
        )
''',
    "relationships front artwork height",
)
path.write_text(text)

# v1 aligned direct-preview geometry with the previous renderer math. v2 makes
# both hit-testing and the raster use imageHeight as the same source of truth.
path = ANDROID / "DirectPreviewTransform.kt"
text = path.read_text()
text = replace_once(
    text,
    '''        RelationshipsTimeline.isRelationships(spec) -> {
            val descriptionHeight = if (card.description.isBlank()) 0f else 115f
            val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
            (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)
        }
        else -> {
            val descriptionHeight = if (card.description.isBlank()) 0f else (refHeight - spec.descriptionTop).coerceAtLeast(0f)
            val titleHeight = if (card.title.isBlank()) 0f else spec.titleHeight
            (refHeight - descriptionHeight - titleHeight).coerceAtLeast(1f)
        }
''',
    '''        else -> RendererArtworkLayout.imageBottom(card, spec).coerceIn(1f, refHeight)
''',
    "preview renderer-owned artwork height",
)
path.write_text(text)

print("Applied SHA renderer identity, renderer-owned artwork bounds, native timeline units, and legacy timing cleanup")
