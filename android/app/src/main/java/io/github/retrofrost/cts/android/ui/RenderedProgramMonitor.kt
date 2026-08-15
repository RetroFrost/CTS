package io.github.retrofrost.cts.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.export.ReferenceFrameRenderer
import io.github.retrofrost.cts.android.model.CtsProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Preview of the actual encoded renderer. There is no parallel Compose model,
 * so anything visible here is the same frame MediaCodec receives.
 */
@Composable
internal fun RenderedProgramMonitor(
    project: CtsProject,
    positionSeconds: Float,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cleanupScope = rememberCoroutineScope()
    val renderer = remember(context, project) {
        ReferenceFrameRenderer(context, project, PREVIEW_WIDTH, PREVIEW_HEIGHT)
    }
    val renderMutex = remember { Mutex() }
    var frame by remember(renderer) { mutableStateOf<Bitmap?>(null) }
    var renderError by remember(renderer) { mutableStateOf<String?>(null) }
    val previewTick = (positionSeconds.coerceAtLeast(0f) * PREVIEW_FPS).toInt()

    DisposableEffect(renderer) {
        onDispose {
            cleanupScope.launch(Dispatchers.Default + NonCancellable) { renderer.close() }
        }
    }

    LaunchedEffect(renderer, previewTick) {
        // Coalesce rapid editor keystrokes without delaying timeline playback.
        if (!isPlaying) delay(75)
        val time = previewTick / PREVIEW_FPS.toFloat()
        val rendered = runCatching {
            withContext(Dispatchers.Default) {
                renderMutex.withLock {
                    coroutineContext.ensureActive()
                    Bitmap.createBitmap(PREVIEW_WIDTH, PREVIEW_HEIGHT, Bitmap.Config.ARGB_8888).also { target ->
                        renderer.render(target, time)
                    }
                }
            }
        }
        rendered.onSuccess {
            frame = it
            renderError = null
        }.onFailure { error ->
            coroutineContext.ensureActive()
            renderError = error.message ?: "Preview renderer failed"
        }
    }

    Surface(
        modifier = modifier.aspectRatio(16f / 9f),
        shape = MaterialTheme.shapes.large,
        color = Color.Black,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            renderError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            } ?: frame?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Video preview",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } ?: CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

private const val PREVIEW_WIDTH = 640
private const val PREVIEW_HEIGHT = 360
private const val PREVIEW_FPS = 30
