package app.moroshka.notes

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

data class Note(val id: String, val text: String) {
    val title: String get() = Regex("(?m)^#\\s+(.+)$").find(text)?.groupValues?.get(1)?.trim() ?: "Без названия"
    val links: List<String> get() = Regex("\\[\\[([^]\\n]+)]]").findAll(text).map { it.groupValues[1].trim() }.toList()
}

class Vault(context: Context) {
    private companion object { val writeLock = SyncAccess.localLock }
    private val history = RevisionStore(File(context.filesDir, "history"))
    data class SavedVersion(val revision: String, val archivedAt: Long)
    data class TrashEntry(val name: String, val title: String, val modifiedAt: Long)
    private val backup = DocumentBackup(context)
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val trash = File(context.filesDir, "trash").apply { mkdirs() }
    private val bases = context.getSharedPreferences("revisions", Context.MODE_PRIVATE)
    private val validId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val trashName = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}(?:\\.md|\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.archived)")
    private fun file(id: String): AtomicFile {
        require(validId.matches(id))
        return AtomicFile(File(dir, "$id.md"))
    }
    fun read(id: String) = synchronized(writeLock) { Note(id, file(id).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }) }
    fun all() = synchronized(writeLock) {
        dir.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
            .filter { it.endsWith(".md") && validId.matches(it.removeSuffix(".md")) }
            .distinct().map { read(it.removeSuffix(".md")) }.sortedBy { it.title.lowercase() }
    }
    fun recent(): List<Note> = all().sortedByDescending { File(dir, it.id + ".md").lastModified() }
    fun create(text: String, withBackup: Boolean = true) = save(Note(UUID.randomUUID().toString(), text), withBackup)
    fun save(note: Note, withBackup: Boolean = true): Note = synchronized(writeLock) {
        val bytes = note.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "Предел одной заметки — 512 КиБ" }
        val file = file(note.id)
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            val previous = file.readFully()
            if (!previous.contentEquals(bytes)) history.record(note.id, previous)
            else {
                if (withBackup) backup.save(note)
                return@synchronized note
            }
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        if (withBackup) backup.save(note)
        note
    }

    fun versions(id: String): List<SavedVersion> = synchronized(writeLock) {
        history.list(id).map { SavedVersion(it.revision, it.archivedAt) }
    }
    fun versionText(id: String, revision: String): String = synchronized(writeLock) { history.read(id, revision) }
    fun trashEntries(): List<TrashEntry> = synchronized(writeLock) {
        trash.listFiles().orEmpty().filter { it.isFile && trashName.matches(it.name) }
            .map { TrashEntry(it.name, Note(it.name.substringBefore('.'), it.readText(Charsets.UTF_8)).title, it.lastModified()) }
            .sortedWith(compareByDescending<TrashEntry> { it.modifiedAt }.thenBy { it.name })
    }
    fun trashText(name: String): String = synchronized(writeLock) {
        require(trashName.matches(name)) { "Некорректное имя записи корзины" }
        File(trash, name).readText(Charsets.UTF_8)
    }

    /** Add-only import; previously deleted IDs are not reused. Retrying is safe. */
    fun restoreArchive(bytes: ByteArray): BackupArchive.Result = synchronized(writeLock) {
        restoreEntries(BackupArchive.decode(bytes), true)
    }

    /** External folders are archival evidence, not instructions to delete live notes. */
    fun restoreFolderNotes(notes: List<Note>): BackupArchive.Result = synchronized(writeLock) {
        require(notes.map { it.id }.distinct().size == notes.size)
        val entries = notes.map {
            require(validId.matches(it.id) && it.text.toByteArray(Charsets.UTF_8).size <= 512 * 1024)
            BackupArchive.Entry(it, KnowledgeApi.revision(it.text))
        }
        restoreEntries(entries, false)
    }

    private fun restoreEntries(entries: List<BackupArchive.Entry>, withBackup: Boolean): BackupArchive.Result {
        val current = all()
        val liveIds = current.map { it.id }.toSet()
        val deletedIds = trash.listFiles().orEmpty().filter { trashName.matches(it.name) }
            .map { it.name.substringBefore('.') }.filter { it !in liveIds }.toSet()
        val plan = BackupArchive.plan(entries, current, deletedIds)
        var added = 0
        for (note in plan.additions) {
            try {
                save(note, withBackup)
                added++
            } catch (error: Exception) {
                throw IllegalStateException("Импорт прерван после $added записей. Существующие заметки не заменены. " +
                    "Повторите импорт после устранения ошибки: " + (error.message ?: "Ошибка записи"), error)
            }
        }
        return BackupArchive.Result(added, plan.skipped, plan.conflicts)
    }

    fun remove(ids: Collection<String>): Unit = synchronized(writeLock) {
        for (id in ids) {
            val note = read(id)
            backup.remove(note, bases.getString(id, null))
            val destination = File(trash, id + ".md")
            check(!destination.exists() && File(dir, id + ".md").renameTo(destination)) { "Не удалось переместить заметку в корзину" }
        }
    }
    /** A deletion imported from a folder might belong to a different server or
     * an older backup. Keep its content, never its pending deletion or base. */
    fun restoreDeleted(note: Note): Unit = synchronized(writeLock) {
        require(validId.matches(note.id))
        archiveDeletedCopy(note)
    }

    private fun archiveDeletedCopy(note: Note) {
        val bytes = note.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "Запись корзины больше 512 КиБ" }
        val suffix = UUID.nameUUIDFromBytes(bytes).toString()
        val destination = File(trash, note.id + "." + suffix + ".archived")
        val file = AtomicFile(destination)
        if (destination.exists() || File(destination.path + ".bak").exists()) {
            check(file.readFully().contentEquals(bytes)) { "Конфликт архивной записи корзины" }
            return
        }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        check(file.readFully().contentEquals(bytes)) { "Не удалось проверить архивную запись корзины" }
    }

    /** Repair interrupted restores or legacy folder imports that left a live note
     * and a pending deletion under the same UUID. The live note wins locally;
     * the old deleted text remains recoverable, and cannot delete remote data. */
    private fun repairDeletionCollisions() {
        val live = all().map { it.id }.toSet()
        for (entry in trash.listFiles().orEmpty()) {
            val id = entry.name.removeSuffix(".md")
            if (!entry.name.endsWith(".md") || id !in live) continue
            archiveDeletedCopy(Note(id, entry.readText(Charsets.UTF_8)))
            backup.clearDeletion(id)
            check(entry.delete()) { "Не удалось завершить восстановление корзины" }
        }
    }
    private fun mark(id: String, revision: String) {
        check(bases.edit().putString(id, revision).commit()) { "Не удалось сохранить ревизию" }
    }
    /** Network exchange is serialized; local mutations are short, guarded checkpoints.
     * A background job loses its lease as soon as an activity becomes visible.
     */
    fun sync(server: String, token: String, permitted: () -> Boolean = { true },
             trackConnection: (HttpsURLConnection?) -> Unit = {}): Int {
        if (!SyncAccess.exchange.tryLock()) throw SyncDeferred()
        try { return exchange(server, token, permitted, trackConnection) }
        finally { SyncAccess.exchange.unlock() }
    }

    private fun exchange(server: String, token: String, permitted: () -> Boolean,
                         trackConnection: (HttpsURLConnection?) -> Unit): Int {
        fun checkpoint() { if (!permitted()) throw SyncDeferred() }
        fun <T> local(action: () -> T): T = synchronized(writeLock) { checkpoint(); action() }
        fun api(path: String, data: JSONObject? = null, method: String = "PUT"): JSONObject {
            checkpoint()
            val conn = URL(server + path).openConnection() as HttpsURLConnection
            try {
                trackConnection(conn)
                checkpoint()
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 30000
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (data != null) {
                    conn.requestMethod = method
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                if (code !in 200..299) throw SyncHttpError(code)
                val response = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                checkpoint()
                return response
            } finally { trackConnection(null); conn.disconnect() }
        }
        val known = local {
            repairDeletionCollisions()
            val ids = all().map { it.id } + trash.listFiles().orEmpty()
                .filter { it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") }
            ids.distinct().mapNotNull { id -> bases.getString(id, null)?.let { id to it } }.toMap()
        }
        val snapshot = SyncPull.snapshot(::api, known, ::checkpoint)
        val list = snapshot.getJSONArray("notes")
        val remote = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }.toMutableMap()
        var conflicts = 0
        val deleted = snapshot.optJSONArray("deleted") ?: org.json.JSONArray()
        for (i in 0 until deleted.length()) local {
            val item = deleted.getJSONObject(i)
            val id = item.getString("id")
            val current = all().firstOrNull { it.id == id } ?: return@local
            val base = bases.getString(id, null)
            val localHash = MessageDigest.getInstance("SHA-256").digest(current.text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (localHash != base) {
                val copy = "# Конфликт · " + current.title + "\n\n" + current.text
                create(if (copy.toByteArray(Charsets.UTF_8).size <= 512 * 1024) copy else current.text)
                conflicts++
            }
            remove(listOf(id))
        }
        val deletedEntries = local { trash.listFiles().orEmpty().filter { it.name.endsWith(".md") } }
        for (entry in deletedEntries) {
            val id = entry.name.removeSuffix(".md")
            val base = local { bases.getString(id, null) }
            val other = remote[id]
            if (other != null && base != null && other.getString("revision") == base) {
                api("/api/notes/" + id, JSONObject().put("base", base), "DELETE")
                remote.remove(id)
            } else if (other != null) local {
                save(Note(id, other.getString("text")))
                backup.clearDeletion(id)
                val archived = File(trash, id + "." + UUID.randomUUID() + ".archived")
                check(entry.renameTo(archived)) { "Не удалось сохранить версию в корзине" }
                mark(id, other.getString("revision"))
                remote.remove(id)
                conflicts++
            }
        }
        for (note in local { all() }) {
            val base = local { bases.getString(note.id, null) }
            val localHash = MessageDigest.getInstance("SHA-256").digest(note.text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val other = remote.remove(note.id)
            if (other != null) {
                val revision = other.getString("revision")
                if (localHash == revision) { local { mark(note.id, revision) }; continue }
                if (revision != base) {
                    local {
                        if (localHash != base) {
                            val copy = "# Конфликт · " + note.title + "\n\n" + note.text
                            create(if (copy.toByteArray(Charsets.UTF_8).size <= 512 * 1024) copy else note.text)
                            conflicts++
                        }
                        save(Note(note.id, other.getString("text")))
                        mark(note.id, revision)
                    }
                    continue
                }
            }
            // Missing UUID without a tombstone can mean server rollback or migration.
            // A null base is a conditional create, never an unconditional overwrite.
            val result = api("/api/notes/" + note.id, JSONObject().put("text", note.text)
                .put("base", if (other == null) JSONObject.NULL else base ?: JSONObject.NULL))
            local { mark(note.id, result.getString("revision")) }
        }
        for ((id, obj) in remote) local {
            save(Note(id, obj.getString("text")))
            mark(id, obj.getString("revision"))
        }
        return conflicts
    }
}
