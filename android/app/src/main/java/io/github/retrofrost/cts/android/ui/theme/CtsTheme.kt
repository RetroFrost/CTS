package io.github.retrofrost.cts.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CtsViolet = Color(0xFF9D8CFF)
val CtsVioletContainer = Color(0xFF332B61)
val CtsBlue = Color(0xFF75B8FF)

// Kept for the retired Compose monitor while it remains source-compatible.
// The active preview uses the export renderer through RenderedProgramMonitor.
val CtsPurple = CtsViolet

private val DarkColors = darkColorScheme(
    primary = CtsViolet,
    onPrimary = Color(0xFF201451),
    primaryContainer = CtsVioletContainer,
    onPrimaryContainer = Color(0xFFE8E0FF),
    secondary = CtsBlue,
    onSecondary = Color(0xFF003258),
    secondaryContainer = Color(0xFF174A70),
    onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFFFFB3C5),
    background = Color(0xFF101116),
    onBackground = Color(0xFFE7E1EA),
    surface = Color(0xFF101116),
    onSurface = Color(0xFFE7E1EA),
    surfaceVariant = Color(0xFF2B2932),
    onSurfaceVariant = Color(0xFFCBC4D0),
    surfaceContainer = Color(0xFF1B1C23),
    surfaceContainerHigh = Color(0xFF25262E),
    surfaceContainerHighest = Color(0xFF303139),
    outline = Color(0xFF948E9A),
    outlineVariant = Color(0xFF49454F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5F48C6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    onPrimaryContainer = Color(0xFF1C075F),
    secondary = Color(0xFF00629B),
    background = Color(0xFFF9F7FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF9F7FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainer = Color(0xFFF0EDF5),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

private val CtsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val CtsTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun CtsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = CtsTypography,
        shapes = CtsShapes,
        content = content,
    )
}
