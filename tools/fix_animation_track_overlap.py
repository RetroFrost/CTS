from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"marker not found in {path}: {old[:160]!r}")
    p.write_text(text.replace(old, new, 1))

bundle = "android/app/src/main/java/io/github/retrofrost/cts/android/RendererBundle.kt"
replace_once(
    bundle,
    '''    private fun easing(x: Float, name: String): Float = when (name.lowercase()) {''',
    '''    /**
     * Returns a value only while this track is actually active.
     *
     * valueAt() intentionally holds the first/last value outside the keyed range,
     * which is useful for persistent properties. Absolute animation overrides such
     * as card.0.x must instead expire when their final keyframe is passed, otherwise
     * the opening position remains pinned while the conveyor starts and later cards
     * collide with it.
     */
    fun valueAtWindowed(timeMs: Int): Float? {
        if (keyframes.isEmpty()) return null
        if (timeMs < keyframes.first().timeMs || timeMs > keyframes.last().timeMs) return null
        return valueAt(timeMs)
    }

    private fun easing(x: Float, name: String): Float = when (name.lowercase()) {''',
)

replace_once(
    bundle,
    '''    val outroFrames: Int
        get() = endWipeFrames + endRiseFrames + endHoldFrames + fadeFrames + blackTailFrames''',
    '''    /** Like [track], but an override disappears outside its declared keyframe window. */
    fun trackWindowed(target: String, timeMs: Int): Float? {
        tracksByTarget[target]?.valueAtWindowed(timeMs)?.let { return it }
        val pieces = target.split('.')
        if (pieces.size >= 3 && pieces[0] == "card") {
            tracksByTarget["card.*.${pieces.drop(2).joinToString(".")}"]?.valueAtWindowed(timeMs)?.let { return it }
        }
        return null
    }

    val outroFrames: Int
        get() = endWipeFrames + endRiseFrames + endHoldFrames + fadeFrames + blackTailFrames''',
)

replace_once(
    bundle,
    '''        "relationships-shadow-outside-v2",
        "relationships-single-owner-pass-v1",''',
    '''        "relationships-shadow-outside-v2",
        "relationships-single-owner-pass-v1",
        "relationships-windowed-card-tracks-v1",''',
)

renderer = Path("android/app/src/main/java/io/github/retrofrost/cts/android/RelationshipsPrecisionFrameRenderer.kt")
text = renderer.read_text()
count = text.count('spec.track("card.$index.')
if count < 8:
    raise SystemExit(f"expected several per-card animation overrides, found {count}")
text = text.replace('spec.track("card.$index.', 'spec.trackWindowed("card.$index.')
renderer.write_text(text)

test = Path("android/app/src/test/java/io/github/retrofrost/cts/android/RelationshipsPrecisionRendererTest.kt")
text = test.read_text()
if "import org.junit.Assert.assertNull" not in text:
    text = text.replace("import org.junit.Assert.assertFalse\n", "import org.junit.Assert.assertFalse\nimport org.junit.Assert.assertNull\n", 1)
if "openingCardPositionTrackExpiresBeforeContinuousScroll" not in text:
    addition = r'''

    @Test
    fun openingCardPositionTrackExpiresBeforeContinuousScroll() {
        val spec = RendererSpec(
            tracks = listOf(
                RendererTrack(
                    target = "card.0.x",
                    keyframes = listOf(
                        RendererKeyframe(374, 0f),
                        RendererKeyframe(899, 0f),
                    ),
                ),
            ),
        )

        assertEquals(0f, spec.trackWindowed("card.0.x", 374))
        assertEquals(0f, spec.trackWindowed("card.0.x", 899))
        assertNull(spec.trackWindowed("card.0.x", 900))

        // Persistent track semantics are unchanged for properties that need them.
        assertEquals(0f, spec.track("card.0.x", 900))
    }

    @Test
    fun rendererAdvertisesWindowedPerCardAnimationTracks() {
        assertTrue(RendererCapabilities.features.contains("relationships-windowed-card-tracks-v1"))
    }
'''
    text = text.rstrip()
    if not text.endswith("}"):
        raise SystemExit("test class closing brace not found")
    text = text[:-1] + addition + "\n}\n"
    test.write_text(text)

doc = Path("docs/wiki/Relationships-Exact-v2.md")
if doc.exists():
    text = doc.read_text().rstrip()
    note = '''\n\n### Windowed per-card animation overrides\n\nExact-v2 per-card animation tracks (`card.<index>.*`) are windowed. They apply only from their first keyframe through their last keyframe and then release ownership back to the normal conveyor/local animation. This prevents opening X/Y/scale/badge tracks from pinning an old card pose into the continuous-scroll phase and colliding with later cards. Persistent global tracks keep the existing hold-before/hold-after behaviour.\n'''
    if "### Windowed per-card animation overrides" not in text:
        text += note
    doc.write_text(text.rstrip() + "\n")
