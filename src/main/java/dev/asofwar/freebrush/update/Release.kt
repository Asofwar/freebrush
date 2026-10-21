package dev.asofwar.freebrush.update

import org.json.JSONObject

/** An app version: `1.2.3` for a release, `1.2.3-dev+abc1234` for a build made between releases ([dev]). */
data class Version(val major: Int, val minor: Int, val patch: Int, val dev: Boolean = false) {
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""v?(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?""")

        fun parse(text: String): Version? = PATTERN.matchEntire(text.trim())?.destructured?.let { (major, minor, patch, pre) ->
            Version(major.toInt(), minor.toInt(), patch.toInt(), dev = pre.isNotEmpty())
        }

        /**
         * Whether [latest] should be offered to someone running [current]: only higher numbers. A development build
         * (`X.Y.Z-dev`) is built from release X.Y.Z or from newer code, so the release with the same numbers would be
         * a step back, possibly to an older data format.
         */
        fun shouldOffer(current: Version, latest: Version): Boolean =
            compareValuesBy(latest, current, Version::major, Version::minor, Version::patch) > 0
    }
}

/** A published release that carries an APK. */
data class Release(
    val version: Version,
    val tag: String,
    val notes: String,
    val page: String,
    val apkUrl: String,
    val apkSize: Long,
    /** `sha256sum` line for the APK; releases without one are not offered. */
    val sha256Url: String,
)

object ReleaseParser {
    /**
     * Reads the GitHub "latest release" JSON. Returns null for a draft or pre-release, a tag that is not a version,
     * or a release without an HTTPS APK asset and its `.sha256`. Throws [org.json.JSONException] when the JSON is
     * malformed.
     */
    fun parse(json: String): Release? {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.optString("tag_name")
        val version = Version.parse(tag) ?: return null
        val array = root.optJSONArray("assets") ?: return null
        val assets = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        val apk = assets.firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
        val apkUrl = apk.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        val sha256Url = assets.firstOrNull { it.optString("name") == apk.optString("name") + ".sha256" }
            ?.optString("browser_download_url")
            ?.takeIf { it.startsWith("https://") }
            ?: return null
        return Release(version, tag, root.optString("body"), root.optString("html_url"), apkUrl, apk.optLong("size"), sha256Url)
    }

    /** The digest from a `sha256sum` line (`<hex>  <file>`), or null when the text is not one. */
    fun parseSha256(text: String): String? =
        text.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
