package dev.asofwar.freebrush.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the app knows about the paired brush; survives restarts. */
data class BrushInfo(
    val address: String,
    val name: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val hardware: String? = null,
    val battery: Int? = null,
    /** Days and sessions since the brush head was last reset, from the brush's own counters. */
    val headDays: Int? = null,
    val headUses: Int? = null,
    /** Brush-head wear counter and its limit as the brush reports them. */
    val headWear: Int? = null,
    val headWearMax: Int? = null,
    val lastSyncMillis: Long? = null,
)

class BrushPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("brush", Context.MODE_PRIVATE)
    private val _brush = MutableStateFlow(read())
    val brush: StateFlow<BrushInfo?> = _brush

    private fun read(): BrushInfo? {
        val address = prefs.getString("address", null) ?: return null
        fun int(key: String) = if (prefs.contains(key)) prefs.getInt(key, 0) else null
        return BrushInfo(
            address = address,
            name = prefs.getString("name", null),
            model = prefs.getString("model", null),
            firmware = prefs.getString("firmware", null),
            hardware = prefs.getString("hardware", null),
            battery = int("battery"),
            headDays = int("headDays"),
            headUses = int("headUses"),
            headWear = int("headWear"),
            headWearMax = int("headWearMax"),
            lastSyncMillis = if (prefs.contains("lastSync")) prefs.getLong("lastSync", 0) else null,
        )
    }

    fun save(info: BrushInfo?) {
        prefs.edit().clear().apply {
            if (info != null) {
                putString("address", info.address)
                info.name?.let { putString("name", it) }
                info.model?.let { putString("model", it) }
                info.firmware?.let { putString("firmware", it) }
                info.hardware?.let { putString("hardware", it) }
                info.battery?.let { putInt("battery", it) }
                info.headDays?.let { putInt("headDays", it) }
                info.headUses?.let { putInt("headUses", it) }
                info.headWear?.let { putInt("headWear", it) }
                info.headWearMax?.let { putInt("headWearMax", it) }
                info.lastSyncMillis?.let { putLong("lastSync", it) }
            }
        }.apply()
        _brush.value = info
    }
}
