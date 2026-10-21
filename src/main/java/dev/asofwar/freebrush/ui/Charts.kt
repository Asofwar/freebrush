package dev.asofwar.freebrush.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asofwar.freebrush.ble.OcleanProtocol
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.Zones
import dev.asofwar.freebrush.data.zoneSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Score as a 270° ring that sweeps from zero and a number that counts up with it.
 * The arc is a gradient that ends in the score's colour.
 */
@Composable
fun ScoreRing(score: Int?, size: Dp = 168.dp, showWord: Boolean = true) {
    val target = (score ?: 0).coerceIn(0, 100)
    val sweep by animateFloatAsState(
        targetValue = target / 100f,
        animationSpec = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val shown by animateIntAsState(target, tween(durationMillis = 1100, easing = FastOutSlowInEasing), label = "score")
    val color = scoreColor(score)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.085f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (score != null && sweep > 0f) {
                // Rotate so the sweep gradient starts where the arc starts (135°).
                rotate(135f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to color.copy(alpha = 0.35f),
                            (0.75f * sweep).coerceAtLeast(0.01f) to color,
                            // The round start cap wraps to the end of the gradient: keep it faded too.
                            1f to color.copy(alpha = 0.35f),
                        ),
                        startAngle = 0f,
                        sweepAngle = 270f * sweep,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (score == null) "—" else shown.toString(),
                fontSize = (size.value * 0.30f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (showWord) {
                Text(scoreWord(score), style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

/** Small round score badge for lists. */
@Composable
fun ScoreBadge(score: Int?) {
    val color = scoreColor(score)
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(score?.toString() ?: "—", color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/**
 * Bars for the last [days] days: height = sessions that day, colour = that day's average score.
 * Bars grow in one after another; a dashed line marks the goal of two sessions a day.
 */
@Composable
fun DaysChart(sessions: List<BrushSession>, days: Int = 14) {
    val today = LocalDate.now()
    val byDay = remember(sessions) { sessions.groupBy { it.time.toLocalDate() } }
    val range = remember(today) { (days - 1 downTo 0).map { today.minusDays(it.toLong()) } }
    val grow = remember(sessions) { List(days) { Animatable(0f) } }
    LaunchedEffect(sessions) {
        grow.forEachIndexed { i, a ->
            launch {
                delay(40L * i)
                a.animateTo(1f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow))
            }
        }
    }
    val empty = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val goal = MaterialTheme.colorScheme.outline
    Column {
        Canvas(Modifier.fillMaxWidth().height(132.dp)) {
            val slot = size.width / days
            val barW = slot * 0.56f
            val maxCount = maxOf(3, range.maxOf { byDay[it]?.size ?: 0 })
            val unit = size.height / maxCount
            val goalY = size.height - unit * 2
            var x = 0f
            while (x < size.width) {
                drawLine(goal, Offset(x, goalY), Offset(x + 8f, goalY), strokeWidth = 2f)
                x += 16f
            }
            range.forEachIndexed { i, day ->
                val list = byDay[day].orEmpty()
                val left = i * slot + (slot - barW) / 2
                if (list.isEmpty()) {
                    drawRoundRect(empty, Offset(left, size.height - 8f), Size(barW, 8f), CornerRadius(4f))
                } else {
                    val avg = list.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                    val h = unit * list.size * grow[i].value
                    val c = scoreColor(avg)
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(c, c.copy(alpha = 0.55f)), startY = size.height - h, endY = size.height),
                        topLeft = Offset(left, size.height - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(barW / 2.4f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            range.forEach { day ->
                Text(
                    if (day == today) "сег" else day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Per-zone brushing time in seconds with animated bars; zones below [MIN_ZONE_SEC] are flagged. */
@Composable
fun ZoneList(session: BrushSession) {
    val seconds = session.zoneSeconds()
    if (seconds.isEmpty()) {
        Text("Щётка не передала данные по зонам", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = seconds.maxOrNull()?.takeIf { it > 0 } ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        seconds.forEachIndexed { i, v ->
            val fill by animateFloatAsState(v.toFloat() / max, tween(700, delayMillis = 60 * i), label = "zone$i")
            val low = v < MIN_ZONE_SEC
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Zones.ALL.getOrElse(i) { "Зона ${i + 1}" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(148.dp),
                )
                Box(
                    Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(fill).clip(RoundedCornerShape(5.dp))
                            .background(if (low) ScoreAmber else MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    "$v с",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (low) ScoreAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
            }
        }
    }
}

/** A zone brushed for less than this is treated as missed, like the official app does. */
const val MIN_ZONE_SEC = 9

/** Five pressure buckets, light to heavy, as one stacked bar. */
@Composable
fun PressureBar(pressure: List<Int>) {
    // Sessions stored before the placeholder was recognised still carry it.
    if (pressure.isEmpty() || pressure == OcleanProtocol.PLACEHOLDER_PRESSURE) return
    val colors = listOf(Color(0xFF9CC9E8), Color(0xFF5FA8D3), ScoreGreen, ScoreAmber, ScoreRed)
    val total = pressure.sum().takeIf { it > 0 } ?: return
    Column {
        Text("Нажим: от слабого к сильному", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).height(12.dp).clip(RoundedCornerShape(6.dp))) {
            pressure.take(5).forEachIndexed { i, v ->
                if (v > 0) Box(Modifier.weight(v.toFloat()).fillMaxHeight().background(colors[i]))
            }
        }
        val heavy = pressure.drop(3).sum() * 100 / total
        if (heavy >= 20) {
            Text("Сильный нажим $heavy % времени", style = MaterialTheme.typography.bodySmall, color = ScoreRed)
        }
    }
}

/**
 * Preset names reported by the community for the Oclean X family; X Pro Digital presets are not confirmed yet.
 * 230 and above are custom programmes.
 */
fun schemeName(id: Int): String = when (id) {
    0 -> "Стандартная чистка"
    72 -> "Средне-сильная чистка"
    73 -> "Сильная чистка"
    74 -> "Чувствительные после чистки"
    75 -> "Ежедневное отбеливание"
    76 -> "Среднее отбеливание"
    77 -> "Сильное отбеливание"
    78 -> "Чувствительная чистка"
    79 -> "Мягкий SPA"
    80 -> "Стандартный SPA"
    81 -> "Глубокий SPA"
    82 -> "После сладкого"
    83 -> "Свежесть после еды"
    84 -> "Массаж дёсен"
    85 -> "Уход за дёснами"
    86 -> "Отбеливание для начинающих"
    87 -> "Чистка брекетов"
    88 -> "Быстрая чистка"
    89 -> "В поездке"
    else -> if (id >= 230) "Своя программа №$id" else "Режим №$id"
}
