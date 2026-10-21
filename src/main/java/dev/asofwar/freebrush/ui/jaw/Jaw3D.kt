package dev.asofwar.freebrush.ui.jaw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.Zones
import dev.asofwar.freebrush.data.zoneSeconds
import dev.asofwar.freebrush.ui.MIN_ZONE_SEC
import dev.asofwar.freebrush.ui.ScoreAmber
import dev.asofwar.freebrush.ui.ScoreGreen
import dev.asofwar.freebrush.ui.ScoreRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.sin

private const val DEFAULT_PITCH = 0.30f

/** How strongly zone colours tint the natural enamel when no zone is selected. */
private const val ZONE_TINT = 0.62f

/** Seconds per zone the app aims for: two minutes spread over eight zones. */
private const val GOAL_ZONE_SEC = 15

/** The mesh has tens of thousands of vertices: build it once per process, off the main thread. */
private object JawCache {
    val mesh: JawMesh by lazy { JawModel.build() }
    val camera: JawCamera by lazy { JawCamera(mesh) }
}

/**
 * Interactive 3D model of both jaws, rendered on the GPU, tinted by brushing time per zone. Drag sideways to
 * rotate (with inertia), tap a tooth surface to select its zone and see its numbers, double-tap to reset.
 * Until touched, the model sways gently.
 */
@Composable
fun Jaw3D(session: BrushSession, history: List<BrushSession>, modifier: Modifier = Modifier) {
    val seconds = session.zoneSeconds()
    if (seconds.size < 8) return

    val mesh by produceState<JawMesh?>(null) {
        value = withContext(Dispatchers.Default) { JawCache.camera; JawCache.mesh }
    }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var yaw by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }
    var selected by remember(session.key) { mutableIntStateOf(-1) }
    var touched by remember { mutableStateOf(false) }
    var fling by remember { mutableStateOf<Job?>(null) }
    var view by remember { mutableStateOf<JawGlView?>(null) }
    val entrance = remember(session.key) { Animatable(0.7f) }
    val colors by rememberUpdatedState(zoneColors(seconds))

    LaunchedEffect(session.key) {
        entrance.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    // Idle sway until the first touch.
    LaunchedEffect(touched) {
        if (touched) return@LaunchedEffect
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> yaw = sin((now - start) / 1e9f * 0.6f) * 0.5f }
        }
    }
    val pulse = rememberInfiniteTransition(label = "glow")
    val glow by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "glow")

    // Push every visible change to the GL thread; nothing is drawn while the model is still.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        snapshotFlow {
            JawGlView.Frame(yaw, pitch, entrance.value, colors, selected, if (selected >= 0) glow else 0f, ZONE_TINT)
        }.collect { v.submit(it) }
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(290.dp)) {
            // Soft contact shadow under the model.
            Canvas(Modifier.matchParentSize()) {
                val c = Offset(size.width / 2, size.height * 0.9f)
                val r = size.width * 0.30f
                scale(1f, 0.16f, pivot = c) {
                    drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent), c, r), r, c)
                }
            }
            val m = mesh
            if (m == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
            } else {
                AndroidView(
                    factory = { ctx -> JawGlView(ctx, m).also { view = it } },
                    modifier = Modifier.matchParentSize(),
                )
            }
            // Gesture layer on top of the GL view.
            Box(
                Modifier
                    .matchParentSize()
                    .pointerInput(session.key) {
                        detectTapGestures(
                            onTap = { o ->
                                touched = true
                                val z = JawCache.camera.pick(o.x, o.y, size.width.toFloat(), size.height.toFloat(), yaw, pitch, entrance.value)
                                selected = if (z != null && z >= 0 && z != selected) z else -1
                                if (z != null && z >= 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDoubleTap = {
                                touched = true
                                fling?.cancel()
                                selected = -1
                                scope.launch {
                                    val y0 = yaw
                                    val p0 = pitch
                                    val target = Math.round(y0 / (2 * PI)).toFloat() * (2 * PI).toFloat()
                                    Animatable(0f).animateTo(1f, tween(500)) {
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
            )
        }
        Spacer(Modifier.height(10.dp))
        ZonePanel(selected, seconds, history)
    }
}

/** Zone colours as sRGB triples: teal brighter with more time, amber under [MIN_ZONE_SEC]. */
@Composable
private fun zoneColors(seconds: List<Int>): FloatArray {
    val max = seconds.max().coerceAtLeast(1)
    // Fixed colours rather than theme ones: they tint enamel and must read the same in light and dark mode.
    val light = Color(0xFF9FEBDD)
    val deep = Color(0xFF14B8A6)
    val missed = Color(0xFFFF8A3D)
    val out = FloatArray(24)
    for (i in 0 until 8) {
        val s = seconds[i]
        val c = if (s < MIN_ZONE_SEC) missed else lerp(light, deep, s.toFloat() / max)
        out[i * 3] = c.red; out[i * 3 + 1] = c.green; out[i * 3 + 2] = c.blue
    }
    return out
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
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF8A3D)))
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
