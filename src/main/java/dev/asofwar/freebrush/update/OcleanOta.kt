package dev.asofwar.freebrush.update

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Oclean's firmware server (oclean-re/firmware/README.md): which OTA file it offers a brush, and the file. It picks
 * the file by the brush's MAC, so a request tells Oclean which brush asks; the app calls it only on the user's tap.
 */
class OcleanOta(private val userAgent: String) {
    /** What the server offers: its newest [version] and the file, null when the brush already has it. */
    class Offer(val version: String, val url: String?)

    /** Asks for the firmware newer than [current]; pass an old version to get the newest file in any case. */
    fun offer(mac: String, model: String, current: String): Offer {
        val body = JSONObject()
            .put("mac", mac)
            .put("model", model)
            .put("ota", current)
            .put("img", "1.0.0.0")
            .put("voice", "1.0.0.0")
            .toString()
        val connection = open(ENDPOINT)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UpdateError("Сервер Oclean ответил HTTP $status")
            val json = connection.inputStream.use { it.readLimited(MAX_JSON) }.decodeToString()
            return parse(json)
        } catch (e: IOException) {
            throw UpdateError("Нет связи с сервером Oclean", offline = true, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /** The firmware file at [url], which must be on Oclean's HTTPS hosts. */
    fun download(url: String): ByteArray {
        require(isOcleanFile(url)) { "Сервер дал ссылку не на Oclean: $url" }
        val connection = open(url)
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw UpdateError("Загрузка прошивки: HTTP $status")
            return connection.inputStream.use { it.readLimited(MAX_FIRMWARE) }
        } catch (e: IOException) {
            throw UpdateError("Прошивка не загрузилась", offline = true, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = false
        setRequestProperty("User-Agent", userAgent)
    }

    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > limit) throw IOException("Response is too large")
        }
        return out.toByteArray()
    }

    companion object {
        private const val ENDPOINT = "https://hwapicore.oclean.com/OTA/v1/V1Brush/OTAUpGrade"
        private const val MAX_JSON = 64 * 1024
        private const val MAX_FIRMWARE = 2 * 1024 * 1024

        fun parse(json: String): Offer = try {
            val root = JSONObject(json)
            if (!root.optBoolean("state")) throw UpdateError("Сервер Oclean: ${root.optString("msg").ifBlank { "отказ" }}")
            val data = root.getJSONObject("data")
            val version = data.optString("newOTAVersion")
            val url = data.optString("otaFilePath").takeIf { it.isNotBlank() && it != "null" }
            Offer(version, url)
        } catch (e: JSONException) {
            throw UpdateError("Не удалось разобрать ответ Oclean", cause = e)
        }

        fun isOcleanFile(url: String): Boolean {
            val u = runCatching { URL(url) }.getOrNull() ?: return false
            return u.protocol == "https" && (u.host == "oclean.com" || u.host.endsWith(".oclean.com"))
        }

        /** A version the server accepts (.NET `Version`, at least `a.b`) from what the brush reports, e.g. `1.0.2.5`. */
        fun serverVersion(firmware: String?): String =
            firmware?.let { Regex("""\d+(\.\d+)+""").find(it)?.value } ?: "1.0.0.0"

        /** Compares dotted versions number by number; missing parts count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
