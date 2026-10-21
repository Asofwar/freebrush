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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only, driven over adb (`am start -S -n dev.asofwar.freebrush/.MainActivity --es ...`):
     * `probe settings` runs the settings probe; `send 020900,030201` writes raw commands and logs the replies.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        if (intent.getStringExtra("probe") == "settings") vm.runProbe()
        intent.getStringExtra("send")?.let { list -> vm.sendRaw(list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }
        intent.getStringExtra("growth")?.toIntOrNull()?.let { vm.setGrowthTotal(it) }
        // `prog 231|2:30,16:30` sends programme 231 with gear:seconds steps.
        intent.getStringExtra("prog")?.let { spec ->
            val (num, steps) = spec.split('|')
            vm.sendProgramme(num.toInt(), steps.split(',').map { s -> s.split(':').let { BrushControls.Step(it[0].toInt(), it[1].toInt()) } }, "отладка $num")
        }
    }
}
