package dev.asofwar.freebrush.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.ActionState
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.BrushControls.Toggle
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.Programmes
import dev.asofwar.freebrush.data.formatDuration

/** Progress of the last write to the brush. */
@Composable
fun ActionLine(action: ActionState) {
    val (text, color) = when (action) {
        ActionState.Idle -> return
        is ActionState.Running -> action.text to MaterialTheme.colorScheme.primary
        is ActionState.Done -> action.text to ScoreGreen
        is ActionState.Failed -> "Не получилось: ${action.text}" to MaterialTheme.colorScheme.error
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color)
}

/** The programme the brush runs now, with a button to choose another. */
@Composable
fun ProgrammeCard(settings: ByteArray?, custom: List<Programme>, busy: Boolean, onChoose: () -> Unit, modifier: Modifier = Modifier) {
    val auto = settings?.let { Toggle.AUTO_MODE.isOn(it) } == true
    val num = settings?.let { it[BrushControls.SCHEME_INDEX].toInt() and 0xFF }
    val p = num?.let { Programmes.find(it, custom) }
    SectionCard(modifier, title = "Режим чистки") {
        when {
            settings == null -> Text("Синхронизируйся, чтобы увидеть режим щётки", style = MaterialTheme.typography.bodyMedium)
            auto -> {
                Text("Утро и вечер", style = MaterialTheme.typography.titleLarge)
                Text("Днём «Эксклюзивный» 4:00, вечером «Закат» 3:00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            p != null -> {
                Text(p.name, style = MaterialTheme.typography.titleLarge)
                Text("${formatDuration(p.seconds)} · программа №${p.num}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                StepBars(p.steps, Modifier.fillMaxWidth())
            }
            else -> Text("Программа №$num", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onChoose, enabled = !busy) { Text("Выбрать программу") }
    }
}

/** Every switch the brush firmware has, including the ones the official app hides. */
@Composable
fun SettingsCard(settings: ByteArray?, busy: Boolean, onToggle: (Toggle, Boolean) -> Unit, onLanguage: (Int) -> Unit, modifier: Modifier = Modifier) {
    var pickLanguage by remember { mutableStateOf(false) }
    SectionCard(modifier, title = "Настройки щётки") {
        if (settings == null) {
            Text("Синхронизируйся, чтобы прочитать настройки щётки", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        // The day/evening switch lives on the programme card; here the rest.
        for (t in Toggle.entries.filter { it != Toggle.AUTO_MODE }) {
            val on = t.isOn(settings)
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { onToggle(t, !on) }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.title, style = MaterialTheme.typography.bodyLarge)
                        if (!t.official) {
                            Spacer(Modifier.width(8.dp))
                            Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.tertiaryContainer) {
                                Text("скрыто в Oclean", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(t.description.substringBefore(". Скрыто"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = on, onCheckedChange = { onToggle(t, it) }, enabled = !busy)
            }
        }
        val lang = settings[BrushControls.LANGUAGE_INDEX].toInt() and 0xFF
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !busy) { pickLanguage = true }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Язык экрана щётки", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(BrushControls.LANGUAGES.firstOrNull { it.first == lang }?.second ?: "№$lang", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (pickLanguage) {
        AlertDialog(
            onDismissRequest = { pickLanguage = false },
            confirmButton = { TextButton(onClick = { pickLanguage = false }) { Text("Закрыть") } },
            title = { Text("Язык экрана щётки") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(BrushControls.LANGUAGES) { (id, title) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { pickLanguage = false; onLanguage(id) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == settings?.let { it[BrushControls.LANGUAGE_INDEX].toInt() and 0xFF }, onClick = { pickLanguage = false; onLanguage(id) })
                            Text(title)
                        }
                    }
                }
            },
        )
    }
}

/** Brush-head actions: reset the counters after a change, set the wear limit. */
@Composable
fun HeadActions(limit: Int?, busy: Boolean, onReset: () -> Unit, onLimit: (Int) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        Button(onClick = { confirm = true }, enabled = !busy) { Text("Я поменял насадку") }
        OutlinedButton(onClick = { pick = true }, enabled = !busy && limit != null) { Text("Ресурс: ${limit ?: "—"}") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; onReset() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } },
            title = { Text("Новая насадка?") },
            text = { Text("Щётка обнулит счётчики насадки: дни, чистки и износ. Отменить это нельзя.") },
        )
    }
    if (pick) {
        AlertDialog(
            onDismissRequest = { pick = false },
            confirmButton = { TextButton(onClick = { pick = false }) { Text("Закрыть") } },
            title = { Text("Ресурс насадки") },
            text = {
                Column {
                    Text(
                        "Сколько щётка считает до замены насадки. Единица у щётки не подписана, похоже на минуты чистки: 240 — около трёх месяцев по 2 минуты дважды в день.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    for (v in listOf(180, 240, 300, 360)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { pick = false; onLimit(v) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == limit, onClick = { pick = false; onLimit(v) })
                            Text("$v")
                        }
                    }
                }
            },
        )
    }
}
