package dev.asofwar.freebrush

import android.content.Intent
import dev.asofwar.freebrush.ble.BrushControls
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.asofwar.freebrush.ui.FreeBrushApp
import dev.asofwar.freebrush.ui.FreeBrushTheme
import dev.asofwar.freebrush.update.Updater

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FreeBrushTheme { FreeBrushApp(vm) }
        }
        handleDebugIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Updater.get(this).autoCheck()
        vm.onAppStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only, driven over adb (`am start -S -n dev.asofwar.freebrush/.MainActivity --es token <t> --es ...`,
     * `<t>` = `freebrush.debugToken` from local.properties; without it any app could send these to the exported activity):
     * `probe settings` runs the settings probe; `send 020900,030201` writes raw commands and logs the replies;
     * `update check` checks GitHub for a release now; `fakesession 1` adds a session that just ended (guided mode check).
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (BuildConfig.DEBUG_TOKEN.isEmpty() || intent.getStringExtra("token") != BuildConfig.DEBUG_TOKEN) return
        if (intent.getStringExtra("probe") == "settings") vm.runProbe()
        intent.getStringExtra("send")?.let { list -> vm.sendRaw(list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
        intent.getStringExtra("growth")?.toIntOrNull()?.let { vm.setGrowthTotal(it) }
        if (intent.getStringExtra("update") == "check") Updater.get(this).check()
        if (intent.getStringExtra("fakesession") != null) vm.addFakeSession()
        // `fakebrush Аня` adds a brush record with no device behind it, to try the switcher with one real brush.
        intent.getStringExtra("fakebrush")?.let { vm.addFakeBrush(it) }
        // `prog 231|2:30,16:30` sends programme 231 with gear:seconds steps.
        intent.getStringExtra("prog")?.let { spec ->
            runCatching {
                val (num, steps) = spec.split('|')
                num.toInt() to steps.split(',').map { s -> s.split(':').let { BrushControls.Step(it[0].toInt(), it[1].toInt()) } }
            }.onSuccess { (num, steps) -> vm.sendProgramme(num, steps, "отладка $num") }
        }
    }
}
