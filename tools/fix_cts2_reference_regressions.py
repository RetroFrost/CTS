from pathlib import Path


def remove_kotlin_test(text: str, name: str) -> str:
    marker = f'    @Test\n    fun {name}()'
    start = text.find(marker)
    if start < 0:
        return text
    brace = text.find('{', start)
    if brace < 0:
        raise SystemExit(f'{name}: opening brace not found')
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth == 0:
                end = i + 1
                while end < len(text) and text[end] == '\n':
                    end += 1
                return text[:start] + text[end:]
        i += 1
    raise SystemExit(f'{name}: closing brace not found')


timeline_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt')
t = timeline_path.read_text()

# The 16,372 frame outro boundary belongs to the canonical 78-card source. Shorter
# projects use the same measured 369-frame ending sequence immediately after their
# own final-card settle + 37-frame hold.
marker = '    private fun malesBadgeClockFrame(index: Int, animationAge: Float): Float {\n'
helper = '''    private fun malesOutroStartFrame(cardCount: Int): Int =
        malesReferenceFrameCount(cardCount) -
            (MALES_END_WIPE_FRAMES + MALES_END_RISE_FRAMES + MALES_END_HOLD_FRAMES + MALES_FADE_FRAMES)

'''
if 'private fun malesOutroStartFrame(' not in t:
    if marker not in t:
        raise SystemExit('males outro helper insertion point missing')
    t = t.replace(marker, helper + marker, 1)

t = t.replace(
    'return smoothStep((frame - MALES_OUTRO_START_FRAME) / MALES_END_WIPE_FRAMES.toFloat())',
    'return smoothStep((frame - malesOutroStartFrame(project.cards.size)) / MALES_END_WIPE_FRAMES.toFloat())',
)
t = t.replace(
    'val riseStart = MALES_OUTRO_START_FRAME + MALES_END_WIPE_FRAMES',
    'val riseStart = malesOutroStartFrame(project.cards.size) + MALES_END_WIPE_FRAMES',
)
t = t.replace(
    'val fadeStart = MALES_REFERENCE_FRAMES - MALES_FADE_FRAMES',
    'val fadeStart = malesReferenceFrameCount(project.cards.size) - MALES_FADE_FRAMES',
)

timeline_path.write_text(t)

# Remove tests for the generic alpha timing model and replace them with measured/sealed contracts.
test_path = Path('android/app/src/test/java/io/github/retrofrost/cts/android/timeline/TimelineEngineTest.kt')
test = test_path.read_text()
for name in (
    'automaticDurationIncludesTheFullReferenceOutro',
    'customLengthChangesOnlySecondsPerScrollingCard',
    'outroCoversOnlyTheFirstThreeColumnsAndThenShowsContent',
):
    test = remove_kotlin_test(test, name)

if 'fun malesMeasuredOutroScalesToProjectCardCount()' not in test:
    insertion = '''    @Test
    fun malesMeasuredOutroScalesToProjectCardCount() {
        val project = CtsProject(
            model = VisualModel.Males,
            cards = List(5) { CtsCard(title = "Card $it") },
        ).normalized()
        val duration = TimelineEngine.duration(project)
        val endingSeconds = (25f + 23f + 273f + 48f) / 60f
        val wipeStart = duration - endingSeconds

        assertEquals(0f, TimelineEngine.outroCoverProgress(project, wipeStart - 1f / 60f), 0.001f)
        assertTrue(TimelineEngine.outroCoverProgress(project, wipeStart + 25f / 60f) > 0.99f)
        assertTrue(TimelineEngine.outroContentAlpha(project, wipeStart + 48f / 60f) > 0.99f)
        assertTrue(TimelineEngine.fadeAlpha(project, duration - 1f / 60f) < 0.1f)

        val finalPlacement = TimelineEngine.placements(project, wipeStart - 1f / 60f).last()
        assertEquals(4, finalPlacement.cardIndex)
        assertEquals(3f, finalPlacement.xInCards, 0.02f)
    }

'''
    marker2 = '    @Test\n    fun relationshipsExactReferenceLocksTheMeasuredFrameDuration() {'
    if marker2 not in test:
        raise SystemExit('relationship test insertion point missing')
    test = test.replace(marker2, insertion + marker2, 1)

test_path.write_text(test)

length_path = Path('android/app/src/test/java/io/github/retrofrost/cts/android/ui/VideoLengthTest.kt')
length = length_path.read_text()
length = remove_kotlin_test(length, 'customModeLengthRetimesTheConveyorAndPersists')
if 'fun shippedReferenceRejectsCustomLength()' not in length:
    marker3 = '    @Test\n    fun automaticLengthClearsAnOlderCustomProjectValue() {'
    insertion3 = '''    @Test
    fun shippedReferenceRejectsCustomLength() {
        DurationRuntime.useCustom(90f)
        val project = CtsProject(
            modelMode = ModelMode.Custom,
            cards = List(7) { CtsCard(title = "Card $it") },
        ).normalized()

        assertEquals(ModelMode.ExactReference, project.modelMode)
        assertNull(project.customDurationSeconds)
        assertEquals(
            TimelineEngine.automaticDuration(project),
            TimelineEngine.duration(project),
            0.001f,
        )
    }

'''
    if marker3 not in length:
        raise SystemExit('VideoLengthTest insertion point missing')
    length = length.replace(marker3, insertion3 + marker3, 1)
length_path.write_text(length)

final_t = timeline_path.read_text()
assert 'malesOutroStartFrame(project.cards.size)' in final_t
assert 'MALES_OUTRO_START_FRAME + MALES_END_WIPE_FRAMES' not in final_t
assert 'customLengthChangesOnlySecondsPerScrollingCard' not in test_path.read_text()
assert 'customModeLengthRetimesTheConveyorAndPersists' not in length_path.read_text()
print('Fixed measured Males tail for variable card counts and replaced alpha timing tests.')
