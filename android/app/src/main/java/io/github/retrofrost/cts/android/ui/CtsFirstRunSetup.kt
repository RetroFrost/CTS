package io.github.retrofrost.cts.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.model.VisualModel

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalAnimationApi::class)
fun CtsFirstRunSetup(
    initialModel: VisualModel,
    onComplete: (VisualModel) -> Unit,
) {
    var page by remember { mutableStateOf(0) }
    var previousPage by remember { mutableStateOf(0) }
    var model by remember { mutableStateOf(initialModel) }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "CTS",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = "Comparison Timeline Studio 2.0",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "${page + 1} / 3",
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            LinearWavyProgressIndicator(
                progress = { (page + 1) / 3f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )

            AnimatedContent(
                targetState = page,
                modifier = Modifier.weight(1f),
                transitionSpec = {
                    val forward = targetState >= previousPage
                    val enter = slideInHorizontally(
                        animationSpec = tween(360, easing = FastOutSlowInEasing),
                        initialOffsetX = { width -> if (forward) width / 3 else -width / 3 },
                    ) + fadeIn(tween(240))
                    val exit = slideOutHorizontally(
                        animationSpec = tween(240, easing = FastOutSlowInEasing),
                        targetOffsetX = { width -> if (forward) -width / 5 else width / 5 },
                    ) + fadeOut(tween(150))
                    enter togetherWith exit
                },
                label = "cts-2-setup-page",
            ) { current ->
                when (current) {
                    0 -> WelcomePage()
                    1 -> ModelPage(
                        selected = model,
                        onSelected = { model = it },
                    )
                    else -> ReadyPage(model)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (page > 0) {
                    OutlinedButton(
                        onClick = {
                            previousPage = page
                            page -= 1
                        },
                        modifier = Modifier
                            .weight(0.42f)
                            .height(58.dp),
                    ) {
                        Text("Back", fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = {
                        if (page < 2) {
                            previousPage = page
                            page += 1
                        } else {
                            onComplete(model)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp),
                ) {
                    Text(
                        if (page < 2) "Continue" else "Open CTS",
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Create the comparison.\nNot the timeline.",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Add your cards and artwork. CTS reproduces the selected reference model and handles preview, animation and export.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))
        FeatureCard(
            icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
            title = "Reference-driven video",
            body = "The model owns its layout, colors and motion. The editor never themes the video.",
        )
    }
}

@Composable
private fun ModelPage(
    selected: VisualModel,
    onSelected: (VisualModel) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Choose a reference model",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "This chooses which original comparison format CTS follows. It does not modify that model.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        VisualModel.entries.forEach { option ->
            val isSelected = option == selected
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { onSelected(option) },
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.Movie,
                            contentDescription = null,
                            modifier = Modifier.padding(13.dp).size(28.dp),
                            tint = if (isSelected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            text = "Measured Males card conveyor, attached title bands, badges, credits and ending.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = "CTS uses one frame-addressed Males reference model. Its visuals stay fixed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyPage(model: VisualModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "Ready for ${model.label}",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = "CTS keeps the reference model sealed while the app around it stays modern and device-aware.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FeatureCard(
            icon = { Icon(Icons.Filled.Movie, contentDescription = null) },
            title = "Preview = export",
            body = "Both use the same measured model timeline and renderer rules.",
        )
        FeatureCard(
            icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
            title = "Dynamic Material 3",
            body = "The CTS interface follows your system colors. The reference video never does.",
        )
        FeatureCard(
            icon = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
            title = "Background export",
            body = "Encoding reports real stages and can continue while the screen is off.",
        )
    }
}

@Composable
private fun FeatureCard(
    icon: @Composable () -> Unit,
    title: String,
    body: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Box(
                    modifier = Modifier.padding(13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
