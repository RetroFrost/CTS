from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


# Legacy shell import accepted by older Compose but rejected by the current toolchain.
legacy = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/CtsApp.kt")
legacy.write_text(
    legacy.read_text().replace("import androidx.compose.foundation.layout.weight\n", ""),
)

shell_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/GoogleCtsApp.kt")
shell = shell_path.read_text()
shell = replace_once(shell, "ProgramMonitor(\n", "ProductionProgramMonitor(\n", "production monitor")
shell = replace_once(
    shell,
    "                selectedCardId = selectedCardId,\n                onSelectCard = onSelectCard,",
    "                selectedCardId = selectedCardId,\n                showEditorGuides = !isPlaying,\n                onSelectCard = onSelectCard,",
    "editor guides",
)
shell = replace_once(
    shell,
    "    val durationSeconds = TimelineEngine.duration(project)\n",
    "    val durationSeconds = TimelineEngine.duration(project)\n\n"
    "    SoundtrackPlaybackEffect(\n"
    "        soundtrack = project.soundtrack,\n"
    "        isPlaying = isPlaying,\n"
    "        positionSeconds = positionSeconds,\n"
    "    )\n",
    "soundtrack playback",
)
shell = replace_once(
    shell,
    '''                    GoogleEditorDestination.Audio -> GoogleEmptyState(
                        icon = Icons.Filled.MusicNote,
                        title = "Add a soundtrack",
                        body = "Audio mixing comes after the Android renderer and export pipeline.",
                    )''',
    '''                    GoogleEditorDestination.Audio -> SoundtrackPanel(
                        project = project,
                        onProjectChanged = { updated ->
                            project = updated.normalized()
                        },
                    )''',
    "audio panel",
)
shell = replace_once(
    shell,
    "        if (selectedCard != null) {",
    '''        item {
            BatchArtworkCard(
                project = project,
                selectedCardId = selectedCardId,
                onProjectChanged = onProjectChanged,
                onSelectCard = onSelectCard,
            )
        }

        if (selectedCard != null) {''',
    "batch artwork card",
)
shell = replace_once(
    shell,
    '''                onSeek = {
                    isPlaying = false
                    positionSeconds = it
                },
            )

            Box(''',
    '''                onSeek = {
                    isPlaying = false
                    positionSeconds = it
                },
            )

            VideoSpeedControl(
                project = project,
                onProjectChanged = { updated ->
                    project = updated.normalized()
                    positionSeconds = positionSeconds.coerceAtMost(TimelineEngine.duration(project))
                    isPlaying = false
                },
            )

            Box(''',
    "video speed controller",
)
shell = replace_once(
    shell,
    '''        GoogleInsertDataDialog(
            existingCards = project.cards,''',
    '''        SmartPasteDataDialog(
            existingCards = project.cards,
            selectedCardId = selectedCardId,''',
    "smart paste dialog",
)
export_start = shell.index("@Composable\nprivate fun GoogleExportEditor(")
export_end = shell.index("\n@Composable\nprivate fun GoogleEmptyState(", export_start)
export_panel = '''@Composable
private fun GoogleExportEditor(
    project: CtsProject,
    onSave: () -> Unit,
) {
    Mp4ExportPanel(
        project = project,
        onSaveProject = onSave,
    )
}
'''
shell = shell[:export_start] + export_panel + shell[export_end:]
shell_path.write_text(shell)

preview_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProductionProgramMonitor.kt")
preview = preview_path.read_text()
illustrated_start = preview.index("@Composable\nprivate fun BoxWithConstraintsScope.ProductionIllustratedCard(")
illustrated_end = preview.index("\n@Composable\nprivate fun BoxWithConstraintsScope.ProductionCompactCard(", illustrated_start)
illustrated = '''@Composable
private fun BoxWithConstraintsScope.ProductionIllustratedCard(
    card: CtsCard,
    showHexagons: Boolean,
    selected: Boolean,
    editorEnabled: Boolean,
    onSelect: () -> Unit,
    onImageTransformChanged: (NormalizedRect) -> Unit,
) {
    ProductionSceneFrame(
        rect = productionImageFrame(VisualModel.Illustrated),
        modifier = Modifier.background(
            Brush.verticalGradient(
                0f to Color(0xFF57D0E6),
                0.64f to Color(0xFF57D0E6),
                0.65f to Color(0xFFEBC57D),
                1f to Color(0xFFF2D69A),
            ),
        ),
    ) {
        ProductionImageSubcard(
            subcard = card.imageSubcard,
            selected = selected,
            editorEnabled = editorEnabled,
            contentScale = ContentScale.Crop,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.612f, 1f, 0.028f),
        modifier = Modifier.background(
            Brush.verticalGradient(listOf(Color.Transparent, Color(0x6A000000))),
        ),
    ) {}

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.628f, 1f, 0.100f),
        modifier = Modifier.background(Color(0xFFF7F6F2)),
    ) {
        ProductionCardText(
            text = card.title,
            color = Color(0xFF151515),
            weight = FontWeight.Bold,
            size = 8.5.sp,
            maxLines = 2,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.721f, 1f, 0.007f),
        modifier = Modifier.background(Color(0xFFBC6300)),
    ) {}

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.728f, 1f, 0.272f),
        modifier = Modifier.background(Color(0xFF161616)),
    ) {
        if (card.description.isNotBlank()) {
            ProductionCardText(
                text = card.description,
                color = Color(0xFFF0F0F0),
                weight = FontWeight.Normal,
                size = 6.3.sp,
                maxLines = 4,
            )
        }
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0.02f, 0.005f, 0.96f, 0.40f),
    ) {
        ProductionBadge(
            card = card,
            showHexagons = showHexagons,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
'''
preview = preview[:illustrated_start] + illustrated + preview[illustrated_end:]
preview = replace_once(
    preview,
    "private fun BoxWithConstraintsScope.ProductionBadge(",
    "private fun BoxScope.ProductionBadge(",
    "badge receiver",
)
preview = replace_once(
    preview,
    "    val badgeWidth = maxWidth * 0.66f\n    val badgeHeight = maxHeight * 0.54f\n\n",
    "",
    "badge local dimensions",
)
preview = replace_once(
    preview,
    "            .width(badgeWidth)\n            .height(badgeHeight)",
    "            .fillMaxSize(0.72f)",
    "badge size",
)
preview = preview.replace(
    "listOf(Color(0xFF8A70F2), Color(0xFF6D55D8))",
    "listOf(Color(0xFFFF4B55), Color(0xFFD71920))",
)
preview = preview.replace(
    "Color(0xFFE4DEFF), ProductionHexagon",
    "Color(0xFFE6B45B), ProductionHexagon",
)
preview = replace_once(
    preview,
    '''        Text(
            text = listOf(card.badgePrimary, card.badgeSecondary)
                .filter(String::isNotBlank)
                .joinToString("\\n"),
            color = Color.White,
            fontWeight = FontWeight.Black,
            fontSize = 9.5.sp,
            lineHeight = 9.5.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )''',
    '''        AutoFitBadgeText(
            text = io.github.retrofrost.cts.android.model.formatBadgeText(
                card.badgePrimary,
                card.badgeSecondary,
            ),
            maxLines = 4,
        )''',
    "adaptive badge text",
)
preview_path.write_text(preview)

renderer_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/export/CtsCanvasSceneRenderer.kt")
renderer = renderer_path.read_text()
illustrated_start = renderer.index("    private fun drawIllustrated(")
illustrated_end = renderer.index("\n    private fun drawCompact(", illustrated_start)
illustrated = '''    private fun drawIllustrated(canvas: Canvas, card: CtsCard, width: Float, height: Float) {
        val imageFrame = frame(width, height, NormalizedRect(0.01f, 0.01f, 0.98f, 0.87f))
        paint.shader = LinearGradient(
            0f,
            imageFrame.top,
            0f,
            imageFrame.bottom,
            intArrayOf(
                Color.rgb(87, 208, 230),
                Color.rgb(87, 208, 230),
                Color.rgb(235, 197, 125),
                Color.rgb(242, 214, 154),
            ),
            floatArrayOf(0f, 0.64f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(imageFrame, paint)
        paint.shader = null
        drawImageSubcard(canvas, card, imageFrame, crop = true)

        val shadow = frame(width, height, NormalizedRect(0f, 0.612f, 1f, 0.028f))
        paint.shader = LinearGradient(
            0f,
            shadow.top,
            0f,
            shadow.bottom,
            Color.TRANSPARENT,
            Color.argb(105, 0, 0, 0),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(shadow, paint)
        paint.shader = null

        val title = frame(width, height, NormalizedRect(0f, 0.628f, 1f, 0.100f))
        fill(canvas, title, Color.rgb(247, 246, 242))
        drawCenteredText(canvas, card.title, title, Color.rgb(21, 21, 21), width * 0.057f, true, 2)

        fillFrame(canvas, width, height, NormalizedRect(0f, 0.721f, 1f, 0.007f), Color.rgb(188, 99, 0))

        val description = frame(width, height, NormalizedRect(0f, 0.728f, 1f, 0.272f))
        fill(canvas, description, Color.rgb(22, 22, 22))
        if (card.description.isNotBlank()) {
            drawCenteredText(
                canvas,
                card.description,
                description,
                Color.rgb(240, 240, 240),
                width * 0.041f,
                false,
                4,
            )
        }

        drawBadge(canvas, card, frame(width, height, NormalizedRect(0f, 0.005f, 1f, 0.52f)))
    }
'''
renderer = renderer[:illustrated_start] + illustrated + renderer[illustrated_end:]
renderer = replace_once(
    renderer,
    "import android.graphics.RectF",
    "import android.graphics.Rect\nimport android.graphics.RectF",
    "integer source rect import",
)
renderer = renderer.replace(
    "stream?.use(BitmapFactory::decodeStream)",
    "stream?.use { BitmapFactory.decodeStream(it) }",
)
renderer = renderer.replace(
    "        val source = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())",
    "        val source = Rect(0, 0, bitmap.width, bitmap.height)",
)
renderer = renderer.replace(
    "                source.left = (bitmap.width - wantedWidth) / 2f\n                source.right = source.left + wantedWidth",
    "                source.left = ((bitmap.width - wantedWidth) / 2f).toInt()\n"
    "                source.right = (source.left + wantedWidth).toInt()",
)
renderer = renderer.replace(
    "                source.top = (bitmap.height - wantedHeight) / 2f\n                source.bottom = source.top + wantedHeight",
    "                source.top = ((bitmap.height - wantedHeight) / 2f).toInt()\n"
    "                source.bottom = (source.top + wantedHeight).toInt()",
)
renderer = renderer.replace(
    "paint.color = Color.rgb(255, 222, 224)",
    "paint.color = Color.rgb(230, 180, 91)",
)
renderer = replace_once(
    renderer,
    '''        val label = listOf(card.badgePrimary, card.badgeSecondary)
            .filter(String::isNotBlank)
            .joinToString("\\n")
        drawCenteredText(canvas, label, badge, Color.WHITE, badge.width() * 0.12f, true, 3)''',
    '''        val label = io.github.retrofrost.cts.android.model.formatBadgeText(
            card.badgePrimary,
            card.badgeSecondary,
        )
        drawCenteredText(canvas, label, badge, Color.WHITE, badge.width() * 0.12f, true, 4)''',
    "canvas badge wrapping",
)
text_start = renderer.index("    private fun drawCenteredText(")
text_end = renderer.index("\n    private fun fillFrame(", text_start)
text_method = '''    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        color: Int,
        size: Float,
        bold: Boolean,
        maxLines: Int,
    ) {
        if (text.isBlank() || rect.width() <= 1f || rect.height() <= 1f) return
        textPaint.color = color
        textPaint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        textPaint.textAlign = Paint.Align.LEFT

        val horizontalPadding = rect.width() * 0.055f
        val layoutWidth = (rect.width() - horizontalPadding * 2f).toInt().coerceAtLeast(1)
        val minimumSize = max(8f, size * 0.48f)
        var candidateSize = size.coerceAtLeast(minimumSize)
        var finalLayout: StaticLayout? = null

        while (finalLayout == null) {
            textPaint.textSize = candidateSize
            val candidate = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val ellipsized = candidate.lineCount > 0 &&
                candidate.getEllipsisCount(candidate.lineCount - 1) > 0
            val fits = !ellipsized && candidate.height <= rect.height()
            if (fits || candidateSize <= minimumSize + 0.01f) {
                finalLayout = candidate
            } else {
                candidateSize = max(minimumSize, candidateSize - 1f)
            }
        }

        val layout = requireNotNull(finalLayout)
        val x = rect.left + horizontalPadding
        val y = rect.top + (rect.height() - layout.height) / 2f
        val save = canvas.save()
        canvas.clipRect(rect)
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restoreToCount(save)
    }
'''
renderer = renderer[:text_start] + text_method + renderer[text_end:]
renderer_path.write_text(renderer)
