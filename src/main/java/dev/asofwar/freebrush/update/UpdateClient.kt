package dev.asofwar.freebrush.update

import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A failed check or download, with a message for the user. [offline] means the network was not reachable. */
class UpdateError(message: String, val offline: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads the latest release of [repository] from the GitHub API and downloads its APK. Blocking: call it from a
 * background thread. Sends nothing but the request itself and a User-Agent naming the app version.
 */
class UpdateClient(private val repository: String, private val userAgent: String) {

    fun latest(): Release {
        val connection = open("https://api.github.com/repos/$repository/releases/latest")
        try {
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw UpdateError("Релизов пока нет")
            if (status != HttpURLConnection.HTTP_OK) throw UpdateError("GitHub ответил HTTP $status")
            val json = connection.inputStream.use { it.readLimited(MAX_JSON) }.decodeToString()
            val release = try {
                ReleaseParser.parse(json)
            } catch (e: JSONException) {
                throw UpdateError("Не удалось разобрать ответ GitHub", cause = e)
            }
            return release ?: throw UpdateError("В последнем релизе нет APK")
        } catch (e: IOException) {
            throw UpdateError("Нет связи с GitHub", offline = true, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads the APK of [release] to [target], checking its SHA-256 when the release publishes one.
     * A partial or mismatching file is deleted. [progress] gets (downloaded, total) bytes; total is 0 when unknown.
     */
    fun download(release: Release, target: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit) {
        val expected = ReleaseParser.parseSha256(fetchText(release.sha256Url))
            ?: throw UpdateError("Контрольная сумма релиза в неизвестном формате")
        val partial = File(target.path + ".part")
        var done = false
        val connection = open(release.apkUrl)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw UpdateError("Загрузка APK: HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        if (cancelled()) throw UpdateError("Загрузка отменена")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        progress(downloaded, total)
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != expected) {
                throw UpdateError("APK не совпал с контрольной суммой релиза")
            }
            if (!partial.renameTo(target)) throw UpdateError("Не удалось сохранить APK")
            done = true
        } catch (e: IOException) {
            throw UpdateError("Загрузка прервалась", offline = true, cause = e)
        } finally {
            connection.disconnect()
            if (!done) partial.delete()
        }
    }

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw UpdateError("GitHub ответил HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readLimited(MAX_JSON) }.decodeToString()
        } catch (e: IOException) {
            throw UpdateError("Нет связи с GitHub", offline = true, cause = e)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        // Release assets redirect from github.com to GitHub's download host; both are HTTPS.
        instanceFollowRedirects = true
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

    private companion object {
        const val MAX_JSON = 1 shl 20
    }
}
