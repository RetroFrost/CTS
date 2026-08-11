from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    text = path.read_text()
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'{label}: expected source block not found in {path}')
    path.write_text(text.replace(old, new, 1))


# 1) Every shipped reference model is a sealed exact-reference preset.
project_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/model/CtsProject.kt')
old = '''    fun normalized(): CtsProject {
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
'''
new = '''    fun normalized(): CtsProject {
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
'''
replace_once(project_path, old, new, 'seal reference project settings')

# 2) A custom app-level pre-roll must not replace the model's own intro animation.
timeline_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt')
old = '''    private fun builtInIntroEnabled(project: CtsProject): Boolean =
        project.showIntro && customIntroDuration(project) <= 0f
'''
new = '''    private fun builtInIntroEnabled(project: CtsProject): Boolean =
        project.showIntro
'''
replace_once(timeline_path, old, new, 'preserve built-in reference intro')

# 3) Export renderer always owns the model background colours; card content cannot replace them.
renderer_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/export/ReferenceFrameRenderer.kt')
old = '''        val background = loadImage(displayCard.imageSubcard.backgroundSource)
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
'''
new = '''        // Reference-model colours are owned by the model and are never replaced by app content.
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
'''
replace_once(renderer_path, old, new, 'lock export background colours')

# 4) Preview uses the same fixed palette and no editor chrome inside the video frame.
monitor_path = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProgramMonitor.kt')
old = '''                    selected = selectedCardId == card.id,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { onImageTransformChanged(card.id, it) },
'''
new = '''                    selected = false,
                    onSelect = { onSelectCard(card.id) },
                    onImageTransformChanged = { _ -> },
'''
replace_once(monitor_path, old, new, 'remove preview editing chrome')

old = '''    if (!displayCard.imageSubcard.backgroundSource.isNullOrBlank()) {
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
'''
new = '''    Frame(
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
'''
replace_once(monitor_path, old, new, 'lock preview background colours')

# 5) Wire the new CTS 2.0 Cards workspace without any model-style/background override controls.
integrator = Path('tools/apply_cts2_cards_workspace.py')
text = integrator.read_text()
text = text.replace('                        onChooseBackground = { backgroundPicker.launch(arrayOf("image/*")) },\n', '')
integrator.write_text(text)

# Contract assertions.
project_text = project_path.read_text()
renderer_text = renderer_path.read_text()
monitor_text = monitor_path.read_text()
cards_text = Path('android/app/src/main/java/io/github/retrofrost/cts/android/ui/CardsWorkspace2.kt').read_text()
assert 'modelMode = if (sealedReference) ModelMode.ExactReference else modelMode' in project_text
assert 'showHexagons = if (sealedReference) true else showHexagons' in project_text
assert 'customDurationSeconds = if (sealedReference) null' in project_text
assert 'backgroundSource' not in renderer_text[renderer_text.index('private fun drawCardBody'):renderer_text.index('private fun drawTextBlock')]
assert 'FullCardBackground(displayCard.imageSubcard.backgroundSource)' not in monitor_text
assert 'selected = false' in monitor_text
assert 'showPlaceholder = false' in monitor_text
assert 'onChooseBackground' not in cards_text
assert 'Reference settings' not in cards_text
assert 'colors = titleFieldColors2()' in cards_text
assert 'focusedContainerColor = Color.White' in cards_text
assert 'focusedTextColor = Color.Black' in cards_text
print('Reference models locked: exact timing/behaviour, fixed colours, clean preview, title contrast preserved.')
