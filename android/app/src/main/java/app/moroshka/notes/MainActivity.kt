package app.moroshka.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
    private var search = ""
    private var tasks = false
    private val autoSave = Runnable { safely { flush() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        vault = Vault(this)
        root = column()
        root.setBackgroundColor(Color.rgb(249,249,242))
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(dp(16), dp(8) + insets.systemWindowInsetTop, dp(16), dp(8) + insets.systemWindowInsetBottom)
            insets
        }
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
        super.onSaveInstanceState(out); out.putString("note", current); out.putString("export", exportId)
    }
    override fun onDestroy() { handler.removeCallbacks(autoSave); worker.shutdown(); super.onDestroy() }
    @Deprecated("Activity back navigation")
    override fun onBackPressed() {
        if (syncing) return
        if (current == null) super.onBackPressed() else safely { flush(); library() }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(text: String, size: Float) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.rgb(38,65,52))
        if (size >= 22) typeface = Typeface.SERIF
    }
    private fun surface(color: Int, radius: Int = 18) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private val ink = Color.rgb(37, 65, 53)
    private val muted = Color.rgb(102, 119, 103)
    private val pale = Color.rgb(235, 239, 227)
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(ink)
        background = surface(pale)
        minHeight = dp(48)
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
        current = null; editor = null; root.removeAllViews()
        val header = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        root.addView(header)
        val brand = column()
        brand.addView(label("MOROSHKA", 12f).apply { letterSpacing = .16f; setTextColor(muted) })
        brand.addView(label("Моя библиотека", 32f))
        header.addView(brand, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(button("⋯") { menu() }.apply { contentDescription = "Действия библиотеки" })
        val query = EditText(this).apply {
            hint = "⌕  Поиск по заметкам и тегам"
            contentDescription = "Поиск по заметкам"
            setSingleLine()
            setText(search)
            textSize = 16f
            setPadding(dp(16), 0, dp(16), 0)
            background = surface(Color.WHITE)
        }
        root.addView(query, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(20); bottomMargin = dp(16) })
        val tabs = row()
        root.addView(tabs)
        fun tab(name: String, selected: Boolean, action: () -> Unit) = button(name, action).apply {
            if (selected) {
                background = surface(ink, 24)
                setTextColor(Color.WHITE)
            }
        }
        tabs.addView(tab("Заметки", !tasks) { tasks = false; library() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(tab("Задачи", tasks) { tasks = true; library() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        val section = label(if (tasks) "Задачи" else "Все заметки", 23f)
        root.addView(section, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(22); bottomMargin = dp(10) })
        val list = column()
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val actions = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        actions.addView(button("↻") {
            val url = settings.getString("url", "").orEmpty()
            val key = settings.getString("key", "").orEmpty()
            if (url.isNotEmpty() && key.isNotEmpty()) synchronize(url, key) else connection()
        }.apply { contentDescription = "Синхронизировать" }, LinearLayout.LayoutParams(dp(56), dp(56)))
        actions.addView(button("＋  Новая заметка") { edit(vault.create("# Новая заметка\n\n"), true) }.apply {
            background = surface(ink, 22); setTextColor(Color.WHITE); textSize = 16f
            elevation = dp(3).toFloat()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(10) })
        fun refresh() {
            list.removeAllViews()
            for (note in vault.all().filter { it.text.contains(search, ignoreCase = true) }) {
                if (!tasks) {
                    val entry = column().apply {
                        background = surface(Color.WHITE, 20)
                        setPadding(dp(18), dp(16), dp(18), dp(16))
                        isFocusable = true
                        contentDescription = note.title
                        setOnClickListener { edit(note) }
                        addView(label(note.title, 22f))
                        val preview = note.text.lines().filter { it.isNotBlank() && !it.startsWith("# ") }.joinToString(" ")
                        if (preview.isNotBlank()) addView(label(preview, 14f).apply {
                            setTextColor(muted); maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(0, dp(6), 0, 0)
                        })
                    }
                    list.addView(entry, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
                } else note.text.lines().forEachIndexed { index, line ->
                    val match = Regex("^\\s*[-*] \\[([ xX])] (.+)").find(line)
                    if (match != null) list.addView(CheckBox(this).apply {
                        text = match.groupValues[2] + " · " + note.title
                        isChecked = match.groupValues[1] != " "
                        setOnClickListener { safely {
                            val lines = note.text.split('\n').toMutableList()
                            lines[index] = line.replaceFirst(Regex("\\[[ xX]]"), if (isChecked) "[x]" else "[ ]")
                            vault.save(note.copy(text = lines.joinToString("\n"))); refresh()
                        } }
                    })
                }
            }
            if (list.childCount == 0) list.addView(label(when {
                search.isNotEmpty() -> "Совпадений нет"
                tasks -> "Добавьте строку «- [ ] Задача» в заметку."
                else -> "Создайте первую заметку или импортируйте файл .md."
            }, 16f))
        }
        changes(query) { search = query.text.toString(); refresh() }; refresh()
    }
    private fun menu() {
        AlertDialog.Builder(this).setItems(arrayOf("Импорт .md", "Настроить сервер")) { _, index ->
            if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
            else connection()
        }.show()
    }
    private fun edit(note: Note, selectTitle: Boolean = false) {
        flush(); current = note.id; root.removeAllViews()
        val bar = row(); root.addView(bar)
        bar.addView(button("← Заметки") { flush(); library() })
        bar.addView(button("Связи") { flush(); connections(vault.read(note.id)) })
        bar.addView(button("⋯") {
            flush()
            AlertDialog.Builder(this).setItems(arrayOf("Экспорт .md", "Поделиться текстом")) { _, index ->
                if (index == 0) {
                    exportId = note.id
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/markdown")
                        .putExtra(Intent.EXTRA_TITLE, vault.read(note.id).title.replace('/', '_') + ".md"), 2)
                } else startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, editor?.text.toString()), "Поделиться заметкой"))
            }.show()
        }.apply { contentDescription = "Действия заметки" })
        val field = EditText(this).apply {
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            typeface = Typeface.SERIF; textSize = 20f; setText(note.text)
            background = null; setPadding(dp(4), dp(24), dp(4), dp(12)); setLineSpacing(dp(4).toFloat(), 1f)
            contentDescription = "Текст Markdown"
        }
        editor = field; root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (selectTitle) { field.requestFocus(); field.setSelection(2, note.text.indexOf('\n')) }
        changes(field) { dirty = true; handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 350) }
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
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        safely {
            if (requestCode == 1) {
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
