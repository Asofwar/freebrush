package dev.asofwar.freebrush.ui

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.hex
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.BrushingPlan
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.ZoneStep
import dev.asofwar.freebrush.data.Zones
import dev.asofwar.freebrush.data.formatDuration
import dev.asofwar.freebrush.data.zoneSeconds
import dev.asofwar.freebrush.ui.jaw.GuideJaw3D
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.util.Locale

private enum class Phase { Ready, Countdown, Running, Done }

/**
 * Guided brushing. The brush sends nothing while it runs, so the phone keeps the time: the programme the brush is set
 * to, split over the eight zones with more time for the zones recent sessions left short. The model turns to each
 * zone, the phone buzzes, beeps and, if wanted, says the zone. Afterwards the app pulls the real record from the
 * brush and puts the plan next to what happened.
 */
@Composable
fun BrushingScreen(
    brush: BrushInfo,
    sessions: List<BrushSession>,
    custom: List<Programme>,
    sync: SyncState,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val prefs = remember { context.getSharedPreferences("guide", Context.MODE_PRIVATE) }
    var sound by remember { mutableStateOf(prefs.getBoolean("sound", true)) }
    var voice by remember { mutableStateOf(prefs.getBoolean("voice", false)) }

    val plan = remember(brush.id) {
        val settings = brush.settingsHex?.let { hex(it) }
        val total = BrushingPlan.programmeSeconds(
            settings,
            custom,
            morning = LocalTime.now().hour < 15,
            auto = settings?.let { BrushControls.Toggle.AUTO_MODE.isOn(it) } == true,
            active = settings?.let { it[BrushControls.SCHEME_INDEX].toInt() and 0xFF },
        )
        BrushingPlan.plan(total, sessions)
    }
    val totalMs = plan.sumOf { it.seconds } * 1000L
    val ends = remember(plan) { plan.runningFold(0L) { acc, s -> acc + s.seconds * 1000L }.drop(1) }

    var phase by rememberSaveable { mutableStateOf(Phase.Ready) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var elapsed by rememberSaveable { mutableLongStateOf(0L) }
    var countdown by remember { mutableIntStateOf(3) }
    var startedEpoch by rememberSaveable { mutableLongStateOf(0L) }
    var confirmExit by remember { mutableStateOf(false) }

    val step = ends.indexOfFirst { elapsed < it }.let { if (it < 0) plan.lastIndex else it }
    val current = plan[step]
    val stepLeftMs = (ends[step] - elapsed).coerceAtLeast(0)
    val progress = List(8) { z ->
        val i = plan.indexOfFirst { it.zone == z }
        val start = if (i == 0) 0L else ends[i - 1]
        ((elapsed - start).toFloat() / (plan[i].seconds * 1000f)).coerceIn(0f, 1f)
    }.map { (it * 20).toInt() / 20f } // 5 % steps: the model is redrawn on visible change only

    // Sound and voice live as long as the screen.
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull() }
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(voice) {
        val engine = if (voice) {
            TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) tts.value?.language = Locale.forLanguageTag("ru")
            }.also { tts.value = it }
        } else {
            null
        }
        onDispose {
            engine?.shutdown()
            tts.value = null
        }
    }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    val soundOn by rememberUpdatedState(sound)
    fun cue(text: String, end: Boolean = false) {
        haptics.performHapticFeedback(if (end) HapticFeedbackType.Confirm else HapticFeedbackType.LongPress)
        if (soundOn) tone?.startTone(if (end) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_BEEP, 150)
        tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "zone")
    }

    // The screen stays on while brushing.
    val running = phase == Phase.Running && !paused
    DisposableEffect(phase) {
        view.keepScreenOn = phase == Phase.Countdown || phase == Phase.Running
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(phase) {
        if (phase != Phase.Countdown) return@LaunchedEffect
        for (n in 3 downTo 1) {
            countdown = n
            delay(1000)
        }
        startedEpoch = System.currentTimeMillis() / 1000
        phase = Phase.Running
        cue(Zones.ALL[plan[0].zone])
    }
    // Pause-aware clock: elapsed real time while running, nothing while paused.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(100)
            val now = SystemClock.elapsedRealtime()
            elapsed = (elapsed + now - last).coerceAtMost(totalMs)
            last = now
            if (elapsed >= totalMs) {
                phase = Phase.Done
                cue("Готово", end = true)
                break
            }
        }
    }
    var announced by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(step, phase) {
        if (phase == Phase.Running && step != announced) {
            announced = step
            cue(Zones.ALL[current.zone])
        }
    }
    // Right after brushing the brush is awake: fetch the real record.
    LaunchedEffect(phase) {
        if (phase != Phase.Done) return@LaunchedEffect
        delay(3_000)
        onSync()
    }

    BackHandler {
        if (phase == Phase.Running || phase == Phase.Countdown) confirmExit = true else onClose()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .wrapContentWidth()
                .widthIn(max = MAX_CONTENT_WIDTH)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Чистка с подсказками",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    if (phase == Phase.Running || phase == Phase.Countdown) confirmExit = true else onClose()
                }) { Text("Закрыть") }
            }
            Spacer(Modifier.height(12.dp))
            GuideJaw3D(
                zone = if (phase == Phase.Ready || phase == Phase.Countdown) plan[0].zone else if (phase == Phase.Done) -1 else current.zone,
                progress = progress,
            )
            Spacer(Modifier.height(18.dp))
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) }, label = "phase") { p ->
                when (p) {
                    Phase.Ready -> ReadyPanel(plan, sound, voice, onSound = {
                        sound = it
                        prefs.edit().putBoolean("sound", it).apply()
                    }, onVoice = {
                        voice = it
                        prefs.edit().putBoolean("voice", it).apply()
                    }, onStart = { phase = Phase.Countdown })
                    Phase.Countdown -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Включи щётку", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("$countdown", fontSize = 96.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    }
                    Phase.Running -> RunningPanel(
                        plan = plan,
                        step = step,
                        stepLeftMs = stepLeftMs,
                        elapsed = elapsed,
                        totalMs = totalMs,
                        paused = paused,
                        onPause = { paused = !paused },
                        onFinish = {
                            phase = Phase.Done
                            cue("Готово", end = true)
                        },
                    )
                    Phase.Done -> DonePanel(plan, elapsed, startedEpoch, sessions, sync, onSync, onClose)
                }
            }
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Прервать") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Продолжить") } },
            title = { Text("Прервать подсказки?") },
            text = { Text("Щётка продолжит работать сама; запись чистки появится после синхронизации.") },
        )
    }
}

@Composable
private fun ReadyPanel(
    plan: List<ZoneStep>,
    sound: Boolean,
    voice: Boolean,
    onSound: (Boolean) -> Unit,
    onVoice: (Boolean) -> Unit,
    onStart: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Включи щётку и нажми «Начать»", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "${formatDuration(plan.sumOf { it.seconds })} · 8 зон по кругу, как подсвечивает модель",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val extra = plan.filter { it.extra > 0 }.sortedByDescending { it.extra }
        if (extra.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionCard(Modifier.fillMaxWidth(), title = "Больше времени, чем обычно") {
                extra.forEach { s ->
                    InfoRow(Zones.ALL[s.zone], "+${s.extra} с")
                }
                Text(
                    "В последних чистках этим зонам доставалось меньше всего.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sound, onClick = { onSound(!sound) }, label = { Text("Звук") })
            FilterChip(selected = voice, onClick = { onVoice(!voice) }, label = { Text("Голос") })
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Начать") }
    }
}

@Composable
private fun RunningPanel(
    plan: List<ZoneStep>,
    step: Int,
    stepLeftMs: Long,
    elapsed: Long,
    totalMs: Long,
    paused: Boolean,
    onPause: () -> Unit,
    onFinish: () -> Unit,
) {
    val current = plan[step]
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Зона ${step + 1} из ${plan.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(Zones.ALL[current.zone], style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(
            "${(stepLeftMs + 999) / 1000}",
            fontSize = 88.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
        )
        if (current.extra > 0) {
            Text(
                "+${current.extra} с: в прошлые разы мало",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { (elapsed.toFloat() / totalMs).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                plan.getOrNull(step + 1)?.let { "Дальше: ${Zones.ALL[it.zone].lowercase()}" } ?: "Последняя зона",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "осталось ${formatDuration(((totalMs - elapsed + 999) / 1000).toInt())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onPause) { Text(if (paused) "Продолжить" else "Пауза") }
            OutlinedButton(onClick = onFinish) { Text("Завершить") }
        }
    }
}

@Composable
private fun DonePanel(
    plan: List<ZoneStep>,
    elapsed: Long,
    startedEpoch: Long,
    sessions: List<BrushSession>,
    sync: SyncState,
    onSync: () -> Unit,
    onClose: () -> Unit,
) {
    // The record of this brushing: the first session that started around when the guide did.
    val actual = sessions.firstOrNull { it.epochSec >= startedEpoch - 120 && it.zoneSeconds().size >= 8 }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Готово", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "По подсказкам ${formatDuration((elapsed / 1000).toInt())}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        when {
            actual != null -> PlanVersusActual(plan, actual)
            sync is SyncState.Running -> Text("Забираю запись со щётки…", style = MaterialTheme.typography.bodyMedium)
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Записи этой чистки ещё нет. Синхронизируй, когда щётка проснётся.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onSync) { Text("Синхронизировать") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
    }
}

/** Two bars per zone: the planned seconds and what the brush recorded. */
@Composable
private fun PlanVersusActual(plan: List<ZoneStep>, session: BrushSession) {
    val real = session.zoneSeconds()
    val top = (plan.maxOf { it.seconds }.coerceAtLeast(real.maxOrNull() ?: 0)).coerceAtLeast(1).toFloat()
    SectionCard(Modifier.fillMaxWidth(), title = "План и что записала щётка") {
        plan.forEach { s ->
            val got = real.getOrElse(s.zone) { 0 }
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Zones.ALL[s.zone], style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.1f))
                Column(Modifier.weight(1f)) {
                    Bar(s.seconds / top, MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(3.dp))
                    Bar(got / top, if (got >= s.seconds * 0.8f) ScoreGreen else readable(ScoreAmber))
                }
                Spacer(Modifier.width(10.dp))
                Text("$got/${s.seconds} с", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            "Серая полоса — план, цветная — по данным щётки.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Bar(fraction: Float, color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
    }
}
