package dev.asofwar.freebrush.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.asofwar.freebrush.AppViewModel
import dev.asofwar.freebrush.SyncState
import dev.asofwar.freebrush.data.BrushInfo
import dev.asofwar.freebrush.data.BrushSession

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
    val brushes by vm.brushes.collectAsStateWithLifecycle()
    // Adding a brush or replacing the one on screen opens the scan over the main screen.
    var pairing by rememberSaveable { mutableStateOf<PairMode?>(null) }
    BackHandler(enabled = pairing != null && brush != null) { pairing = null }

    val screen = when {
        !granted -> 0
        brush == null || pairing != null -> 1
        else -> 2
    }
    // The surface gives every screen the theme's text colour; without it text outside cards stays black in dark mode.
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "root") { s ->
        when (s) {
            0 -> PermissionScreen { launcher.launch(BT_PERMISSIONS) }
            1 -> {
                val mode = pairing ?: PairMode.First
                PairScreen(
                    mode = mode,
                    known = brushes.map { it.address }.toSet(),
                    nextNumber = brushes.size + 1,
                    onCancel = if (brush != null) ({ pairing = null }) else null,
                    onPick = { device, label ->
                        if (mode == PairMode.Replace) vm.replace(device) else vm.pair(device, label)
                        pairing = null
                    },
                )
            }
            else -> brush?.let { MainScreen(vm, it, brushes, onPair = { mode -> pairing = mode }) }
        }
    }
    }
    UpdateDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, brush: BrushInfo, brushes: List<BrushInfo>, onPair: (PairMode) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var open by remember { mutableStateOf<BrushSession?>(null) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Only a sync the user pulled for shows the pull indicator; automatic ones show in the sync chip.
    var pulled by remember { mutableStateOf(false) }

    // Pull fresh data every time the app opens.
    LaunchedEffect(Unit) { vm.syncOnOpen() }
    LaunchedEffect(sync) {
        if (sync !is SyncState.Running) pulled = false
        when (val s = sync) {
            is SyncState.Done -> if (s.added > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            is SyncState.Failed -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            else -> Unit
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, null) },
                        label = { Text(t.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (brushes.size > 1) BrushSwitcher(brushes, brush, vm::select, Modifier.padding(top = 8.dp))
            PullToRefreshBox(
                isRefreshing = pulled && sync is SyncState.Running,
                onRefresh = {
                    pulled = true
                    vm.syncNow()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 6 * dir }) togetherWith
                            (fadeOut(tween(160)) + slideOutHorizontally(tween(320)) { -it / 6 * dir })
                    },
                    label = "tabs",
                ) { t ->
                    when (t) {
                        Tab.Today -> TodayScreen(sessions, sync, brush, vm::syncNow) { open = it }
                        Tab.History -> HistoryScreen(sessions) { open = it }
                        Tab.Brush -> BrushScreen(vm, brush, sync, onPair)
                    }
                }
            }
        }
    }
    open?.let { s -> SessionSheet(s, sessions) { open = null } }
}
