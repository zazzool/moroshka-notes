package app.moroshka.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.security.MessageDigest
import java.util.UUID

/**
 * Each saved version gets a new document. An interrupted write never overwrites
 * the preceding version. Files are regular Markdown and survive app uninstall.
 */
class DocumentBackup(private val context: Context) {
    private val prefs = context.getSharedPreferences("document_backup", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private val pattern = Regex("^([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})_([0-9]{13})_[0-9a-f]{8}_([0-9a-f]{64})\\.md$")
    val configured: Boolean get() = prefs.getString("tree", null) != null
    private fun tree(): Uri = Uri.parse(requireNotNull(prefs.getString("tree", null)))

    fun connect(uri: Uri, vault: Vault) {
        resolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Read before writing anything, so existing documents are never silently replaced.
        val stored = versions(uri)
        val removed = removedItems(uri)
        for ((id, value) in removed) {
            vault.restoreDeleted(Note(id, value.getString("text")))
        }
        val live = stored.filter { it.id !in removed }.groupBy { it.id }.map { (id, entries) ->
            val latest = entries.maxWith(compareBy<Version> { it.time }.thenBy { it.text })
            Note(id, latest.text)
        }
        vault.restoreFolderNotes(live)
        check(prefs.edit().putString("tree", uri.toString()).commit())
        for (note in vault.all()) save(note)
    }

    private data class Version(val id: String, val time: Long, val text: String)
    private fun versions(uri: Uri): List<Version> {
        val result = mutableListOf<Version>()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        requireNotNull(resolver.query(children, columns, null, null, null)).use { cursor ->
            while (cursor.moveToNext()) {
                val match = pattern.matchEntire(cursor.getString(1) ?: continue) ?: continue
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                val bytes = requireNotNull(resolver.openInputStream(document)).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 512 * 1024) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                if (bytes.size > 512 * 1024) continue
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                if (hash == match.groupValues[3]) result.add(Version(match.groupValues[1], match.groupValues[2].toLong(), bytes.toString(Charsets.UTF_8)))
            }
        }
        return result
    }

    private fun removedItems(uri: Uri): Map<String, org.json.JSONObject> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val result = mutableMapOf<String, org.json.JSONObject>()
        requireNotNull(resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)).use { cursor ->
            val expression = Regex("^([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\\.deleted\\.json$")
            while (cursor.moveToNext()) expression.matchEntire(cursor.getString(0) ?: "")?.let {
                val document = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(1))
                val value = requireNotNull(resolver.openInputStream(document)).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 4 * 1024 * 1024) { "Слишком большая метка удаления в папке" }
                        output.write(buffer, 0, count)
                    }
                    val decoder = Charsets.UTF_8.newDecoder()
                    val text = decoder.decode(java.nio.ByteBuffer.wrap(output.toByteArray())).toString()
                    val tokenizer = org.json.JSONTokener(text)
                    val data = tokenizer.nextValue() as? org.json.JSONObject
                        ?: error("Повреждена метка удаления в папке")
                    require(tokenizer.nextClean() == '\u0000') { "Повреждена метка удаления в папке" }
                    val body = data.opt("text") as? String ?: error("В метке удаления нет текста")
                    require(body.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Слишком большая удалённая заметка" }
                    data
                }
                result[it.groupValues[1]] = value
            }
        }
        return result
    }
    fun clearDeletion(id: String) {
        if (!configured) return
        val uri = tree()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID)
        requireNotNull(resolver.query(children, columns, null, null, null)).use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == id + ".deleted.json") {
                    val document = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(1))
                    check(DocumentsContract.deleteDocument(resolver, document)) { "Не удалось обновить метку удаления" }
                }
            }
        }
    }
    fun remove(note: Note, base: String?) {
        if (!configured) return
        val uri = tree()
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        if (note.id in removedItems(uri)) return
        val document = requireNotNull(DocumentsContract.createDocument(resolver, root, "application/json", note.id + ".deleted.json"))
        requireNotNull(resolver.openOutputStream(document, "w")).use { it.write(org.json.JSONObject().put("text", note.text).put("base", base ?: org.json.JSONObject.NULL).toString().toByteArray()) }
    }
    fun save(note: Note) {
        if (!configured) return
        val uri = tree()
        val bytes = note.text.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val name = "${note.id}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${hash}.md"
        val doc = requireNotNull(DocumentsContract.createDocument(resolver, root, "text/markdown", name))
        requireNotNull(resolver.openOutputStream(doc, "w")).use { it.write(bytes); it.flush() }
    }
}
