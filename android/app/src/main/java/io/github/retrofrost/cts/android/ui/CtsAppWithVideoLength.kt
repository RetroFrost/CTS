package io.github.retrofrost.cts.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.DurationRuntime
import kotlin.math.roundToInt

/**
 * Keeps the previous CTS Easy duration workflow visible while using the recreated editor.
 * Automatic length is the default. A custom MM:SS target scales the entire timeline and is
 * used by both live preview and the WorkManager background encoder.
 */
@Composable
fun CtsAndroidAppWithVideoLength() {
    var showDialog by remember { mutableStateOf(false) }
    val customSeconds = DurationRuntime.effectiveCustomSeconds()

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            CtsAndroidAppV2()
        }
        Surface(
            tonalElevation = 5.dp,
            shadowElevation = 7.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Schedule, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Video length", fontWeight = FontWeight.Black)
                    Text(
                        if (customSeconds == null) {
                            "Automatic · based on the number of cards"
                        } else {
                            "Custom · ${formatVideoLength(customSeconds)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { showDialog = true }) {
                    Text("Set length")
                }
            }
        }
    }

    if (showDialog) {
        VideoLengthDialog(
            initialCustomSeconds = customSeconds,
            onDismiss = { showDialog = false },
            onAutomatic = {
                DurationRuntime.useAutomatic()
                showDialog = false
            },
            onCustom = { seconds ->
                DurationRuntime.useCustom(seconds)
                showDialog = false
            },
        )
    }
}

@Composable
private fun VideoLengthDialog(
    initialCustomSeconds: Float?,
    onDismiss: () -> Unit,
    onAutomatic: () -> Unit,
    onCustom: (Float) -> Unit,
) {
    var customMode by remember { mutableStateOf(initialCustomSeconds != null) }
    var input by remember {
        mutableStateOf(formatVideoLength(initialCustomSeconds ?: 60f, padMinutes = true))
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video length") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This works like previous CTS versions: keep automatic timing, or enter a target length. " +
                        "A custom target speeds up or slows down the whole animation.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !customMode,
                        onClick = {
                            customMode = false
                            error = null
                        },
                        label = { Text("Automatic") },
                    )
                    FilterChip(
                        selected = customMode,
                        onClick = {
                            customMode = true
                            error = null
                        },
                        label = { Text("Custom") },
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null
                    },
                    enabled = customMode,
                    singleLine = true,
                    label = { Text("Target length") },
                    placeholder = { Text("MM:SS") },
                    supportingText = {
                        Text("Examples: 00:45, 01:30, 10:00")
                    },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!customMode) {
                        onAutomatic()
                    } else {
                        val seconds = parseVideoLength(input)
                        if (seconds == null) {
                            error = "Enter a valid length such as 01:30."
                        } else {
                            onCustom(seconds)
                        }
                    }
                },
            ) {
                Text("Apply")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun parseVideoLength(text: String): Float? {
    val value = text.trim()
    if (value.isEmpty()) return null
    val parts = value.split(':')
    if (parts.size !in 1..3 || parts.any { it.isBlank() || it.any { char -> !char.isDigit() } }) {
        return null
    }
    val numbers = parts.map { it.toLongOrNull() ?: return null }
    val total = when (numbers.size) {
        1 -> numbers[0]
        2 -> {
            if (numbers[1] !in 0..59) return null
            numbers[0] * 60 + numbers[1]
        }
        3 -> {
            if (numbers[1] !in 0..59 || numbers[2] !in 0..59) return null
            numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
        else -> return null
    }
    if (total !in 1..86_400) return null
    return total.toFloat()
}

internal fun formatVideoLength(seconds: Float, padMinutes: Boolean = false): String {
    val total = seconds.coerceAtLeast(0f).roundToInt()
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val remainder = total % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, remainder)
        padMinutes -> "%02d:%02d".format(minutes, remainder)
        else -> "%d:%02d".format(minutes, remainder)
    }
}
