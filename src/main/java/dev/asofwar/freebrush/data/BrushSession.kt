package dev.asofwar.freebrush.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * One brushing session as reported by the brush.
 *
 * [raw] keeps the undecoded record bytes (hex), so history can be re-decoded when the decoder improves.
 * Fields the brush did not send are null or empty: a session seen only in the short "no new records"
 * header has no score, zones or pressure.
 */
data class BrushSession(
    /** Session start, seconds since the epoch. Also the identity used for de-duplication. */
    val epochSec: Long,
    /** UTC offset the brush stamped on the record, in minutes; null when the record did not carry one. */
    val tzOffsetMin: Int? = null,
    /** Time actually brushed. */
    val durationSec: Int,
    /** Length of the programme that was running. */
    val plannedSec: Int? = null,
    val score: Int? = null,
    /** Programme (scheme) number: below 230 a preset, 230 and above a custom one. */
    val schemeId: Int? = null,
    /** Relative brushing time per zone, in [Zones.ALL] order. */
    val zones: List<Int> = emptyList(),
    /** Share of time in five pressure buckets, light to heavy, roughly summing to 100. */
    val pressure: List<Int> = emptyList(),
    val raw: String = "",
) {
    val key: Long get() = epochSec

    /** Start time in the phone's current time zone. */
    val time: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault())

    val isComplete: Boolean get() = score != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("epoch", epochSec)
        tzOffsetMin?.let { put("tz", it) }
        put("duration", durationSec)
        plannedSec?.let { put("planned", it) }
        score?.let { put("score", it) }
        schemeId?.let { put("scheme", it) }
        put("zones", JSONArray(zones))
        put("pressure", JSONArray(pressure))
        put("raw", raw)
    }

    companion object {
        fun fromJson(o: JSONObject): BrushSession = BrushSession(
            epochSec = o.getLong("epoch"),
            tzOffsetMin = o.optIntOrNull("tz"),
            durationSec = o.getInt("duration"),
            plannedSec = o.optIntOrNull("planned"),
            score = o.optIntOrNull("score"),
            schemeId = o.optIntOrNull("scheme"),
            zones = o.optJSONArray("zones").toIntList(),
            pressure = o.optJSONArray("pressure").toIntList(),
            raw = o.optString("raw"),
        )

        private fun JSONObject.optIntOrNull(name: String): Int? = if (has(name)) getInt(name) else null
        private fun JSONArray?.toIntList(): List<Int> =
            if (this == null) emptyList() else List(length()) { getInt(it) }
    }
}

/**
 * Mouth zones in the order the brush reports them.
 *
 * Provisional: the order follows the community protocol reference, which maps zone k to the official
 * app's area k+1 (upper-left outer, upper-left inner, lower-left outer, ...). It still has to be checked
 * against a real brush.
 */
object Zones {
    val ALL = listOf(
        "Верх слева снаружи",
        "Верх слева внутри",
        "Низ слева снаружи",
        "Низ слева внутри",
        "Верх справа снаружи",
        "Верх справа внутри",
        "Низ справа снаружи",
        "Низ справа внутри",
    )
}
