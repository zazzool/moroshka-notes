package app.moroshka.notes

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Geocoder
import java.util.Locale
import android.speech.RecognizerIntent
import java.text.DateFormat
import java.util.Date
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
    private var placeField: AutoCompleteTextView? = null
    private var search = ""
    private var tasks = false
    private val autoSave = Runnable { safely { flush() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        vault = Vault(this)
        root = column()
        root.setBackgroundColor(Color.rgb(249,249,242))
        root.setPadding(dp(16), dp(8), dp(16), dp(8))
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
        if (!DocumentBackup(this).configured) {
            root.addView(button("Выбрать папку для сохранения заметок") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 4)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        }
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
        actions.addView(button("＋  Новая заметка") { edit(vault.create("# " + suggestedTitle() + "\n\n"), true) }.apply {
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
        AlertDialog.Builder(this).setItems(arrayOf("Импорт .md", "Папка для сохранения", "Удалить заметки", "Подключить другое устройство", "Настроить сервер")) { _, index ->
            if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
            else if (index == 1) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 4)
            else if (index == 2) deleteNotes()
            else if (index == 3) pairDevice()
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
        if (selectTitle) { field.requestFocus(); field.setSelection(note.text.length) }
        val tools = row()
        root.addView(tools)
        tools.addView(button("＋ Связь") { insertLink() }, LinearLayout.LayoutParams(0, dp(52), 1f))
        tools.addView(button("Место") { choosePlace() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        tools.addView(button("🎙 Голос") { voiceInput() }, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        changes(field) { dirty = true; handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 350) }
    }
    private fun suggestedTitle(): String {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..11 -> "утро"
            in 12..16 -> "день"
            in 17..22 -> "вечер"
            else -> "ночь"
        }
        val date = java.text.SimpleDateFormat("d MMMM yyyy 'года'", Locale("ru")).format(now.time)
        val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time)
        return "$date, $part · $time"
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
        safely { flush() }
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
    private fun voiceInput() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Продиктуйте текст заметки")
            startActivityForResult(intent, 3)
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
