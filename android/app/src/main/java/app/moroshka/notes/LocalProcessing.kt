package app.moroshka.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

/** Execution policy belongs to the current client, not to a note or archive. */
internal class ProcessingPrefs(context:Context) {
    private val prefs=context.getSharedPreferences("processing_v1",Context.MODE_PRIVATE)
    companion object {
        val operations=linkedMapOf(
            "metadata" to "Заголовки, теги и тип", "summary" to "Краткое содержание",
            "search" to "Расширенный поиск", "links" to "Предложения связей", "ocr" to "Распознавание фото")
        val modes=listOf("local","server","off")
        val labels=listOf("На устройстве","Сервер · OpenRouter","Выключено")
    }
    fun mode(op:String):String {
        require(op in operations)
        return prefs.getString(op,"local")?.takeIf{it in modes}?:"local"
    }
    fun save(values:Map<String,String>){
        require(values.keys==operations.keys&&values.values.all{it in modes})
        val edit=prefs.edit()
        values.forEach{(op,mode)->edit.putString(op,mode)}
        check(edit.commit()){"Не удалось сохранить настройки обработки"}
    }
}

/** Small, deterministic offline methods. These are not a generative LLM or
 * embedding model. Never sends network requests or mutates the vault. */
internal object LocalProcessing {
    private val words=Regex("[\\p{L}\\p{N}]{3,40}")
    private val stop=setOf("это","как","что","для","или","при","так","уже","ещё","еще","его","она","они","если","есть","был","быть","все","всё","этот","этой","который","которые","можно","нужно","надо","более","также","the","and","for","with","this","that","are","was","from","have","not","but","you","your","can","will","into")
    private fun clean(text:String)=MarkdownMetadata.proseLines(PhotoStore.strip(text)).map{it.second.trim()}
        .filter{it.isNotBlank()&&!Regex("^#{1,6}(?:\\s|$)").containsMatchIn(it)&&
            !Regex("^(?:#[\\p{L}\\p{N}_-]+\\s*)+$").matches(it)}
        .joinToString("\n").take(32000)
    private fun terms(text:String)=words.findAll(text.lowercase(Locale.ROOT).replace('ё','е')).map{it.value}
        .filter{it !in stop&&it.any{ch->ch.isLetter()}}.take(5000).toList()
    private fun counts(text:String)=terms(text).groupingBy{it}.eachCount()
    private fun similarity(a:Map<String,Int>,b:Map<String,Int>):Double {
        if(a.isEmpty()||b.isEmpty())return 0.0
        val dot=a.entries.sumOf{(word,n)->n.toDouble()*(b[word]?:0)}
        return dot/(sqrt(a.values.sumOf{it.toDouble()*it})*sqrt(b.values.sumOf{it.toDouble()*it}))
    }
    fun metadata(note:Note):JSONObject {
        val body=clean(note.text)
        val title=body.lineSequence().firstOrNull{it.isNotBlank()}?.replace(Regex("^[-*]\\s+(?:\\[[ xX]]\\s+)?"),"")
            ?.trim()?.take(100)?.filter{it.code>=32}?.trim().orEmpty().ifBlank{note.title.take(100)}
        val existing=MarkdownMetadata.tags(note.text).map{it.lowercase(Locale.ROOT)}.toSet()
        val tags=counts(body).entries.filter{it.key !in existing&&it.key.length>=4}
            .sortedWith(compareByDescending<Map.Entry<String,Int>>{it.value}.thenBy{it.key}).take(5).map{it.key}
        val lower=body.lowercase(Locale.ROOT)
        val category=when{
            MarkdownMetadata.proseLines(note.text).any{Regex("^\\s*[-*]\\s+\\[[ xX]]\\s+").containsMatchIn(it.second)}->"task"
            Regex("(^|\\s)(проект|project)(\\s|:|$)").containsMatchIn(lower)->"project"
            Regex("(^|\\s)(идея|idea)(\\s|:|$)").containsMatchIn(lower)->"idea"
            Regex("https?://|doi:").containsMatchIn(lower)->"reference"
            else->"note"
        }
        return JSONObject().put("id",note.id).put("revision",KnowledgeApi.revision(note.text))
            .put("title",title.ifBlank{"Без названия"}).put("category",category).put("tags",JSONArray(tags))
    }
    fun summary(text:String):String {
        val body=clean(text).ifBlank{PhotoStore.strip(text).take(32000)}
        if(body.isBlank())return "В заметке нет текста."
        val candidates=body.split(Regex("(?<=[.!?…])\\s+|\\n+")).map{it.trim()}.filter{it.isNotBlank()}.take(200)
        val frequencies=counts(body)
        return candidates.withIndex().sortedWith(compareByDescending<IndexedValue<String>>{
            val t=terms(it.value).distinct()
            if(t.isEmpty())0.0 else t.sumOf{word->(frequencies[word]?:0).toDouble()}/sqrt(t.size.toDouble())
        }.thenBy{it.index}).take(3).sortedBy{it.index}.joinToString("\n\n"){it.value.take(600)}
    }
    fun rank(query:String,notes:List<Note>,exclude:String?=null):JSONObject {
        val q=counts(query.take(32000))
        val rows=notes.asSequence().filter{it.id!=exclude}.map{
            it to similarity(q,counts(clean(it.text)+" "+it.title+" "+MarkdownMetadata.tags(it.text).joinToString(" ")))
        }.filter{it.second>0.0}.sortedWith(compareByDescending<Pair<Note,Double>>{it.second}.thenBy{it.first.id})
            .take(12).map{(note,score)->JSONObject().put("id",note.id).put("title",note.title)
                .put("revision",KnowledgeApi.revision(note.text)).put("similarity",score)}.toList()
        return JSONObject().put("notes",JSONArray(rows))
    }
}
