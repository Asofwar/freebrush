package dev.asofwar.freebrush.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.ble.FoundDevice
import dev.asofwar.freebrush.ble.scanDevices
import kotlin.math.sin

@Composable
fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Bubbles(Modifier.size(180.dp).staggeredEntrance(0))
        Spacer(Modifier.height(24.dp))
        Text("FreeBrush", style = MaterialTheme.typography.displayMedium, modifier = Modifier.staggeredEntrance(1))
        Spacer(Modifier.height(8.dp))
        Text(
            "Твоя щётка Oclean без аккаунта и облака",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.staggeredEntrance(2),
        )
        Spacer(Modifier.height(28.dp))
        Column(Modifier.staggeredEntrance(3), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feature(Icons.Bluetooth, "Забирает чистки со щётки по Bluetooth")
            Feature(Icons.Check, "Без интернета: у приложения нет на него разрешения")
            Feature(Icons.Star, "Оценка, зоны и серия дней — на телефоне")
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp).staggeredEntrance(4)) {
            Text("Разрешить Bluetooth")
        }
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Foam bubbles rising and wobbling around a brush head: the welcome illustration. */
@Composable
private fun Bubbles(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "bubbles")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "phase")
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val head = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Brush head and handle.
        drawRoundRect(head, Offset(w * 0.40f, h * 0.38f), androidx.compose.ui.geometry.Size(w * 0.20f, h * 0.30f), androidx.compose.ui.geometry.CornerRadius(24f))
        drawRoundRect(primary, Offset(w * 0.46f, h * 0.66f), androidx.compose.ui.geometry.Size(w * 0.08f, h * 0.32f), androidx.compose.ui.geometry.CornerRadius(20f))
        for (i in 0 until 4) {
            drawLine(primary, Offset(w * (0.44f + i * 0.04f), h * 0.30f), Offset(w * (0.44f + i * 0.04f), h * 0.38f), strokeWidth = 6f)
        }
        // Eleven bubbles, each on its own rise cycle with a sideways wobble.
        for (i in 0 until 11) {
            val p = (phase + i / 11f) % 1f
            val x = w * (0.18f + 0.64f * ((i * 37) % 11) / 10f) + sin((p * 6.28f) + i) * 10f
            val y = h * (0.62f - 0.6f * p)
            val r = (6f + (i % 4) * 4f) * (0.6f + 0.4f * p)
            val c = if (i % 2 == 0) primary else tertiary
            drawCircle(c.copy(alpha = (1f - p) * 0.7f), r, Offset(x, y), style = Stroke(3f))
        }
    }
}

/** Scan for the brush with a radar pulse while looking. */
@Composable
fun PairScreen(
    mode: PairMode,
    known: Set<String>,
    nextNumber: Int,
    onCancel: (() -> Unit)?,
    onPick: (FoundDevice, label: String?) -> Unit,
) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf<FoundDevice?>(null) }
    LaunchedEffect(Unit) {
        try {
            scanDevices(context).collect { d ->
                val i = found.indexOfFirst { it.address == d.address }
                if (i >= 0) found[i] = d else found += d
            }
        } catch (e: Exception) {
            error = e.message
        }
    }
    val list = found.filter { it.address !in known && (showAll || it.looksLikeOclean) }.sortedByDescending { it.rssi }
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "radar") {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Radar(Modifier.size(200.dp))
                Text(
                    when (mode) {
                        PairMode.First -> "Ищу щётку"
                        PairMode.Add -> "Ищу ещё одну щётку"
                        PairMode.Replace -> "Ищу новую щётку"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Возьми щётку в руку или нажми кнопку, чтобы она проснулась. Приложение Oclean лучше закрыть: щётка держит только одно соединение.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Показать только Oclean" else "Показать все устройства") }
                if (onCancel != null) TextButton(onClick = onCancel) { Text("Отмена") }
            }
        }
        items(list, key = { it.address }) { d ->
            Surface(
                onClick = { if (mode == PairMode.Replace) onPick(d, null) else picked = d },
                shape = MaterialTheme.shapes.medium,
                color = if (d.looksLikeOclean) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth().animateItem(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (d.looksLikeOclean) Icons.Brush else Icons.Bluetooth, null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name ?: "Без имени", style = MaterialTheme.typography.titleMedium)
                        Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    SignalBars(d.rssi)
                }
            }
        }
    }
    picked?.let { d ->
        NameDialog(
            title = "Чья это щётка?",
            initial = "",
            // The first brush may keep its Bluetooth name; the next ones get numbers to tell them apart.
            fallback = if (mode == PairMode.First) null else "Щётка $nextNumber",
            onDone = { label -> picked = null; onPick(d, label) },
            onDismiss = { picked = null },
        )
    }
}

/** Three rings expanding from a Bluetooth badge, staggered. */
@Composable
private fun Radar(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    val color = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2
            for (i in 0 until 3) {
                val q = (p + i / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - q) * 0.45f), radius = maxR * (0.25f + 0.75f * q), style = Stroke(4f))
            }
        }
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Bluetooth, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
    }
}

/** Signal strength as four bars from RSSI. */
@Composable
private fun SignalBars(rssi: Int) {
    val level = when {
        rssi > -60 -> 4
        rssi > -70 -> 3
        rssi > -80 -> 2
        else -> 1
    }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (i in 1..4) {
            Box(Modifier.width(4.dp).height((4 + i * 4).dp).clip(CircleShape).background(if (i <= level) on else off))
        }
    }
}
