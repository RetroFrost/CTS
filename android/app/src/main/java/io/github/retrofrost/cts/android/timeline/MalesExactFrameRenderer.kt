package io.github.retrofrost.cts.android.timeline

/**
 * Exact-frame Males reference renderer contract.
 *
 * The renderer consumes canonical source-frame state rather than interpolating
 * between hand-picked keyframes. Frame timing is locked to the 60 FPS source.
 */
object MalesExactFrameRenderer {
    const val SOURCE_FPS = 60
    const val SOURCE_FRAME_COUNT = 16_741
    const val SOURCE_DURATION_MS = 279_016L

    data class FrameState(
        val frame: Int,
        val cardOffsetPx: Float,
        val badgeOffsetYPx: Float,
        val badgeScale: Float,
        val badgeOpacity: Float,
        val titleVisible: Boolean,
    )

    fun frameAt(timeMs: Long): Int =
        (timeMs.coerceAtLeast(0L) * SOURCE_FPS / 1000L)
            .toInt()
            .coerceIn(0, SOURCE_FRAME_COUNT - 1)

    /**
     * Exact source-state lookup. Unknown states deliberately do not invent an
     * easing curve; callers must supply the canonical per-frame table.
     */
    fun stateAt(frame: Int, table: List<FrameState>): FrameState {
        require(table.isNotEmpty()) { "Males frame-state table is empty" }
        return table[frame.coerceIn(0, table.lastIndex)]
    }
}
