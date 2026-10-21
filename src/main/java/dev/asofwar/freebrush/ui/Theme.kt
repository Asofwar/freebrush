package dev.asofwar.freebrush.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Material You colours from the wallpaper (minSdk 31 always has them). */
@Composable
fun FreeBrushTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, content = content)
}

/** Score colour: red below 60, amber below 80, green above. */
fun scoreColor(score: Int?): Color = when {
    score == null -> Color.Gray
    score < 60 -> Color(0xFFD9534F)
    score < 80 -> Color(0xFFE0A030)
    else -> Color(0xFF2E9E6A)
}
