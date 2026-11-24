package app.moroshka.notes

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/** Opt-in, OS-scheduled sync. No credentials are passed in JobInfo extras. */
internal object BackgroundSync {
    const val periodicId = 18401
    const val onceId = 18402
    private const val enabledKey = "auto_sync"
    private const val statusKey = "auto_sync_status"
    private const val lastKey = "auto_sync_last"
    private fun prefs(context: Context) = context.getSharedPreferences("connection", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(enabledKey, false)
    fun credentials(context: Context): Pair<String, String>? {
        val p = prefs(context)
        val url = p.getString("url", "").orEmpty()
        val key = p.getString("key", "").orEmpty()
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null ||
            uri.rawQuery != null || uri.rawFragment != null || !uri.path.isNullOrEmpty() || key.isBlank()) return null
        return url to key
    }
    fun status(context: Context): String {
        val p = prefs(context)
        val status = p.getString(statusKey, "Ожидает синхронизации").orEmpty()
        val last = p.getLong(lastKey, 0)
        return if (last > 0) status + "\nПоследний успешный обмен: " +
            java.text.DateFormat.getDateTimeInstance().format(java.util.Date(last)) else status
    }
    fun record(context: Context, message: String, success: Boolean = false) {
        val edit = prefs(context).edit().putString(statusKey, message)
        if (success) edit.putLong(lastKey, System.currentTimeMillis())
        edit.apply()
    }
    fun configure(context: Context, value: Boolean) {
        if (value) require(credentials(context) != null) { "Сначала подключите сервер." }
        check(prefs(context).edit().putBoolean(enabledKey, value).commit()) { "Не удалось сохранить настройку" }
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!value) {
            scheduler.cancel(periodicId); scheduler.cancel(onceId)
            return
        }
        record(context, "Ожидает синхронизации")
        try { schedule(context) } catch (error: Exception) {
            prefs(context).edit().putBoolean(enabledKey, false).commit()
            scheduler.cancel(periodicId); scheduler.cancel(onceId)
            throw error
        }
    }
    fun schedule(context: Context) {
        if (!enabled(context) || credentials(context) == null) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        fun builder(id: Int) = JobInfo.Builder(id, ComponentName(context, BackgroundSyncJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setPersisted(true)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if (scheduler.getPendingJob(periodicId) == null) {
            check(scheduler.schedule(builder(periodicId).setPeriodic(30 * 60_000L).build()) == JobScheduler.RESULT_SUCCESS) {
                "Не удалось запланировать периодическую синхронизацию"
            }
        }
        if (scheduler.getPendingJob(onceId) == null) {
            check(scheduler.schedule(builder(onceId).setMinimumLatency(5_000L).build()) == JobScheduler.RESULT_SUCCESS) {
                "Не удалось запланировать синхронизацию"
            }
        }
    }
}

class BackgroundSyncJob : JobService() {
    private class Run(val params: JobParameters) {
        val cancelled = AtomicBoolean(false)
        private val connection = AtomicReference<HttpsURLConnection?>()
        fun track(conn: HttpsURLConnection?) {
            connection.set(conn)
            if (cancelled.get()) connection.getAndSet(null)?.disconnect()
        }
        fun cancel() {
            cancelled.set(true)
            connection.getAndSet(null)?.disconnect()
        }
    }
    private val runs = mutableMapOf<Int, Run>() // main thread only
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        if (!BackgroundSync.enabled(this)) return false
        val credentials = BackgroundSync.credentials(this) ?: return false
        val lease = SyncAccess.backgroundLease() ?: return false
        val run = Run(params)
        runs.put(params.jobId, run)?.cancel()
        worker.execute {
            fun allowed() = !run.cancelled.get() && SyncAccess.backgroundAllowed(lease) &&
                BackgroundSync.enabled(this) && BackgroundSync.credentials(this) == credentials
            var retry = false
            try {
                if (allowed()) {
                    val conflicts = Vault(applicationContext).sync(credentials.first, credentials.second, ::allowed, run::track)
                    if (allowed()) BackgroundSync.record(this,
                        if (conflicts == 0) "Синхронизировано" else "Сохранены конфликтные копии: $conflicts", true)
                }
            } catch (error: Exception) {
                if (allowed()) {
                    retry = error !is SyncHttpError || error.code != 401
                    BackgroundSync.record(this, if (error is SyncHttpError && error.code == 401)
                        "Проверьте ключ сервера" else "Обмен отложен; локальные заметки сохранены")
                }
            } finally {
                main.post {
                    if (runs[params.jobId] === run) {
                        runs.remove(params.jobId)
                        if (!run.cancelled.get()) jobFinished(params, retry)
                    }
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        runs.remove(params.jobId)?.cancel()
        return BackgroundSync.enabled(this)
    }
    override fun onDestroy() {
        runs.values.forEach { it.cancel() }
        runs.clear()
        worker.shutdownNow()
        super.onDestroy()
    }
}
