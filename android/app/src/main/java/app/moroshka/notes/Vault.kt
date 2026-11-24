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
    private val backup = DocumentBackup(context)
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val trash = File(context.filesDir, "trash").apply { mkdirs() }
    private val bases = context.getSharedPreferences("revisions", Context.MODE_PRIVATE)
    private val validId = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private fun file(id: String): AtomicFile {
        require(validId.matches(id))
        return AtomicFile(File(dir, "$id.md"))
    }
    fun read(id: String) = Note(id, file(id).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
    fun all() = dir.listFiles().orEmpty().map { it.name.removeSuffix(".bak") }
        .filter { it.endsWith(".md") && validId.matches(it.removeSuffix(".md")) }
        .distinct().map { read(it.removeSuffix(".md")) }.sortedBy { it.title.lowercase() }
    fun recent(): List<Note> = all().sortedByDescending { File(dir, it.id + ".md").lastModified() }
    fun create(text: String, withBackup: Boolean = true) = save(Note(UUID.randomUUID().toString(), text), withBackup)
    fun save(note: Note, withBackup: Boolean = true): Note {
        val bytes = note.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "Предел одной заметки — 512 КиБ" }
        val file = file(note.id)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        if (withBackup) backup.save(note)
        return note
    }
    fun remove(ids: Collection<String>) {
        for (id in ids) {
            val note = read(id)
            backup.remove(note, bases.getString(id, null))
            val destination = File(trash, id + ".md")
            check(!destination.exists() && File(dir, id + ".md").renameTo(destination)) { "Не удалось переместить заметку в корзину" }
        }
    }
    fun restoreDeleted(note: Note, base: String?) {
        val destination = File(trash, note.id + ".md")
        if (!destination.exists()) {
            val file = AtomicFile(destination)
            val stream = file.startWrite()
            try {
                stream.write(note.text.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }
        }
        if (base != null) mark(note.id, base)
    }
    private fun mark(id: String, revision: String) {
        check(bases.edit().putString(id, revision).commit()) { "Не удалось сохранить ревизию" }
    }
    fun sync(server: String, token: String): Int {
        fun api(path: String, data: JSONObject? = null, method: String = "PUT"): JSONObject {
            val conn = URL(server + path).openConnection() as HttpsURLConnection
            try {
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
                check(code in 200..299) {
                    when (code) {
                        401 -> "Проверьте ключ сервера"
                        409 -> "Заметка изменилась во время обмена. Повторите синхронизацию."
                        else -> "Ответ сервера HTTP $code"
                    }
                }
                return JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            } finally { conn.disconnect() }
        }
        val snapshot = api("/api/notes")
        val list = snapshot.getJSONArray("notes")
        val remote = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }.toMutableMap()
        var conflicts = 0
        val deleted = snapshot.optJSONArray("deleted") ?: org.json.JSONArray()
        for (i in 0 until deleted.length()) {
            val item = deleted.getJSONObject(i)
            val id = item.getString("id")
            val local = all().firstOrNull { it.id == id } ?: continue
            val base = bases.getString(id, null)
            val localHash = MessageDigest.getInstance("SHA-256").digest(local.text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            if (localHash != base) {
                val copy = "# Конфликт · " + local.title + "\n\n" + local.text
                create(if (copy.toByteArray(Charsets.UTF_8).size <= 512 * 1024) copy else local.text)
                conflicts++
            }
            remove(listOf(id))
        }
        for (entry in trash.listFiles().orEmpty().filter { it.name.endsWith(".md") }) {
            val id = entry.name.removeSuffix(".md")
            val base = bases.getString(id, null)
            val other = remote[id]
            if (other != null && base != null && other.getString("revision") == base) {
                api("/api/notes/" + id, JSONObject().put("base", base), "DELETE")
                remote.remove(id)
            } else if (other != null) {
                save(Note(id, other.getString("text")))
                backup.clearDeletion(id)
                val archived = File(trash, id + "." + UUID.randomUUID() + ".archived")
                check(entry.renameTo(archived)) { "Не удалось сохранить версию в корзине" }
                mark(id, other.getString("revision"))
                remote.remove(id)
                conflicts++
            }
        }
        for (note in all()) {
            val base = bases.getString(note.id, null)
            val localHash = MessageDigest.getInstance("SHA-256").digest(note.text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val other = remote.remove(note.id)
            if (other != null) {
                val revision = other.getString("revision")
                if (localHash == revision) { mark(note.id, revision); continue }
                if (revision != base) {
                    if (localHash != base) {
                        val copy = "# Конфликт · " + note.title + "\n\n" + note.text
                        create(if (copy.toByteArray(Charsets.UTF_8).size <= 512 * 1024) copy else note.text)
                        conflicts++
                    }
                    save(Note(note.id, other.getString("text")))
                    mark(note.id, revision)
                    continue
                }
            }
            val result = api("/api/notes/" + note.id, JSONObject().put("text", note.text).put("base", base ?: JSONObject.NULL))
            mark(note.id, result.getString("revision"))
        }
        for ((id, obj) in remote) {
            save(Note(id, obj.getString("text")))
            mark(id, obj.getString("revision"))
        }
        return conflicts
    }
}
