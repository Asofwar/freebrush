package dev.asofwar.freebrush.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Brand palette: sea-glass teal with a cool blue accent. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF00796F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF2E4),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF4A635F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E2),
    onSecondaryContainer = Color(0xFF05201C),
    tertiary = Color(0xFF3B6489),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCFE5FF),
    onTertiaryContainer = Color(0xFF001D33),
    background = Color(0xFFF4FBF9),
    onBackground = Color(0xFF161D1C),
    surface = Color(0xFFF4FBF9),
    onSurface = Color(0xFF161D1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF5F3),
    surfaceContainer = Color(0xFFE8EFED),
    surfaceContainerHigh = Color(0xFFE3EAE8),
    surfaceContainerHighest = Color(0xFFDDE4E2),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A),
)

/** Dark palette from the FreeBrush design spec (slices/spec.md): near-black teal ground, mint accents. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FE0CB),
    onPrimary = Color(0xFF08201B),
    primaryContainer = Color(0xFF117D70),
    onPrimaryContainer = Color(0xFFF1FAF7),
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    // Selected chips.
    secondaryContainer = Color(0xFF117D70),
    onSecondaryContainer = Color(0xFFF1FAF7),
    tertiary = Color(0xFFA4CBF5),
    onTertiary = Color(0xFF02334F),
    tertiaryContainer = Color(0xFF214A6F),
    onTertiaryContainer = Color(0xFFCFE5FF),
    background = Color(0xFF0F1514),
    onBackground = Color(0xFFE7EEEC),
    surface = Color(0xFF0F1514),
    onSurface = Color(0xFFE7EEEC),
    surfaceVariant = Color(0xFF2C4240),
    onSurfaceVariant = Color(0xFFA9BFBE),
    surfaceContainerLowest = Color(0xFF0A100F),
    // Cards.
    surfaceContainerLow = Color(0xFF1A2120),
    // Bottom bar.
    surfaceContainer = Color(0xFF121918),
    surfaceContainerHigh = Color(0xFF1F2827),
    surfaceContainerHighest = Color(0xFF2A3432),
    // Unselected chip outline; card borders and dividers.
    outline = Color(0xFF5B7D7B),
    outlineVariant = Color(0xFF293735),
    error = Color(0xFFFF8171),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun FreeBrushTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}

val ScoreRed = Color(0xFFE5574F)
val ScoreAmber = Color(0xFFF0A93B)
val ScoreGreen = Color(0xFF2FB37C)

/**
 * A status colour dark enough to read as text on the light theme's surfaces (4.5:1 or better); unchanged in the
 * dark theme, where the bright tones already read well.
 */
@Composable
fun readable(c: Color): Color = if (isSystemInDarkTheme()) c else lerp(c, Color.Black, 0.45f)

/** Score colour: red below 60, amber below 80, green from 80. */
fun scoreColor(score: Int?): Color = when {
    score == null -> Color(0xFF8A9593)
    score < 60 -> ScoreRed
    score < 80 -> ScoreAmber
    else -> ScoreGreen
}

/** One word for the score, shown under the number. */
fun scoreWord(score: Int?): String = when {
    score == null -> "нет оценки"
    score < 40 -> "слабо"
    score < 60 -> "можно лучше"
    score < 80 -> "неплохо"
    score < 90 -> "хорошо"
    else -> "отлично"
}
