package dev.asofwar.freebrush.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** What the app knows about one person's brush; survives restarts. */
data class BrushInfo(
    /** Stable key of this brush's record and history. Stays when the person replaces the brush itself. */
    val id: String,
    val address: String,
    /** Name the user gave, usually its owner's. */
    val label: String? = null,
    /** Bluetooth name of the brush. */
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
    /** Raw settings block last read from the brush (hex), see OcleanProtocol.Settings. */
    val settingsHex: String? = null,
    /**
     * Growth points FreeBrush has collected from the brush; written back to it as the base of its level. Null for a
     * brush added to the app: its base is unknown, so the brush keeps counting its level itself and nothing is
     * written, which keeps the level a reinstall or a second phone would otherwise reset.
     */
    val growthTotal: Int? = null,
    val lastSyncMillis: Long? = null,
) {
    val title: String get() = label ?: name ?: "Щётка"

    /** The same person's record moved to another physical brush: history, name and level stay. */
    fun replacedBy(address: String, name: String?) = BrushInfo(
        id = id,
        address = address,
        label = label,
        name = name,
        growthTotal = growthTotal,
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("address", address)
        put("label", label)
        put("name", name)
        put("model", model)
        put("firmware", firmware)
        put("hardware", hardware)
        put("battery", battery)
        put("headDays", headDays)
        put("headUses", headUses)
        put("headWear", headWear)
        put("headWearMax", headWearMax)
        put("settings", settingsHex)
        put("growth", growthTotal)
        put("lastSync", lastSyncMillis)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().substring(0, 8)

        fun fromJson(o: JSONObject): BrushInfo {
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key)
            fun int(key: String) = if (o.isNull(key)) null else o.optInt(key)
            return BrushInfo(
                id = o.getString("id"),
                address = o.getString("address"),
                label = str("label"),
                name = str("name"),
                model = str("model"),
                firmware = str("firmware"),
                hardware = str("hardware"),
                battery = int("battery"),
                headDays = int("headDays"),
                headUses = int("headUses"),
                headWear = int("headWear"),
                headWearMax = int("headWearMax"),
                settingsHex = str("settings"),
                growthTotal = if (o.isNull("growth")) null else o.optInt("growth"),
                lastSyncMillis = if (o.isNull("lastSync")) null else o.optLong("lastSync"),
            )
        }
    }
}

/**
 * The brushes the app knows, one per person, and which one the screens show. Stored as JSON in preferences;
 * each brush's history lives in its own file (see [SessionStore]).
 */
class BrushPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences("brushes", Context.MODE_PRIVATE)
    private var list: List<BrushInfo>
    private var currentId: String?

    init {
        migrateSingleBrush()
        list = read()
        currentId = prefs.getString(KEY_CURRENT, null)?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
    }

    private val _brushes = MutableStateFlow(list)
    val brushes: StateFlow<List<BrushInfo>> = _brushes

    private val _current = MutableStateFlow(list.firstOrNull { it.id == currentId })
    /** The brush the screens show. */
    val current: StateFlow<BrushInfo?> = _current

    fun get(id: String): BrushInfo? = list.firstOrNull { it.id == id }

    /** Adds a brush and shows it. */
    @Synchronized
    fun add(info: BrushInfo) {
        list = list + info
        currentId = info.id
        save()
    }

    /**
     * Applies [change] to the stored record of brush [id], if it is still there. [durable] writes it to disk before
     * returning, for what must survive the process being killed right after (the growth total the brush now uses).
     */
    @Synchronized
    fun update(id: String, durable: Boolean = false, change: (BrushInfo) -> BrushInfo) {
        if (list.none { it.id == id }) return
        list = list.map { if (it.id == id) change(it) else it }
        save(durable)
    }

    @Synchronized
    fun select(id: String) {
        if (list.none { it.id == id }) return
        currentId = id
        save()
    }

    /** Forgets brush [id]; its history file is the caller's to delete. */
    @Synchronized
    fun remove(id: String) {
        list = list.filterNot { it.id == id }
        if (currentId == id) currentId = list.firstOrNull()?.id
        save()
    }

    private fun read(): List<BrushInfo> {
        val text = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            List(arr.length()) { BrushInfo.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // Unreadable list: keep it aside and start over rather than crash on every start.
            prefs.edit().putString(KEY_LIST + "_corrupt", text).remove(KEY_LIST).commit()
            emptyList()
        }
    }

    private fun save(durable: Boolean = false) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val edit = prefs.edit().putString(KEY_LIST, arr.toString()).putString(KEY_CURRENT, currentId)
        if (durable) edit.commit() else edit.apply()
        _brushes.value = list
        _current.value = list.firstOrNull { it.id == currentId }
    }

    /** Versions up to 0.1 kept one brush in the "brush" preferences and its history in sessions.json. */
    private fun migrateSingleBrush() {
        val old = context.getSharedPreferences("brush", Context.MODE_PRIVATE)
        val address = old.getString("address", null) ?: return
        if (prefs.contains(KEY_LIST)) return
        fun int(key: String) = if (old.contains(key)) old.getInt(key, 0) else null
        val info = BrushInfo(
            // Derived from the address, so a migration cut short by a crash finds the same history file next time.
            id = address.replace(":", "").lowercase(),
            address = address,
            name = old.getString("name", null),
            model = old.getString("model", null),
            firmware = old.getString("firmware", null),
            hardware = old.getString("hardware", null),
            battery = int("battery"),
            headDays = int("headDays"),
            headUses = int("headUses"),
            headWear = int("headWear"),
            headWearMax = int("headWearMax"),
            settingsHex = old.getString("settings", null),
            growthTotal = old.getInt("growth", 0),
            lastSyncMillis = if (old.contains("lastSync")) old.getLong("lastSync", 0) else null,
        )
        File(context.filesDir, "sessions.json").takeIf { it.exists() }?.renameTo(SessionStore.fileFor(context, info.id))
        // commit(): the new list must be on disk before the old record goes.
        prefs.edit().putString(KEY_LIST, JSONArray().put(info.toJson()).toString()).putString(KEY_CURRENT, info.id).commit()
        old.edit().clear().commit()
    }

    private companion object {
        const val KEY_LIST = "list"
        const val KEY_CURRENT = "current"
    }
}
