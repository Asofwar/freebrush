package dev.asofwar.freebrush.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.BrushControls.Step
import dev.asofwar.freebrush.data.Motor
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.Programmes
import dev.asofwar.freebrush.data.formatDuration

fun motorColor(m: Motor): Color = when (m) {
    Motor.CLEAN -> Color(0xFF2DD4BF)
    Motor.WHITEN -> Color(0xFF93C5FD)
    Motor.MASSAGE -> Color(0xFFF9A8D4)
    Motor.SENSITIVE -> Color(0xFFFDE68A)
}

/** Steps as bars: width follows the step's time, height its level within the mode, colour the mode. */
@Composable
fun StepBars(steps: List<Step>, modifier: Modifier = Modifier, height: Int = 28) {
    Row(modifier.height(height.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (s in steps) {
            val m = Motor.of(s.gear)
            val fraction = 0.3f + 0.7f * Motor.level(s.gear) / m.levels
            Box(
                Modifier.weight(s.seconds.toFloat()).fillMaxHeight(fraction)
                    .clip(RoundedCornerShape(3.dp)).background(motorColor(m)),
            )
        }
    }
}

/**
 * All programmes the brush can run: its built-in day/evening behaviour, its built-in modes, Oclean's catalogue
 * and the user's own ones, each with a button to switch the brush to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgrammesSheet(
    active: Int?,
    autoMode: Boolean,
    custom: List<Programme>,
    onAuto: () -> Unit,
    onSend: (Programme) -> Unit,
    onSave: (Programme) -> Unit,
    onDelete: (Programme) -> Unit,
    newNum: () -> Int?,
    onClose: () -> Unit,
) {
    var open by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<Programme?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), modifier = Modifier.navigationBarsPadding()) {
            item {
                Text("Программы", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onAuto,
                    shape = MaterialTheme.shapes.medium,
                    color = if (autoMode) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Утро и вечер", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Как из коробки: днём «Эксклюзивный», вечером «Закат». Щётка переключает сама",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (autoMode) Icon(Icons.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            section("Режимы щётки")
            items(Programmes.BUILT_IN, key = { "b${it.num}" }) { p ->
                ProgrammeRow(p, !autoMode && active == p.num, open == p.num, { open = if (open == p.num) null else p.num }) {
                    Button(onClick = { onSend(p) }) { Text("Включить") }
                }
            }
            section("Каталог Oclean")
            items(Programmes.CATALOGUE, key = { "c${it.num}" }) { p ->
                ProgrammeRow(p, !autoMode && active == p.num, open == p.num, { open = if (open == p.num) null else p.num }) {
                    Button(onClick = { onSend(p) }) { Text("Включить") }
                    OutlinedButton(onClick = {
                        val n = newNum() ?: return@OutlinedButton
                        editing = p.copy(num = n, name = "${p.name} (моя)", description = "")
                    }) { Text("Взять за основу") }
                }
            }
            section("Мои программы")
            items(custom, key = { "m${it.num}" }) { p ->
                ProgrammeRow(p, !autoMode && active == p.num, open == p.num, { open = if (open == p.num) null else p.num }) {
                    Button(onClick = { onSend(p) }) { Text("Включить") }
                    OutlinedButton(onClick = { editing = p }) { Text("Изменить") }
                    TextButton(onClick = { onDelete(p) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val n = newNum() ?: return@OutlinedButton
                        editing = Programme(n, "Моя программа", List(4) { Step(16, 30) })
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Создать программу") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Без ограничений официального приложения: до 25 своих программ по ${BrushControls.MAX_STEPS} шагов, 32 уровня чистки, отбеливание, массаж и деликатный режим, шаги от 5 секунд до 4 минут 15 секунд.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    editing?.let { p ->
        ProgrammeEditor(p, onDismiss = { editing = null }, onSave = { onSave(it); editing = null }, onSaveAndSend = { onSave(it); onSend(it); editing = null })
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String) {
    item(key = "s$title") {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
    }
}

@Composable
private fun ProgrammeRow(p: Programme, active: Boolean, expanded: Boolean, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${formatDuration(p.seconds)} · ${p.steps.size} ${plural(p.steps.size, "шаг", "шага", "шагов")}" +
                            if (p.description.isNotEmpty()) " · ${p.description}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (expanded) 4 else 1,
                    )
                }
                if (active) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            StepBars(p.steps, Modifier.fillMaxWidth())
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.steps.forEachIndexed { i, s ->
                        val m = Motor.of(s.gear)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(motorColor(m)))
                            Text(
                                "  ${i + 1}. ${m.title}${if (m.levels > 1) ", уровень ${Motor.level(s.gear)}" else ""} — ${formatDuration(s.seconds)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) { actions() }
                }
            }
        }
    }
}

/** Editor of a custom programme: name and any number of steps, each with mode, level and time. */
@Composable
private fun ProgrammeEditor(initial: Programme, onDismiss: () -> Unit, onSave: (Programme) -> Unit, onSaveAndSend: (Programme) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    val steps = remember { mutableStateListOf<Step>().apply { addAll(initial.steps) } }
    fun result() = Programme(initial.num, name.ifBlank { "Моя программа" }, steps.toList())

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f)) {
            Column(Modifier.padding(20.dp)) {
                Text("Программа №${initial.num}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                StepBars(steps, Modifier.fillMaxWidth(), height = 36)
                Text(
                    "Всего ${formatDuration(steps.sumOf { it.seconds })} · ${steps.size} ${plural(steps.size, "шаг", "шага", "шагов")}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    steps.forEachIndexed { i, s -> StepEditor(i, s, onChange = { steps[i] = it }, onRemove = if (steps.size > 1) ({ steps.removeAt(i) }) else null) }
                    OutlinedButton(
                        onClick = { steps.add(steps.lastOrNull() ?: Step(16, 30)) },
                        enabled = steps.size < BrushControls.MAX_STEPS,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (steps.size < BrushControls.MAX_STEPS) "Добавить шаг" else "Щётка принимает до ${BrushControls.MAX_STEPS} шагов")
                    }
                    if (steps.size > 6) {
                        Text(
                            "Официальное приложение разрешает до 6 шагов, щётка принимает до ${BrushControls.MAX_STEPS}: проверено.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = { onSave(result()) }) { Text("Сохранить") }
                    Button(onClick = { onSaveAndSend(result()) }) { Text("Включить") }
                }
            }
        }
    }
}

@Composable
private fun StepEditor(index: Int, step: Step, onChange: (Step) -> Unit, onRemove: (() -> Unit)?) {
    val motor = Motor.of(step.gear)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(motorColor(motor)))
                Text("  Шаг ${index + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Убрать") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Motor.entries.forEach { m ->
                    FilterChip(
                        selected = m == motor,
                        onClick = { onChange(step.copy(gear = m.gear(if (m == motor) Motor.level(step.gear) else (m.levels + 1) / 2))) },
                        label = { Text(m.title, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            if (motor.levels > 1) {
                val level = Motor.level(step.gear)
                Text("Уровень $level из ${motor.levels}", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = level.toFloat(),
                    onValueChange = { onChange(step.copy(gear = motor.gear(it.toInt()))) },
                    valueRange = 1f..motor.levels.toFloat(),
                    steps = (motor.levels - 2).coerceAtLeast(0),
                )
            }
            Text("Время ${formatDuration(step.seconds)}", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = step.seconds.toFloat(),
                onValueChange = { onChange(step.copy(seconds = (it.toInt() / 5 * 5).coerceIn(5, 255))) },
                valueRange = 5f..255f,
            )
        }
    }
}
