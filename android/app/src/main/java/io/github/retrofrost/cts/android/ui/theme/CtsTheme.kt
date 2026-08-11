package io.github.retrofrost.cts.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun CtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
