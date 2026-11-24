package app.moroshka.notes

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Portable plaintext archive, interoperable with web/backup.mjs. */
object BackupArchive {
    const val maxBytes = 32 * 1024 * 1024
    private const val maxNoteBytes = 512 * 1024
    private const val maxNotes = 10000
    private const val format = "moroshka-notes-backup"
    private val idPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    data class Entry(val note: Note, val sha256: String)
    data class Plan(val additions: List<Note>, val skipped: Int, val conflicts: Int)
    data class Result(val added: Int, val skipped: Int, val conflicts: Int)

    private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun validate(note: Note, ids: MutableSet<String>) {
        require(idPattern.matches(note.id)) { "Некорректный UUID заметки в архиве" }
        require(ids.add(note.id)) { "В архиве повторяется UUID заметки" }
        require(note.text.toByteArray(Charsets.UTF_8).size <= maxNoteBytes) { "Заметка в архиве больше 512 КиБ" }
    }

    fun encode(notes: List<Note>): ByteArray {
        require(notes.size <= maxNotes) { "В архиве не более 10 000 заметок" }
        val ids = mutableSetOf<String>()
        val entries = JSONArray()
        for (note in notes) {
            validate(note, ids)
            entries.put(JSONObject().put("id", note.id).put("text", note.text).put("sha256", digest(note.text)))
        }
        return JSONObject().put("format", format).put("version", 1).put("notes", entries)
            .toString().toByteArray(Charsets.UTF_8).also {
                require(it.size <= maxBytes) { "Архив больше 32 МиБ" }
            }
    }

    fun read(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= maxBytes) { "Архив больше 32 МиБ" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Entry> {
        require(bytes.size <= maxBytes) { "Архив больше 32 МиБ" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        val tokenizer = JSONTokener(text)
        val data = tokenizer.nextValue() as? JSONObject ?: error("Неподдерживаемый формат архива Морошки")
        require(tokenizer.nextClean() == '\u0000' && data.opt("format") == format &&
            data.opt("version") == 1) { "Неподдерживаемый формат архива Морошки" }
        val array = data.optJSONArray("notes") ?: error("В архиве нет списка заметок")
        require(array.length() <= maxNotes) { "В архиве не более 10 000 заметок" }
        val ids = mutableSetOf<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.opt("id") as? String ?: error("Некорректный UUID заметки")
            val body = item.opt("text") as? String ?: error("Некорректный текст заметки")
            val note = Note(id, body)
            validate(note, ids)
            val hash = digest(body)
            require(item.opt("sha256") == hash) { "Контрольная сумма заметки не совпадает: $id" }
            Entry(note, hash)
        }
    }

    private fun conflictId(entry: Entry, attempt: Int): String {
        val h = digest("moroshka-backup-v1\n" + entry.note.id + "\n" + entry.sha256 + "\n" + attempt)
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-8" + h.substring(13, 16) +
            "-8" + h.substring(17, 20) + "-" + h.substring(20, 32)
    }

    fun plan(verified: List<Entry>, current: List<Note>, blocked: Set<String> = emptySet()): Plan {
        val known = current.associateBy { it.id }.toMutableMap()
        val reserved = verified.map { it.note.id }.toSet()
        val additions = mutableListOf<Note>()
        var skipped = 0
        var conflicts = 0
        for (entry in verified) {
            val note = entry.note
            var id = note.id
            if (id !in blocked && known[id]?.text == note.text) { skipped++; continue }
            if (id in known || id in blocked) {
                id = (0 until 100).asSequence().map { conflictId(entry, it) }.firstOrNull {
                    it !in reserved && it !in blocked && (it !in known || known[it]?.text == note.text)
                } ?: error("Не удалось выделить идентификатор конфликтной копии")
                if (id in known) { skipped++; continue }
                conflicts++
            }
            val restored = note.copy(id = id)
            known[id] = restored
            additions.add(restored)
        }
        return Plan(additions, skipped, conflicts)
    }
}
