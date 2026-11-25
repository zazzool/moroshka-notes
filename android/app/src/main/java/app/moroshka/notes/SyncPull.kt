package app.moroshka.notes

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Fetch metadata first; never substitute dirty local text for a server revision. */
internal object SyncPull {
    private val idPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val revisionPattern = Regex("[0-9a-f]{64}")
    private const val INVALID = "Некорректный ответ сервера при синхронизации"
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map { array.getJSONObject(it) }

    fun snapshot(api: (String, JSONObject?, String) -> JSONObject,
                 known: Map<String, String>, checkpoint: () -> Unit): JSONObject {
        checkpoint()
        val manifest = try { api("/api/sync/manifest", null, "GET") }
        catch (error: SyncHttpError) {
            if (error.code != 404) throw error
            checkpoint()
            return api("/api/notes", null, "GET")
        }
        checkpoint()
        check(manifest.optInt("version", -1) == 1) { INVALID }
        val notes = objects(manifest.getJSONArray("notes"))
        val deleted = manifest.getJSONArray("deleted")
        val ids = mutableSetOf<String>()
        for (item in notes + objects(deleted)) {
            val id = item.opt("id") as? String
            val revision = item.opt("revision") as? String
            check(id != null && idPattern.matches(id) && revision != null &&
                revisionPattern.matches(revision) && ids.add(id)) { INVALID }
        }
        val entries = notes.associate { item ->
            val id = item.getString("id")
            id to JSONObject().put("id",id).put("revision",item.getString("revision")).apply {
                val time=item.optLong("modifiedAt",0L)
                if(time>0)put("modifiedAt",time)
            }
        }.toMutableMap()
        val wanted = entries.values.filter { known[it.getString("id")] != it.getString("revision") }
        for (batch in wanted.chunked(8)) {
            checkpoint()
            val expected = batch.associate { it.getString("id") to it.getString("revision") }.toMutableMap()
            val response = api("/api/sync/read", JSONObject().put("notes",JSONArray(batch.map{JSONObject().put("id",it.getString("id")).put("revision",it.getString("revision"))})), "POST")
            checkpoint()
            val received = objects(response.getJSONArray("notes"))
            check(received.size == batch.size) { INVALID }
            for (note in received) {
                val id = note.opt("id") as? String ?: error(INVALID)
                val revision = note.opt("revision") as? String ?: error(INVALID)
                val text = note.opt("text") as? String ?: error(INVALID)
                val bytes = text.toByteArray(Charsets.UTF_8)
                check(expected[id] == revision && bytes.size <= 512 * 1024 &&
                    digest(bytes) == revision) { INVALID }
                checkpoint()
                expected.remove(id)
                entries.getValue(id).put("text",text)
            }
            check(expected.isEmpty()) { INVALID }
        }
        return JSONObject().put("notes",JSONArray(entries.values.toList())).put("deleted",deleted)
    }
}
