package dev.asofwar.freebrush.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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

    val screen = when {
        !granted -> 0
        brush == null -> 1
        else -> 2
    }
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "root") { s ->
        when (s) {
            0 -> PermissionScreen { launcher.launch(BT_PERMISSIONS) }
            1 -> PairScreen(onPick = vm::pair)
            else -> brush?.let { MainScreen(vm, it) }
        }
    }
    UpdateDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, brush: BrushInfo) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var open by remember { mutableStateOf<BrushSession?>(null) }
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val sync by vm.sync.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // Only a sync the user pulled for shows the pull indicator; automatic ones show in the sync chip.
    var pulled by remember { mutableStateOf(false) }

    // Pull fresh data every time the app opens.
    LaunchedEffect(brush.address) { vm.syncNow() }
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
                    )
                }
            }
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = pulled && sync is SyncState.Running,
            onRefresh = {
                pulled = true
                vm.syncNow()
            },
            modifier = Modifier.padding(pad).fillMaxSize(),
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
                    Tab.Brush -> BrushScreen(vm, brush, sync)
                }
            }
        }
    }
    open?.let { s -> SessionSheet(s, sessions) { open = null } }
}
