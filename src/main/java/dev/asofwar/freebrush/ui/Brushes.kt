package dev.asofwar.freebrush.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.data.BrushInfo

/** Why the brush scan is open: the first brush, one more person's brush, or a new brush for the person on screen. */
enum class PairMode { First, Add, Replace }

/**
 * Asks for a brush's name, usually its owner's. An empty name falls back to [fallback]
 * (null keeps the brush's Bluetooth name).
 */
@Composable
fun NameDialog(title: String, initial: String, fallback: String?, onDone: (String?) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val done = { onDone(text.trim().ifEmpty { fallback }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Имя поможет различать щётки: оно в переключателе сверху и в приветствии.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(24) },
                    singleLine = true,
                    label = { Text("Имя") },
                    placeholder = { Text(fallback ?: "Аня") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { Button(onClick = done) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Chips to switch between people's brushes; shown only when there are several. */
@Composable
fun BrushSwitcher(brushes: List<BrushInfo>, current: BrushInfo, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val titles = distinctTitles(brushes)
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        brushes.forEachIndexed { i, b ->
            FilterChip(
                selected = b.id == current.id,
                onClick = { onSelect(b.id) },
                label = { Text(titles[i]) },
            )
        }
    }
}

/** Brush titles, numbered where two would read the same (two unnamed brushes of one model). */
fun distinctTitles(brushes: List<BrushInfo>): List<String> {
    val counts = brushes.groupingBy { it.title }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return brushes.map { b ->
        if (counts.getValue(b.title) == 1) {
            b.title
        } else {
            val n = (seen[b.title] ?: 0) + 1
            seen[b.title] = n
            "${b.title} $n"
        }
    }
}
