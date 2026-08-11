package io.github.retrofrost.cts.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.VisualModel

@Composable
fun CtsFirstRunSetup(
    initialModel: VisualModel,
    onComplete: (VisualModel) -> Unit,
) {
    var page by remember { mutableStateOf(0) }
    var model by remember { mutableStateOf(initialModel) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 22.dp),
        ) {
            Text(
                text = "CTS",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (page + 1) / 3f },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(34.dp))

            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    fadeIn(tween(220, easing = FastOutSlowInEasing)) togetherWith
                        fadeOut(tween(130, easing = FastOutSlowInEasing))
                },
                label = "cts-setup-page",
            ) { current ->
                when (current) {
                    0 -> SetupPage(
                        eyebrow = "Welcome",
                        title = "Create comparisons without editing a timeline.",
                        body = "Add your cards and artwork. CTS handles the layout, animation, timing and final video export for you.",
                    )
                    1 -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SetupPage(
                            eyebrow = "Video style",
                            title = "Choose how your cards should look.",
                            body = "You can change this later for any project.",
                        )
                        VisualModel.entries.forEach { option ->
                            FilterChip(
                                selected = model == option,
                                onClick = { model = option },
                                label = { Text(option.label) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    else -> SetupPage(
                        eyebrow = "Ready",
                        title = "Everything important stays automatic.",
                        body = "Preview and final export share the same renderer, Auto picks an efficient hardware encoder when available, and long exports can continue in the background.",
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (page > 0) {
                    OutlinedButton(
                        onClick = { page -= 1 },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back")
                    }
                }
                Button(
                    onClick = {
                        if (page < 2) page += 1 else onComplete(model)
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (page < 2) "Continue" else "Start creating", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SetupPage(
    eyebrow: String,
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = eyebrow,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
