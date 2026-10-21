package dev.asofwar.freebrush.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asofwar.freebrush.AppViewModel
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.ble.FoundDevice
import dev.asofwar.freebrush.ble.scanDevices
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushSession
import dev.asofwar.freebrush.data.averageScore
import dev.asofwar.freebrush.data.byDay
import dev.asofwar.freebrush.data.formatDuration
import dev.asofwar.freebrush.data.since
import dev.asofwar.freebrush.data.streakDays
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

private val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

private enum class Tab(val title: String, val icon: ImageVector) {
    Today("Сегодня", Icons.Today),
    History("История", Icons.History),
    Brush("Щётка", Icons.Brush),
}

@Composable
fun FreeBrushApp(vm: AppViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(BT_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        granted = res.values.all { it }
    }
    val brush by vm.brush.collectAsStateWithLifecycle()

    when {
        !granted -> PermissionScreen { launcher.launch(BT_PERMISSIONS) }
        brush == null -> PairScreen(onPick = vm::pair)
        else -> MainScreen(vm, brush!!)
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("FreeBrush", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.padding(8.dp))
        Text(
            "Приложению нужен доступ к Bluetooth, чтобы найти щётку и забрать с неё историю чисток. " +
                "Интернет приложению не нужен: все данные остаются на телефоне.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.padding(16.dp))
        Button(onClick = onRequest) { Text("Разрешить Bluetooth") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairScreen(onPick: (FoundDevice) -> Unit) {
    val context = LocalContext.current
    val found = remember { mutableStateListOf<FoundDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
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
    Scaffold(topBar = { TopAppBar(title = { Text("Поиск щётки") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            Text(
                "Возьми щётку в руку или нажми кнопку, чтобы она проснулась. " +
                    "Официальное приложение Oclean лучше закрыть: щётка держит только одно соединение.",
                style = MaterialTheme.typography.bodyMedium,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  Ищу…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Только Oclean" else "Все устройства") }
            }
            val list = found.filter { showAll || it.looksLikeOclean }.sortedByDescending { it.rssi }
            LazyColumn {
                items(list, key = { it.address }) { d ->
                    Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 12.dp)) {
                        Text(d.name ?: "Без имени", fontWeight = FontWeight.Medium)
                        Text("${d.address}  ·  ${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, brush: BrushInfo) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()

    // Pull fresh data every time the app opens.
    LaunchedEffect(brush.address) { vm.syncNow() }

    Scaffold(
        topBar = { TopAppBar(title = { Text(tab.title) }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                    )
                }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = sync is SyncState.Running,
            onRefresh = vm::syncNow,
            modifier = Modifier.padding(pad).fillMaxSize(),
        ) {
            when (tab) {
                Tab.Today -> TodayScreen(sessions, sync, brush)
                Tab.History -> HistoryScreen(sessions)
                Tab.Brush -> BrushScreen(vm, brush, sync)
            }
        }
    }
}

@Composable
private fun SyncLine(sync: SyncState, brush: BrushInfo) {
    val text = when (sync) {
        is SyncState.Running -> sync.step
        is SyncState.Done -> if (sync.added > 0) "Новых чисток: ${sync.added}" else "Новых чисток нет"
        is SyncState.Failed -> "Не удалось синхронизироваться: ${sync.message}"
        SyncState.Idle -> brush.lastSyncMillis?.let { "Синхронизация: " + DateFormat.getDateTimeInstance().format(Date(it)) }
            ?: "Ещё не синхронизировано"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (sync is SyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TodayScreen(sessions: List<BrushSession>, sync: SyncState, brush: BrushInfo) {
    val today = LocalDate.now()
    val todayCount = sessions.count { it.time.toLocalDate() == today }
    val last = sessions.firstOrNull()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SyncLine(sync, brush)
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(last?.score)
                Column(Modifier.padding(start = 20.dp)) {
                    Text("Последняя чистка", style = MaterialTheme.typography.labelLarge)
                    if (last == null) {
                        Text("Пока нет данных", style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(formatDuration(last.durationSec), style = MaterialTheme.typography.headlineMedium)
                        Text(last.time.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)), style = MaterialTheme.typography.bodySmall)
                        last.overPressureCount?.takeIf { it > 0 }?.let {
                            Text("Сильное нажатие: $it раз", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Сегодня", "$todayCount из 2", Modifier.weight(1f))
            StatCard("Серия", "${sessions.streakDays()} дн.", Modifier.weight(1f))
            StatCard("Ср. за 7 дн.", sessions.since(today.minusDays(6)).averageScore()?.toString() ?: "—", Modifier.weight(1f))
        }
        if (last != null && last.zones.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Зоны последней чистки", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                    ZoneBars(last.zones, "")
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Две недели", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 8.dp))
                DaysChart(sessions)
            }
        }
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun HistoryScreen(sessions: List<BrushSession>) {
    var open by remember { mutableStateOf<BrushSession?>(null) }
    if (sessions.isEmpty()) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp)) {
            Text("История пуста. Почисти зубы и потяни экран вниз для синхронизации.")
        }
        return
    }
    val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        sessions.byDay().forEach { (day, list) ->
            item(key = day.toString()) {
                Text(
                    day.format(dayFormat),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(list, key = { it.key }) { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { open = s }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.time.format(timeFormat), modifier = Modifier.width(64.dp))
                    Text(formatDuration(s.durationSec), modifier = Modifier.weight(1f))
                    Text(
                        s.score?.toString() ?: "—",
                        color = scoreColor(s.score),
                        fontWeight = FontWeight.Bold,
                    )
                }
                HorizontalDivider()
            }
        }
    }
    open?.let { s -> SessionDialog(s) { open = null } }
}

@Composable
private fun SessionDialog(s: BrushSession, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
        title = { Text(s.time.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Оценка: ${s.score ?: "—"}")
                Text("Длительность: ${formatDuration(s.durationSec)}")
                s.validDurationSec?.let { Text("Эффективно: ${formatDuration(it)}") }
                s.schemeId?.let { Text("Режим: $it") }
                s.overPressureCount?.let { Text("Сильное нажатие: $it") }
                if (s.zones.isNotEmpty()) ZoneBars(s.zones, "")
                if (s.pressure.isNotEmpty()) Text("Нажим: ${s.pressure.joinToString()}")
                Text(s.raw, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        },
    )
}

@Composable
private fun BrushScreen(vm: AppViewModel, brush: BrushInfo, sync: SyncState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by vm.log.collectAsStateWithLifecycle()
    var confirmForget by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(brush.name ?: "Oclean", style = MaterialTheme.typography.titleLarge)
                InfoRow("Заряд", brush.battery?.let { "$it %" })
                InfoRow("Насадка в работе", brush.headDays?.let { "$it дн." })
                InfoRow("Модель", brush.model)
                InfoRow("Прошивка", brush.firmware)
                InfoRow("Железо", brush.hardware)
                InfoRow("Адрес", brush.address)
            }
        }
        SyncLine(sync, brush)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::syncNow, enabled = sync !is SyncState.Running) { Text("Синхронизировать") }
            OutlinedButton(onClick = {
                scope.launch {
                    val uri = vm.exportCsv()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Экспорт истории"))
                }
            }) { Text("Экспорт CSV") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirmForget = true }) { Text("Сменить щётку") }
            OutlinedButton(onClick = { confirmClear = true }) { Text("Стереть историю") }
        }
        if (log.isNotEmpty()) {
            Text("Журнал синхронизации", style = MaterialTheme.typography.titleSmall)
            Text(log.joinToString("\n"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            confirmButton = { TextButton(onClick = { confirmForget = false; vm.forget() }) { Text("Сменить") } },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Отмена") } },
            text = { Text("Отвязать эту щётку и найти другую? История чисток сохранится.") },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Стереть") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
            text = { Text("Удалить всю историю чисток с телефона? Это нельзя отменить.") },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}
