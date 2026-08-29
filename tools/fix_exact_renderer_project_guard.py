from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))

# Keep compatibility validation independent from Android graphics so it can be
# regression-tested by the normal JVM unit-test task.
guard = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererProjectGuard.kt"
Path(guard).write_text('''package io.github.retrofrost.cts.android

data class RendererProjectCompatibility(
    val compatible: Boolean,
    val issues: List<String>,
) {
    fun message(rendererName: String): String = if (compatible) {
        "$rendererName is compatible with this project."
    } else {
        "$rendererName is source-locked and cannot be applied to this project: ${issues.joinToString()}."
    }
}

object RendererProjectGuard {
    private fun isSourceLocked(spec: RendererSpec): Boolean =
        RelationshipsPrecisionFrameRenderer.enabled(spec) && spec.precisionMode == "frame-exact"

    fun check(project: StudioProject, spec: RendererSpec): RendererProjectCompatibility {
        if (!isSourceLocked(spec)) return RendererProjectCompatibility(true, emptyList())

        val issues = mutableListOf<String>()
        if (project.width != spec.referenceWidth || project.height != spec.referenceHeight) {
            issues += "resolution is ${project.width}×${project.height}; requires ${spec.referenceWidth}×${spec.referenceHeight}"
        }
        if (project.fps != spec.referenceFps) {
            issues += "frame rate is ${project.fps} fps; requires ${spec.referenceFps} fps"
        }
        if (spec.canonicalCardCount > 0 && project.cards.size != spec.canonicalCardCount) {
            issues += "card count is ${project.cards.size}; requires ${spec.canonicalCardCount}"
        }
        if (!project.autoLength) {
            issues += "custom duration is enabled; canonical automatic duration is required"
        }
        return RendererProjectCompatibility(issues.isEmpty(), issues)
    }

    fun requireCompatible(project: StudioProject, spec: RendererSpec) {
        val result = check(project, spec)
        require(result.compatible) {
            result.message(spec.name) + " Use a compatible renderer or restore the canonical project settings."
        }
    }
}
''')

bridge = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBridge.kt"
replace_once(
    bridge,
    '''    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engine(spec)) {
        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)
        "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)
        else -> NativeTimeline.totalFrameCount(project, spec)
    }.coerceAtLeast(1)

    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =''',
    '''    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engine(spec)) {
        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)
        "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)
        else -> NativeTimeline.totalFrameCount(project, spec)
    }.coerceAtLeast(1)

    fun projectCompatibility(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ): RendererProjectCompatibility = RendererProjectGuard.check(project, spec)

    fun requireProjectCompatibility(
        project: StudioProject,
        spec: RendererSpec = RendererRuntime.active,
    ) = RendererProjectGuard.requireCompatible(project, spec)

    fun rendererIntroFrames(spec: RendererSpec = RendererRuntime.active): Int =''',
)
replace_once(
    bridge,
    '''    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val spec = RendererRuntime.active
        val fps = if (''',
    '''    fun metadata(project: StudioProject): RenderMetadata = synchronized(lock) {
        val spec = RendererRuntime.active
        requireProjectCompatibility(project, spec)
        val fps = if (''',
)
replace_once(
    bridge,
    '''    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap = when (engine(spec)) {
        "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
            relationshipsPrecisionRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        } else {
            relationshipsRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
        "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        else -> nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
    }

    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray = when (engine(spec)) {
        "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
            relationshipsPrecisionRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        } else {
            relationshipsRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
        "ribbon-exact" -> ribbonRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        else -> nativeRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
    }''',
    '''    private fun renderEngine(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): Bitmap {
        requireProjectCompatibility(project, spec)
        return when (engine(spec)) {
            "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
                relationshipsPrecisionRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            } else {
                relationshipsRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            }
            "ribbon-exact" -> ribbonRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            else -> nativeRenderer.render(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
    }

    private fun renderEngineRgba(project: StudioProject, spec: RendererSpec, frame: Int, width: Int, height: Int): ByteArray {
        requireProjectCompatibility(project, spec)
        return when (engine(spec)) {
            "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
                relationshipsPrecisionRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            } else {
                relationshipsRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            }
            "ribbon-exact" -> ribbonRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
            else -> nativeRenderer.renderRgba(project, frame, width.coerceAtLeast(2), height.coerceAtLeast(2))
        }
    }''',
)

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        dir.mkdirs()''',
    '''        val report = RendererCapabilities.report(spec)
        require(report.compatible) { report.errors.joinToString("\\n") }
        ProjectAutosave.load(context)?.let { project ->
            RendererProjectGuard.requireCompatible(project, spec)
        }
        dir.mkdirs()''',
)

app = "android/app/src/main/java/io/github/retrofrost/cts/android/CubicalCompareApplication.kt"
Path(app).write_text('''package io.github.retrofrost.cts.android

import android.app.Application

class CubicalCompareApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val store = RendererStore(this)
        val selected = store.active()
        val project = ProjectAutosave.load(this)
        RendererRuntime.active = if (
            project != null && !RendererProjectGuard.check(project, selected).compatible
        ) {
            // Keep the exact renderer installed, but never let an incompatible
            // autosaved project start up as a renderer/project hybrid.
            store.reset()
        } else {
            selected
        }
    }
}
''')

test = "android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt"
text = Path(test).read_text()
insert = '''

    @Test
    fun sourceLockedRendererRejectsProjectShapeChanges() {
        val spec = RendererSpec(
            id = "relationships.source.locked",
            name = "Relationships Source Exact",
            engine = "relationships-exact",
            precisionMode = "frame-exact",
            referenceWidth = 1920,
            referenceHeight = 1080,
            referenceFps = 60,
            canonicalCardCount = 40,
            canonicalFrameCount = 11130,
            tags = listOf("relationships.exact.v2=true"),
        )
        val changed = StudioProject(
            cards = List(8) { StudioCard(title = "Card ${it + 1}") },
            autoLength = false,
            customLengthSeconds = 153.867,
        )

        val result = RendererProjectGuard.check(changed, spec)

        assertFalse(result.compatible)
        assertTrue(result.issues.any { it.startsWith("card count") })
        assertTrue(result.issues.any { it.startsWith("custom duration") })
    }

    @Test
    fun adaptiveRendererStillAcceptsAnyProjectShape() {
        val project = StudioProject(
            cards = List(3) { StudioCard(title = "Card ${it + 1}") },
            autoLength = false,
            customLengthSeconds = 12.0,
        )
        assertTrue(RendererProjectGuard.check(project, RendererSpec.builtIn()).compatible)
    }
'''
if text.rstrip().endswith('}'):
    text = text.rstrip()[:-1] + insert + '\n}\n'
else:
    raise SystemExit('test class closing brace not found')
Path(test).write_text(text)
