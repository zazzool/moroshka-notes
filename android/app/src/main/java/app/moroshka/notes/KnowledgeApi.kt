package app.moroshka.notes

import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal class KnowledgeApi(private val server: String, private val key: String) {
    fun request(path: String, data: JSONObject? = null, method: String = if (data == null) "GET" else "POST"): JSONObject {
        require(path.startsWith("/api/ai/") || path.startsWith("/api/media/"))
        val connection = URL(server + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 12000
            connection.readTimeout = 55000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $key")
            if (data != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type","application/json")
                connection.outputStream.use { it.write(data.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(4096)
                val text = StringBuilder()
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 512 * 1024) { "Слишком большой ответ сервера" }
                    text.append(buffer,0,count)
                }
                runCatching { JSONObject(text.toString()) }.getOrNull()
            }
            if (code !in 200..299) error(response?.optString("error")?.takeIf { it.isNotBlank() } ?: "Ответ сервера HTTP $code")
            return requireNotNull(response) { "Некорректный ответ сервера" }
        } finally { connection.disconnect() }
    }
    companion object {
        fun revision(text: String) = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val labels = mapOf("note" to "Заметка", "task" to "Задача", "idea" to "Идея", "project" to "Проект", "reference" to "Материал")
        private val validTag = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,39}")
        fun metadata(text: String, heading: String?, tags: List<String>, category: String?): String {
            require(heading == null || (heading.isNotBlank() && heading.length <= 120 && heading.none { it.code < 32 }))
            require(tags.size <= 8 && tags.all { validTag.matches(it) })
            require(category == null || category in labels)
            var updated = if (heading == null) text else MarkdownMetadata.rename(text,heading)
            if(category!=null){
                val managed=labels.keys.map{"type-"+it}.toSet()
                updated=MarkdownMetadata.removeMatching(updated) {
                    val name=it.lowercase(java.util.Locale.ROOT)
                    name in managed && name!="type-"+category
                }
            }
            val accepted=if(category==null)tags else tags.filter{
                it.lowercase(java.util.Locale.ROOT) !in labels.keys.map { name -> "type-"+name }
            }
            updated=MarkdownMetadata.addAll(updated,accepted+listOfNotNull(category?.let{"type-"+it}))
            require(updated.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Предел одной заметки — 512 КиБ" }
            return updated
        }
        fun link(text:String,target:String,titles:List<String>,existing:List<String>):String {
            require(target.isNotBlank() && target.none { it == '[' || it == ']' || it.code < 32 }) { "Название нельзя использовать в вики-ссылке" }
            require(titles.count { it.trim().equals(target.trim(),true) } == 1) { "Название неоднозначно; сначала переименуйте заметку" }
            if (existing.any { it.trim().equals(target.trim(),true) }) return text
            val result = "[[" + target + "]]\n\n" + text
            require(result.toByteArray(Charsets.UTF_8).size <= 512 * 1024) { "Предел одной заметки — 512 КиБ" }
            return result
        }
    }
}
