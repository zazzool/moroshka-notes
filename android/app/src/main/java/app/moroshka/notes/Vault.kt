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
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
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
    fun create(text: String) = save(Note(UUID.randomUUID().toString(), text))
    fun save(note: Note): Note {
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
        return note
    }
    private fun mark(id: String, revision: String) {
        check(bases.edit().putString(id, revision).commit()) { "Не удалось сохранить ревизию" }
    }
    fun sync(server: String, token: String): Int {
        fun api(path: String, data: JSONObject? = null): JSONObject {
            val conn = URL(server + path).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 12000
                conn.readTimeout = 30000
                conn.setRequestProperty("Authorization", "Bearer $token")
                if (data != null) {
                    conn.requestMethod = "PUT"
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
        val list = api("/api/notes").getJSONArray("notes")
        val remote = (0 until list.length()).map { list.getJSONObject(it) }.associateBy { it.getString("id") }.toMutableMap()
        var conflicts = 0
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
