package dev.asofwar.freebrush.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.asofwar.freebrush.R
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.ble.BrushControls
import dev.asofwar.freebrush.ble.BrushControls.Toggle
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.Programme
import dev.asofwar.freebrush.data.Programmes
import dev.asofwar.freebrush.data.formatDuration

/** Top of the Brush tab: whose brush it is, its picture and its charge. */
@Composable
fun BrushHeaderCard(brush: BrushInfo, sync: SyncState, onSync: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    brush.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                brush.model?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Image(painterResource(R.drawable.brush_art), null, modifier = Modifier.height(140.dp).width(48.dp))
            Spacer(Modifier.width(14.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Battery, null, tint = batteryColor(brush.battery), modifier = Modifier.size(30.dp))
                Spacer(Modifier.height(4.dp))
                Text(brush.battery?.let { "$it %" } ?: "—", style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
        SyncChip(sync, brush, onSync)
    }
}

/** Compact card with an icon, a caption, a value and its detail; the whole card is the button. */
@Composable
private fun Tile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    caption: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: @Composable () -> Unit,
) {
    Surface(
        modifier.fillMaxHeight().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        // Icon above the text, so a narrow phone still fits the value on one line.
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                Spacer(Modifier.weight(1f))
                Icon(Icons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(caption, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            detail()
        }
    }
}

/** The programme the brush runs and the brush head, side by side. */
@Composable
fun ModeAndHeadRow(
    brush: BrushInfo,
    settings: ByteArray?,
    custom: List<Programme>,
    busy: Boolean,
    onChooseProgramme: () -> Unit,
    onResetHead: () -> Unit,
    onHeadLimit: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var headDetails by remember { mutableStateOf(false) }
    val auto = settings?.let { Toggle.AUTO_MODE.isOn(it) } == true
    val num = settings?.let { it[BrushControls.SCHEME_INDEX].toInt() and 0xFF }
    val p = num?.let { Programmes.find(it, custom) }
    val (mode, modeDetail) = when {
        settings == null -> "—" to "синхронизируй щётку"
        auto -> "Утро и вечер" to "4:00 и 3:00"
        p != null -> p.name to formatDuration(p.seconds)
        else -> "Программа №$num" to ""
    }
    val wear = brush.headWear?.let { w -> brush.headWearMax?.takeIf { it > 0 }?.let { w.toFloat() / it } }?.coerceIn(0f, 1f)
    val worn = wear != null && wear >= 0.9f

    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(Icons.Tune, "Режим чистки", mode, enabled = !busy && settings != null, onClick = onChooseProgramme, modifier = Modifier.weight(1f)) {
            if (modeDetail.isNotEmpty()) {
                Text(modeDetail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Tile(
            Icons.BrushHead,
            "Насадка",
            wear?.let { "Ресурс ${((1f - it) * 100).toInt()}%" } ?: "Ресурс —",
            enabled = true,
            onClick = { headDetails = true },
            modifier = Modifier.weight(1f),
        ) {
            if (wear != null) {
                Spacer(Modifier.height(8.dp))
                Gauge(1f - wear, if (worn) ScoreRed else MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (worn) "Пора менять" else brush.headDays?.let { "$it дн. в работе" } ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (worn) ScoreRed else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (headDetails) {
        AlertDialog(
            onDismissRequest = { headDetails = false },
            confirmButton = { TextButton(onClick = { headDetails = false }) { Text("Закрыть") } },
            title = { Text("Насадка") },
            text = {
                Column {
                    InfoRow("Дней в работе", brush.headDays?.toString())
                    InfoRow("Чисток этой насадкой", brush.headUses?.toString())
                    InfoRow("Износ по счётчику щётки", if (brush.headWear != null) "${brush.headWear} из ${brush.headWearMax ?: "—"}" else null)
                    if (worn) {
                        Spacer(Modifier.height(6.dp))
                        Text("Похоже, насадку пора менять", style = MaterialTheme.typography.labelLarge, color = ScoreRed)
                    }
                    HeadActions(brush.headWearMax, busy, onReset = { headDetails = false; onResetHead() }, onLimit = onHeadLimit)
                }
            },
        )
    }
}

/** Divider between rows of a list card, aligned with the row text. */
@Composable
fun RowDivider() {
    HorizontalDivider(Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}
