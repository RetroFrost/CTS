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
# RendererSpec: expose track existence/window metadata. A renderer animation
# must be distinguishable from a generic fallback; valueAt() alone deliberately
# clamps outside a track window and therefore cannot answer that question.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBundle.kt"
text = path.read_text()
old = '''    fun track(target: String, timeMs: Int): Float? {
        tracksByTarget[target]?.valueAt(timeMs)?.let { return it }
        val pieces = target.split('.')
        if (pieces.size >= 3 && pieces[0] == "card") {
            tracksByTarget["card.*.${pieces.drop(2).joinToString(".")}"]?.valueAt(timeMs)?.let { return it }
        }
        return null
    }

    /** Like [track], but an override disappears outside its declared keyframe window. */
    fun trackWindowed(target: String, timeMs: Int): Float? {
        tracksByTarget[target]?.valueAtWindowed(timeMs)?.let { return it }
        val pieces = target.split('.')
        if (pieces.size >= 3 && pieces[0] == "card") {
            tracksByTarget["card.*.${pieces.drop(2).joinToString(".")}"]?.valueAtWindowed(timeMs)?.let { return it }
        }
        return null
    }
'''
new = '''    private fun trackObject(target: String): RendererTrack? {
        tracksByTarget[target]?.let { return it }
        val pieces = target.split('.')
        if (pieces.size >= 3 && pieces[0] == "card") {
            tracksByTarget["card.*.${pieces.drop(2).joinToString(".")}"]?.let { return it }
        }
        return null
    }

    fun track(target: String, timeMs: Int): Float? = trackObject(target)?.valueAt(timeMs)

    /** Like [track], but an override disappears outside its declared keyframe window. */
    fun trackWindowed(target: String, timeMs: Int): Float? = trackObject(target)?.valueAtWindowed(timeMs)

    fun hasTrack(target: String): Boolean = trackObject(target) != null
    fun trackStart(target: String): Int? = trackObject(target)?.keyframes?.firstOrNull()?.timeMs
    fun trackEnd(target: String): Int? = trackObject(target)?.keyframes?.lastOrNull()?.timeMs
'''
text = replace_once(text, old, new, "renderer track identity")

# A tag that changes interpreter behaviour is a runtime dependency, not merely
# decoration. Old Puberty bundles omitted these from requiredFeatures, allowing
# an older app to accept them and silently render generic motion.
if '"puberty-outro-source-lock-v1"' not in text:
    text = replace_once(
        text,
        '        "puberty-badge-source-lock-v3",\n',
        '        "puberty-badge-source-lock-v3",\n        "puberty-outro-source-lock-v1",\n        "strict-renderer-dispatch-v1",\n',
        "source-lock capabilities",
    )
elif '"strict-renderer-dispatch-v1"' not in text:
    text = replace_once(
        text,
        '        "puberty-outro-source-lock-v1",\n',
        '        "puberty-outro-source-lock-v1",\n        "strict-renderer-dispatch-v1",\n',
        "strict dispatch capability",
    )

# Semantic validation: never claim a renderer is compatible if the bundle asks
# for source-locked behaviour but forgot to declare the corresponding runtime
# feature, or if an exact feature is declared without the tracks it requires.
needle = '''        val missing = spec.requiredFeatures.filterNot { it in features }
        if (missing.isNotEmpty()) errors += "Unsupported renderer features: ${missing.joinToString()}"
'''
replacement = needle + '''        fun requireDeclaredForTag(tag: String) {
            if (spec.tags.contains(tag) && tag !in spec.requiredFeatures) {
                errors += "Renderer tag '$tag' changes runtime behaviour but is not declared in requiredFeatures."
            }
        }
        requireDeclaredForTag("puberty-badge-source-lock-v3")
        requireDeclaredForTag("puberty-outro-source-lock-v1")
        if (spec.engine == "ribbon-exact" && "exact-scroll-track" in spec.requiredFeatures &&
            spec.tracks.none { it.target.startsWith("ribbon.scroll.") }) {
            errors += "Ribbon renderer requires exact-scroll-track but contains no ribbon.scroll.* track."
        }
        if (spec.engine == "ribbon-exact" && "affine-badge-transform" in spec.requiredFeatures) {
            val missingOpeningAffine = (0..3).filter { index ->
                listOf("m00", "m01", "m10", "m11", "tx", "ty").any { component ->
                    !spec.hasTrack("ribbon.open.$index.$component") && !spec.hasTrack("ribbon.open.$component")
                }
            }
            if (missingOpeningAffine.isNotEmpty()) {
                errors += "Ribbon affine badge transform is incomplete for opening cards ${missingOpeningAffine.joinToString()}."
            }
        }
'''
text = replace_once(text, needle, replacement, "renderer semantic capability validation")
path.write_text(text)


# ---------------------------------------------------------------------------
# RendererBridge: the manifest engine is authoritative. fromJson() already
# translates truly legacy id-prefix bundles to an engine, so runtime dispatch
# must not second-guess the manifest and silently choose a different engine.
# Empty projects still keep the renderer's canonical timeline loaded.
# ---------------------------------------------------------------------------
path = ANDROID / "RendererBridge.kt"
text = path.read_text()
old = '''    private fun engine(spec: RendererSpec = RendererRuntime.active): String = when {
        InfiniteTimeline.isInfinite(spec) -> "infinite-timeline-exact"
        RelationshipsTimeline.isRelationships(spec) -> "relationships-exact"
        RibbonTimeline.isRibbon(spec) -> "ribbon-exact"
        else -> "native-standard"
    }
'''
new = '''    internal fun engineKind(spec: RendererSpec = RendererRuntime.active): String = when (spec.engine) {
        "infinite-timeline-exact" -> "infinite-timeline-exact"
        "relationships-exact" -> "relationships-exact"
        "ribbon-exact" -> "ribbon-exact"
        "native-standard" -> "native-standard"
        else -> error("Renderer engine '${spec.engine}' passed validation but has no runtime dispatcher.")
    }
'''
text = replace_once(text, old, new, "manifest-owned renderer engine")
text = text.replace('when (engine(spec))', 'when (engineKind(spec))')
old_base = '''    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int = when (engineKind(spec)) {
        "infinite-timeline-exact" -> InfiniteTimeline.totalFrameCount(project, spec)
        "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)
        "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)
        else -> NativeTimeline.totalFrameCount(project, spec)
    }.coerceAtLeast(1)
'''
new_base = '''    private fun baseFrameCount(project: StudioProject, spec: RendererSpec): Int {
        if (project.cards.isEmpty() && spec.canonicalFrameCount > 0) return spec.canonicalFrameCount
        return when (engineKind(spec)) {
            "infinite-timeline-exact" -> InfiniteTimeline.totalFrameCount(project, spec)
            "relationships-exact" -> RelationshipsTimeline.totalFrameCount(project, spec)
            "ribbon-exact" -> RibbonTimeline.totalFrameCount(project, spec)
            else -> NativeTimeline.totalFrameCount(project, spec)
        }.coerceAtLeast(1)
    }
'''
text = replace_once(text, old_base, new_base, "empty-project renderer timeline")
path.write_text(text)


# ---------------------------------------------------------------------------
# Ribbon engine: explicit animation tracks own their visibility/timing windows.
# Legacy timing constants are only fallbacks for bundles that do not declare the
# corresponding animation. This fixes imported tracks being silently ignored.
# ---------------------------------------------------------------------------
path = ANDROID / "RibbonFrameRenderer.kt"
text = path.read_text()

# Empty data must not unload/replace the active renderer. It can still render
# its renderer-owned background/timeline; cards simply have nothing to draw.
text = replace_once(
    text,
    '''        canvas.drawColor(frameBackgroundColor(spec, frame))
        if (project.cards.isEmpty()) return
        val contentEnd = RibbonTimeline.contentEndFrame(project, spec)
''',
    '''        canvas.drawColor(frameBackgroundColor(spec, frame))
        if (project.cards.isEmpty()) return
        val contentEnd = RibbonTimeline.contentEndFrame(project, spec)
''',
    "ribbon empty renderer state",
)

old_open = '''            val exactPrefix = "ribbon.open.$index"
            val explicitVisibility = motionTrack(spec, "$exactPrefix.visible", local)
            if (explicitVisibility != null) {
                if (explicitVisibility <= 0.001f) return
            } else if (local < OPENING_BADGE_FIRST_FRAME) return
            age = motionTrack(spec, "$exactPrefix.age", local)
                ?: ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                    (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Source-exact bundles may provide a different frame-addressed affine
            // path for every opening badge. Older Ribbon bundles keep their shared path.
            val prefix = if (spec.track("$exactPrefix.m00", local) != null) exactPrefix else "ribbon.open"
'''
new_open = '''            val exactPrefix = "ribbon.open.$index"
            val explicitVisibilityTrack = spec.hasTrack("$exactPrefix.visible")
            val explicitAffine = listOf("m00", "m01", "m10", "m11", "tx", "ty")
                .any { spec.hasTrack("$exactPrefix.$it") }
            if (explicitVisibilityTrack) {
                val visible = spec.trackWindowed("$exactPrefix.visible", local)
                    ?: spec.track("$exactPrefix.visible", local)
                    ?: 0f
                if (visible <= 0.001f) return
            } else if (explicitAffine) {
                val firstExactFrame = listOf("m00", "m01", "m10", "m11", "tx", "ty")
                    .mapNotNull { spec.trackStart("$exactPrefix.$it") }
                    .minOrNull() ?: 0
                if (local < firstExactFrame) return
            } else if (local < OPENING_BADGE_FIRST_FRAME) return
            age = motionTrack(spec, "$exactPrefix.age", local)
                ?: ((local.coerceAtMost(OPENING_BADGE_FINAL_FRAME) - OPENING_BADGE_FIRST_FRAME).toFloat() /
                    (OPENING_BADGE_FINAL_FRAME - OPENING_BADGE_FIRST_FRAME)) * BADGE_ENTRY_AGE

            // Source-exact bundles may provide a different frame-addressed affine
            // path for every opening badge. Older Ribbon bundles keep their shared path.
            val prefix = if (explicitAffine) exactPrefix else "ribbon.open"
'''
text = replace_once(text, old_open, new_open, "opening badge track ownership")

old_later = '''        } else {
            if (local < spec.laterBadgeFallStartFrame) return
            age = if (sourceLockedBadge) BADGE_ENTRY_AGE else
                (local - spec.laterBadgeFallStartFrame).toFloat() / 103f * 2.25f
            matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: laterBadgeYOffset(local))
        }
'''
new_later = '''        } else {
            val exactBadgeTargets = listOf(
                "ribbon.card.$index.badge.y",
                "ribbon.card.$index.badge.scale",
                "ribbon.card.$index.text.progress",
                "ribbon.card.$index.shine.progress",
            )
            val exactBadgeStart = exactBadgeTargets.mapNotNull(spec::trackStart).minOrNull()
            if (exactBadgeStart != null) {
                if (local < exactBadgeStart) return
            } else if (local < spec.laterBadgeFallStartFrame) return
            age = if (sourceLockedBadge) BADGE_ENTRY_AGE else
                (local - (exactBadgeStart ?: spec.laterBadgeFallStartFrame)).toFloat() / 103f * 2.25f
            matrix.setTranslate(0f, motionTrack(spec, "ribbon.card.$index.badge.y", local) ?: motionTrack(spec, "ribbon.later.badge.y", local) ?: laterBadgeYOffset(local))
        }
'''
text = replace_once(text, old_later, new_later, "later badge track ownership")

text = replace_once(
    text,
    'object RibbonTimeline {\n    fun isRibbon(spec: RendererSpec): Boolean = spec.id.startsWith("ribbon.")',
    'object RibbonTimeline {\n    fun isRibbon(spec: RendererSpec): Boolean = spec.engine == "ribbon-exact"',
    "ribbon engine manifest authority",
)
path.write_text(text)


# ---------------------------------------------------------------------------
# Direct GPU path must use the same manifest-owned dispatcher as preview.
# Otherwise preview can use a renderer while export quietly selects a fallback.
# ---------------------------------------------------------------------------
path = ANDROID / "DirectGpuVideoExporter.kt"
text = path.read_text()
old_dispatch = '''            when {
                InfiniteTimeline.isInfinite(spec) ->
                    drawFourArg(infiniteRenderer, canvas, project, engineFrame, spec)
                RelationshipsTimeline.isRelationships(spec) && RelationshipsPrecisionFrameRenderer.enabled(spec) ->
                    drawPrecision(canvas, project, engineFrame, spec)
                RelationshipsTimeline.isRelationships(spec) ->
                    drawFourArg(relationshipsRenderer, canvas, project, engineFrame, spec)
                RibbonTimeline.isRibbon(spec) ->
                    drawFourArg(ribbonRenderer, canvas, project, engineFrame, spec)
                else ->
                    drawFourArg(nativeRenderer, canvas, project, engineFrame, spec)
            }
'''
new_dispatch = '''            when (RendererBridge.engineKind(spec)) {
                "infinite-timeline-exact" ->
                    drawFourArg(infiniteRenderer, canvas, project, engineFrame, spec)
                "relationships-exact" -> if (RelationshipsPrecisionFrameRenderer.enabled(spec)) {
                    drawPrecision(canvas, project, engineFrame, spec)
                } else {
                    drawFourArg(relationshipsRenderer, canvas, project, engineFrame, spec)
                }
                "ribbon-exact" ->
                    drawFourArg(ribbonRenderer, canvas, project, engineFrame, spec)
                else ->
                    drawFourArg(nativeRenderer, canvas, project, engineFrame, spec)
            }
'''
text = replace_once(text, old_dispatch, new_dispatch, "direct GPU manifest engine")
path.write_text(text)


# ---------------------------------------------------------------------------
# Instrumentation coverage: CI must prove that engine dispatch is not inferred
# from IDs, special tags cannot masquerade as compatible, empty projects retain
# the renderer timeline, and explicit Ribbon tracks are introspectable.
# ---------------------------------------------------------------------------
path = ANDROID / "../androidTest/java/io/github/retrofrost/cts/android/RuntimeIntegrityInstrumentedTest.kt"
path = path.resolve()
text = path.read_text()
anchor = '''    private fun rendererBytes(spec: RendererSpec): ByteArray = ByteArrayOutputStream().use { output ->
'''
tests = '''    @Test
    fun rendererEngineFieldIsAuthoritativeInsteadOfIdPrefix() {
        assertEquals("ribbon-exact", RendererBridge.engineKind(RendererSpec(id = "not-a-ribbon-prefix", engine = "ribbon-exact")))
        assertEquals("native-standard", RendererBridge.engineKind(RendererSpec(id = "ribbon.misleading-id", engine = "native-standard")))
    }

    @Test
    fun sourceLockedTagsCannotSilentlyFallBackWithoutRequiredCapability() {
        val bad = RendererSpec(
            id = "ribbon.bad-source-lock-contract",
            engine = "ribbon-exact",
            tags = listOf("puberty-outro-source-lock-v1"),
            requiredFeatures = listOf("custom-outro"),
        )
        val report = RendererCapabilities.report(bad)
        assertFalse(report.compatible)
        assertTrue(report.errors.any { it.contains("puberty-outro-source-lock-v1") })
    }

    @Test
    fun emptyProjectKeepsCanonicalRendererTimelineLoaded() {
        val spec = RendererSpec(
            id = "ribbon.empty-project-test",
            engine = "ribbon-exact",
            canonicalFrameCount = 777,
            canonicalCardCount = 50,
        )
        val project = StudioProject(cards = emptyList(), fps = 60)
        val metadata = RendererBridge.metadata(project, spec)
        assertEquals(777, metadata.frameCount)
    }

    @Test
    fun explicitRibbonAnimationTracksExposeTheirOwnWindow() {
        val spec = RendererSpec(
            id = "ribbon.track-window-test",
            engine = "ribbon-exact",
            tracks = listOf(
                RendererTrack("ribbon.card.4.badge.y", listOf(RendererKeyframe(91, 900f), RendererKeyframe(120, 0f))),
            ),
        )
        assertTrue(spec.hasTrack("ribbon.card.4.badge.y"))
        assertEquals(91, spec.trackStart("ribbon.card.4.badge.y"))
        assertEquals(120, spec.trackEnd("ribbon.card.4.badge.y"))
        assertEquals(null, spec.trackWindowed("ribbon.card.4.badge.y", 90))
        assertEquals(900f, spec.trackWindowed("ribbon.card.4.badge.y", 91)!!, 0.001f)
    }

'''
if tests not in text:
    if text.count(anchor) != 1:
        raise SystemExit("instrumentation insertion anchor changed")
    text = text.replace(anchor, tests + anchor, 1)
path.write_text(text)

print("Applied manifest-owned renderer dispatch, strict capability checks, explicit animation ownership, and empty-data renderer timeline fixes")
