package dev.asofwar.freebrush.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asofwar.freebrush.AppViewModel
import dev.asofwar.freebrush.FirmwareState
import dev.asofwar.freebrush.ble.GoodixDfuUpdate
import dev.asofwar.freebrush.data.BrushInfo

/** The brush's firmware: what it runs, where a new one comes from, and the install behind a warning. */
@Composable
fun FirmwareCard(vm: AppViewModel, brush: BrushInfo, modifier: Modifier = Modifier) {
    val state by vm.firmware.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::firmwareFromFile) }
    var confirm by remember { mutableStateOf<FirmwareState.Ready?>(null) }
    val free = state !is FirmwareState.Working && state !is FirmwareState.Installing

    SectionCard(modifier, title = "Прошивка щётки") {
        InfoRow("Стоит", brush.firmware)
        Text(
            "Проверка спрашивает сервер Oclean и сообщает ему MAC и модель щётки. Файл OTA.bin ставится и без интернета.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RowDivider()
        ActionRow(
            Icons.Download,
            (state as? FirmwareState.Working)?.text ?: "Проверить на сервере Oclean",
            MaterialTheme.colorScheme.primary,
            spinningRotation(state is FirmwareState.Working),
        ) { if (free) vm.firmwareFromServer() }
        RowDivider()
        ActionRow(Icons.File, "Поставить из файла OTA.bin…") { if (free) pick.launch(arrayOf("*/*")) }

        when (val s = state) {
            is FirmwareState.UpToDate -> {
                Note("Новее нет: на сервере ${s.version}")
                TextButton(onClick = { vm.firmwareFromServer(again = true) }) { Text("Скачать ${s.version} ещё раз") }
            }
            is FirmwareState.Ready -> {
                Spacer(Modifier.height(8.dp))
                Text(s.label, style = MaterialTheme.typography.titleSmall)
                Note(
                    "Образ «${s.firmware.info.comment}», ${s.firmware.bytes.size / 1024} КБ, контрольная сумма сошлась. " +
                        "Совпадёт ли он со щёткой, проверю перед записью.",
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirm = s }, modifier = Modifier.fillMaxWidth()) { Text("Установить") }
                TextButton(onClick = vm::closeFirmware) { Text("Отмена") }
            }
            is FirmwareState.Installed -> {
                Note(s.text)
                TextButton(onClick = vm::closeFirmware) { Text("Понятно") }
            }
            is FirmwareState.Failed -> {
                Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = vm::closeFirmware) { Text(if (s.retry != null) "Назад к файлу" else "Понятно") }
            }
            else -> Unit
        }
    }

    confirm?.let { ready ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Установить прошивку?") },
            text = {
                Column {
                    Text(ready.label, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Запись идёт около минуты. Держи щётку рядом с телефоном и не закрывай приложение.\n" +
                            "• Заряд щётки — не меньше ${GoodixDfuUpdate.MIN_BATTERY} %, иначе запись не начнётся.\n" +
                            "• Новый образ пишется в свободную область; работающая прошивка меняется только в конце, " +
                            "после проверки суммы.\n" +
                            "• Это неофициальная установка. Сбой в последние секунды может сделать щётку неработоспособной. " +
                            "Ставишь на свой риск.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.installFirmware()
                }) { Text("Установить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }

    (state as? FirmwareState.Installing)?.let { InstallingDialog(it) }
}

/** Progress of the write; cannot be dismissed, and keeps the screen on while it runs. */
@Composable
private fun InstallingDialog(s: FirmwareState.Installing) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val fraction = if (s.total > 0) s.done.toFloat() / s.total else 0f
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Прошивка щётки") },
        text = {
            Column {
                Gauge(fraction, MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("${(fraction * 100).toInt()} %", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(s.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Не закрывай приложение и не уноси щётку.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
}
