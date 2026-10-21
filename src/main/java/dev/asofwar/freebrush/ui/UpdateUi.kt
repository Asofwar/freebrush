package dev.asofwar.freebrush.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asofwar.freebrush.BuildConfig
import dev.asofwar.freebrush.update.Release
import dev.asofwar.freebrush.update.UpdateState
import dev.asofwar.freebrush.update.Updater

/** Version, the automatic-check switch and a manual check, on the Brush tab. */
@Composable
fun UpdatesCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val updater = remember { Updater.get(context) }
    val state by updater.state.collectAsStateWithLifecycle()
    val auto by updater.auto.collectAsStateWithLifecycle()
    val checking = state is UpdateState.Checking

    SectionCard(modifier, title = "Обновления") {
        InfoRow("Версия", BuildConfig.VERSION_NAME)
        RowDivider()
        Row(
            Modifier.fillMaxWidth().clickable { updater.setAuto(!auto) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Проверять обновления", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Раз в день · через GitHub. О тебе и щётке ничего не отправляет; выключи, и приложение " +
                        "не будет выходить в интернет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = auto, onCheckedChange = updater::setAuto)
        }
        RowDivider()
        ActionRow(
            Icons.Download,
            if (checking) "Проверяю…" else "Проверить сейчас",
            MaterialTheme.colorScheme.primary,
        ) { updater.check() }
        (state as? UpdateState.Message)?.let {
            Text(it.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Offers a found update and follows it through download and install. Shown over every tab. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val updater = remember { Updater.get(context) }
    val state by updater.state.collectAsStateWithLifecycle()

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Вышла FreeBrush ${s.release.version}") },
            text = { ReleaseNotes(s.release) },
            confirmButton = { TextButton(onClick = { updater.download(s.release) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Позже") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Загрузка ${s.release.version}") },
            text = {
                Column {
                    Gauge(s.progress ?: 0f, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(s.progress?.let { "${(it * 100).toInt()} %" } ?: "…", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Отмена") } },
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Обновление загружено") },
            text = {
                Text(
                    if (context.packageManager.canRequestPackageInstalls()) {
                        "FreeBrush закроется на пару секунд и обновится до ${s.release.version}. Данные останутся."
                    } else {
                        "Android попросит разрешить FreeBrush устанавливать приложения. Включи разрешение, " +
                            "вернись сюда и нажми «Установить» ещё раз."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { updater.install(context) }) { Text("Установить") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Позже") } },
        )
        is UpdateState.Confirm -> LaunchedEffect(s) {
            try {
                context.startActivity(s.intent)
                updater.confirmShown()
            } catch (e: android.content.ActivityNotFoundException) {
                updater.dismiss()
            }
        }
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Установка ${s.release.version}") },
            text = { Text("FreeBrush закроется. Если не откроется сам, запусти его снова.") },
            confirmButton = {},
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Скрыть") } },
        )
        is UpdateState.Error -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Обновление не удалось") },
            text = { Text(s.text) },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Понятно") } },
        )
        else -> Unit
    }
}

@Composable
private fun ReleaseNotes(release: Release) {
    // The notes are the release's CHANGELOG section: hard-wrapped list items are joined back into one line each,
    // and markdown markers would only get in the way here.
    val lines = mutableListOf<String>()
    for (raw in release.notes.lines()) {
        val line = raw.trimEnd()
        if (line.startsWith("  ") && lines.lastOrNull()?.isNotEmpty() == true) {
            lines[lines.lastIndex] = lines.last() + " " + line.trim()
        } else {
            lines += line
        }
    }
    val text = lines
        .map { it.removePrefix("### ").replace(Regex("^- "), "• ").replace("`", "") }
        .joinToString("\n")
        .trim()
        .let { if (it.length > 1500) it.take(1500) + "…" else it }
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        Text("Сейчас установлена ${BuildConfig.VERSION_NAME}.", style = MaterialTheme.typography.bodyMedium)
        if (text.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
