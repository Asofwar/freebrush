package dev.asofwar.freebrush.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime

/**
 * One brushing session as reported by the brush.
 *
 * [raw] keeps the undecoded record bytes (hex), so history can be re-decoded when the parser improves.
 * Fields the brush did not send are null.
 */
data class BrushSession(
    val time: LocalDateTime,
    val durationSec: Int,
    val validDurationSec: Int? = null,
    val score: Int? = null,
    val schemeId: Int? = null,
    /** Brushing time or pressure per zone, in [Zones.ALL] order. Empty when not reported. */
    val zones: List<Int> = emptyList(),
    val pressure: List<Int> = emptyList(),
    val overPressureCount: Int? = null,
    val raw: String = "",
) {
    /** Identity for de-duplication: the brush stamps every session with its start time. */
    val key: String get() = time.toString()

    fun toJson(): JSONObject = JSONObject().apply {
        put("time", time.toString())
        put("duration", durationSec)
        validDurationSec?.let { put("validDuration", it) }
        score?.let { put("score", it) }
        schemeId?.let { put("scheme", it) }
        put("zones", JSONArray(zones))
        put("pressure", JSONArray(pressure))
        overPressureCount?.let { put("overPressure", it) }
        put("raw", raw)
    }

    companion object {
        fun fromJson(o: JSONObject): BrushSession = BrushSession(
            time = LocalDateTime.parse(o.getString("time")),
            durationSec = o.getInt("duration"),
            validDurationSec = o.optIntOrNull("validDuration"),
            score = o.optIntOrNull("score"),
            schemeId = o.optIntOrNull("scheme"),
            zones = o.optJSONArray("zones").toIntList(),
            pressure = o.optJSONArray("pressure").toIntList(),
            overPressureCount = o.optIntOrNull("overPressure"),
            raw = o.optString("raw"),
        )

        private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null
        private fun JSONArray?.toIntList(): List<Int> =
            if (this == null) emptyList() else List(length()) { getInt(it) }
    }
}

/** Mouth zones in the order the brush reports them (BrushAreaType in the official app). */
object Zones {
    val ALL = listOf(
        "Верх слева снаружи",
        "Верх спереди снаружи",
        "Верх справа снаружи",
        "Верх справа внутри",
        "Низ слева снаружи",
        "Низ спереди снаружи",
        "Низ справа снаружи",
        "Низ справа внутри",
    )
}
