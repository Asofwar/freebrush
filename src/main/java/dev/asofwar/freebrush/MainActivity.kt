package dev.asofwar.freebrush

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
    }
}
