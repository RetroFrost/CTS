package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable

/** Small local helper so count-based lazy items remain explicit and compile independently. */
fun LazyListScope.items(
    count: Int,
    itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
) {
    repeat(count) { index ->
        item(key = index) {
            itemContent(index)
        }
    }
}
