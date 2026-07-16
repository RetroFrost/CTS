from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise RuntimeError(f"Patch target not found: {label}")
    return text.replace(old, new, 1)


# Match the uploaded reference video: four narrow cards and its slower continuous strip.
model_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/model/CtsProject.kt")
model = model_path.read_text()
model = replace_once(
    model,
    'Illustrated("illustrated_cards", "Illustrated Cards", 3)',
    'Illustrated("illustrated_cards", "Illustrated Cards", 4)',
    "four illustrated cards",
)
model_path.write_text(model)

timeline_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/timeline/TimelineEngine.kt")
timeline = timeline_path.read_text()
timeline = replace_once(
    timeline,
    "const val SCROLL_SECONDS = 10f / 3f",
    "const val SCROLL_SECONDS = 4.4f",
    "reference scroll rate",
)
timeline_path.write_text(timeline)


# Preview renderer: exact card bands measured from the 640x360 source video.
preview_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/ui/ProductionProgramMonitor.kt")
preview = preview_path.read_text()

shape_start = preview.index("private val ProductionHexagon = GenericShape")
shape_end = preview.index("\n\n@Composable\nprivate fun ProductionParentCard", shape_start)
octagon = '''private val ProductionHexagon = GenericShape { size, _ ->
    moveTo(size.width * 0.23f, 0f)
    lineTo(size.width * 0.77f, 0f)
    lineTo(size.width, size.height * 0.23f)
    lineTo(size.width, size.height * 0.77f)
    lineTo(size.width * 0.77f, size.height)
    lineTo(size.width * 0.23f, size.height)
    lineTo(0f, size.height * 0.77f)
    lineTo(0f, size.height * 0.23f)
    close()
}'''
preview = preview[:shape_start] + octagon + preview[shape_end:]

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
    // Source-video geometry at 640x360:
    // artwork 263px, title 40px, separator 2px, description 55px.
    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0f, 1f, 0.730f),
        modifier = Modifier.background(
            Brush.verticalGradient(
                0f to Color(0xFF59CFE5),
                0.64f to Color(0xFF59CFE5),
                0.65f to Color(0xFFEBC77F),
                1f to Color(0xFFF1D89E),
            ),
        ),
    ) {
        ProductionImageSubcard(
            subcard = card.imageSubcard,
            selected = selected,
            editorEnabled = editorEnabled,
            // Transparent lineal-color artwork remains fully visible. Full-scene artwork can
            // still be enlarged with the existing resize handles to create a crop.
            contentScale = ContentScale.Fit,
            onSelect = onSelect,
            onTransformChanged = onImageTransformChanged,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.730f, 1f, 0.112f),
        modifier = Modifier.background(Color(0xFFF7F6F2)),
    ) {
        ProductionCardText(
            text = card.title,
            color = Color(0xFF171717),
            weight = FontWeight.Bold,
            size = 9.0.sp,
            maxLines = 2,
        )
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.842f, 1f, 0.006f),
        modifier = Modifier.background(Color(0xFFA56000)),
    ) {}

    ProductionSceneFrame(
        rect = NormalizedRect(0f, 0.848f, 1f, 0.152f),
        modifier = Modifier.background(Color(0xFF171717)),
    ) {
        if (card.description.isNotBlank()) {
            ProductionCardText(
                text = card.description,
                color = Color(0xFFDADADA),
                weight = FontWeight.Normal,
                size = 6.2.sp,
                maxLines = 4,
            )
        }
    }

    ProductionSceneFrame(
        rect = NormalizedRect(0.14f, 0.005f, 0.72f, 0.370f),
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
    ".border(0.5.dp, Color(0xFF050506))",
    ".border(1.dp, Color(0xFF050506))",
    "full black card dividers",
)
preview = replace_once(
    preview,
    ".fillMaxSize(0.72f)",
    ".fillMaxSize(0.88f)",
    "reference badge size",
)
preview = preview.replace("Color(0xFFE6B45B)", "Color(0xFFFFA6AA)")
preview = preview.replace(
    "VisualModel.Illustrated -> NormalizedRect(0.01f, 0.01f, 0.98f, 0.87f)",
    "VisualModel.Illustrated -> NormalizedRect(0f, 0f, 1f, 0.730f)",
)
preview_path.write_text(preview)


# MP4 Canvas renderer: use the same geometry and contain lineal-color artwork.
renderer_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/export/CtsCanvasSceneRenderer.kt")
renderer = renderer_path.read_text()
illustrated_start = renderer.index("    private fun drawIllustrated(")
illustrated_end = renderer.index("\n    private fun drawCompact(", illustrated_start)
illustrated = '''    private fun drawIllustrated(canvas: Canvas, card: CtsCard, width: Float, height: Float) {
        val imageFrame = frame(width, height, NormalizedRect(0f, 0f, 1f, 0.730f))
        paint.shader = LinearGradient(
            0f,
            imageFrame.top,
            0f,
            imageFrame.bottom,
            intArrayOf(
                Color.rgb(89, 207, 229),
                Color.rgb(89, 207, 229),
                Color.rgb(235, 199, 127),
                Color.rgb(241, 216, 158),
            ),
            floatArrayOf(0f, 0.64f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(imageFrame, paint)
        paint.shader = null
        drawImageSubcard(canvas, card, imageFrame, crop = false)

        val title = frame(width, height, NormalizedRect(0f, 0.730f, 1f, 0.112f))
        fill(canvas, title, Color.rgb(247, 246, 242))
        drawCenteredText(
            canvas,
            card.title,
            title,
            Color.rgb(23, 23, 23),
            width * 0.105f,
            true,
            2,
        )

        fillFrame(
            canvas,
            width,
            height,
            NormalizedRect(0f, 0.842f, 1f, 0.006f),
            Color.rgb(165, 96, 0),
        )

        val description = frame(width, height, NormalizedRect(0f, 0.848f, 1f, 0.152f))
        fill(canvas, description, Color.rgb(23, 23, 23))
        if (card.description.isNotBlank()) {
            drawCenteredText(
                canvas,
                card.description,
                description,
                Color.rgb(218, 218, 218),
                width * 0.055f,
                false,
                4,
            )
        }

        drawBadge(
            canvas,
            card,
            frame(width, height, NormalizedRect(0.14f, 0.005f, 0.72f, 0.370f)),
        )
    }
'''
renderer = renderer[:illustrated_start] + illustrated + renderer[illustrated_end:]
renderer = replace_once(
    renderer,
    "        paint.strokeWidth = max(1f, width * 0.003f)",
    "        paint.strokeWidth = max(2f, width * 0.0125f)",
    "export card dividers",
)
renderer = replace_once(
    renderer,
    "        val badgeWidth = owner.width() * 0.66f\n        val badgeHeight = owner.height() * 0.54f",
    "        val badgeWidth = owner.width() * 0.88f\n        val badgeHeight = owner.height() * 0.88f",
    "export badge size",
)
renderer = renderer.replace("Color.rgb(230, 180, 91)", "Color.rgb(255, 166, 170)")

hexagon_start = renderer.index("    private fun hexagonPath(")
hexagon_end = renderer.index("\n\n    private fun loadBitmap(", hexagon_start)
octagon_path = '''    private fun hexagonPath(rect: RectF): Path = Path().apply {
        moveTo(rect.left + rect.width() * 0.23f, rect.top)
        lineTo(rect.left + rect.width() * 0.77f, rect.top)
        lineTo(rect.right, rect.top + rect.height() * 0.23f)
        lineTo(rect.right, rect.top + rect.height() * 0.77f)
        lineTo(rect.left + rect.width() * 0.77f, rect.bottom)
        lineTo(rect.left + rect.width() * 0.23f, rect.bottom)
        lineTo(rect.left, rect.top + rect.height() * 0.77f)
        lineTo(rect.left, rect.top + rect.height() * 0.23f)
        close()
    }'''
renderer = renderer[:hexagon_start] + octagon_path + renderer[hexagon_end:]
renderer_path.write_text(renderer)


# MediaCodec input surfaces are EGL-thread-affine. A coroutine can otherwise resume on a
# different Default worker after publishing progress, which makes eglMakeCurrent fail on
# Samsung/Exynos devices. Keep the complete video loop on one dedicated encoder thread.
exporter_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/export/CtsMp4Exporter.kt")
exporter = exporter_path.read_text()
exporter = replace_once(
    exporter,
    "import kotlinx.coroutines.Dispatchers\n",
    "import kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.asCoroutineDispatcher\n",
    "encoder dispatcher import",
)
exporter = replace_once(
    exporter,
    "import java.io.File\n",
    "import java.io.File\nimport java.util.concurrent.Executors\n",
    "encoder executor import",
)
old_call = '''            encodeVideo(
                context = context,
                project = normalized,
                preset = preset,
                duration = duration,
                frameCount = frameCount,
                outputFile = videoFile,
                onProgress = { progress -> report(progress * videoWeight) },
            )'''
new_call = '''            val encoderDispatcher = Executors
                .newSingleThreadExecutor { runnable ->
                    Thread(runnable, "cts-video-encoder").apply { priority = Thread.NORM_PRIORITY }
                }
                .asCoroutineDispatcher()
            try {
                withContext(encoderDispatcher) {
                    encodeVideo(
                        context = context,
                        project = normalized,
                        preset = preset,
                        duration = duration,
                        frameCount = frameCount,
                        outputFile = videoFile,
                        onProgress = { progress -> report(progress * videoWeight) },
                    )
                }
            } finally {
                encoderDispatcher.close()
            }'''
exporter = replace_once(exporter, old_call, new_call, "single-thread video encoding")
exporter_path.write_text(exporter)


# Prefer the recordable RGB888 EGL configuration used by Android encoder surfaces. Alpha
# channels are deliberately omitted because several Mali/Exynos drivers reject that config.
surface_path = Path("android/app/src/main/java/io/github/retrofrost/cts/android/export/CodecInputSurface.kt")
surface = surface_path.read_text()
surface = replace_once(
    surface,
    '''    fun makeCurrent() {
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            "Could not make encoder EGL surface current"
        }
    }''',
    '''    fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            val code = EGL14.eglGetError()
            error(
                "Could not make encoder EGL surface current " +
                    "(EGL 0x${Integer.toHexString(code)})",
            )
        }
    }''',
    "diagnostic EGL makeCurrent",
)
setup_start = surface.index("    private fun setupEgl() {")
setup_end = surface.index("\n\n    private fun checkEgl(", setup_start)
setup = '''    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display !== EGL14.EGL_NO_DISPLAY) { "Could not get EGL display" }

        val versions = IntArray(2)
        check(EGL14.eglInitialize(display, versions, 0, versions, 1)) {
            "Could not initialize EGL"
        }
        check(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
            "Could not bind the OpenGL ES EGL API"
        }

        val config = chooseRecordableConfig()
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context !== EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed with EGL error 0x${Integer.toHexString(EGL14.eglGetError())}"
        }

        eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface !== EGL14.EGL_NO_SURFACE) {
            "eglCreateWindowSurface failed with EGL error 0x${Integer.toHexString(EGL14.eglGetError())}"
        }
    }

    private fun chooseRecordableConfig(): android.opengl.EGLConfig {
        val choices = listOf(
            intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            ),
            intArrayOf(
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 5,
                EGL14.EGL_GREEN_SIZE, 6,
                EGL14.EGL_BLUE_SIZE, 5,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            ),
        )

        choices.forEach { attributes ->
            val configs = arrayOfNulls<android.opengl.EGLConfig>(16)
            val count = IntArray(1)
            if (
                EGL14.eglChooseConfig(
                    display,
                    attributes,
                    0,
                    configs,
                    0,
                    configs.size,
                    count,
                    0,
                ) && count[0] > 0
            ) {
                configs.firstOrNull { it != null }?.let { return it }
            }
        }
        error(
            "No recordable EGL window configuration " +
                "(EGL 0x${Integer.toHexString(EGL14.eglGetError())})",
        )
    }'''
surface = surface[:setup_start] + setup + surface[setup_end:]
surface_path.write_text(surface)
