from pathlib import Path
import re


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f"{label}: source block not found")
    return text.replace(old, new, 1)


def insert_after(text: str, marker: str, addition: str, label: str) -> str:
    if addition.strip() in text:
        return text
    if marker not in text:
        raise SystemExit(f"{label}: marker not found")
    return text.replace(marker, marker + addition, 1)


timeline_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt")
test_path = Path("android/app/src/test/java/io/github/retrofrost/cts/android/timeline/TimelineEngineTest.kt")
project_test_path = Path("android/app/src/test/java/io/github/retrofrost/cts/android/model/CtsProjectTest.kt")
overlay_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceOverlayRenderer.kt")

t = timeline_path.read_text()

# Exact means native source clock. The old Android alpha accidentally doubled every reference duration.
t = t.replace(
    "/** Exact Reference keeps every measured source frame but presents it at half speed. */\n    const val EXACT_REFERENCE_PLAYBACK_RATE = 0.5f",
    "/** Exact Reference maps one output frame to one measured source frame. */\n    const val EXACT_REFERENCE_PLAYBACK_RATE = 1f",
)
if "const val EXACT_REFERENCE_PLAYBACK_RATE = 1f" not in t:
    raise SystemExit("native reference playback rate was not applied")

males_constants_marker = "    const val MALES_REFERENCE_FPS = 60\n"
males_constants = '''    private const val MALES_CANONICAL_CARD_COUNT = 78
    private const val MALES_CONVEYOR_START_FRAME = 528
    private const val MALES_STEADY_START_FRAME = 620
    private const val MALES_STEADY_START_SHIFT = 0.614439f
    private const val MALES_STEADY_END_FRAME = 16_335
    private const val MALES_STEADY_END_SHIFT = 74f
    private const val MALES_STEADY_PERIOD_FRAMES = 214.14294f
    private const val MALES_FINAL_HOLD_FRAMES = 37
    private const val MALES_OUTRO_START_FRAME = 16_372
    private const val MALES_END_WIPE_FRAMES = 25
    private const val MALES_END_RISE_FRAMES = 23
    private const val MALES_END_HOLD_FRAMES = 273
    private const val MALES_FADE_FRAMES = 48
    private const val MALES_BADGE_DEEMPHASIS_SECONDS = 1f
    private const val MALES_BADGE_ACTIVE_SCALE = 1f
    private const val MALES_BADGE_MEDIUM_SCALE = 272f / 298f
    private const val MALES_BADGE_SMALL_SCALE = 248f / 298f

    // Full-video separator tracking from the canonical MP4. The opening hand-off is
    // not a generic easing curve: it has a one-time acceleration before the steady conveyor.
    private val malesPhasePullKeys = arrayOf(
        528f to 0.000000f,
        535f to 0.035055f,
        540f to 0.047559f,
        550f to 0.089242f,
        560f to 0.160102f,
        570f to 0.230962f,
        580f to 0.301822f,
        590f to 0.385186f,
        600f to 0.464382f,
        610f to 0.535242f,
        620f to MALES_STEADY_START_SHIFT,
    )
'''
if "MALES_CANONICAL_CARD_COUNT" not in t:
    t = insert_after(t, males_constants_marker, males_constants, "males measured constants")

# Canonical Males duration has its own measured clock, just like Relationships.
auto_marker = "    fun automaticDuration(project: CtsProject): Float {\n        val parts = timelineParts(project)\n"
auto_add = '''        if (project.model == VisualModel.Males && isSealedReference(project)) {
            return customIntroDuration(project) +
                malesReferenceFrameCount(project.cards.size) / MALES_REFERENCE_FPS.toFloat()
        }
'''
if auto_add.strip() not in t:
    t = insert_after(t, auto_marker, auto_add, "males canonical duration")

# A sealed Males project remains visually alive through its canonical outro, not the old generic duration.
t = replace_once(
    t,
    "        val modelTime = modelTime(project, outputTimeSeconds)\n        if (modelTime >= modelDuration(project)) return emptyList()\n",
    '''        val modelTime = modelTime(project, outputTimeSeconds)
        val activeDuration = if (project.model == VisualModel.Males && isSealedReference(project)) {
            malesReferenceFrameCount(project.cards.size) / MALES_REFERENCE_FPS.toFloat()
        } else {
            modelDuration(project)
        }
        if (modelTime >= activeDuration) return emptyList()
''',
    "males active duration",
)

# Replace the generic 200-frame conveyor with the measured whole-video separator clock.
pattern = re.compile(
    r"        val easedShift = if \(lockedMales\) \{\n"
    r"            val sourceFrame = \(modelTime \* MALES_REFERENCE_FPS\)\.toInt\(\)\n"
    r"            val pull = .*?\n"
    r"            \(rawShift \* MALES_CONVEYOR_STRIDE \+ pull\)\.coerceAtMost\(maximumShift\.toFloat\(\)\)\n"
    r"        \} else if \(completedShifts >= maximumShift\) \{",
    re.S,
)
if "malesConveyorShift(sourceFrame" not in t:
    match = pattern.search(t)
    if not match:
        raise SystemExit("males conveyor branch not found")
    replacement = '''        val easedShift = if (lockedMales) {
            val sourceFrame = (modelTime * MALES_REFERENCE_FPS).toInt()
            malesConveyorShift(sourceFrame, maximumShift.toFloat())
        } else if (completedShifts >= maximumShift) {'''
    t = t[:match.start()] + replacement + t[match.end():]

# Later-card badge clocks must move with the measured conveyor period rather than the obsolete 3.333 s cycle.
t = t.replace(
    '''                    val cardStart = scrollStart + (index - initialCount) * parts.scrollSeconds
                    (modelTime - cardStart - MALES_POST_BADGE_DELAY) * MALES_POST_BADGE_SPEED
''',
    '''                    val cardStart = malesCardStartFrame(index) / MALES_REFERENCE_FPS
                    (modelTime - cardStart - MALES_POST_BADGE_DELAY) * MALES_POST_BADGE_SPEED
''',
)

old_affine = '''                        badgeAffine = if (!lockedMales) BadgeAffine.Identity else if (index < initialCount) {
                            malesOpeningBadgeAffine(exactBadgeAge)
                        } else {
                            malesPostBadgeAffine(exactBadgeAge)
                        },
'''
new_affine = '''                        badgeAffine = if (!lockedMales) {
                            BadgeAffine.Identity
                        } else {
                            malesMeasuredBadgeAffine(
                                index = index,
                                age = exactBadgeAge,
                                sourceFrame = (modelTime * MALES_REFERENCE_FPS).toInt(),
                                cardCount = cardCount,
                                initialCount = initialCount,
                            )
                        },
'''
if old_affine in t:
    t = t.replace(old_affine, new_affine, 1)
elif "malesMeasuredBadgeAffine(" not in t:
    raise SystemExit("males badge affine branch not found")

helper_marker = "    private fun malesBodyProgress(localTime: Float): Float {\n"
helpers = '''    private fun malesConveyorShift(sourceFrame: Int, maximumShift: Float): Float {
        if (maximumShift <= 0f || sourceFrame <= MALES_CONVEYOR_START_FRAME) return 0f
        val measured = if (sourceFrame <= MALES_STEADY_START_FRAME) {
            val frame = sourceFrame.toFloat()
            val right = malesPhasePullKeys.indexOfFirst { frame <= it.first }
            when {
                right <= 0 -> malesPhasePullKeys.first().second
                else -> {
                    val (f0, s0) = malesPhasePullKeys[right - 1]
                    val (f1, s1) = malesPhasePullKeys[right]
                    lerp(s0, s1, (frame - f0) / (f1 - f0))
                }
            }
        } else {
            MALES_STEADY_START_SHIFT +
                (sourceFrame - MALES_STEADY_START_FRAME) / MALES_STEADY_PERIOD_FRAMES
        }
        return measured.coerceIn(0f, maximumShift)
    }

    private fun malesFrameForShift(targetShift: Float): Float {
        if (targetShift <= 0f) return MALES_CONVEYOR_START_FRAME.toFloat()
        if (targetShift <= MALES_STEADY_START_SHIFT) {
            val right = malesPhasePullKeys.indexOfFirst { targetShift <= it.second }
            if (right <= 0) return malesPhasePullKeys.first().first
            val (f0, s0) = malesPhasePullKeys[right - 1]
            val (f1, s1) = malesPhasePullKeys[right]
            val amount = ((targetShift - s0) / (s1 - s0).coerceAtLeast(0.000001f)).coerceIn(0f, 1f)
            return lerp(f0, f1, amount)
        }
        return MALES_STEADY_START_FRAME +
            (targetShift - MALES_STEADY_START_SHIFT) * MALES_STEADY_PERIOD_FRAMES
    }

    private fun malesCardStartFrame(index: Int): Float = when {
        index <= 0 -> 0f
        index < 4 -> index * 120f
        else -> malesFrameForShift((index - 4).toFloat())
    }

    private fun malesReferenceFrameCount(cardCount: Int): Int {
        if (cardCount <= 0) return 0
        if (cardCount == MALES_CANONICAL_CARD_COUNT) return MALES_REFERENCE_FRAMES
        val settledFrame = when (cardCount) {
            1 -> 120
            2 -> 240
            3 -> 360
            4 -> 535
            else -> kotlin.math.ceil(malesFrameForShift((cardCount - 4).toFloat()).toDouble()).toInt()
        }
        return settledFrame + MALES_FINAL_HOLD_FRAMES +
            MALES_END_WIPE_FRAMES + MALES_END_RISE_FRAMES + MALES_END_HOLD_FRAMES + MALES_FADE_FRAMES
    }

    private fun malesBadgeClockFrame(index: Int, animationAge: Float): Float {
        val start = malesCardStartFrame(index)
        return if (index < 4) {
            start + animationAge * MALES_REFERENCE_FPS
        } else {
            start + MALES_POST_BADGE_DELAY * MALES_REFERENCE_FPS +
                animationAge / MALES_POST_BADGE_SPEED * MALES_REFERENCE_FPS
        }
    }

    private fun easeInOutCubic(value: Float): Float {
        val x = value.coerceIn(0f, 1f)
        val q = -2f * x + 2f
        return if (x < 0.5f) 4f * x * x * x else 1f - q * q * q / 2f
    }

    private fun malesStageScale(index: Int, sourceFrame: Int, cardCount: Int): Float {
        var scale = MALES_BADGE_ACTIVE_SCALE
        if (index + 1 < cardCount) {
            val next = index + 1
            val speed = if (next < 4) 1f else MALES_POST_BADGE_SPEED
            val trigger = malesBadgeClockFrame(next, 1.72f)
            val duration = MALES_BADGE_DEEMPHASIS_SECONDS / speed * MALES_REFERENCE_FPS
            scale = lerp(scale, MALES_BADGE_MEDIUM_SCALE, easeInOutCubic((sourceFrame - trigger) / duration))
        }
        if (index + 2 < cardCount) {
            val next = index + 2
            val speed = if (next < 4) 1f else MALES_POST_BADGE_SPEED
            val trigger = malesBadgeClockFrame(next, 1.72f)
            val duration = MALES_BADGE_DEEMPHASIS_SECONDS / speed * MALES_REFERENCE_FPS
            val p = easeInOutCubic((sourceFrame - trigger) / duration)
            if (p > 0f) scale = lerp(MALES_BADGE_MEDIUM_SCALE, MALES_BADGE_SMALL_SCALE, p)
        }
        return scale
    }

    private fun malesMeasuredBadgeAffine(
        index: Int,
        age: Float,
        sourceFrame: Int,
        cardCount: Int,
        initialCount: Int,
    ): BadgeAffine {
        if (age < MALES_BADGE_ENTRY_END) {
            return if (index < initialCount) malesOpeningBadgeAffine(age) else malesPostBadgeAffine(age)
        }
        val scale = malesStageScale(index, sourceFrame, cardCount)
        val cx = 243.5f
        val cy = 203.5f
        return BadgeAffine(scale, 0f, 0f, scale, cx * (1f - scale), cy * (1f - scale))
    }

'''
if "private fun malesConveyorShift(" not in t:
    if helper_marker not in t:
        raise SystemExit("males measured helpers: marker not found")
    t = t.replace(helper_marker, helpers + helper_marker, 1)

# Measured Males outro clock: final conveyor reaches shift 74 at 16335, holds 37 frames,
# then 25 wipe + 23 rise + 273 hold + 48 fade = frame 16741.
cover_marker = "    fun outroCoverProgress(project: CtsProject, outputTimeSeconds: Float): Float {\n"
cover_add = '''        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = (contentOutputTime(project, outputTimeSeconds) * MALES_REFERENCE_FPS).toInt()
            return smoothStep((frame - MALES_OUTRO_START_FRAME) / MALES_END_WIPE_FRAMES.toFloat())
        }
'''
if cover_add.strip() not in t:
    t = insert_after(t, cover_marker, cover_add, "males measured wipe")

content_marker = "    fun outroContentAlpha(project: CtsProject, outputTimeSeconds: Float): Float {\n"
content_add = '''        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = (contentOutputTime(project, outputTimeSeconds) * MALES_REFERENCE_FPS).toInt()
            val riseStart = MALES_OUTRO_START_FRAME + MALES_END_WIPE_FRAMES
            return ((frame - riseStart) / MALES_END_RISE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
'''
if content_add.strip() not in t:
    t = insert_after(t, content_marker, content_add, "males measured rise")

fade_marker = "    fun fadeAlpha(project: CtsProject, outputTimeSeconds: Float): Float {\n"
fade_add = '''        if (project.model == VisualModel.Males && isSealedReference(project)) {
            val frame = (contentOutputTime(project, outputTimeSeconds) * MALES_REFERENCE_FPS).toInt()
            val fadeStart = MALES_REFERENCE_FRAMES - MALES_FADE_FRAMES
            return 1f - ((frame - fadeStart) / MALES_FADE_FRAMES.toFloat()).coerceIn(0f, 1f)
        }
'''
if fade_add.strip() not in t:
    t = insert_after(t, fade_marker, fade_add, "males measured fade")

timeline_path.write_text(t)

# The Males end group rises as a group from below; geometry is fixed to the measured 3-column canvas.
o = overlay_path.read_text()
old_geometry = '''        val alpha = (255f * contentAlpha.coerceIn(0f, 1f)).toInt()
        paint.color = Color.argb(alpha, 17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val margin = width * 0.02f
        val gap = width * 0.025f
        val boxTop = height * 0.17f
        val boxBottom = height * 0.53f
        val boxWidth = (overlayRight - margin * 2 - gap) / 2f
        paint.color = Color.argb(alpha, 224, 0, 0)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f, 12f, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + height * 0.045f, height * 0.027f, paint, true)

        val credits = RectF(overlayRight * 0.32f, height * 0.62f, overlayRight * 0.68f, height * 0.84f)
        paint.color = Color.argb(alpha, 98, 95, 86)
        canvas.drawRoundRect(credits, 12f, 12f, paint)
        paint.color = Color.argb(alpha, 255, 255, 255)
'''
new_geometry = '''        val rise = 1f - (1f - contentAlpha.coerceIn(0f, 1f)).let { it * it * it }
        val yOffset = height * (1f - rise)
        val alpha = 255
        paint.color = Color.rgb(17, 17, 17)
        canvas.drawRect(0f, 0f, overlayRight, height.toFloat(), paint)

        val scaleX = width / 1920f
        val scaleY = height / 1080f
        val margin = 25f * scaleX
        val gap = 42f * scaleX
        val boxTop = 245f * scaleY + yOffset
        val boxBottom = 665f * scaleY + yOffset
        val boxWidth = (overlayRight - margin * 2f - gap) / 2f
        paint.color = Color.argb(alpha, 216, 0, 22)
        canvas.drawRoundRect(RectF(margin, boxTop, margin + boxWidth, boxBottom), 12f * scaleX, 12f * scaleY, paint)
        canvas.drawRoundRect(RectF(margin + boxWidth + gap, boxTop, overlayRight - margin, boxBottom), 12f * scaleX, 12f * scaleY, paint)
        paint.color = Color.WHITE
        drawCentered(canvas, "BEST VIDEO FOR YOU", margin, boxWidth, boxTop + 35f * scaleY, 35f * scaleY, paint, true)
        drawCentered(canvas, "NEWEST VIDEO", margin + boxWidth + gap, boxWidth, boxTop + 35f * scaleY, 35f * scaleY, paint, true)

        val creditWidth = 460f * scaleX
        val creditHeight = 150f * scaleY
        val creditLeft = (overlayRight - creditWidth) / 2f
        val creditTop = 790f * scaleY + yOffset
        val credits = RectF(creditLeft, creditTop, creditLeft + creditWidth, creditTop + creditHeight)
        paint.color = Color.rgb(96, 93, 84)
        canvas.drawRoundRect(credits, 20f * scaleX, 20f * scaleY, paint)
        paint.color = Color.WHITE
'''
if old_geometry in o:
    o = o.replace(old_geometry, new_geometry, 1)
elif "val yOffset = height * (1f - rise)" not in o:
    raise SystemExit("Males outro geometry block not found")
overlay_path.write_text(o)

# Update tests that encoded the alpha-era half-speed behavior.
test = test_path.read_text()
test = test.replace(
    "        assertEquals(371f, TimelineEngine.duration(project), 0.0001f)\n",
    "        assertEquals(185.5f, TimelineEngine.duration(project), 0.0001f)\n",
)
if "fun malesCanonicalFullVideoUsesAll16741Frames()" not in test:
    needle = "    @Test\n    fun relationshipsExactReferenceLocksTheMeasuredFrameDuration() {"
    addition = '''    @Test
    fun malesCanonicalFullVideoUsesAll16741Frames() {
        val project = CtsProject(
            model = VisualModel.Males,
            cards = List(78) { CtsCard(title = "Age $it") },
        ).normalized()
        assertEquals(16_741f / 60f, TimelineEngine.duration(project), 0.0001f)
    }

    @Test
    fun exactReferenceRunsAtNativeSourceSpeed() {
        assertEquals(1f, TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE, 0f)
    }

'''
    if needle not in test:
        raise SystemExit("timeline test insertion point missing")
    test = test.replace(needle, addition + needle, 1)
test_path.write_text(test)

pt = project_test_path.read_text()
old_test = '''    @Test
    fun customModeRetainsChosenVideoFormat() {
        val project = CtsProject(
            modelMode = ModelMode.Custom,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(1280, project.export.width)
        assertEquals(720, project.export.height)
        assertEquals(30, project.export.fps)
    }
'''
new_test = '''    @Test
    fun shippedReferenceModelCannotBeRetimedOrReformattedByTheApp() {
        val project = CtsProject(
            model = VisualModel.Males,
            modelMode = ModelMode.Custom,
            customDurationSeconds = 42f,
            showHexagons = false,
            showIntro = false,
            showDisclaimer = false,
            showOutro = false,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(ModelMode.ExactReference, project.modelMode)
        assertEquals(null, project.customDurationSeconds)
        assertEquals(true, project.showHexagons)
        assertEquals(true, project.showIntro)
        assertEquals(true, project.showDisclaimer)
        assertEquals(true, project.showOutro)
        assertEquals(1920, project.export.width)
        assertEquals(1080, project.export.height)
        assertEquals(60, project.export.fps)
    }
'''
if old_test in pt:
    pt = pt.replace(old_test, new_test, 1)
elif "shippedReferenceModelCannotBeRetimedOrReformattedByTheApp" not in pt and "referenceModelRejectsCustomVideoFormatAndMode" not in pt:
    raise SystemExit("project sealed-model test block not found")
project_test_path.write_text(pt)

# Assertions make CI fail before Gradle if any app-level override leaks back in.
final_t = timeline_path.read_text()
assert "EXACT_REFERENCE_PLAYBACK_RATE = 1f" in final_t
assert "MALES_STEADY_END_FRAME = 16_335" in final_t
assert "MALES_OUTRO_START_FRAME = 16_372" in final_t
assert "malesConveyorShift(sourceFrame" in final_t
assert "malesReferenceFrameCount(project.cards.size)" in final_t
assert "assertEquals(185.5f" in test_path.read_text()
print("Applied full-video measured CTS reference clocks: native speed, Males conveyor/outro, sealed tests.")
