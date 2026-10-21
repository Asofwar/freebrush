package dev.asofwar.freebrush.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.Zones
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Score as a ring with the number in the middle. */
@Composable
fun ScoreRing(score: Int?, size: Dp = 120.dp) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val color = scoreColor(score)
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = Stroke(width = this.size.minDimension * 0.09f, cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(this.size.width - stroke.width, this.size.height - stroke.width)
            drawArc(track, 135f, 270f, false, Offset(inset, inset), arcSize, style = stroke)
            if (score != null) {
                drawArc(color, 135f, 270f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arcSize, style = stroke)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "—", fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.Bold)
            Text("баллов", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Per-zone values as labelled bars, scaled to the largest zone. */
@Composable
fun ZoneBars(zones: List<Int>, unit: String) {
    if (zones.isEmpty()) {
        Text("Щётка не передала данные по зонам", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = zones.maxOrNull()?.takeIf { it > 0 } ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        zones.forEachIndexed { i, v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Zones.ALL.getOrElse(i) { "Зона ${i + 1}" },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(150.dp),
                )
                LinearProgressIndicator(
                    progress = { v.toFloat() / max },
                    modifier = Modifier.weight(1f).height(8.dp),
                    drawStopIndicator = {},
                )
                Text("$v$unit", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp).width(44.dp))
            }
        }
    }
}

/** Bars for the last [days] days: height = sessions that day (goal 2), colour = average score. */
@Composable
fun DaysChart(sessions: List<BrushSession>, days: Int = 14) {
    val today = LocalDate.now()
    val byDay = sessions.groupBy { it.time.toLocalDate() }
    val range = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val goal = MaterialTheme.colorScheme.outline
    Column {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val slot = size.width / days
            val barW = slot * 0.6f
            val maxCount = maxOf(3, range.maxOf { byDay[it]?.size ?: 0 })
            val unit = size.height / maxCount
            // Dashed-looking goal line at two sessions a day.
            val goalY = size.height - unit * 2
            var x = 0f
            while (x < size.width) {
                drawLine(goal, Offset(x, goalY), Offset(x + 6f, goalY), strokeWidth = 2f)
                x += 12f
            }
            range.forEachIndexed { i, day ->
                val list = byDay[day].orEmpty()
                val left = i * slot + (slot - barW) / 2
                if (list.isEmpty()) {
                    drawRoundRect(empty, Offset(left, size.height - 6f), Size(barW, 6f), CornerRadius(3f))
                } else {
                    val avg = list.mapNotNull { it.score }.takeIf { it.isNotEmpty() }?.average()?.toInt()
                    val h = unit * list.size
                    drawRoundRect(scoreColor(avg), Offset(left, size.height - h), Size(barW, h), CornerRadius(6f))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            range.forEach { day ->
                Text(
                    if (day == today) "сег" else day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
