from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'{label}: expected source block not found in {path}')
    path.write_text(text.replace(old, new, 1))


project_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/model/CtsProject.kt')
timeline_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt')
renderer_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceFrameRenderer.kt')
monitor_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt')
project_test_path = Path('android/app/src/test/java/io/github/retrofrost/cts/android/model/CtsProjectTest.kt')
timeline_test_path = Path('android/app/src/test/java/io/github/retrofrost/cts/android/timeline/TimelineEngineTest.kt')

# 1) Project normalization seals every shipped reference model.
replace_once(
    project_path,
    '''    fun normalized(): CtsProject {
        val safeExport = export.normalized()
        val modelExport = if (modelMode == ModelMode.ExactReference) {
            safeExport.copy(width = 1920, height = 1080, fps = 60)
        } else {
            safeExport
        }
        return copy(
            version = SharedContract.PROJECT_VERSION,
            cards = cards.map { it.withOwnedImageSubcard() },
            customDurationSeconds = DurationRuntime.normalizeProjectValue(customDurationSeconds),
            soundtrack = soundtrack.normalized(),
            introVideo = introVideo.normalized(),
            export = modelExport,
        )
    }
''',
    '''    fun normalized(): CtsProject {
        val safeExport = export.normalized()
        val sealedReference = model == VisualModel.Males || model == VisualModel.Relationships
        val modelExport = if (sealedReference) {
            safeExport.copy(width = 1920, height = 1080, fps = 60)
        } else {
            safeExport
        }
        return copy(
            version = SharedContract.PROJECT_VERSION,
            modelMode = if (sealedReference) ModelMode.ExactReference else modelMode,
            cards = cards.map { it.withOwnedImageSubcard() },
            showHexagons = if (sealedReference) true else showHexagons,
            showIntro = if (sealedReference) true else showIntro,
            showDisclaimer = if (sealedReference) true else showDisclaimer,
            showOutro = if (sealedReference) true else showOutro,
            customDurationSeconds = if (sealedReference) null else DurationRuntime.normalizeProjectValue(customDurationSeconds),
            soundtrack = soundtrack.normalized(),
            introVideo = introVideo.normalized(),
            export = modelExport,
        )
    }
''',
    'seal reference project settings',
)

# 2) TimelineEngine itself enforces reference behavior, even if fed an old/un-normalized project.
replace_once(
    timeline_path,
    '''    private fun isLockedRelationships(project: CtsProject): Boolean =
        project.model == VisualModel.Relationships && project.modelMode == ModelMode.ExactReference

    private fun playbackRate(project: CtsProject): Float =
        if (project.modelMode == ModelMode.ExactReference) EXACT_REFERENCE_PLAYBACK_RATE else 1f

    fun customIntroDuration(project: CtsProject): Float = if (
        project.showIntro && !project.introVideo.uri.isNullOrBlank()
    ) {
''',
    '''    private fun isSealedReference(project: CtsProject): Boolean =
        project.model == VisualModel.Males || project.model == VisualModel.Relationships

    private fun isLockedRelationships(project: CtsProject): Boolean =
        project.model == VisualModel.Relationships

    private fun playbackRate(project: CtsProject): Float =
        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) {
            EXACT_REFERENCE_PLAYBACK_RATE
        } else {
            1f
        }

    fun customIntroDuration(project: CtsProject): Float = if (
        !project.introVideo.uri.isNullOrBlank()
    ) {
''',
    'seal playback rate and pre-roll',
)

replace_once(
    timeline_path,
    '''    private fun builtInIntroEnabled(project: CtsProject): Boolean =
        project.showIntro && customIntroDuration(project) <= 0f
''',
    '''    private fun builtInIntroEnabled(project: CtsProject): Boolean =
        isSealedReference(project) || project.showIntro
''',
    'preserve built-in reference intro',
)

replace_once(
    timeline_path,
    '''    private fun tailSeconds(project: CtsProject): Float = if (project.showOutro) {
        END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + OUTRO_HOLD_SECONDS + FADE_SECONDS
    } else {
        END_HOLD_SECONDS
    }
''',
    '''    private fun tailSeconds(project: CtsProject): Float = if (isSealedReference(project) || project.showOutro) {
        END_HOLD_SECONDS + OUTRO_COVER_SECONDS + OUTRO_CONTENT_DELAY_SECONDS + OUTRO_HOLD_SECONDS + FADE_SECONDS
    } else {
        END_HOLD_SECONDS
    }
''',
    'seal reference outro duration',
)

replace_once(
    timeline_path,
    '''            val outro = if (project.showOutro) {
                RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                    RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            } else 0
''',
    '''            val outro = if (isSealedReference(project) || project.showOutro) {
                RELATIONSHIPS_END_WIPE_FRAMES + RELATIONSHIPS_END_RISE_FRAMES +
                    RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            } else 0
''',
    'seal relationships outro duration',
)

for old, new, label in [
    ('        if (project.modelMode == ModelMode.ExactReference) return automatic\n',
     '        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return automatic\n',
     'seal custom duration'),
    ('        if (project.modelMode == ModelMode.ExactReference) return parts.automaticScrollSeconds\n',
     '        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return parts.automaticScrollSeconds\n',
     'seal scroll duration'),
    ('        if (project.modelMode == ModelMode.ExactReference) return output * playbackRate(project)\n',
     '        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) return output * playbackRate(project)\n',
     'seal model time'),
    ('        if (project.modelMode == ModelMode.ExactReference) {\n            return customIntroDuration(project) + modelTime / playbackRate(project)\n        }\n',
     '        if (isSealedReference(project) || project.modelMode == ModelMode.ExactReference) {\n            return customIntroDuration(project) + modelTime / playbackRate(project)\n        }\n',
     'seal output time'),
    ('        val lockedMales = project.model == VisualModel.Males && project.modelMode == ModelMode.ExactReference\n',
     '        val lockedMales = project.model == VisualModel.Males && isSealedReference(project)\n',
     'seal males measured motion'),
]:
    replace_once(timeline_path, old, new, label)

replace_once(
    timeline_path,
    '''        if (
            project.cards.isEmpty() || project.model != VisualModel.Males || !project.showDisclaimer ||
            customIntroDuration(project) > 0f
        ) return false
''',
    '''        if (
            project.cards.isEmpty() || project.model != VisualModel.Males ||
            (!isSealedReference(project) && !project.showDisclaimer) ||
            customIntroVisible(project, outputTimeSeconds)
        ) return false
''',
    'preserve males intro after app pre-roll',
)

replace_once(
    timeline_path,
    '        if (project.model != VisualModel.Relationships || !project.showDisclaimer) return 0f\n',
    '        if (project.model != VisualModel.Relationships || (!isSealedReference(project) && !project.showDisclaimer)) return 0f\n',
    'seal relationships disclaimer',
)

replace_once(
    timeline_path,
    '        if (!project.showOutro) return 0f\n        if (isLockedRelationships(project)) {\n',
    '        if (!isSealedReference(project) && !project.showOutro) return 0f\n        if (isLockedRelationships(project)) {\n',
    'seal outro cover',
)
# The same prefix exists for outroContentAlpha; replace the next remaining copy.
replace_once(
    timeline_path,
    '        if (!project.showOutro) return 0f\n        if (isLockedRelationships(project)) {\n',
    '        if (!isSealedReference(project) && !project.showOutro) return 0f\n        if (isLockedRelationships(project)) {\n',
    'seal outro content',
)

replace_once(
    timeline_path,
    '''            if (!project.showOutro || frame >= contentEnd + RELATIONSHIPS_END_WIPE_FRAMES +
                RELATIONSHIPS_END_RISE_FRAMES + RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            ) return emptyList()
''',
    '''            if ((!isSealedReference(project) && !project.showOutro) ||
                frame >= contentEnd + RELATIONSHIPS_END_WIPE_FRAMES +
                RELATIONSHIPS_END_RISE_FRAMES + RELATIONSHIPS_END_HOLD_FRAMES + RELATIONSHIPS_FADE_FRAMES
            ) return emptyList()
''',
    'seal relationships end placement',
)

replace_once(
    timeline_path,
    '        if (!project.showOutro) return 1f\n        if (isLockedRelationships(project)) {\n',
    '        if (!isSealedReference(project) && !project.showOutro) return 1f\n        if (isLockedRelationships(project)) {\n',
    'seal fade',
)

# 3) Export renderer owns reference colours and badge/intro visibility.
replace_once(
    renderer_path,
    '''        val background = loadImage(displayCard.imageSubcard.backgroundSource)
        if (background != null) {
            drawCenterCrop(
                canvas = canvas,
                bitmap = background,
                destination = RectF(0f, 0f, cardWidth, height.toFloat()),
                focusX = 0.5f,
                focusY = 0.5f,
                zoom = 1f,
            )
        } else {
            val topColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 105, 211) else Color.rgb(19, 141, 219)
            val bottomColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 88, 181) else Color.rgb(11, 116, 190)
            paint.shader = LinearGradient(
                image.left,
                image.top,
                image.left,
                image.bottom,
                intArrayOf(topColor, topColor, bottomColor),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(image, paint)
            paint.shader = null
        }
''',
    '''        // Reference-model colours are owned by the model and are never replaced by app content.
        val topColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 105, 211) else Color.rgb(19, 141, 219)
        val bottomColor = if (project.model == VisualModel.Relationships) Color.rgb(0, 88, 181) else Color.rgb(11, 116, 190)
        paint.shader = LinearGradient(
            image.left,
            image.top,
            image.left,
            image.bottom,
            intArrayOf(topColor, topColor, bottomColor),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(image, paint)
        paint.shader = null
''',
    'lock export background colours',
)
replace_once(
    renderer_path,
    '            if (project.showHexagons && placement.badgeVisible) {\n',
    '            if (placement.badgeVisible) {\n',
    'seal export badges',
)
replace_once(
    renderer_path,
    '                project.showIntro && TimelineEngine.customIntroDuration(project) <= 0f,\n',
    '                true,\n',
    'seal relationships prelude rendering',
)

# 4) Preview uses the same model palette and no editing chrome inside the video image.
replace_once(
    monitor_path,
    '''            if (project.model == VisualModel.Relationships && project.showIntro &&
                TimelineEngine.customIntroDuration(project) <= 0f &&
                relationshipsFrame in 1 until TimelineEngine.RELATIONSHIPS_INTRO_OVERLAY_END_FRAME
            ) {
''',
    '''            if (project.model == VisualModel.Relationships &&
                relationshipsFrame in 1 until TimelineEngine.RELATIONSHIPS_INTRO_OVERLAY_END_FRAME
            ) {
''',
    'seal preview relationships prelude',
)
replace_once(
    monitor_path,
    '                    badgeVisible = project.showHexagons && placement.badgeVisible,\n',
    '                    badgeVisible = placement.badgeVisible,\n',
    'seal preview badges',
)
replace_once(
    monitor_path,
    '''                    selected = selectedCardId == card.id,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { onImageTransformChanged(card.id, it) },
''',
    '''                    selected = false,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { _ -> },
''',
    'remove preview editing chrome',
)
replace_once(
    monitor_path,
    '''    if (!displayCard.imageSubcard.backgroundSource.isNullOrBlank()) {
        Frame(NormalizedRect.Full) {
            FullCardBackground(displayCard.imageSubcard.backgroundSource)
        }
    }
    Frame(
        frames.image,
        Modifier.background(
            if (!displayCard.imageSubcard.backgroundSource.isNullOrBlank()) {
                Color.Transparent
            } else if (model == VisualModel.Relationships) {
                Color(0xFF1F1F1F)
            } else {
                Color.Transparent
            },
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(artworkReveal.coerceIn(0f, 1f))
                .align(Alignment.TopCenter)
                .background(
                    if (!displayCard.imageSubcard.backgroundSource.isNullOrBlank()) {
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                    } else {
                        Brush.verticalGradient(
                            if (model == VisualModel.Relationships) {
                                listOf(Color(0xFF0069D3), Color(0xFF0058B5))
                            } else {
                                listOf(Color(0xFF138DDB), Color(0xFF0B74BE))
                            },
                        )
                    },
                ),
        ) {
            ImageSubcardFrame(
                displayCard.imageSubcard,
                selected,
                onSelect,
                onImageTransformChanged,
                showPlaceholder = displayCard.imageSubcard.backgroundSource.isNullOrBlank(),
            )
        }
    }
''',
    '''    Frame(
        frames.image,
        Modifier.background(
            if (model == VisualModel.Relationships) Color(0xFF1F1F1F) else Color.Transparent,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(artworkReveal.coerceIn(0f, 1f))
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        if (model == VisualModel.Relationships) {
                            listOf(Color(0xFF0069D3), Color(0xFF0058B5))
                        } else {
                            listOf(Color(0xFF138DDB), Color(0xFF0B74BE))
                        },
                    ),
                ),
        ) {
            ImageSubcardFrame(
                displayCard.imageSubcard,
                selected = false,
                onSelect = onSelect,
                onTransformChanged = { _ -> },
                showPlaceholder = false,
            )
        }
    }
''',
    'lock preview background colours',
)

# 5) Wire the new Cards workspace without model-style/background override controls.
integrator = Path('tools/apply_cts2_cards_workspace.py')
text = integrator.read_text()
text = text.replace('                        onChooseBackground = { backgroundPicker.launch(arrayOf("image/*")) },\n', '')
integrator.write_text(text)

# 6) Update tests to the sealed-reference contract.
replace_once(
    project_test_path,
    '''    @Test
    fun customModeRetainsChosenVideoFormat() {
        val project = CtsProject(
            modelMode = ModelMode.Custom,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(1280, project.export.width)
        assertEquals(720, project.export.height)
        assertEquals(30, project.export.fps)
    }
''',
    '''    @Test
    fun referenceModelRejectsCustomVideoFormatAndMode() {
        val project = CtsProject(
            model = VisualModel.Males,
            modelMode = ModelMode.Custom,
            showHexagons = false,
            showIntro = false,
            showDisclaimer = false,
            showOutro = false,
            customDurationSeconds = 12f,
            export = ExportSettings(width = 1280, height = 720, fps = 30),
        ).normalized()

        assertEquals(ModelMode.ExactReference, project.modelMode)
        assertEquals(true, project.showHexagons)
        assertEquals(true, project.showIntro)
        assertEquals(true, project.showDisclaimer)
        assertEquals(true, project.showOutro)
        assertEquals(null, project.customDurationSeconds)
        assertEquals(1920, project.export.width)
        assertEquals(1080, project.export.height)
        assertEquals(60, project.export.fps)
    }
''',
    'update project sealing test',
)

replace_once(
    timeline_test_path,
    '''    @Test
    fun customMp4IntroPrecedesTheReferenceTimeline() {
        val withoutIntro = CtsProject(model = VisualModel.Relationships, showIntro = false)
        val withIntro = withoutIntro.copy(
            showIntro = true,
            introVideo = IntroVideoSettings(
                uri = "content://intro.mp4",
                displayName = "intro.mp4",
                durationSeconds = 7.5f,
            ),
        )

        assertEquals(
            TimelineEngine.automaticDuration(withoutIntro) + 7.5f,
            TimelineEngine.automaticDuration(withIntro),
            0.001f,
        )
        assertTrue(TimelineEngine.customIntroVisible(withIntro, 7.49f))
        assertTrue(!TimelineEngine.customIntroVisible(withIntro, 7.5f))
        assertEquals(
            TimelineEngine.RELATIONSHIPS_INTRO_FRAMES,
            TimelineEngine.relationshipsSourceFrame(withIntro, 7.5f),
        )
    }
''',
    '''    @Test
    fun customMp4IntroPrecedesButDoesNotReplaceReferenceIntro() {
        val withoutIntro = CtsProject(model = VisualModel.Relationships)
        val withIntro = withoutIntro.copy(
            introVideo = IntroVideoSettings(
                uri = "content://intro.mp4",
                displayName = "intro.mp4",
                durationSeconds = 7.5f,
            ),
        )

        assertEquals(
            TimelineEngine.automaticDuration(withoutIntro) + 7.5f,
            TimelineEngine.automaticDuration(withIntro),
            0.001f,
        )
        assertTrue(TimelineEngine.customIntroVisible(withIntro, 7.49f))
        assertTrue(!TimelineEngine.customIntroVisible(withIntro, 7.5f))
        assertEquals(0, TimelineEngine.relationshipsSourceFrame(withIntro, 7.5f))
    }
''',
    'preserve reference intro after pre-roll test',
)

replace_once(
    timeline_test_path,
    '''    @Test
    fun customLengthChangesOnlySecondsPerScrollingCard() {
        val automaticProject = CtsProject(model = VisualModel.Males, modelMode = ModelMode.Custom)
        val automaticDuration = TimelineEngine.automaticDuration(automaticProject)
        val customProject = automaticProject.copy(customDurationSeconds = automaticDuration + 6f)
        val scrollStart = 4 * REVEAL_SECONDS + INTRO_TAIL_HOLD_SECONDS
        assertEquals(automaticDuration + 6f, TimelineEngine.duration(customProject), 0.0001f)
        assertEquals(SCROLL_SECONDS + 6f, TimelineEngine.secondsPerCard(customProject), 0.0001f)
        assertEquals(scrollStart, TimelineEngine.modelTime(customProject, scrollStart), 0.0001f)
    }
''',
    '''    @Test
    fun referenceModelIgnoresCustomTimingRequests() {
        val project = CtsProject(
            model = VisualModel.Males,
            modelMode = ModelMode.Custom,
            customDurationSeconds = 42f,
        )
        assertEquals(TimelineEngine.automaticDuration(project), TimelineEngine.duration(project), 0.0001f)
        assertEquals(
            SCROLL_SECONDS / TimelineEngine.EXACT_REFERENCE_PLAYBACK_RATE,
            TimelineEngine.secondsPerCard(project),
            0.0001f,
        )
    }
''',
    'replace custom timing test with reference lock test',
)

# Contract assertions.
project_text = project_path.read_text()
timeline_text = timeline_path.read_text()
renderer_text = renderer_path.read_text()
monitor_text = monitor_path.read_text()
cards_text = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CardsWorkspace2.kt').read_text()
assert 'modelMode = if (sealedReference) ModelMode.ExactReference else modelMode' in project_text
assert 'showHexagons = if (sealedReference) true else showHexagons' in project_text
assert 'customDurationSeconds = if (sealedReference) null' in project_text
assert 'private fun isSealedReference(project: CtsProject)' in timeline_text
assert 'val lockedMales = project.model == VisualModel.Males && isSealedReference(project)' in timeline_text
assert 'backgroundSource' not in renderer_text[renderer_text.index('private fun drawCardBody'):renderer_text.index('private fun drawTextBlock')]
assert 'project.showHexagons && placement.badgeVisible' not in renderer_text
assert 'FullCardBackground(displayCard.imageSubcard.backgroundSource)' not in monitor_text
assert 'badgeVisible = placement.badgeVisible' in monitor_text
assert 'selected = false' in monitor_text
assert 'showPlaceholder = false' in monitor_text
assert 'onChooseBackground' not in cards_text
assert 'Reference settings' not in cards_text
assert 'colors = titleFieldColors2()' in cards_text
assert 'focusedContainerColor = Color.White' in cards_text
assert 'focusedTextColor = Color.Black' in cards_text
print('Reference models locked at project + timeline + renderer levels; title contrast preserved.')
