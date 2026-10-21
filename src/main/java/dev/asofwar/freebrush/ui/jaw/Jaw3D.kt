package dev.asofwar.freebrush.ui.jaw

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.Zones
import dev.asofwar.freebrush.data.zoneSeconds
import dev.asofwar.freebrush.ui.MIN_ZONE_SEC
import dev.asofwar.freebrush.ui.ScoreAmber
import dev.asofwar.freebrush.ui.ScoreGreen
import dev.asofwar.freebrush.ui.ScoreRed
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

private const val DEFAULT_PITCH = 0.22f

/** "Л" beside the patient's left molars (+x), "П" beside the right ones. */
private val SIDE_LABELS = listOf("Л" to 3.75f, "П" to -3.75f)

/** Seconds per zone the app aims for: two minutes spread over eight zones. */
private const val GOAL_ZONE_SEC = 15

/**
 * Interactive 3D model of both jaws coloured by brushing time per zone. Drag to rotate (with inertia),
 * tap a tooth surface to select its zone and see its numbers, double-tap to reset the view.
 * Until touched, the model sways gently.
 */
@Composable
fun Jaw3D(session: BrushSession, history: List<BrushSession>, modifier: Modifier = Modifier) {
    val seconds = session.zoneSeconds()
    if (seconds.size < 8) return

    val mesh = remember { JawModel.build() }
    val renderer = remember(mesh) { JawRenderer(mesh) }
    val paint = remember { Paint().apply { isAntiAlias = true } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val labelPaint = remember(labelColor) {
        Paint().apply {
            isAntiAlias = true
            color = labelColor
            textAlign = Paint.Align.CENTER
            textSize = with(density) { 15.dp.toPx() }
            isFakeBoldText = true
        }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }
    var selected by remember(session.key) { mutableIntStateOf(-1) }
    var touched by remember { mutableStateOf(false) }
    var fling by remember { mutableStateOf<Job?>(null) }
    val entrance = remember(session.key) { Animatable(0.6f) }

    LaunchedEffect(session.key) {
        entrance.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // Idle sway until the first touch.
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val t = (now - start) / 1e9f
                yaw = sin(t * 0.7f) * 0.55f
            }
        }
    }
    val pulse = rememberInfiniteTransition(label = "glow")
    val glow by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "glow")

    val zoneColors = zoneColors(seconds)
    val gum = lerp(Color(0xFFE7A1A6), MaterialTheme.colorScheme.surface, 0.12f).toArgb()

    Column(modifier) {
        Box {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .clipToBounds()
                    .pointerInput(session.key) {
                        detectTapGestures(
                            onTap = { o ->
                                touched = true
                                val z = renderer.hitTest(o.x, o.y)
                                selected = if (z >= 0 && z != selected) z else -1
                                if (z >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDoubleTap = {
                                touched = true
                                fling?.cancel()
                                selected = -1
                                scope.launch {
                                    val y0 = yaw
                                    val p0 = pitch
                                    val target = Math.round(y0 / (2 * PI)).toFloat() * (2 * PI).toFloat()
                                    Animatable(0f).animateTo(1f, tween(450)) {
                                        yaw = y0 + (target - y0) * value
                                        pitch = p0 + (DEFAULT_PITCH - p0) * value
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Rotation starts only from a sideways drag, so vertical swipes still scroll the page.
                        // Once rotating, vertical movement tilts the model too.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            touched = true
                            fling?.cancel()
                            val tracker = VelocityTracker()
                            tracker.addPosition(start.uptimeMillis, start.position)
                            drag(start.id) { change ->
                                val d = change.positionChange()
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                yaw += d.x * 0.011f
                                pitch = (pitch + d.y * 0.0045f).coerceIn(-0.9f, 1.1f)
                            }
                            val v = tracker.calculateVelocity()
                            fling = scope.launch {
                                animateDecay(yaw, v.x * 0.011f, FloatExponentialDecaySpec(frictionMultiplier = 1.6f)) { value, _ ->
                                    yaw = value
                                }
                            }
                        }
                    },
            ) {
                renderer.render(
                    width = size.width,
                    height = size.height,
                    yaw = yaw,
                    pitch = pitch,
                    scale = entrance.value,
                    zoneColors = zoneColors,
                    gumColor = gum,
                    selected = selected,
                    glow = if (selected >= 0) glow else 0f,
                )
                drawIntoCanvas { c ->
                    c.nativeCanvas.drawVertices(
                        AndroidCanvas.VertexMode.TRIANGLES,
                        renderer.triangleCount * 6,
                        renderer.verts, 0,
                        null, 0,
                        renderer.colors, 0,
                        null, 0, 0,
                        paint,
                    )
                    // Side labels follow the model: the patient's left is +x.
                    for ((label, x) in SIDE_LABELS) {
                        val (px, py, depth) = renderer.project(x, 0f, 0.4f)
                        labelPaint.alpha = (255 * (0.35f + 0.65f * depth)).toInt().coerceIn(0, 255)
                        c.nativeCanvas.drawText(label, px, py + labelPaint.textSize / 3, labelPaint)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ZonePanel(selected, seconds, history)
    }
}

/** Zone colours: teal from dim to bright by time, amber when under [MIN_ZONE_SEC]; mixed into enamel white. */
@Composable
private fun zoneColors(seconds: List<Int>): IntArray {
    val max = seconds.max().coerceAtLeast(1)
    val bright = MaterialTheme.colorScheme.primary
    val enamel = Color(0xFFF3F0E8)
    return IntArray(8) { i ->
        val s = seconds[i]
        val tint = if (s < MIN_ZONE_SEC) ScoreAmber else lerp(enamel, bright, 0.35f + 0.65f * s / max)
        lerp(enamel, tint, 0.85f).toArgb()
    }
}

@Composable
private fun ZonePanel(selected: Int, seconds: List<Int>, history: List<BrushSession>) {
    AnimatedContent(
        targetState = selected,
        transitionSpec = { (fadeIn(tween(220)) + slideInVertically { it / 4 }) togetherWith fadeOut(tween(120)) },
        label = "zone",
    ) { zone ->
        if (zone < 0) {
            val weakest = seconds.indices.minBy { seconds[it] }
            Column {
                Text(
                    "Крути модель пальцем, нажми на зуб — покажу его зону. Двойное нажатие вернёт вид.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(ScoreAmber))
                    Text(
                        "  меньше $MIN_ZONE_SEC с · меньше всего: ${Zones.ALL[weakest].lowercase()}, ${seconds[weakest]} с",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        } else {
            ZoneStats(zone, seconds, history)
        }
    }
}

@Composable
private fun ZoneStats(zone: Int, seconds: List<Int>, history: List<BrushSession>) {
    val s = seconds[zone]
    val total = seconds.sum().coerceAtLeast(1)
    val since = LocalDate.now().minusDays(6)
    val week = history.filter { !it.time.toLocalDate().isBefore(since) }.map { it.zoneSeconds() }.filter { it.size == 8 }
    val weekAvg = week.takeIf { it.isNotEmpty() }?.map { it[zone] }?.average()?.toInt()
    val (status, color) = when {
        s >= GOAL_ZONE_SEC -> "хорошо прочищена" to ScoreGreen
        s >= MIN_ZONE_SEC -> "маловато" to ScoreAmber
        else -> "почти пропущена" to ScoreRed
    }
    Surface(shape = MaterialTheme.shapes.medium, color = color.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(Zones.ALL[zone], style = MaterialTheme.typography.titleMedium)
            Text(status, style = MaterialTheme.typography.labelLarge, color = color)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("$s с", "в этой чистке")
                Metric("${s * 100 / total} %", "от всего времени")
                Metric(weekAvg?.let { "$it с" } ?: "—", "в среднем за неделю")
            }
            Spacer(Modifier.height(10.dp))
            val hint = when {
                s >= GOAL_ZONE_SEC -> "Цель — $GOAL_ZONE_SEC с на зону, здесь она выполнена."
                else -> "Добавь ещё ${GOAL_ZONE_SEC - s} с: цель — $GOAL_ZONE_SEC с на каждую из 8 зон, это 2 минуты на всю чистку."
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (weekAvg != null && week.size > 1) {
                val trend = s - weekAvg
                if (trend != 0) {
                    Text(
                        if (trend > 0) "На $trend с дольше, чем обычно" else "На ${-trend} с меньше, чем обычно",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (trend > 0) ScoreGreen else ScoreAmber,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, caption: String) {
    Column(Modifier.width(92.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
