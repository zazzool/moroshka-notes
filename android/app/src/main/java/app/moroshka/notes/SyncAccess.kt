package app.moroshka.notes

import java.util.concurrent.locks.ReentrantLock

/** One network exchange at a time, plus short local-only critical sections.
 * A foreground activity invalidates any previous background lease permanently.
 * Never perform network I/O under localLock.
 */
internal object SyncAccess {
    val localLock = Any()
    val exchange = ReentrantLock()
    private val owners = mutableSetOf<Any>()
    @Volatile private var foregroundCount = 0
    private var generation = 0L

    fun enter(owner: Any) = synchronized(localLock) {
        if (owners.add(owner)) generation++
        foregroundCount = owners.size
        Unit
    }
    fun leave(owner: Any) = synchronized(localLock) { owners.remove(owner); foregroundCount = owners.size; Unit }
    fun epoch(): Long = synchronized(localLock) { generation }
    fun sameEpoch(epoch: Long): Boolean = synchronized(localLock) { generation == epoch }
    fun backgroundLease(): Long? {
        if (foregroundCount > 0) return null
        return synchronized(localLock) { if (owners.isEmpty()) generation else null }
    }
    fun backgroundAllowed(lease: Long): Boolean = synchronized(localLock) {
        owners.isEmpty() && generation == lease
    }
}
internal class SyncDeferred : IllegalStateException("Другой обмен выполняется или отложен. Повторите синхронизацию.")
internal class SyncHttpError(val code: Int) : IllegalStateException(when (code) {
    401 -> "Проверьте ключ сервера"
    409 -> "Заметка изменилась во время обмена. Повторите синхронизацию."
    else -> "Ответ сервера HTTP $code"
})
