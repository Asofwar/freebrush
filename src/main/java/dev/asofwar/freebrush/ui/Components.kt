package dev.asofwar.freebrush.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.data.BrushInfo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Rounded surface card used for every section. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 14.dp))
            }
            content()
        }
    }
}

/** Screen title with an optional subtitle, scrolled with the content. */
@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.headlineLarge)
    }
}

/** Sync status pill: spinner while running, a check or warning that pops in when done. */
@Composable
fun SyncChip(sync: SyncState, brush: BrushInfo, onRetry: () -> Unit) {
    AnimatedContent(
        targetState = sync,
        contentKey = { it::class },
        transitionSpec = { (fadeIn(tween(250)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(150)) },
        label = "sync",
    ) { state ->
        val (icon, text, tint) = when (state) {
            is SyncState.Running -> Triple(null, state.step, MaterialTheme.colorScheme.primary)
            is SyncState.Done -> Triple(
                Icons.Check,
                if (state.added > 0) "Новых чисток: ${state.added}" else "Всё синхронизировано",
                ScoreGreen,
            )
            is SyncState.Failed -> Triple(Icons.Warning, state.message, MaterialTheme.colorScheme.error)
            SyncState.Idle -> Triple(
                Icons.Clock,
                brush.lastSyncMillis?.let { "Синхронизация: " + relativeTime(Instant.ofEpochMilli(it)) } ?: "Ещё не синхронизировано",
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.12f),
            modifier = Modifier.clickable(enabled = state is SyncState.Failed || state is SyncState.Idle, onClick = onRetry),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon == null) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
                } else {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Small tile: icon in a tinted circle, a big value and a caption. */
@Composable
fun StatTile(icon: ImageVector, value: String, caption: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.height(10.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

/** Clickable list row with a leading icon. */
@Composable
fun ActionRow(icon: ImageVector, text: String, tint: Color = MaterialTheme.colorScheme.onSurface, iconRotation: Float = 0f, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp).rotate(iconRotation))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

/** Rotation that spins while [active], for the sync icon. */
@Composable
fun spinningRotation(active: Boolean): Float {
    if (!active) return 0f
    val t = rememberInfiniteTransition(label = "spin")
    val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    return r
}

/** Horizontal gauge that fills to [fraction] with a spring, used for battery and brush-head wear. */
@Composable
fun Gauge(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val fill by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "gauge")
    Box(
        modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(6.dp)).background(color))
    }
}

/** Gentle pulse between 0.6 and 1 for things that need attention. */
@Composable
fun pulseAlpha(): Float {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    return a
}

fun batteryColor(level: Int?): Color = when {
    level == null -> Color(0xFF8A9593)
    level < 20 -> ScoreRed
    level < 40 -> ScoreAmber
    else -> ScoreGreen
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ru"))

/** "сегодня, 12:13", "вчера, 21:46" or "20 окт., 21:46". */
fun relativeTime(t: LocalDateTime): String {
    val day = t.toLocalDate()
    val today = LocalDate.now()
    val prefix = when (day) {
        today -> "сегодня"
        today.minusDays(1) -> "вчера"
        else -> day.format(dateFormat)
    }
    return "$prefix, ${t.format(timeFormat)}"
}

fun relativeTime(i: Instant): String = relativeTime(LocalDateTime.ofInstant(i, ZoneId.systemDefault()))

/** "Сегодня", "Вчера" or "вторник, 20 октября". */
fun dayTitle(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> day.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru")))
            .replaceFirstChar { it.uppercase() }
    }
}

fun greeting(): String = when (LocalDateTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}
