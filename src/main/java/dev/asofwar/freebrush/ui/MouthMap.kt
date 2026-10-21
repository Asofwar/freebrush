package dev.asofwar.freebrush.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.Zones
import dev.asofwar.freebrush.data.zoneSeconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Both jaws seen from above: the upper arch opens down, the lower one opens up. Each arch has an outer band
 * (cheek side) and an inner band (tongue side), split into left and right halves, which gives the brush's
 * eight zones. Brighter means more time; zones under [MIN_ZONE_SEC] are amber. Segments sweep in one by one.
 */
@Composable
fun MouthMap(session: BrushSession, modifier: Modifier = Modifier) {
    val seconds = session.zoneSeconds()
    if (seconds.size < 8) return
    val progress = remember(session.key) { List(8) { Animatable(0f) } }
    LaunchedEffect(session.key) {
        DRAW_ORDER.forEachIndexed { step, zone ->
            launch {
                delay(90L * step)
                progress[zone].animateTo(1f, tween(650, easing = FastOutSlowInEasing))
            }
        }
    }
    val max = seconds.max().coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val bright = MaterialTheme.colorScheme.primary
    val colors = seconds.map { s ->
        if (s < MIN_ZONE_SEC) ScoreAmber else lerp(dim, bright, s.toFloat() / max)
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(210.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val rx = w * 0.36f
                val ry = h * 0.40f
                val outer = 22.dp.toPx()
                val inner = 15.dp.toPx()
                val upper = Offset(w / 2, h * 0.47f)
                val lower = Offset(w / 2, h * 0.53f)
                // Arc angles: 180..270 is the viewer's upper-left quarter, 270..360 upper-right,
                // 90..180 lower-left, 0..90 lower-right.
                for ((zone, spec) in SEGMENTS) {
                    val center = if (spec.upper) upper else lower
                    val scale = if (spec.outer) 1f else 0.64f
                    val stroke = if (spec.outer) outer else inner
                    val start = when {
                        spec.upper && spec.left -> 180f
                        spec.upper -> 270f
                        spec.left -> 90f
                        else -> 0f
                    }
                    drawBand(track, center, rx * scale, ry * scale, start, 90f, stroke)
                    // Sweep away from the midline so both halves grow from the front teeth outwards.
                    val p = progress[zone].value
                    if (p > 0f) {
                        val fromMid = (spec.upper && !spec.left) || (!spec.upper && spec.left)
                        val sweep = 90f * p
                        val s = if (fromMid) start else start + 90f - sweep
                        drawBand(colors[zone], center, rx * scale, ry * scale, s, sweep, stroke)
                    }
                }
            }
            Text("верх", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 36.dp))
            Text("низ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(top = 36.dp))
            Text("Л", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp))
            Text("П", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegendDot(bright)
            Text(" больше времени   ", style = MaterialTheme.typography.labelSmall)
            LegendDot(dim)
            Text(" меньше   ", style = MaterialTheme.typography.labelSmall)
            LegendDot(ScoreAmber)
            Text(" меньше $MIN_ZONE_SEC с", style = MaterialTheme.typography.labelSmall)
        }
        val weakest = seconds.indices.minBy { seconds[it] }
        Spacer(Modifier.height(6.dp))
        Text(
            "Меньше всего: ${Zones.ALL[weakest].lowercase()} — ${seconds[weakest]} с",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

private fun DrawScope.drawBand(color: Color, center: Offset, rx: Float, ry: Float, start: Float, sweep: Float, stroke: Float) {
    // A small gap between neighbouring segments.
    val gap = 2.5f
    if (sweep <= gap) return
    drawArc(
        color = color,
        startAngle = start + gap / 2,
        sweepAngle = sweep - gap,
        useCenter = false,
        topLeft = Offset(center.x - rx, center.y - ry),
        size = Size(rx * 2, ry * 2),
        style = Stroke(stroke, cap = StrokeCap.Butt),
    )
}

private class Segment(val upper: Boolean, val left: Boolean, val outer: Boolean)

/** Zone index to its place, following [Zones.ALL]. */
private val SEGMENTS = listOf(
    0 to Segment(upper = true, left = true, outer = true),
    1 to Segment(upper = true, left = true, outer = false),
    2 to Segment(upper = false, left = true, outer = true),
    3 to Segment(upper = false, left = true, outer = false),
    4 to Segment(upper = true, left = false, outer = true),
    5 to Segment(upper = true, left = false, outer = false),
    6 to Segment(upper = false, left = false, outer = true),
    7 to Segment(upper = false, left = false, outer = false),
)

/** Order in which segments appear: outer bands first, clockwise from the upper left. */
private val DRAW_ORDER = listOf(0, 4, 6, 2, 1, 5, 7, 3)
