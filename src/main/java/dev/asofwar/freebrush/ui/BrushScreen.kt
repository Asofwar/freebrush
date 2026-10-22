package dev.asofwar.freebrush.ui

import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asofwar.freebrush.ActionState
import dev.asofwar.freebrush.AppViewModel
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.GoodixDfu
import dev.asofwar.freebrush.ble.hex
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.data.BrushInfo
import kotlinx.coroutines.launch

@Composable
fun BrushScreen(vm: AppViewModel, brush: BrushInfo, sync: SyncState, onPair: (PairMode) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log by vm.log.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val custom by vm.customProgrammes.collectAsStateWithLifecycle()
    val settings = brush.settingsHex?.let { hex(it) }
    // A write queues behind a running sync, so only another write in progress locks the controls.
    val busy = action is ActionState.Running
    var programmes by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val running = sync is SyncState.Running

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BrushHeaderCard(brush, sync, vm::syncNow, Modifier.staggeredEntrance(0))

        ActionLine(action)

        ModeAndHeadRow(
            brush,
            settings,
            custom,
            busy,
            onChooseProgramme = { programmes = true },
            onResetHead = vm::resetHead,
            onHeadLimit = vm::setHeadLimit,
            modifier = Modifier.staggeredEntrance(1),
        )

        SettingsCard(settings, busy, onToggle = vm::setToggle, onLanguage = vm::setLanguage, modifier = Modifier.staggeredEntrance(2))

        SectionCard(Modifier.staggeredEntrance(3)) {
            ActionRow(Icons.Sync, if (running) "Синхронизация…" else "Синхронизировать", MaterialTheme.colorScheme.primary, spinningRotation(running)) {
                vm.syncNow()
            }
            RowDivider()
            ActionRow(Icons.Share, "Экспорт истории в CSV") {
                scope.launch {
                    val uri = vm.exportCsv()
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Экспорт истории"))
                }
            }
            RowDivider()
            ActionRow(Icons.Edit, "Переименовать") { renaming = true }
            RowDivider()
            ActionRow(Icons.PersonAdd, "Добавить щётку другого человека") { onPair(PairMode.Add) }
            RowDivider()
            ActionRow(Icons.Swap, "Заменить на новую щётку") { confirmReplace = true }
            RowDivider()
            ActionRow(Icons.Trash, "Стереть историю", MaterialTheme.colorScheme.error) { confirmClear = true }
            RowDivider()
            ActionRow(Icons.Trash, "Удалить щётку", MaterialTheme.colorScheme.error) { confirmRemove = true }
        }

        if (GoodixDfu.supports(brush.hardware)) FirmwareCard(vm, brush, Modifier.staggeredEntrance(4))

        UpdatesCard(Modifier.staggeredEntrance(4))

        SectionCard(Modifier.staggeredEntrance(5), title = "Об устройстве") {
            InfoRow("Модель", brush.model)
            InfoRow("Прошивка", brush.firmware)
            InfoRow("Железо", brush.hardware)
            InfoRow("Адрес", brush.address)
            if (BrushControls.hasGrowth(brush.hardware)) {
                InfoRow("Очки роста (уровень на экране щётки)", brush.growthTotal?.toString() ?: "ведёт сама щётка")
                if (brush.growthTotal != null) {
                    Text(
                        "+1 за каждую чистку от 2 минут и очки, которые щётка даёт сама",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (log.isNotEmpty()) SyncLog(log, Modifier.staggeredEntrance(6))
        Spacer(Modifier.height(8.dp))
    }

    if (programmes) {
        ProgrammesSheet(
            active = settings?.let { it[BrushControls.SCHEME_INDEX].toInt() and 0xFF },
            autoMode = settings?.let { BrushControls.Toggle.AUTO_MODE.isOn(it) } == true,
            custom = custom,
            onAuto = { programmes = false; vm.setToggle(BrushControls.Toggle.AUTO_MODE, true) },
            onSend = { programmes = false; vm.sendProgramme(it) },
            onSave = vm::saveProgramme,
            onDelete = vm::deleteProgramme,
            newNum = vm::newProgrammeNum,
            onClose = { programmes = false },
        )
    }
    if (renaming) {
        NameDialog(
            title = "Имя щётки",
            initial = brush.label.orEmpty(),
            fallback = null,
            onDone = { renaming = false; vm.rename(it) },
            onDismiss = { renaming = false },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            confirmButton = { TextButton(onClick = { confirmReplace = false; onPair(PairMode.Replace) }) { Text("Искать") } },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("Отмена") } },
            title = { Text("Заменить щётку?") },
            text = {
                Text(
                    "Новая щётка займёт место «${brush.title}»: история, имя и уровень на экране щётки перейдут к ней, " +
                        "старую приложение отвяжет. Для щётки другого человека выберите «Добавить щётку».",
                )
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.remove() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Отмена") } },
            title = { Text("Удалить «${brush.title}»?") },
            text = { Text("Щётка отвяжется, её история удалится с телефона. Отменить это нельзя. На самой щётке останутся последние 32 чистки.") },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("Стереть") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
            title = { Text("Стереть историю?") },
            text = { Text("Все чистки «${brush.title}» удалятся с телефона, отменить это нельзя. На щётке останутся только последние 32.") },
        )
    }
}

@Composable
private fun SyncLog(log: List<String>, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    SectionCard(modifier) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Журнал синхронизации", style = MaterialTheme.typography.titleMedium)
                Text("Пригодится для отчёта о новой модели щётки", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Chevron, null, modifier = Modifier.size(22.dp).rotate(turn))
        }
        AnimatedVisibility(open) {
            val context = LocalContext.current
            val text = log.joinToString("\n")
            Column {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SelectionContainer {
                    Text(text, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("FreeBrush log", text))
                    }) { Text("Скопировать") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Журнал синхронизации"))
                    }) { Text("Поделиться") }
                }
            }
        }
    }
}
