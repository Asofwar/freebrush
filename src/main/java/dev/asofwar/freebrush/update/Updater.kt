package dev.asofwar.freebrush.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import dev.asofwar.freebrush.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /** Result of a check the user started, shown in the updates card. */
    data class Message(val text: String) : UpdateState
    data class Available(val release: Release) : UpdateState

    /** [progress] is 0..1, or null while the size is unknown. */
    data class Downloading(val release: Release, val progress: Float?) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Installing(val release: Release) : UpdateState

    /** A download or install that went wrong, shown in the update dialog. */
    data class Error(val text: String) : UpdateState
}

/**
 * In-app updates from GitHub Releases: a daily check (it can be turned off), the APK download and its install
 * through [PackageInstaller]. One per process, so the dialog, the settings card and the install result share it.
 *
 * The session asks Android to skip the confirmation. That works once FreeBrush is the app that installed itself;
 * the first update after an install from a browser or a file manager still shows the system prompt.
 */
class Updater private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val client = UpdateClient(BuildConfig.UPDATE_REPOSITORY, "FreeBrush/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cancelled = AtomicBoolean(false)
    private val dir = File(context.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _auto = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    /** Whether the app checks for updates by itself. Off means FreeBrush never goes online. */
    val auto: StateFlow<Boolean> = _auto.asStateFlow()

    init {
        // Nothing is in progress in a fresh process: an APK left here was installed already or abandoned.
        dir.listFiles()?.forEach { it.delete() }
    }

    fun setAuto(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
        _auto.value = enabled
    }

    /** Runs the automatic check when it is on and the last one was long enough ago. */
    fun autoCheck() {
        val last = prefs.getLong(KEY_LAST, 0)
        if (_auto.value && System.currentTimeMillis() - last >= AUTO_INTERVAL_MS) check(manual = false)
    }

    /** Check started by the user: reports the outcome even when there is nothing new. */
    fun check(manual: Boolean = true) {
        if (job?.isActive == true) return
        if (manual) _state.value = UpdateState.Checking
        job = scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.latest() } }
            val error = result.exceptionOrNull()
            // Offline: keep the old time, so the next start with a network checks again.
            if ((error as? UpdateError)?.offline != true) prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            val release = result.getOrNull()
            val current = Version.parse(BuildConfig.VERSION_NAME)
            _state.value = when {
                release == null -> {
                    Log.w(TAG, "Update check failed", error)
                    if (manual) UpdateState.Message(error?.message ?: "Не удалось проверить обновления") else UpdateState.Idle
                }
                current == null || Version.shouldOffer(current, release.version, manual) -> UpdateState.Available(release)
                manual -> UpdateState.Message("Установлена последняя версия, ${BuildConfig.VERSION_NAME}")
                else -> UpdateState.Idle
            }
        }
    }

    fun dismiss() {
        cancelled.set(true)
        _state.value = UpdateState.Idle
    }

    fun download(release: Release) {
        if (job?.isActive == true) return
        cancelled.set(false)
        _state.value = UpdateState.Downloading(release, null)
        job = scope.launch {
            val apk = File(dir, "FreeBrush-${release.version}.apk")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    var shown = -1
                    client.download(release, apk, cancelled::get) { done, total ->
                        val percent = if (total > 0) (done * 100 / total).toInt() else -1
                        if (percent != shown && !cancelled.get()) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, if (percent >= 0) percent / 100f else null)
                        }
                    }
                }
            }
            when {
                cancelled.get() -> {
                    apk.delete()
                    // A progress update may have landed after the user cancelled.
                    if (_state.value is UpdateState.Downloading) _state.value = UpdateState.Idle
                }
                result.isSuccess -> _state.value = UpdateState.Ready(release, apk)
                else -> {
                    Log.w(TAG, "Update download failed", result.exceptionOrNull())
                    _state.value = UpdateState.Error(result.exceptionOrNull()?.message ?: "Загрузка не удалась")
                }
            }
        }
    }

    /**
     * Installs the downloaded APK. Android first wants the user to allow installs from FreeBrush; until then
     * this opens that setting and the update stays ready.
     */
    fun install(activity: Context) {
        val ready = _state.value as? UpdateState.Ready ?: return
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            try {
                activity.startActivity(settings)
            } catch (e: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
            return
        }
        _state.value = UpdateState.Installing(ready.release)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { commit(ready.apk) } }
            result.exceptionOrNull()?.let {
                Log.w(TAG, "Update install failed", it)
                _state.value = UpdateState.Error("Установка не запустилась: ${it.message}")
            }
        }
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("FreeBrush.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer adds the status and, if needed, the confirmation intent.
                val result = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstallResultReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val installing = _state.value as? UpdateState.Installing ?: return
        val apk = File(dir, "FreeBrush-${installing.release.version}.apk")
        _state.value = when (status) {
            // On success Android replaces the process, so this is not seen. Cancelled: the update stays ready.
            PackageInstaller.STATUS_SUCCESS -> UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Ready(installing.release, apk)
            else -> UpdateState.Error("Android не установил обновление" + (message?.let { ": $it" } ?: ""))
        }
    }

    companion object {
        private const val TAG = "FreeBrushUpdate"
        private const val KEY_AUTO = "auto"
        private const val KEY_LAST = "last_check"
        private const val AUTO_INTERVAL_MS = 20L * 60 * 60 * 1000

        @Volatile
        private var instance: Updater? = null

        fun get(context: Context): Updater =
            instance ?: synchronized(this) { instance ?: Updater(context.applicationContext).also { instance = it } }
    }
}

/** Receives the outcome of the install session; shows the system confirmation when Android asks for one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            try {
                context.startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return)
            } catch (e: RuntimeException) {
                Updater.get(context).onInstallResult(PackageInstaller.STATUS_FAILURE, e.message)
            }
            return
        }
        Updater.get(context).onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
