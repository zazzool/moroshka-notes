package app.moroshka.notes

import java.util.Locale

/** Conservative top-level Markdown scanner; mirrors web/markdown.mjs.
 * Fences and same-line code spans are protected from metadata edits.
 */
object MarkdownMetadata {
    private val tag = Regex("(?<!\\S)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,39})(?![\\p{L}\\p{N}_-])")
    private val valid = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,39}")
    private val heading = Regex("^( {0,3})#[ \\t]+([^\\r\\n]+)")
    private val anyHeading = Regex("^ {0,3}#{1,6}(?:[ \\t]|\\r?$)")
    private val marker = Regex("^ {0,3}(\u0060{3,}|~{3,})([^\\r\\n]*)\\r?$")
    private val inline = Regex("\u0060+")
    private val wiki = Regex("(?<!\\\\)\\[\\[([^\\]\\r\\n]+)]]")
    private data class Scan(val lines: MutableList<String>, val prose: List<Pair<Int,String>>, val openFence:Boolean)

    private fun scan(text:String): Scan {
        val lines = text.split('\n').toMutableList()
        val prose = mutableListOf<Pair<Int,String>>()
        var fence:String? = null
        for ((index,line) in lines.withIndex()) {
            val match = marker.matchEntire(line)
            val run = match?.groupValues?.get(1)
            val open = fence
            if (open != null) {
                if (run != null && run.first() == open.first() && run.length >= open.length &&
                    match.groupValues[2].all { it == ' ' || it == '\t' }) fence = null
                continue
            }
            if (run != null && !(run.first() == '\u0060' && match.groupValues[2].contains('\u0060'))) {
                fence = run; continue
            }
            if (line.startsWith("    ") || line.startsWith("\t")) continue
            prose.add(index to line)
        }
        return Scan(lines,prose,fence!=null)
    }

    fun proseLines(text:String): List<Pair<Int,String>> = scan(text).prose

    private fun mask(line:String):String {
        val runs=inline.findAll(line).toList()
        val result=StringBuilder(line)
        var i=0
        while(i<runs.size){
            val open=runs[i]
            var j=i+1
            while(j<runs.size && runs[j].value.length!=open.value.length)j++
            if(j==runs.size){i++;continue}
            for(k in open.range.first..runs[j].range.last)result.setCharAt(k,' ')
            i=j+1
        }
        return result.toString()
    }
    fun title(text:String):String = proseLines(text).firstNotNullOfOrNull { (_,line) ->
        heading.find(line)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotEmpty() }
    } ?: "Без названия"

    fun links(text:String):List<String> = proseLines(text).flatMap { (_,line) ->
        wiki.findAll(mask(line)).filter { line.substring(it.range)==it.value }
            .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    }
    fun removeLink(text:String,target:String):String {
        val data=scan(text)
        for((index,line) in data.prose) {
            val matches=wiki.findAll(mask(line)).filter {
                line.substring(it.range)==it.value && it.groupValues[1].trim().equals(target,true)
            }.toList()
            for(match in matches.asReversed())
                data.lines[index]=data.lines[index].replaceRange(match.range,match.groupValues[1])
        }
        return data.lines.joinToString("\n")
    }
    fun tags(text:String):List<String> {
        val tags=linkedMapOf<String,String>()
        for((_,line) in proseLines(text)){
            if(anyHeading.containsMatchIn(line))continue
            tag.findAll(mask(line)).forEach {
                val name=it.groupValues[1]
                tags.putIfAbsent(name.lowercase(Locale.ROOT),name)
            }
        }
        return tags.values.toList()
    }

    fun add(text:String,value:String):String = addAll(text,listOf(value.trim().removePrefix("#")))
    fun addAll(text:String,values:List<String>):String {
        val known=tags(text).map { it.lowercase(Locale.ROOT) }.toMutableSet()
        val added=values.filter {
            require(valid.matches(it)) { "Используйте буквы, цифры, дефис или подчёркивание." }
            known.add(it.lowercase(Locale.ROOT))
        }
        if(added.isEmpty())return text
        val line=added.joinToString(" "){"#"+it}+"\n"
        if(scan(text).openFence)return line+"\n"+text
        val separator=when {
            text.isEmpty()||text.endsWith("\n\n")->""
            text.endsWith("\n")->"\n"
            else->"\n\n"
        }
        return text+separator+line
    }
    fun remove(text:String,name:String):String = removeMatching(text){it.equals(name,true)}
    fun removeMatching(text:String,predicate:(String)->Boolean):String {
        val data=scan(text)
        for((index,line) in data.prose){
            if(anyHeading.containsMatchIn(line))continue
            val matches=tag.findAll(mask(line)).filter{predicate(it.groupValues[1])}.toList()
            for(match in matches.asReversed())data.lines[index]=data.lines[index].removeRange(match.range)
        }
        return data.lines.joinToString("\n")
    }
    fun rename(text:String,name:String):String {
        val value=name.trim()
        require(value.isNotEmpty()&&value.none{it.code<32}){"Введите заголовок в одну строку."}
        val data=scan(text)
        for((index,line) in data.prose){
            val match=heading.find(line)?:continue
            data.lines[index]=line.replaceRange(match.range,match.groupValues[1]+"# "+value)
            return data.lines.joinToString("\n")
        }
        return "# "+value+"\n\n"+text
    }
}
