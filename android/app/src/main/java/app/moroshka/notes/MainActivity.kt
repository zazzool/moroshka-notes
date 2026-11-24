package app.moroshka.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Geocoder
import java.util.Locale
import android.speech.RecognizerIntent
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.net.URI
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var vault: Vault
    private lateinit var root: LinearLayout
    private val settings by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var editor: EditText? = null
    private var dirty = false
    private var syncing = false
    private var pendingShare: String? = null
    private var exportId: String? = null
    private var placeField: AutoCompleteTextView? = null
    private val design by lazy { Design(this) }
    private val drafts by lazy { getSharedPreferences("capture_draft", MODE_PRIVATE) }
    private var captureDraft = ""
    private var page = "home"
    private var search = ""
    private var tasks = false
    private val autoSave = Runnable { safely { flush() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        vault = Vault(this)
        captureDraft = drafts.getString("text", "").orEmpty()
        page = state?.getString("page") ?: "home"
        root = column()
        root.setBackgroundColor(design.background)
        root.setPadding(dp(16), dp(8), dp(16), dp(8))
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(16), dp(8) + insets.systemWindowInsetTop, dp(16), dp(8) + insets.systemWindowInsetBottom)
            insets
        }
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (night) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContentView(root)
        exportId = state?.getString("export")
        val restore = state?.getString("note")
        if (restore != null) edit(vault.read(restore)) else library()
        if (state == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); receive(intent) }
    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (syncing) pendingShare = text else safely { flush(); edit(vault.create(text)) }
    }
    override fun onPause() { safely { flush() }; super.onPause() }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putString("note", current); out.putString("export", exportId); out.putString("page", page)
    }
    override fun onDestroy() { handler.removeCallbacks(autoSave); worker.shutdown(); super.onDestroy() }
    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        if (syncing) return
        if (current != null) safely { flush(); library() }
        else if (page != "home") { page = "home"; library() }
        else super.onBackPressed()
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(design.text)
        if (size >= 18) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val ink get() = design.text
    private val muted get() = design.muted
    private val pale get() = design.tint
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(ink)
        background = design.panel(pale, 16)
        minHeight = dp(48)
        stateListAnimator = null
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { safely(action) }
    }
    private fun safely(action: () -> Unit) {
        try { action() } catch (e: Exception) { notice(e.message ?: "Не удалось выполнить действие") }
    }
    private fun notice(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("Закрыть", null).show() }
    private fun changes(field: EditText, action: () -> Unit) {
        field.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = action()
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun flush() {
        handler.removeCallbacks(autoSave)
        if (dirty) { vault.save(Note(requireNotNull(current), requireNotNull(editor).text.toString())); dirty = false }
    }
    private fun library() {
        current = null
        editor = null
        root.removeAllViews()
        val header = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply { setImageResource(R.drawable.berry) }, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(label("Морошка", 24f).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(button("⌕") { page = "search"; library() }.apply {
            contentDescription = "Поиск"; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(button("⋯") { menu() }.apply {
            contentDescription = "Действия библиотеки"; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        root.addView(header)

        val content = column()
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        when (page) {
            "home" -> homeContent(content)
            "search" -> searchContent(content)
            "links" -> linksContent(content)
            else -> libraryContent(content)
        }
        val nav = row().apply {
            background = design.panel(design.surface, 20, true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = listOf("home" to "Главная", "search" to "Поиск", "links" to "Связи", "library" to "Библиотека")
        for ((destination, name) in items) {
            nav.addView(button(name) { page = destination; library() }.apply {
                textSize = 13f
                setPadding(dp(2), dp(4), dp(2), dp(4))
                minHeight = dp(48)
                maxLines = 2
                isSelected = page == destination
                background = design.panel(if (page == destination) design.tint else design.surface, 16)
                contentDescription = if (page == destination) "$name, выбранный раздел" else name
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun section(container: LinearLayout, title: String) {
        container.addView(label(title, 18f), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20); bottomMargin = dp(10)
        })
    }

    private fun homeContent(content: LinearLayout) {
        val capture = column().apply {
            background = design.panel(design.surface, 20, true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val field = EditText(this).apply {
            hint = "О чём вы думаете?"
            contentDescription = "Текст новой заметки"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity = android.view.Gravity.TOP
            textSize = 16f
            setTextColor(design.text)
            setHintTextColor(design.muted)
            background = design.panel(design.background, 12, true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            minLines = 3; maxLines = 7
            setText(captureDraft)
        }
        capture.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        changes(field) {
            captureDraft = field.text.toString()
            drafts.edit().putString("text", captureDraft).apply()
        }
        val actions = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        actions.addView(button("Голос") { voiceInput(5) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        actions.addView(button("Сохранить") { saveCapture() }.apply {
            background = design.primary(); setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        capture.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        content.addView(capture)
        if (!DocumentBackup(this).configured) {
            content.addView(button("Выбрать папку для сохранения") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 4)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        }
        if (settings.getString("url", "").isNullOrBlank()) {
            content.addView(button("Подключить или развернуть сервер") {
                AlertDialog.Builder(this).setItems(arrayOf("Развернуть свой сервер", "Подключить существующий")) { _, choice ->
                    if (choice == 0) installServer() else connection()
                }.show()
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        section(content, "Недавние")
        val recent = vault.recent()
        if (recent.isEmpty()) content.addView(label("Здесь появится ваша первая заметка.", 16f).apply { setTextColor(muted) })
        else {
            recent.take(4).forEach { noteCard(content, it) }
            content.addView(button("Все заметки") { page = "library"; tasks = false; library() },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    private fun saveCapture() {
        if (captureDraft.isBlank()) { notice("Напишите или продиктуйте мысль."); return }
        val title = captureDraft.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?.removePrefix("# ")?.take(80)?.trim().orEmpty().ifBlank { "Новая заметка" }
        val text = "# $title\n\n$captureDraft"
        var draftID = drafts.getString("id", null) ?: java.util.UUID.randomUUID().toString().also {
            check(drafts.edit().putString("id", it).commit()) { "Не удалось сохранить черновик" }
        }
        val existing = vault.all().firstOrNull { it.id == draftID }
        if (existing != null && existing.text != text) {
            draftID = java.util.UUID.randomUUID().toString()
            check(drafts.edit().putString("id", draftID).commit()) { "Не удалось сохранить черновик" }
        }
        vault.save(Note(draftID, text))
        check(drafts.edit().remove("text").remove("id").commit()) { "Не удалось обновить черновик" }
        captureDraft = ""
        Toast.makeText(this, "Заметка сохранена", Toast.LENGTH_SHORT).show()
        edit(vault.read(draftID))
    }

    private fun searchContent(content: LinearLayout) {
        section(content, "Поиск")
        val field = EditText(this).apply {
            hint = "Найти мысль или тему"
            contentDescription = "Поиск по заметкам"
            setSingleLine(); setText(search); textSize = 16f
            setTextColor(design.text); setHintTextColor(design.muted)
            setPadding(dp(12), 0, dp(12), 0)
            background = design.panel(design.surface, 12, true)
        }
        content.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
        val results = column()
        content.addView(results, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        val notes = vault.recent()
        fun refresh() {
            results.removeAllViews()
            val matches = notes.filter { it.text.contains(search, ignoreCase = true) }
            if (matches.isEmpty()) results.addView(label(if (search.isBlank()) "Заметок пока нет." else "Совпадений нет. Попробуйте другое слово.", 16f).apply { setTextColor(muted) })
            else matches.forEach { noteCard(results, it) }
        }
        changes(field) { search = field.text.toString(); refresh() }
        refresh()
    }

    private fun libraryContent(content: LinearLayout) {
        section(content, "Библиотека")
        val controls = row()
        controls.addView(button("Заметки") { tasks = false; library() }.apply {
            background = design.panel(if (!tasks) design.tint else design.surface, 16, true)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        controls.addView(button("Задачи") { tasks = true; library() }.apply {
            background = design.panel(if (tasks) design.tint else design.surface, 16, true)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        content.addView(controls)
        val list = column()
        content.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        fun refresh() {
            list.removeAllViews()
            for (note in vault.recent()) {
                if (!tasks) noteCard(list, note)
                else note.text.lines().forEachIndexed { index, line ->
                    val match = Regex("^\\s*[-*] \\[([ xX])] (.+)").find(line) ?: return@forEachIndexed
                    list.addView(CheckBox(this).apply {
                        text = match.groupValues[2] + " · " + note.title
                        setTextColor(design.text); buttonTintList = ColorStateList.valueOf(design.secondary)
                        minHeight = dp(48); isChecked = match.groupValues[1] != " "
                        setOnClickListener { safely {
                            val lines = note.text.split('\n').toMutableList()
                            lines[index] = line.replaceFirst(Regex("\\[[ xX]]"), if (isChecked) "[x]" else "[ ]")
                            vault.save(note.copy(text = lines.joinToString("\n"))); refresh()
                        } }
                    })
                }
            }
            if (list.childCount == 0) list.addView(label(if (tasks) "Задач пока нет." else "Заметок пока нет.", 16f).apply { setTextColor(muted) })
        }
        refresh()
        content.addView(button("Синхронизировать") {
            val url = settings.getString("url", "").orEmpty()
            val key = settings.getString("key", "").orEmpty()
            if (url.isNotEmpty() && key.isNotEmpty()) synchronize(url, key) else connection()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
    }

    private fun noteCard(container: LinearLayout, note: Note) {
        val entry = column().apply {
            background = design.panel(design.surface, 20, true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            minimumHeight = dp(48); isFocusable = true
            setOnClickListener { edit(note) }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(note.title)
                    .setItems(arrayOf("Открыть", "Удалить")) { _, choice ->
                        if (choice == 0) edit(note)
                        else AlertDialog.Builder(this@MainActivity).setMessage("Удалить эту заметку?")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить") { _, _ -> safely { vault.remove(listOf(note.id)); library() } }.show()
                    }.show()
                true
            }
            addView(label(note.title, 18f))
            val preview = note.text.lines().filter { it.isNotBlank() && !it.startsWith("# ") }.joinToString(" ")
            if (preview.isNotBlank()) addView(label(preview, 16f).apply {
                setTextColor(muted); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(6), 0, 0)
            })
            val tags = MarkdownMetadata.tags(note.text)
            contentDescription = listOf(note.title, preview, tags.joinToString(" ") { "#" + it })
                .filter { it.isNotBlank() }.joinToString(". ")
            if (tags.isNotEmpty()) addView(label(tags.joinToString("  ") { "#$it" }, 13f).apply {
                setTextColor(design.secondary); setPadding(0, dp(8), 0, 0); maxLines = 2
            })
        }
        container.addView(entry, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
    }

    private fun linksContent(content: LinearLayout) {
        section(content, "Связи")
        val notes = vault.recent()
        if (notes.isEmpty()) {
            content.addView(label("Создайте заметки и свяжите их, чтобы увидеть граф.", 16f).apply { setTextColor(muted) })
            return
        }
        val graph = KnowledgeGraph(this, notes, { edit(it) }, { from, to ->
            notice("«" + from.title + "» содержит ссылку на «" + to.title + "».")
        })
        graph.background = design.panel(design.surface, 20, true)
        content.addView(graph, LinearLayout.LayoutParams(MATCH_PARENT, dp(250)))
        if (notes.size > 16) content.addView(label("На графе показаны 16 недавних заметок. Полный список ниже.", 13f).apply { setTextColor(muted) })
        section(content, "Список заметок")
        notes.forEach { note ->
            val entry = button(note.title) { edit(note) }
            entry.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            entry.background = design.panel(design.surface, 16, true)
            content.addView(entry, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            note.links.distinct().forEach { target ->
                val linked = notes.firstOrNull { it.title.equals(target, ignoreCase = true) }
                val description = if (linked == null) "Связь: ${note.title} → $target (заметка ещё не создана)"
                    else "Связь: ${note.title} → ${linked.title}"
                val edge = button(description) {
                    if (linked == null) notice("«${note.title}» содержит ссылку на ещё не созданную заметку «$target».")
                    else notice("«${note.title}» содержит ссылку на «${linked.title}».")
                }
                edge.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                content.addView(edge, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
            }
        }
    }
    private fun menu() {
        AlertDialog.Builder(this).setItems(arrayOf("Импорт .md", "Папка для сохранения", "Удалить заметки", "Подключить другое устройство", "Развернуть свой сервер", "Настроить сервер")) { _, index ->
            if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
            else if (index == 1) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 4)
            else if (index == 2) deleteNotes()
            else if (index == 3) pairDevice()
            else if (index == 4) installServer()
            else connection()
        }.show()
    }
    private fun deleteNotes() {
        val notes = vault.all()
        if (notes.isEmpty()) return notice("Нет заметок для удаления.")
        val selected = BooleanArray(notes.size)
        AlertDialog.Builder(this).setTitle("Выберите заметки")
            .setMultiChoiceItems(notes.map { it.title }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                val ids = notes.filterIndexed { index, _ -> selected[index] }.map { it.id }
                if (ids.isNotEmpty()) AlertDialog.Builder(this).setMessage("Удалить заметок: ${ids.size}?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить") { _, _ -> safely { vault.remove(ids); library() } }.show()
            }.show()
    }
    private fun edit(note: Note, selectTitle: Boolean = false) {
        flush(); current = note.id; root.removeAllViews()
        val bar = row(); root.addView(bar)
        bar.addView(button("← Заметки") { flush(); library() })
        bar.addView(button("Связи") { flush(); connections(vault.read(note.id)) })
        bar.addView(button("⋯") {
            flush()
            AlertDialog.Builder(this).setItems(arrayOf("Изменить заголовок", "Теги", "Удалить связь", "Локальный граф", "Экспорт .md", "Поделиться текстом")) { _, index ->
                safely {
                    when (index) {
                        0 -> renameTitle()
                        1 -> manageTags()
                        2 -> removeLink()
                        3 -> showLocalGraph(vault.read(note.id))
                        4 -> {
                            exportId = note.id
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown")
                                .putExtra(Intent.EXTRA_TITLE, vault.read(note.id).title.replace('/', '_') + ".md"), 2)
                        }
                        5 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, editor?.text.toString()), "Поделиться заметкой"))
                    }
                }
            }.show()
        }.apply { contentDescription = "Действия заметки" })
        val field = EditText(this).apply {
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            typeface = Typeface.create("sans-serif", Typeface.NORMAL); textSize = 16f; setText(note.text)
            setTextColor(design.text)
            background = design.panel(design.surface, 20, true)
            setPadding(dp(16), dp(16), dp(16), dp(16)); setLineSpacing(dp(5).toFloat(), 1f)
            contentDescription = "Текст Markdown"
        }
        editor = field
        root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(8) })
        if (selectTitle) { field.requestFocus(); field.setSelection(note.text.length) }
        val tools = row()
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        root.addView(toolScroll)
        tools.addView(button("＋ Связь") { insertLink() })
        tools.addView(button("Теги") { manageTags() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Граф") { flush(); showLocalGraph(vault.read(note.id)) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Место") { choosePlace() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Голос") { voiceInput() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        changes(field) { dirty = true; handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 350) }
    }
    private fun renameTitle() {
        val field = editor ?: return
        val input = EditText(this).apply {
            setSingleLine()
            setText(vault.read(requireNotNull(current)).title)
            selectAll()
        }
        AlertDialog.Builder(this).setTitle("Заголовок").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> safely {
                field.setText(MarkdownMetadata.rename(field.text.toString(), input.text.toString()))
                flush()
            } }.show()
    }

    private fun manageTags() {
        val field = editor ?: return
        val tags = MarkdownMetadata.tags(field.text.toString())
        val options = listOf("Добавить тег") + tags.map { "Удалить #$it" }
        AlertDialog.Builder(this).setTitle("Теги").setItems(options.toTypedArray()) { _, index ->
            if (index == 0) {
                val input = EditText(this).apply { hint = "Название тега"; setSingleLine() }
                AlertDialog.Builder(this).setTitle("Новый тег").setView(input)
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Добавить") { _, _ -> safely {
                        field.setText(MarkdownMetadata.add(field.text.toString(), input.text.toString()))
                        flush()
                    } }.show()
            } else safely {
                field.setText(MarkdownMetadata.remove(field.text.toString(), tags[index - 1]))
                flush()
            }
        }.show()
    }

    private fun removeLink() {
        val field = editor ?: return
        val links = Note(requireNotNull(current), field.text.toString()).links.distinct()
        if (links.isEmpty()) return notice("В заметке пока нет исходящих связей.")
        AlertDialog.Builder(this).setTitle("Удалить связь").setItems(links.toTypedArray()) { _, index ->
            val pattern = Regex("\\[\\[\\s*" + Regex.escape(links[index]) + "\\s*]]", RegexOption.IGNORE_CASE)
            field.setText(pattern.replace(field.text.toString()) { it.value.removePrefix("[[").removeSuffix("]]") })
            safely { flush() }
        }.show()
    }

    private fun showLocalGraph(note: Note) {
        val all = vault.recent()
        val related = listOf(note) + all.filter { other ->
            other.id != note.id && (note.links.any { it.equals(other.title, true) } ||
                other.links.any { it.equals(note.title, true) })
        }
        val container = column().apply { setPadding(dp(16), dp(8), dp(16), dp(8)) }
        lateinit var dialog: AlertDialog
        val graph = KnowledgeGraph(this, related, { target -> dialog.dismiss(); edit(target) }, { from, to ->
            notice("«" + from.title + "» содержит ссылку на «" + to.title + "».")
        })
        graph.background = design.panel(design.surface, 20, true)
        container.addView(graph, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)))
        related.forEach { target -> container.addView(button(target.title) { dialog.dismiss(); edit(target) }) }
        dialog = AlertDialog.Builder(this).setTitle("Связи заметки").setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Закрыть", null).create()
        dialog.show()
    }

    private fun choosePlace() {
        val preferences = getSharedPreferences("places", MODE_PRIVATE)
        val suggestions = preferences.getString("recent", "").orEmpty().split("\n").filter { it.isNotBlank() }
        val field = AutoCompleteTextView(this).apply {
            hint = "Город или место"
            setSingleLine()
            threshold = 1
            setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, suggestions))
        }
        placeField = field
        val content = column()
        content.setPadding(dp(20), 0, dp(20), 0)
        content.addView(field)
        content.addView(button("Определить местоположение") {
            if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
            else locate()
        })
        AlertDialog.Builder(this).setTitle("Место").setView(content)
            .setNegativeButton("Отмена") { _, _ -> placeField = null }
            .setPositiveButton("Добавить") { _, _ ->
                val name = field.text.toString().trim().replace("\n", " ")
                placeField = null
                if (name.isNotEmpty()) {
                    preferences.edit().putString("recent", (listOf(name) + suggestions.filterNot { it.equals(name, true) }).take(10).joinToString("\n")).apply()
                    editor?.let { text ->
                        val end = text.text.indexOf('\n').let { if (it < 0) text.length() else it }
                        text.text.insert(end, " · " + name)
                    }
                }
            }.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) locate()
    }
    private fun locate() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val location = manager.getProviders(true).mapNotNull { provider ->
            try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        }.maxByOrNull { it.time }
        if (location == null) {
            notice("Местоположение пока недоступно. Введите место вручную.")
            return
        }
        worker.execute {
            val place = try {
                @Suppress("DEPRECATION")
                val address = Geocoder(this, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
                listOfNotNull(address?.locality, address?.subLocality).distinct().joinToString(", ")
            } catch (_: Exception) { "" }
            runOnUiThread {
                if (place.isEmpty()) notice("Место не найдено. Введите его вручную.")
                else placeField?.setText(place)
            }
        }
    }
    private fun insertLink() {
        flush()
        val available = vault.all().filter { it.id != current }
        if (available.isEmpty()) {
            notice("Сначала создайте ещё одну заметку.")
            return
        }
        AlertDialog.Builder(this).setTitle("Добавить связь")
            .setItems(available.map { it.title }.toTypedArray()) { _, index ->
                val field = editor ?: return@setItems
                val start = field.selectionStart.coerceAtLeast(0)
                val end = field.selectionEnd.coerceAtLeast(start)
                field.text.replace(start, end, "[[" + available[index].title + "]]")
            }.show()
    }
    private fun voiceInput(requestCode: Int = 3) {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Продиктуйте текст заметки")
            startActivityForResult(intent, requestCode)
        } catch (e: android.content.ActivityNotFoundException) {
            notice("На устройстве не найдено приложение для распознавания речи.")
        }
    }
    private fun connections(note: Note) {
        val all = vault.all()
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        for (name in note.links.distinct()) {
            val matches = all.filter { it.title.equals(name, true) }
            if (matches.isEmpty()) entries.add("Создать · $name" to { edit(vault.create("# $name\n\n")) })
            for (target in matches) entries.add("→ " + target.title to { edit(target) })
        }
        for (source in all.filter { it.id != note.id && it.links.any { s -> s.equals(note.title, true) } }) {
            entries.add("← " + source.title to { edit(source) })
        }
        if (entries.isEmpty()) return notice("Добавьте [[Название]] для связи с другой заметкой.")
        AlertDialog.Builder(this).setTitle("Связи").setItems(entries.map { it.first }.toTypedArray()) { _, i -> safely(entries[i].second) }.show()
    }
    private fun installServer() {
        val content = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        val host = EditText(this).apply {
            hint = "IP-адрес или домен сервера"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val password = EditText(this).apply {
            hint = "Пароль root"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        content.addView(host)
        content.addView(password)
        content.addView(label("Сервер должен быть доступен по SSH, а порты 80 и 443 — из интернета.", 14f))
        val dialog = AlertDialog.Builder(this).setTitle("Свой сервер").setView(content)
            .setNegativeButton("Отмена", null).setPositiveButton("Развернуть", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val address = host.text.toString().trim()
                val secret = password.text.toString().toByteArray(Charsets.UTF_8)
                if (address.isBlank() || secret.isEmpty()) {
                    secret.fill(0)
                    notice("Введите адрес и пароль root.")
                    return@setOnClickListener
                }
                password.text.clear()
                dialog.dismiss()
                val status = label("Подключение…", 16f).apply { setPadding(dp(24), dp(16), dp(24), dp(16)) }
                val progressDialog = AlertDialog.Builder(this).setTitle("Развёртывание сервера")
                    .setView(status).setNegativeButton("В фоне", null).setCancelable(false).create()
                progressDialog.show()
                worker.execute {
                    val result = runCatching {
                        ServerInstaller(this) { fingerprint ->
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var accepted = false
                            runOnUiThread {
                                if (isDestroyed) { latch.countDown(); return@runOnUiThread }
                                AlertDialog.Builder(this).setTitle("Проверьте SSH-ключ сервера")
                                    .setMessage(fingerprint + "\n\nСверьте отпечаток с данными вашего VPS-провайдера.")
                                    .setPositiveButton("Совпадает") { _, _ -> accepted = true; latch.countDown() }
                                    .setNegativeButton("Отмена") { _, _ -> latch.countDown() }
                                    .setOnCancelListener { latch.countDown() }.show()
                            }
                            latch.await(90, java.util.concurrent.TimeUnit.SECONDS) && accepted
                        }.install(address, secret) { message -> runOnUiThread { status.text = message } }.also { (url, key) ->
                            check(settings.edit().putString("url", url).putString("key", key).commit()) {
                                "Сервер готов, но настройки подключения не сохранились. Повторите подключение."
                            }
                        }
                    }
                    secret.fill(0)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        progressDialog.dismiss()
                        result.onSuccess { (url, key) ->
                            Toast.makeText(this, "Сервер готов. Синхронизируем заметки.", Toast.LENGTH_LONG).show()
                            safely { synchronize(url, key) }
                        }.onFailure { notice(it.message ?: "Не удалось развернуть сервер") }
                    }
                }
            }
        }
        dialog.show()
    }
    private fun pairDevice() {
        val address = settings.getString("url", "").orEmpty()
        val token = settings.getString("key", "").orEmpty()
        if (address.isBlank() || token.isBlank()) {
            notice("Сначала подключите этот телефон к своему серверу.")
            return
        }
        val content = column().apply { setPadding(dp(20), 0, dp(20), 0) }
        content.addView(label("Откройте на компьютере адрес:", 16f))
        content.addView(label(address, 18f).apply { setTextIsSelectable(true); setPadding(0, dp(6), 0, dp(6)) })
        content.addView(button("Поделиться адресом") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, address), "Адрес Moroshka Notes"))
        })
        content.addView(label("На компьютере нажмите «Подключение» и введите показанный код:", 16f))
        val code = EditText(this).apply {
            hint = "8 цифр"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            contentDescription = "Код с компьютера"
        }
        content.addView(code)
        val dialog = AlertDialog.Builder(this).setTitle("Подключить компьютер").setView(content)
            .setNegativeButton("Отмена", null).setPositiveButton("Подтвердить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = code.text.toString()
                if (!value.matches(Regex("[0-9]{8}"))) {
                    code.error = "Введите 8 цифр"
                    return@setOnClickListener
                }
                val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                confirm.isEnabled = false
                worker.execute {
                    val result = runCatching {
                        val conn = java.net.URL(address + "/pair/approve").openConnection() as javax.net.ssl.HttpsURLConnection
                        try {
                            conn.instanceFollowRedirects = false
                            conn.connectTimeout = 12000
                            conn.readTimeout = 15000
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Authorization", "Bearer $token")
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.outputStream.use { it.write(org.json.JSONObject().put("code", value).toString().toByteArray(Charsets.UTF_8)) }
                            when (conn.responseCode) {
                                in 200..299 -> Unit
                                401 -> error("Проверьте ключ сервера на этом телефоне.")
                                404 -> error("Код не найден или истёк. Получите новый код на компьютере.")
                                else -> error("Не удалось подключить устройство. Код ответа: ${conn.responseCode}")
                            }
                        } finally { conn.disconnect() }
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        confirm.isEnabled = true
                        result.onSuccess {
                            dialog.dismiss()
                            Toast.makeText(this, "Компьютер подключён", Toast.LENGTH_LONG).show()
                        }.onFailure { code.error = it.message ?: "Сервер недоступен" }
                    }
                }
            }
        }
        dialog.show()
    }
    private fun connection() {
        val fields = column()
        val url = EditText(this).apply { hint = "https://notes.example.org"; setSingleLine(); setText(settings.getString("url","")) }
        val key = EditText(this).apply {
            hint = "Ключ синхронизации"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(settings.getString("key",""))
        }
        fields.addView(url); fields.addView(key)
        AlertDialog.Builder(this).setTitle("Свой сервер").setView(fields).setNegativeButton("Отмена",null)
            .setPositiveButton("Синхронизировать") { _, _ -> safely {
                val address = url.text.toString().trim().trimEnd('/')
                val uri = URI(address)
                require(uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null &&
                    uri.rawFragment == null && uri.path.isNullOrEmpty()) { "Укажите HTTPS-адрес сервера без пути" }
                val token = key.text.toString().trim()
                require(token.isNotBlank()) { "Введите ключ сервера" }
                settings.edit().putString("url", address).putString("key",token).apply()
                synchronize(address, token)
            } }.show()
    }
    private fun synchronize(url: String, key: String) {
        flush(); syncing = true; root.removeAllViews(); root.addView(label("Синхронизация…",22f))
        worker.execute {
            val result = runCatching { vault.sync(url, key) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                syncing = false; library()
                val shared = pendingShare
                if (shared != null) { pendingShare = null; safely { edit(vault.create(shared)) } }
                result.onSuccess {
                    if (it > 0) notice("Обе версии сохранены. Конфликтных копий: $it. Проверьте их и повторите синхронизацию.")
                    else Toast.makeText(this,"Синхронизировано",Toast.LENGTH_SHORT).show()
                }.onFailure { notice(it.message ?: "Сервер недоступен") }
            }
        }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 5) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            captureDraft = if (captureDraft.isBlank()) spoken else "$captureDraft $spoken"
            drafts.edit().putString("text", captureDraft).apply()
            page = "home"
            library()
            return
        }
        if (requestCode == 3) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            editor?.let { field ->
                val start = field.selectionStart.coerceAtLeast(0)
                val end = field.selectionEnd.coerceAtLeast(start)
                field.text.replace(start, end, spoken)
            }
            return
        }
        val uri = data?.data ?: return
        safely {
            if (requestCode == 4) {
                DocumentBackup(this).connect(uri, vault)
                library()
                notice("Папка подключена. Новые версии заметок будут сохраняться в ней. После переустановки выберите эту папку снова.")
            } else if (requestCode == 1) {
                val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        require(out.size() + n <= 512 * 1024) { "Файл больше 512 КиБ" }; out.write(buffer,0,n)
                    }
                    out.toByteArray()
                }
                edit(vault.create(bytes.toString(Charsets.UTF_8)))
            } else if (requestCode == 2) {
                val note = vault.read(requireNotNull(exportId))
                requireNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(note.text.toByteArray(Charsets.UTF_8)) }
                exportId = null
            }
        }
    }
}
