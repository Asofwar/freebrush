package dev.asofwar.freebrush.ble

import android.content.Context
import dev.asofwar.freebrush.data.BrushSession

class SyncResult(
    val model: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val battery: Int? = null,
    val headDays: Int? = null,
    val sessions: List<BrushSession> = emptyList(),
)

class OcleanClient(private val context: Context) {
    suspend fun sync(address: String, onStep: (String) -> Unit): SyncResult {
        onStep("Подключение к $address")
        return SyncResult()
    }
}
