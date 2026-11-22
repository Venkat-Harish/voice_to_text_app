package app.voice.dictate.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.voice.dictate.data.ThemeMode

/** The prototype's palette (src/styles/theme.css): paper, ink, turmeric, leaf and chilli. */
@Immutable
data class VoiceColors(
    val paper: Color,
    val paperDeep: Color,
    val card: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val line: Color,
    val turmeric: Color,
    val turmericSoft: Color,
    val leaf: Color,
    val leafSoft: Color,
    val chilli: Color,
    val chilliSoft: Color,
)

private val Light = VoiceColors(
    paper = Color(0xFFF5EFE4), paperDeep = Color(0xFFEADFCB), card = Color(0xFFFFFBF4),
    ink = Color(0xFF1E1A16), ink2 = Color(0xFF6B6157), ink3 = Color(0xFFA2978A), line = Color(0xFFE4DACB),
    turmeric = Color(0xFFD98A1F), turmericSoft = Color(0xFFF5E2C2),
    leaf = Color(0xFF3E5A3A), leafSoft = Color(0xFFDCE5D5),
    chilli = Color(0xFFB4432C), chilliSoft = Color(0xFFF4DCD4),
)

private val Dark = VoiceColors(
    paper = Color(0xFF16130F), paperDeep = Color(0xFF0E0C09), card = Color(0xFF201C17),
    ink = Color(0xFFF2EBE0), ink2 = Color(0xFFB9AE9F), ink3 = Color(0xFF807668), line = Color(0xFF342E27),
    turmeric = Color(0xFFE6A03D), turmericSoft = Color(0xFF3B2C16),
    leaf = Color(0xFF6E9A63), leafSoft = Color(0xFF1E2A1B),
    chilli = Color(0xFFE26B50), chilliSoft = Color(0xFF3A1E18),
)

val LocalVoiceColors = staticCompositionLocalOf { Light }

object Voice {
    val colors: VoiceColors @Composable get() = LocalVoiceColors.current
}

val Serif = FontFamily.Serif
val Mono = FontFamily.Monospace

private fun typography(c: VoiceColors) = Typography(
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, color = c.ink2),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, color = c.ink2),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 0.9.sp),
)

@Composable
fun VoiceTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val c = if (dark) Dark else Light
    // Turmeric is light, so primary buttons get ink text for contrast (as in the prototype).
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.turmeric, onPrimary = Color(0xFF1E1A16), primaryContainer = c.turmericSoft, onPrimaryContainer = c.ink,
            secondary = c.leaf, onSecondary = Color.White, secondaryContainer = c.leafSoft, onSecondaryContainer = c.ink,
            tertiary = c.chilli, error = c.chilli, errorContainer = c.chilliSoft,
            background = c.paper, onBackground = c.ink, surface = c.paper, onSurface = c.ink,
            surfaceVariant = c.paperDeep, onSurfaceVariant = c.ink2, surfaceContainer = c.card,
            surfaceContainerLow = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.paperDeep,
            outline = c.line, outlineVariant = c.line,
        )
    } else {
        lightColorScheme(
            primary = c.turmeric, onPrimary = Color(0xFF1E1A16), primaryContainer = c.turmericSoft, onPrimaryContainer = c.ink,
            secondary = c.leaf, onSecondary = Color.White, secondaryContainer = c.leafSoft, onSecondaryContainer = c.ink,
            tertiary = c.chilli, error = c.chilli, errorContainer = c.chilliSoft,
            background = c.paper, onBackground = c.ink, surface = c.paper, onSurface = c.ink,
            surfaceVariant = c.paperDeep, onSurfaceVariant = c.ink2, surfaceContainer = c.card,
            surfaceContainerLow = c.card, surfaceContainerHigh = c.card, surfaceContainerHighest = c.paperDeep,
            outline = c.line, outlineVariant = c.line,
        )
    }
    CompositionLocalProvider(LocalVoiceColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography(c), content = content)
    }
}
