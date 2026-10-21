package dev.asofwar.freebrush.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.ui.jaw.Jaw3D
import dev.asofwar.freebrush.data.averageScore
import dev.asofwar.freebrush.data.formatDuration
import dev.asofwar.freebrush.data.isMorning
import dev.asofwar.freebrush.data.since
import dev.asofwar.freebrush.data.streakDays
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun TodayScreen(
    sessions: List<BrushSession>,
    sync: SyncState,
    brush: BrushInfo,
    onSync: () -> Unit,
    onOpen: (BrushSession) -> Unit,
) {
    val today = LocalDate.now()
    val todays = sessions.filter { it.time.toLocalDate() == today }
    val last = sessions.firstOrNull()
    val week = sessions.since(today.minusDays(6))
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.staggeredEntrance(0)) {
            ScreenHeader(
                greeting(),
                today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru"))).replaceFirstChar { it.uppercase() },
            )
        }
        Box(Modifier.staggeredEntrance(1)) { SyncChip(sync, brush, onSync) }

        HeroCard(last, todays, Modifier.staggeredEntrance(2), onOpen)

        Row(Modifier.staggeredEntrance(3), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val streak = sessions.streakDays()
            StatTile(Icons.Flame, "$streak", "дн. подряд", ScoreAmber, Modifier.weight(1f))
            StatTile(Icons.Star, week.averageScore()?.toString() ?: "—", "средняя оценка за неделю", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            val avgTime = week.map { it.durationSec }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            StatTile(Icons.Clock, avgTime?.let { formatDuration(it) } ?: "—", "среднее время за неделю", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }

        if (last != null && last.zones.isNotEmpty()) {
            SectionCard(Modifier.staggeredEntrance(4), title = "Зоны последней чистки") {
                Jaw3D(last, sessions)
            }
        }

        SectionCard(Modifier.staggeredEntrance(5), title = "Две недели") {
            DaysChart(sessions)
            Spacer(Modifier.height(8.dp))
            Text(
                "Высота — число чисток за день, пунктир — цель 2 раза. Цвет — средняя оценка.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HeroCard(last: BrushSession?, todays: List<BrushSession>, modifier: Modifier, onOpen: (BrushSession) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainerLow,
        onClick = { last?.let(onOpen) },
        enabled = last != null,
    ) {
        Column(
            Modifier
                .background(
                    Brush.linearGradient(
                        listOf(colors.primaryContainer.copy(alpha = 0.55f), colors.tertiaryContainer.copy(alpha = 0.35f)),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(last?.score, size = 150.dp)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text("Последняя чистка", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    if (last == null) {
                        Text("Пока нет данных", style = MaterialTheme.typography.titleLarge)
                        Text("Почисти зубы и потяни экран вниз", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(formatDuration(last.durationSec), style = MaterialTheme.typography.displayMedium)
                        last.plannedSec?.let {
                            Text("из ${formatDuration(it)} программы", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text(relativeTime(last.time), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DaySlot(Icons.Today, "Утро", todays.firstOrNull { it.isMorning() }, Modifier.weight(1f))
                DaySlot(Icons.Moon, "Вечер", todays.firstOrNull { !it.isMorning() }, Modifier.weight(1f))
            }
        }
    }
}

/** Morning or evening slot of today's goal; it pops when filled. */
@Composable
private fun DaySlot(icon: ImageVector, label: String, session: BrushSession?, modifier: Modifier) {
    val done = session != null
    val pop by animateFloatAsState(if (done) 1f else 0.92f, spring(dampingRatio = 0.4f, stiffness = 300f), label = "slot")
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.scale(pop),
        shape = MaterialTheme.shapes.medium,
        color = if (done) colors.primary.copy(alpha = 0.14f) else colors.surface.copy(alpha = 0.5f),
        border = if (done) null else BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(if (done) colors.primary else colors.onSurface.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (done) Icons.Check else icon, null, tint = if (done) colors.onPrimary else colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    session?.let { "${it.time.toLocalTime().toString().take(5)} · ${it.score ?: "—"}" } ?: "ещё нет",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}
