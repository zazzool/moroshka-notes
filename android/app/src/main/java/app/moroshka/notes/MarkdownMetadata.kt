package app.moroshka.notes

/** Tags remain ordinary Markdown. Fenced code and headings are left untouched. */
object MarkdownMetadata {
    private val tag = Regex("(?<!\\S)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,39})(?![\\p{L}\\p{N}_-])")
    private val valid = Regex("[\\p{L}\\p{N}_][\\p{L}\\p{N}_-]{0,39}")
    private val heading = Regex("^#[ \\t]+([^\\r\\n]+)")

    private fun transform(text: String, includeHeadings: Boolean = false, action: (String) -> String): String {
        var fence: String? = null
        return text.split("\n").joinToString("\n") { line ->
            val trimmed = line.trimStart()
            val marker = Regex("^(?:\u0060{3,}|~{3,})").find(trimmed)?.value
            if (marker != null) {
                val open = fence
                if (open == null) fence = marker else if (open.first() == marker.first() && marker.length >= open.length) fence = null
                line
            } else if (fence != null || (!includeHeadings && trimmed.startsWith("# "))) line else action(line)
        }
    }

    fun tags(text: String): List<String> {
        val tags = linkedSetOf<String>()
        transform(text) { line ->
            tag.findAll(line).forEach { tags.add(it.groupValues[1]) }
            line
        }
        return tags.toList()
    }

    fun add(text: String, value: String): String {
        val name = value.trim().removePrefix("#")
        require(valid.matches(name)) { "Используйте буквы, цифры, дефис или подчёркивание." }
        if (tags(text).any { it.equals(name, true) }) return text
        val separator = when {
            text.isEmpty() || text.endsWith("\n\n") -> ""
            text.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return text + separator + "#" + name + "\n"
    }

    fun remove(text: String, name: String): String = transform(text) { line ->
        tag.replace(line) { match -> if (match.groupValues[1].equals(name, true)) "" else match.value }
    }

    fun rename(text: String, name: String): String {
        val value = name.trim()
        require(value.isNotEmpty() && !value.contains('\n') && !value.contains('\r')) { "Введите заголовок в одну строку." }
        var found = false
        val renamed = transform(text, includeHeadings = true) { line ->
            val match = if (found) null else heading.find(line)
            if (match == null) line else {
                found = true
                line.replaceRange(match.range, "# $value")
            }
        }
        return if (found) renamed else "# $value\n\n$text"
    }
}
