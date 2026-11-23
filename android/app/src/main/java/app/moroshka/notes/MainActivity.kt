package app.moroshka.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; setOnClickListener { safely(action) }
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
        root.addView(label("Moroshka Notes", 30f))
        val controls = row(); root.addView(controls)
        controls.addView(button("＋ Заметка") { edit(vault.create("# Новая заметка\n\n"), true) })
        controls.addView(button("Синхронизация") { connection() })
        controls.addView(button("⋯") { menu() }.apply { contentDescription = "Действия библиотеки" })
        val query = EditText(this).apply { hint = "Поиск по тексту или #тегу"; setSingleLine(); setText(search) }
        root.addView(query)
        val toggle = button(if (tasks) "Задачи ▾" else "Заметки ▾") { tasks = !tasks; library() }
        root.addView(toggle)
        val list = column(); root.addView(ScrollView(this).apply { addView(list) })
        fun refresh() {
            list.removeAllViews()
            for (note in vault.all().filter { it.text.contains(search, ignoreCase = true) }) {
                if (!tasks) {
                    val entry = label(note.title, 22f).apply {
                        setPadding(dp(8), dp(12), dp(8), dp(12))
                        isFocusable = true; setOnClickListener { edit(note) }
                    }
                    list.addView(entry)
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
