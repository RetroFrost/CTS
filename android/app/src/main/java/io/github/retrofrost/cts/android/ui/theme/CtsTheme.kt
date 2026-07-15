package io.github.retrofrost.cts.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CtsPurple = Color(0xFF7D67EE)
val CtsPurpleSoft = Color(0xFFB9ACFF)
val CtsCanvas = Color(0xFF090B0F)
val CtsPanel = Color(0xFF11151C)
val CtsPanelRaised = Color(0xFF181D27)
val CtsLine = Color(0xFF2A3140)

private val DarkColors = darkColorScheme(
    primary = CtsPurpleSoft,
    onPrimary = Color(0xFF21165E),
    primaryContainer = Color(0xFF3C2D8C),
    onPrimaryContainer = Color(0xFFE8E2FF),
    secondary = Color(0xFFB9C4D8),
    background = CtsCanvas,
    onBackground = Color(0xFFF2F4F8),
    surface = CtsPanel,
    onSurface = Color(0xFFF2F4F8),
    surfaceVariant = CtsPanelRaised,
    onSurfaceVariant = Color(0xFFC1C8D4),
    outline = CtsLine,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5C45C7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7E0FF),
    onPrimaryContainer = Color(0xFF1B085E),
    secondary = Color(0xFF596274),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF16181D),
    surface = Color.White,
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE7E9F0),
    onSurfaceVariant = Color(0xFF444A55),
    outline = Color(0xFFC4C8D2),
)

@Composable
fun CtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
