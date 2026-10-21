package dev.asofwar.freebrush.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.ui.jaw.Jaw3D
import dev.asofwar.freebrush.data.averageScore
import dev.asofwar.freebrush.data.byDay
import dev.asofwar.freebrush.data.formatDuration
import dev.asofwar.freebrush.data.since
import java.time.LocalDate
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(sessions: List<BrushSession>, onOpen: (BrushSession) -> Unit) {
    if (sessions.isEmpty()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            ScreenHeader("История")
            Spacer(Modifier.height(24.dp))
            Text("Здесь появятся твои чистки. Почисти зубы и потяни экран вниз.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val month = sessions.since(LocalDate.now().minusDays(29))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
        item(key = "header") {
            Column(Modifier.staggeredEntrance(0)) {
                ScreenHeader("История", "${sessions.size} ${plural(sessions.size, "чистка", "чистки", "чисток")}")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(Icons.Star, month.averageScore()?.toString() ?: "—", "средняя за 30 дней", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                    val best = sessions.maxByOrNull { it.score ?: -1 }
                    StatTile(Icons.Flame, best?.score?.toString() ?: "—", "лучшая оценка", ScoreAmber, Modifier.weight(1f))
                    val days = month.map { it.time.toLocalDate() }.distinct().size
                    StatTile(Icons.Today, "$days из 30", "дней с чисткой", MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                }
            }
        }
        sessions.byDay().forEach { (day, list) ->
            stickyHeader(key = "d$day") {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayTitle(day), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        list.averageScore()?.let {
                            Text("в среднем $it", style = MaterialTheme.typography.labelMedium, color = scoreColor(it))
                        }
                    }
                }
            }
            items(list, key = { it.key }) { s ->
                SessionRow(s, Modifier.animateItem()) { onOpen(s) }
            }
        }
    }
}

@Composable
private fun SessionRow(s: BrushSession, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            ScoreBadge(s.score)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.time.toLocalTime().toString().take(5), style = MaterialTheme.typography.titleMedium)
                Text(
                    s.schemeId?.let { schemeName(it) } ?: "Чистка",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(96.dp)) {
                Text(formatDuration(s.durationSec), style = MaterialTheme.typography.titleMedium)
                // Bar against the two-minute recommendation.
                Gauge(
                    s.durationSec / 120f,
                    if (s.durationSec >= 120) ScoreGreen else MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                    Modifier.padding(top = 6.dp).width(72.dp),
                )
            }
        }
    }
}

/** Session details in a bottom sheet: score, numbers, the mouth map and per-zone seconds. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(s: BrushSession, history: List<BrushSession>, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(dayTitle(s.time.toLocalDate()) + ", " + s.time.toLocalTime().toString().take(5), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(s.score, size = 130.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfoRow("Чистил", formatDuration(s.durationSec))
                    InfoRow("Программа", s.plannedSec?.let { formatDuration(it) })
                    InfoRow("Режим", s.schemeId?.let { schemeName(it) })
                    InfoRow("Пояс щётки", s.tzOffsetMin?.let { "UTC%+d:%02d".format(it / 60, abs(it % 60)) })
                }
            }
            if (!s.isComplete) {
                Text("Щётка передала только начало записи: без оценки и зон.", style = MaterialTheme.typography.bodySmall)
            }
            if (s.zones.isNotEmpty()) {
                SectionCard(title = "Зоны") {
                    Jaw3D(s, history)
                    Spacer(Modifier.height(16.dp))
                    ZoneList(s)
                }
            }
            PressureBar(s.pressure)
            TechDetails(s.raw)
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun TechDetails(raw: String) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column {
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Технические данные", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Chevron, null, modifier = Modifier.size(20.dp).rotate(turn))
        }
        AnimatedVisibility(open) {
            Box(Modifier.padding(bottom = 8.dp)) {
                Text(raw, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

/** Russian plural: 1 чистка, 2 чистки, 5 чисток. */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}
