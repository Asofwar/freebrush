package dev.asofwar.freebrush.ui.jaw

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import dev.asofwar.freebrush.jaw.BakedJaw
import dev.asofwar.freebrush.jaw.JawCamera
import dev.asofwar.freebrush.jaw.JawCodec
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

private const val DEFAULT_PITCH = 0.34f

/** Seconds per zone the app aims for: two minutes spread over eight zones. */
private const val GOAL_ZONE_SEC = 15

/** Where the model comes to rest after its entrance turn. */
private const val REST_YAW = -0.5f

/** Light-ring colours: fixed, not themed, so the data reads the same in light and dark mode. */
private val RING_FRESH = Color(0xFF34D399)
private val RING_DEEP = Color(0xFF2DD4BF)
private val RING_MISSED = Color(0xFFFF9F0A)

/**
 * Camera angles (yaw, pitch) that present each zone to the viewer. Outer zones are seen from the front-side;
 * inner ones from behind, through the open back of the arches. Right-side zones mirror the left ones.
 */
private val ZONE_VIEWS = arrayOf(
    -0.80f to 0.10f, 2.60f to 0.02f, -0.80f to 0.34f, 2.60f to 0.24f,
    0.80f to 0.10f, -2.60f to 0.02f, 0.80f to 0.34f, -2.60f to 0.24f,
)

/**
 * The jaw is generated on the build machine and shipped baked in the APK (assets/jaw.bin): the phone only reads
 * it, once per process, off the main thread.
 */
private object JawCache {
    @Volatile
    private var loaded: Pair<BakedJaw, JawCamera>? = null

    fun get(context: Context): Pair<BakedJaw, JawCamera> = loaded ?: synchronized(this) {
        loaded ?: run {
            val start = System.nanoTime()
            val jaw = JawCodec.decode(context.assets.open("jaw.bin").use { it.readBytes() })
            Log.d("JawGl", "baked jaw: ${jaw.vertexCount} vertices loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
            (jaw to JawCamera(jaw)).also { loaded = it }
        }
    }
}

/**
 * A porcelain dentition on a glossy stage, rendered on the GPU. Brushing time per zone is shown as light: a
 * glowing line under every zone, mint when brushed enough, amber when missed. Drag sideways to turn it (with
 * inertia); tap a tooth or a light line and the model turns to present that zone; double-tap to reset.
 *
 * Frames are drawn only while something moves: the model makes one slow entrance turn and then rests, every
 * animation is finite, and nothing is sent to the GPU while the model is scrolled out of view.
 */
/** What the model shows, in words: how many zones got enough time. */
private fun jawDescription(s: BrushSession): String {
    val zones = s.zones
    if (zones.isEmpty()) return "3D-модель зубов: по этой чистке нет данных о зонах"
    val brushed = zones.count { it >= 9 }
    return "3D-модель зубов: прочищено $brushed из ${zones.size} зон, остальные меньше 9 секунд"
}

@Composable
fun Jaw3D(session: BrushSession, history: List<BrushSession>, modifier: Modifier = Modifier) {
    val seconds = session.zoneSeconds()
    if (seconds.size < 8) return

    val context = LocalContext.current.applicationContext
    val baked by produceState<Pair<BakedJaw, JawCamera>?>(null) {
        value = withContext(Dispatchers.IO) { JawCache.get(context) }
    }
    val camera = baked?.second
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val dark = isSystemInDarkTheme()

    var yaw by remember { mutableFloatStateOf(REST_YAW - 0.9f) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }
    var selected by remember(session.key) { mutableIntStateOf(-1) }
    var touched by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(true) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var view by remember { mutableStateOf<JawGlView?>(null) }
    val entrance = remember(session.key) { Animatable(0.82f) }
    val rings by rememberUpdatedState(ringData(seconds))

    // Entrance: the model grows in and makes one slow, settling turn, then rests.
    LaunchedEffect(session.key) {
        launch { entrance.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessVeryLow)) }
        if (!touched) {
            val from = yaw
            Animatable(0f).animateTo(1f, tween(2600, easing = FastOutSlowInEasing)) {
                if (!touched) yaw = from + (REST_YAW - from) * value
            }
        }
    }
    // A selected zone lights up once and stays lit; no endless pulsing.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected < 0) return@LaunchedEffect
        glow.snapTo(0f)
        glow.animateTo(1f, tween(320))
        glow.animateTo(0.55f, tween(900))
    }

    // Push changes to the GL thread only while the model is on screen; nothing is drawn while it is still.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        snapshotFlow {
            if (!visible) null
            else JawGlView.Frame(yaw, pitch, entrance.value, rings, selected, if (selected >= 0) glow.value else 0f)
        }.collect { frame -> frame?.let(v::submit) }
    }

    /** Turns the model to [targetYaw]/[targetPitch] along the shortest way round, then runs [then]. */
    fun turnTo(targetYaw: Float, targetPitch: Float, then: () -> Unit = {}) {
        motion?.cancel()
        motion = scope.launch {
            val turn = (2 * PI).toFloat()
            var dy = (targetYaw - yaw) % turn
            if (dy > PI) dy -= turn
            if (dy < -PI) dy += turn
            val y0 = yaw
            val p0 = pitch
            Animatable(0f).animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 60f)) {
                yaw = y0 + dy * value
                pitch = p0 + (targetPitch - p0) * value
            }
            then()
        }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .height(300.dp)
                // Bounds clipped to the window are empty once the stage scrolls out of view.
                .onGloballyPositioned { visible = !it.boundsInWindow().isEmpty }
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.radialGradient(
                        if (dark) listOf(Color(0xFF2E3837), Color(0xFF0A0F0E)) else listOf(Color.White, Color(0xFFDDE5E3)),
                        center = Offset.Unspecified,
                        radius = 900f,
                    ),
                ),
        ) {
            val b = baked
            if (b == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
            } else {
                AndroidView(
                    factory = { ctx -> JawGlView(ctx, b.first, b.second).also { view = it } },
                    modifier = Modifier.matchParentSize(),
                )
            }
            // Gesture layer on top of the GL view; TalkBack reads a summary instead of the picture.
            Box(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = jawDescription(session) }
                    .pointerInput(session.key, camera) {
                        detectTapGestures(
                            onTap = { o ->
                                touched = true
                                val z = camera?.pick(o.x, o.y, size.width.toFloat(), size.height.toFloat(), yaw, pitch, entrance.value)
                                if (z != null && z >= 0 && z != selected) {
                                    selected = z
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    val (vy, vp) = ZONE_VIEWS[z]
                                    turnTo(vy, vp)
                                } else {
                                    selected = -1
                                }
                            },
                            onDoubleTap = {
                                selected = -1
                                touched = true
                                turnTo(REST_YAW, DEFAULT_PITCH)
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        // Turning starts only from a sideways drag, so vertical swipes still scroll the page.
                        // Once turning, vertical movement changes the viewing height too.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            touched = true
                            motion?.cancel()
                            val tracker = VelocityTracker()
                            tracker.addPosition(start.uptimeMillis, start.position)
                            drag(start.id) { change ->
                                val d = change.positionChange()
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                yaw += d.x * 0.010f
                                pitch = (pitch + d.y * 0.004f).coerceIn(0f, 0.95f)
                            }
                            val v = tracker.calculateVelocity()
                            motion = scope.launch {
                                animateDecay(yaw, v.x * 0.010f, FloatExponentialDecaySpec(frictionMultiplier = 1.4f)) { value, _ ->
                                    yaw = value
                                }
                            }
                        }
                    },
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { ZonePanel(selected, seconds, history) }
    }
}

/** Per zone: ring colour (sRGB) and brightness, from the seconds brushed. */
private fun ringData(seconds: List<Int>): FloatArray {
    val out = FloatArray(32)
    for (i in 0 until 8) {
        val s = seconds[i]
        val t = (s.toFloat() / GOAL_ZONE_SEC).coerceIn(0f, 1f)
        val c = if (s < MIN_ZONE_SEC) RING_MISSED else lerp(RING_FRESH, RING_DEEP, t)
        out[i * 4] = c.red; out[i * 4 + 1] = c.green; out[i * 4 + 2] = c.blue
        out[i * 4 + 3] = if (s < MIN_ZONE_SEC) 0.85f else 0.55f + 0.45f * t
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 18.dp, height = 4.dp).clip(CircleShape).background(RING_DEEP))
                    Text("  прочищено", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.size(width = 18.dp, height = 4.dp).clip(CircleShape).background(RING_MISSED))
                    Text("  меньше $MIN_ZONE_SEC с", style = MaterialTheme.typography.labelMedium)
                }
                Text(
                    "Меньше всего — ${Zones.ALL[weakest].lowercase()}, ${seconds[weakest]} с. " +
                        "Коснись зуба или линии, чтобы увидеть зону.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

/** Guide colours: the zone to brush now, zones already brushed, zones still ahead. */
private val GUIDE_NOW = Color(0xFF4FE0CB)
private val GUIDE_DONE = Color(0xFF34D399)
private val GUIDE_AHEAD = Color(0xFF8A9593)

/**
 * The jaw for guided brushing. Whenever [zone] changes it turns to present that zone and lights it; zones already
 * brushed glow by how much of their time is done ([progress], 0..1 per zone in [Zones.ALL] order), zones ahead stay
 * dim. No gestures: the hands are busy. Frames are drawn only when the zone or the progress changes.
 */
@Composable
fun GuideJaw3D(zone: Int, progress: List<Float>, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val baked by produceState<Pair<BakedJaw, JawCamera>?>(null) {
        value = withContext(Dispatchers.IO) { JawCache.get(context) }
    }
    val dark = isSystemInDarkTheme()
    var yaw by remember { mutableFloatStateOf(REST_YAW) }
    var pitch by remember { mutableFloatStateOf(DEFAULT_PITCH) }
    var visible by remember { mutableStateOf(true) }
    var view by remember { mutableStateOf<JawGlView?>(null) }
    val glow = remember { Animatable(0f) }
    val rings by rememberUpdatedState(guideRings(zone, progress))

    // Each new zone: a short flash of light, and the model turns to show it.
    LaunchedEffect(zone) {
        if (zone !in 0..7) return@LaunchedEffect
        launch {
            glow.snapTo(0f)
            glow.animateTo(1f, tween(320))
            glow.animateTo(0.7f, tween(900))
        }
        val (targetYaw, targetPitch) = ZONE_VIEWS[zone]
        val turn = (2 * PI).toFloat()
        var dy = (targetYaw - yaw) % turn
        if (dy > PI) dy -= turn
        if (dy < -PI) dy += turn
        val y0 = yaw
        val p0 = pitch
        Animatable(0f).animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 60f)) {
            yaw = y0 + dy * value
            pitch = p0 + (targetPitch - p0) * value
        }
    }
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        snapshotFlow { if (!visible) null else JawGlView.Frame(yaw, pitch, 1f, rings, zone, glow.value) }
            .collect { frame -> frame?.let(v::submit) }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(300.dp)
            .onGloballyPositioned { visible = !it.boundsInWindow().isEmpty }
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.radialGradient(
                    if (dark) listOf(Color(0xFF2E3837), Color(0xFF0A0F0E)) else listOf(Color.White, Color(0xFFDDE5E3)),
                    center = Offset.Unspecified,
                    radius = 900f,
                ),
            )
            .semantics { contentDescription = "3D-модель зубов, сейчас: ${Zones.ALL.getOrElse(zone) { "—" }.lowercase()}" },
    ) {
        val b = baked
        if (b == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
        } else {
            AndroidView(
                factory = { ctx -> JawGlView(ctx, b.first, b.second).also { view = it } },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

private fun guideRings(zone: Int, progress: List<Float>): FloatArray {
    val out = FloatArray(32)
    for (i in 0 until 8) {
        val p = progress.getOrElse(i) { 0f }.coerceIn(0f, 1f)
        val c = when {
            i == zone -> GUIDE_NOW
            p > 0f -> GUIDE_DONE
            else -> GUIDE_AHEAD
        }
        out[i * 4] = c.red
        out[i * 4 + 1] = c.green
        out[i * 4 + 2] = c.blue
        out[i * 4 + 3] = when {
            i == zone -> 0.6f + 0.4f * p
            p > 0f -> 0.35f + 0.5f * p
            else -> 0.12f
        }
    }
    return out
}
