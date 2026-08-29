package io.github.retrofrost.cts.android

/**
 * Frame-scoped ownership ledger for renderer composition.
 *
 * A logical element owns exactly one paint pass per frame. Intentional layers
 * inside that element (gradient, stroke, shine, text) remain legal, but a
 * second caller cannot paint the same logical element again.
 */
internal class RenderPassLedger {
    private val claimed = HashSet<String>()

    fun claim(key: String): Boolean = claimed.add(key)

    fun once(key: String, block: () -> Unit) {
        if (claim(key)) block()
    }
}
