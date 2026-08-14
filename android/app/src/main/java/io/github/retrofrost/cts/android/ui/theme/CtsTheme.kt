package io.github.retrofrost.cts.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

val CtsPurple = Color(0xFF6750A4)
val CtsPurpleSoft = Color(0xFFD0BCFF)
val CtsCanvas = Color(0xFFF7F7F7)
val CtsPanel = Color.White
val CtsPanelRaised = Color(0xFFEDEDED)
val CtsLine = Color(0xFF79747E)

private val DarkColors = darkColorScheme(
    primary = CtsPurpleSoft,
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    background = Color(0xFF121212),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2B2930),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    background = CtsCanvas,
    onBackground = Color(0xFF1C1B1F),
    surface = CtsPanel,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = CtsPanelRaised,
    onSurfaceVariant = Color(0xFF49454F),
    outline = CtsLine,
)

/**
 * CTS intentionally uses the compact, restrained visual language of classic Material:
 * small corner radii, fixed colors, normal progress indicators and no dynamic/expressive
 * Material You treatment. Material 3 remains underneath only for the current Compose
 * component implementation, while the component metrics are kept close to Material 2.
 */
@Composable
fun CtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(2.dp),
        medium = RoundedCornerShape(4.dp),
        large = RoundedCornerShape(4.dp),
        extraLarge = RoundedCornerShape(4.dp),
    )
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        content = content,
    )
}
