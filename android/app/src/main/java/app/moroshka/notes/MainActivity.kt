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
    private val processing by lazy { ProcessingPrefs(this) }
    private val localOcr by lazy { LocalOcr(applicationContext) }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null
    private var editor: EditText? = null
    private var dirty = false
    private var syncing = false
    private var visible = false
    private var wasStopped = false
    private val syncOwner = Any()
    private var pendingShare: String? = null
    private var exportId: String? = null
    private var placeField: AutoCompleteTextView? = null
    private val design by lazy { Design(this) }
    private val drafts by lazy { getSharedPreferences("capture_draft", MODE_PRIVATE) }
    private var captureDraft = ""
    private var photoTarget: String? = null
    private var editorImages = emptyList<String>()
    private var feedLimit = 40
    private val photoWorker = Executors.newFixedThreadPool(2)
    private var page = "home"
    private var search = ""
    private var tasks = false
    private val autoSave = Runnable { safely { flush() } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        SyncAccess.enter(syncOwner)
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
        photoTarget = state?.getString("photoTarget")
        val restore = state?.getString("note")
        if (restore != null) edit(vault.read(restore)) else library()
        if (state == null) receive(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); SyncAccess.enter(syncOwner); receive(intent) }
    private fun receive(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        if (syncing) pendingShare = text else safely { flush(); edit(vault.create(text)) }
    }
    override fun onStart() {
        super.onStart()
        SyncAccess.enter(syncOwner)
        visible = true
        if (wasStopped && !syncing && !dirty) safely {
            val id = current
            if (id == null) library()
            else {
                val fresh = vault.all().firstOrNull { it.id == id }
                if (fresh == null) library()
                else if (PhotoStore.strip(fresh.text) != editor?.text.toString() || PhotoStore.refs(fresh.text) != editorImages) edit(fresh)
            }
        }
        wasStopped = false
    }
    override fun onPause() { safely { flush() }; super.onPause() }
    override fun onStop() {
        visible = false; wasStopped = true
        releaseBackground()
        super.onStop()
    }
    private fun releaseBackground() {
        if (visible || syncing || dirty) return
        SyncAccess.leave(syncOwner)
        runCatching { BackgroundSync.schedule(applicationContext) }.onFailure {
            BackgroundSync.record(applicationContext, "Не удалось запланировать автоматический обмен")
        }
    }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out); out.putString("note", current); out.putString("export", exportId); out.putString("page", page); out.putString("photoTarget",photoTarget)
    }
    override fun onDestroy() {
        handler.removeCallbacks(autoSave)
        val context = applicationContext
        // If a manual exchange/archive is still running, release only after its queued work.
        worker.execute {
            SyncAccess.leave(syncOwner)
            runCatching { BackgroundSync.schedule(context) }
        }
        worker.shutdown()
        photoWorker.shutdownNow()
        super.onDestroy()
    }
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
        typeface = if (size >= 18) design.semibold else design.regular
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
        minHeight = dp(48); minWidth = dp(48)
        typeface = design.semibold
        stateListAnimator = null
        foreground = design.feedback()
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { safely(action) }
    }
    private fun Button.icon(resource: Int, color: Int = design.text, above: Boolean = false) {
        val drawable = getDrawable(resource)!!.mutate().apply {
            setTint(color)
            setBounds(0, 0, dp(20), dp(20))
        }
        if (above) setCompoundDrawables(null, drawable, null, null)
        else setCompoundDrawablesRelative(drawable, null, null, null)
        compoundDrawablePadding = dp(4)
    }
    private fun decorative(resource: Int, height: Int) = ImageView(this).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = design.panel(design.surface, 16)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(height))
    }
    private fun landscape(): Int {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            design.dark || hour in 0..5 || hour >= 22 -> R.drawable.landscape_night
            hour in 6..10 -> R.drawable.landscape_dawn
            hour in 17..21 -> R.drawable.landscape_sunset
            else -> R.drawable.landscape_cloudberry
        }
    }

    private fun hero(): FrameLayout = FrameLayout(this).apply {
        tag = "homeHero"
        val largeText = resources.configuration.fontScale >= 1.3f
        background = design.panel(design.surface, 20)
        clipToOutline = true
        addView(ImageView(this@MainActivity).apply {
            setImageResource(landscape())
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(View(this@MainActivity).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00000000, 0xE021172D.toInt()))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(label("Мысли в знания, идеи в проекты", if (largeText) 16f else 18f).apply {
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(8), dp(16), dp(10))
            setShadowLayer(dp(2).toFloat(), 0f, 1f, 0xFF211B29.toInt())
        }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, android.view.Gravity.BOTTOM))
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
        if (dirty) { vault.save(Note(requireNotNull(current), PhotoStore.compose(requireNotNull(editor).text.toString(),editorImages))); dirty = false }
    }
    private fun library() {
        current = null
        editor = null
        editorImages = emptyList()
        root.removeAllViews()
        val header = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val largeText = resources.configuration.fontScale >= 1.3f
        header.addView(label("Морошка", if (largeText) 18f else 24f).apply {
            setPadding(0, 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Морошка"
        },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        header.addView(button("") { page = "search"; library() }.apply {
            icon(R.drawable.icon_search)
            contentDescription = "Поиск"; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(button("") { menu() }.apply {
            icon(R.drawable.icon_more)
            contentDescription = "Действия библиотеки"; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        root.addView(header)

        val content = column()
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        when (page) {
            "home" -> homeContent(content)
            "search" -> searchContent(content)
            "links" -> linksContent(content)
            else -> libraryContent(content)
        }
        root.addView(capturePanel(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        val nav = column().apply {
            background = design.panel(design.surface, 20, true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val items = listOf("home" to "Заметки", "search" to "Поиск", "links" to "Связи", "library" to "Задачи")
        val navRows = if (largeText) items.chunked(2) else listOf(items)
        for (itemsInRow in navRows) {
            val navRow = row()
            nav.addView(navRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            for ((destination, name) in itemsInRow) navRow.addView(button(name) { page = destination; if(destination=="library") tasks=true; library() }.apply {
                textSize = 13f
                setPadding(dp(2), dp(4), dp(2), dp(4))
                minHeight = dp(48)
                maxLines = 2
                icon(when (destination) {
                    "home" -> R.drawable.icon_home
                    "search" -> R.drawable.icon_search
                    "links" -> R.drawable.icon_graph
                    else -> R.drawable.icon_library
                }, if (page == destination) design.secondary else design.text, above = !largeText)
                isSelected = page == destination
                background = design.panel(if (page == destination) design.tint else design.surface, 16)
                contentDescription = if (page == destination) "$name, выбранный раздел" else name
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        root.addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        // Give the editor and save action room when the soft keyboard resizes the window.
        val layoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val visible = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(visible)
            @Suppress("DEPRECATION")
            val screenHeight = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.heightPixels
            val keyboardOpen = !isInMultiWindowMode && screenHeight - visible.bottom > dp(160)
            val visibility = if (keyboardOpen) View.GONE else View.VISIBLE
            if (nav.visibility != visibility) nav.visibility = visibility

        }
        val observer = nav.viewTreeObserver
        observer.addOnGlobalLayoutListener(layoutListener)
        nav.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
            }
        })
    }

    private fun section(container: LinearLayout, title: String) {
        container.addView(label(title, 18f), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = dp(20); bottomMargin = dp(10)
        })
    }

    private fun homeContent(content: LinearLayout) {
        val recent=vault.recent()
        if(recent.isEmpty()){
            content.addView(label("Здесь появятся ваши заметки. Запишите мысль или сделайте фото ниже.",16f).apply{setTextColor(muted)})
        }else{
            var previous=""
            val date=java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
            for(note in recent.take(feedLimit)){
                val day=date.format(java.util.Date(vault.modifiedAt(note.id)))
                if(day!=previous){
                    content.addView(label(day,13f).apply{setTextColor(muted);setPadding(0,dp(12),0,dp(5))})
                    previous=day
                }
                noteCard(content,note)
            }
            if(recent.size>feedLimit)content.addView(button("Показать ещё"){feedLimit+=40;library()})
        }
    }

    private fun capturePanel():LinearLayout {
        val capture=column().apply{
            tag="bottomCapture"
            background=design.panel(design.surface,12,true)
            setPadding(dp(8),dp(6),dp(8),dp(6))
        }
        val field=EditText(this).apply{
            hint="Записать мысль…"
            contentDescription="Текст новой заметки"
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            gravity=android.view.Gravity.TOP
            textSize=16f;typeface=design.regular
            setTextColor(design.text);setHintTextColor(design.muted)
            background=design.panel(design.background,8)
            setPadding(dp(10),dp(8),dp(10),dp(8))
            minHeight=dp(48);minLines=1;maxLines=3
            setText(captureDraft)
        }
        capture.addView(field,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        changes(field){
            captureDraft=field.text.toString()
            drafts.edit().putString("text",captureDraft).apply()
        }
        val actions=row().apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        val large=resources.configuration.fontScale>=1.3f
        actions.addView(button(if(large)"Фото" else "Фото"){choosePhoto(null)}.apply{
            contentDescription="Добавить фотозаметку"
            setPadding(dp(4),0,dp(4),0)
        },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
        actions.addView(button("Голос"){voiceInput(5)}.apply{
            contentDescription="Голосовая заметка"
            setPadding(dp(4),0,dp(4),0)
            if(!large)icon(R.drawable.icon_mic)
        },LinearLayout.LayoutParams(0,WRAP_CONTENT,1f).apply{marginStart=dp(4)})
        actions.addView(button("Сохранить"){saveCapture()}.apply{
            background=design.primary();setTextColor(Color.WHITE);setPadding(dp(4),0,dp(4),0)
        },LinearLayout.LayoutParams(0,WRAP_CONTENT,1.6f).apply{marginStart=dp(4)})
        capture.addView(actions,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT).apply{topMargin=dp(4)})
        return capture
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
        page="home";library()
    }

    private fun searchContent(content: LinearLayout) {
        section(content, "Поиск")
        content.addView(button("Расширенный поиск") { showSemanticSearch() }.apply { icon(R.drawable.icon_sparkles) },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        val field = EditText(this).apply {
            hint = "Найти мысль или тему"
            contentDescription = "Поиск по заметкам"
            setSingleLine(); setText(search); textSize = 16f; typeface = design.regular
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
            if (matches.isEmpty()) {

                results.addView(label(if (search.isBlank()) "Заметок пока нет." else "Совпадений нет. Попробуйте другое слово.", 16f).apply { setTextColor(muted) })
            } else matches.forEach { noteCard(results, it) }
        }
        changes(field) { search = field.text.toString(); refresh() }
        refresh()
    }

    private fun libraryContent(content: LinearLayout) {
        section(content, "Задачи")
        tasks=true
        val list = column()
        content.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        fun refresh() {
            list.removeAllViews()
            for (note in vault.recent()) {
                if (!tasks) noteCard(list, note)
                else MarkdownMetadata.proseLines(note.text).forEach { (index, line) ->
                    val match = Regex("^\\s*[-*] \\[([ xX])] (.+)").find(line) ?: return@forEach
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
            if (list.childCount == 0) {

                list.addView(label(if (tasks) "Задач пока нет." else "Заметок пока нет.", 16f).apply { setTextColor(muted) })
            }
        }
        refresh()
    }

    private fun syncSettings() {
        val content=column().apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
        content.addView(button("Синхронизировать") {
            val url = settings.getString("url", "").orEmpty()
            val key = settings.getString("key", "").orEmpty()
            if (url.isNotEmpty() && key.isNotEmpty()) synchronize(url, key) else connection()
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })
        val syncStatus = label(BackgroundSync.status(this), 13f).apply { setTextColor(muted) }
        content.addView(CheckBox(this).apply {
            text = "Автосинхронизация в фоне"
            setTextColor(design.text); buttonTintList = ColorStateList.valueOf(design.secondary)
            minHeight = dp(48); isChecked = BackgroundSync.enabled(this@MainActivity)
            setOnClickListener {
                try {
                    BackgroundSync.configure(this@MainActivity, isChecked)
                    syncStatus.text = BackgroundSync.status(this@MainActivity)
                    syncStatus.visibility = if (isChecked) View.VISIBLE else View.GONE
                } catch (error: Exception) {
                    isChecked = BackgroundSync.enabled(this@MainActivity)
                    notice(error.message ?: "Не удалось изменить настройку")
                }
            }
        })
        syncStatus.visibility = if (BackgroundSync.enabled(this)) View.VISIBLE else View.GONE
        content.addView(syncStatus)
        AlertDialog.Builder(this).setTitle("Синхронизация").setView(content).setPositiveButton("Закрыть",null).show()
    }

    private fun noteCard(container: LinearLayout, note: Note) {
        val entry = column().apply {
            background = design.panel(design.surface, 10, true)
            foreground = design.feedback()
            setPadding(dp(12), dp(10), dp(12), dp(10))
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
            val images=PhotoStore.refs(note.text)
            val body=PhotoStore.preview(note.text)
            val time=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
                .format(java.util.Date(vault.modifiedAt(note.id)))
            val tags=MarkdownMetadata.tags(note.text)
            val context=listOf(time,if(images.isNotEmpty())"Фото: "+images.size else "",
                if(note.links.isNotEmpty())"Связей: "+note.links.distinct().size else "",
                tags.take(3).joinToString("  "){tag->KnowledgeApi.labels[tag.removePrefix("type-")]?.takeIf{tag.startsWith("type-")} ?: "#"+tag})
                .filter{it.isNotBlank()}.joinToString(" · ")
            val line=row().apply{gravity=android.view.Gravity.TOP}
            val words=column()
            words.addView(label(body,16f).apply{
                maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END
            })
            if(note.title!="Без названия" && note.title!=body && !body.startsWith(note.title+" ")){
                words.addView(label(note.title,12f).apply{setTextColor(muted);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            }
            words.addView(label(context,12f).apply{
                setTextColor(muted);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
                setPadding(0,dp(5),0,0)
            })
            line.addView(words,LinearLayout.LayoutParams(0,WRAP_CONTENT,1f))
            if(images.isNotEmpty()){
                val image=thumbnail(images.first(),88)
                line.addView(image,LinearLayout.LayoutParams(dp(72),dp(72)).apply{marginStart=dp(10)})
            }
            addView(line)
            contentDescription=listOf(body,note.title,context).distinct().joinToString(". ")
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
        graph.background = design.graphPanel()
        graph.clipToOutline = true
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
        AlertDialog.Builder(this).setItems(arrayOf("Импорт .md", "Папка для сохранения", "Удалить заметки", "Подключить другое устройство", "Развернуть свой сервер", "Настроить сервер", "Сохранить архив библиотеки", "Восстановить из архива", "Корзина на устройстве", "Синхронизация", "Обработка заметок и OpenRouter")) { _, index ->
            if (index == 0) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 1)
            else if (index == 1) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 4)
            else if (index == 2) deleteNotes()
            else if (index == 3) pairDevice()
            else if (index == 4) installServer()
            else if (index == 5) connection()
            else if (index == 6) {
                if (captureDraft.isNotBlank()) notice("Сначала сохраните черновик на главной. В архив входят только сохранённые заметки.")
                else AlertDialog.Builder(this).setMessage("Архив содержит все сохранённые заметки в открытом виде. Ключи и настройки подключения не включены. Храните файл в безопасном месте.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Выбрать файл") { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE, "Moroshka-backup.json"), 6)
                    }.show()
            } else if (index == 7) {
                AlertDialog.Builder(this).setMessage("Восстановление добавит заметки. Существующие записи не будут перезаписаны; конфликтующие версии сохранятся отдельно.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Выбрать архив") { _, _ ->
                        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE), 7)
                    }.show()
            } else if(index==8) safely { showTrash() }
            else if(index==9) safely { syncSettings() }
            else safely { processingSettings() }
        }.show()
    }

    private fun showTrash() {
        val items = vault.trashEntries()
        if (items.isEmpty()) return notice("Локальная корзина пуста.")
        val format = java.text.DateFormat.getDateTimeInstance()
        val names = items.map { it.title + "\n" + format.format(java.util.Date(it.modifiedAt)) }
        AlertDialog.Builder(this).setTitle("Корзина на устройстве")
            .setItems(names.toTypedArray()) { _, index -> safely {
                val item = items[index]
                previewRevision(item.title, vault.trashText(item.name))
            } }.setNegativeButton("Закрыть", null).show()
    }

    private fun showVersions(id: String) {
        flush()
        val versions = vault.versions(id)
        if (versions.isEmpty()) return notice("На этом устройстве пока нет предыдущих сохранённых редакций.")
        val format = java.text.DateFormat.getDateTimeInstance()
        val names = versions.map { format.format(java.util.Date(it.archivedAt)) + " · " + it.revision.take(10) }
        AlertDialog.Builder(this).setTitle("История на устройстве")
            .setItems(names.toTypedArray()) { _, index -> safely {
                previewRevision("Предыдущая редакция", vault.versionText(id, versions[index].revision))
            } }.setNegativeButton("Закрыть", null).show()
    }

    private fun previewRevision(title: String, content: String) {
        val scroll = ScrollView(this)
        scroll.addView(label(content, 16f).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        })
        AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setNegativeButton("Закрыть", null)
            .setPositiveButton("Сохранить копию") { _, _ -> safely {
                // Never resurrect an old UUID or overwrite the current note.
                flush()
                edit(vault.create(content))
            } }.show()
    }

    private fun archiveWork(message: String, operation: () -> String) {
        flush()
        syncing = true
        root.removeAllViews()
        root.addView(label(message, 22f))
        worker.execute {
            val result = runCatching(operation)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                syncing = false
                library()
                val shared = pendingShare
                if (shared != null) { pendingShare = null; safely { edit(vault.create(shared)) } }
                result.onSuccess { notice(it) }.onFailure { notice(it.message ?: "Не удалось обработать архив") }
                releaseBackground()
            }
        }
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
        flush(); current = note.id; editorImages=PhotoStore.refs(note.text); root.removeAllViews()
        val bar = row().apply { gravity = android.view.Gravity.CENTER_VERTICAL }; root.addView(bar)
        bar.addView(button("Заметки") { flush(); library() }.apply { icon(R.drawable.icon_back) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bar.addView(button("Связи") { flush(); connections(vault.read(note.id)) }.apply { icon(R.drawable.icon_link) },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
        bar.addView(button("") {
            flush()
            AlertDialog.Builder(this).setItems(arrayOf("Изменить заголовок", "Теги", "Удалить связь", "Локальный граф", "Экспорт .md", "Поделиться текстом", "История на устройстве", "Предложения ИИ", "Предложения связей", "Экспорт заметки с вложениями", "Краткое содержание")) { _, index ->
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
                        6 -> showVersions(note.id)
                        7 -> showAiSuggestions()
                        8 -> showSemanticLinks()
                        9 -> {
                            exportId = note.id
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                                .addCategory(Intent.CATEGORY_OPENABLE)
                                .putExtra(Intent.EXTRA_TITLE, vault.read(note.id).title.replace('/', '_').take(100) + ".moroshka.json"), 10)
                        }
                        10 -> showSummary()
                    }
                }
            }.show()
        }.apply { icon(R.drawable.icon_more); contentDescription = "Действия заметки"; setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) })
        val field = EditText(this).apply {
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            typeface = design.regular; textSize = 16f; setText(PhotoStore.strip(note.text))
            setTextColor(design.text)
            background = design.panel(design.surface, 20, true)
            setPadding(dp(16), dp(16), dp(16), dp(16)); setLineSpacing(dp(5).toFloat(), 1f)
            contentDescription = "Текст Markdown"
        }
        editor = field
        root.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(16); bottomMargin = dp(8) })
        if (selectTitle) { field.requestFocus(); field.setSelection(field.text.length) }
        if(editorImages.isNotEmpty())root.addView(photoStrip(note),LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        val tools = row()
        val toolScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        }
        root.addView(toolScroll)
        tools.addView(button("Фото"){choosePhoto(note.id)})
        if(editorImages.isNotEmpty())tools.addView(button("Распознать текст"){
            val ids=editorImages.toList()
            if(ids.size==1)recognizePhoto(ids.first(),note.id)
            else AlertDialog.Builder(this).setTitle("Выберите фотографию")
                .setItems(ids.mapIndexed{index,_->"Фото "+(index+1)}.toTypedArray()){_,index->safely{recognizePhoto(ids[index],note.id)}}
                .setNegativeButton("Отмена",null).show()
        })
        tools.addView(button("＋ Связь") { insertLink() }.apply { icon(R.drawable.icon_link) })
        tools.addView(button("Теги") { manageTags() }.apply { icon(R.drawable.icon_tag) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Граф") { flush(); showLocalGraph(vault.read(note.id)) }.apply { icon(R.drawable.icon_graph) },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Место") { choosePlace() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        tools.addView(button("Голос") { voiceInput() }.apply { icon(R.drawable.icon_mic) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        changes(field) { dirty = true; handler.removeCallbacks(autoSave); handler.postDelayed(autoSave, 350) }
    }
    private fun thumbnail(id:String,size:Int):ImageView {
        val view=ImageView(this).apply{
            scaleType=ImageView.ScaleType.CENTER_CROP
            background=design.panel(design.tint,8)
            clipToOutline=true
            contentDescription="Фотография"
        }
        photoWorker.execute{
            val bitmap=runCatching{vault.photos.bitmap(id,size*2)}.getOrNull()
            runOnUiThread{if(!isDestroyed && view.isAttachedToWindow && bitmap!=null)view.setImageBitmap(bitmap) else bitmap?.recycle()}
        }
        return view
    }
    private fun photoStrip(note:Note):HorizontalScrollView {
        val items=row().apply{gravity=android.view.Gravity.CENTER_VERTICAL}
        for(id in editorImages){
            val view=thumbnail(id,76).apply{
                isFocusable=true;isClickable=true;contentDescription="Открыть фотографию"
                setOnClickListener{safely{showPhoto(id,note.id)}}
            }
            items.addView(view,LinearLayout.LayoutParams(dp(76),dp(76)).apply{marginEnd=dp(6)})
        }
        return HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;addView(items)}
    }
    private fun cameraFile():java.io.File = java.io.File(cacheDir,"camera/capture.jpg")
    private fun choosePhoto(target:String?) {
        flush()
        AlertDialog.Builder(this).setTitle(if(target==null)"Фотозаметка" else "Добавить изображение")
            .setItems(arrayOf("Сделать фото","Выбрать изображение")){_,choice->safely{
                photoTarget=target
                if(choice==0){
                    val file=cameraFile()
                    file.parentFile?.mkdirs();file.delete()
                    val uri=androidx.core.content.FileProvider.getUriForFile(this,packageName+".camera",file)
                    val intent=Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                        .putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri)
                        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData=android.content.ClipData.newRawUri("Photo",uri)
                    startActivityForResult(intent,9)
                }else startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*")
                    .addCategory(Intent.CATEGORY_OPENABLE),8)
            }}.setNegativeButton("Отмена",null).show()
    }
    private fun importPhoto(uri:android.net.Uri?,camera:Boolean) {
        val target=photoTarget
        photoTarget=null
        val draft=captureDraft
        flush()
        syncing=true
        val progress=AlertDialog.Builder(this).setMessage("Сохранение фотографии…").setCancelable(false).create()
        progress.show()
        worker.execute{
            val result=runCatching{
                val id=if(camera)cameraFile().inputStream().use{vault.photos.importBytes(PhotoStore.bounded(it,32*1024*1024))}
                    else vault.photos.importUri(requireNotNull(uri))
                if(target==null){
                    val heading=draft.lineSequence().firstOrNull{it.isNotBlank()}?.trim()?.removePrefix("# ")?.take(80)?.trim().orEmpty().ifBlank{"Фотозаметка"}
                    vault.create(PhotoStore.append("# "+heading+"\n\n"+draft,id))
                }else{
                    val note=vault.read(target)
                    vault.save(note.copy(text=PhotoStore.append(note.text,id)))
                }
            }
            if(camera&&result.isSuccess)cameraFile().delete()
            runOnUiThread{
                if(isDestroyed)return@runOnUiThread
                syncing=false;progress.dismiss()
                result.onSuccess{note->
                    if(target==null){
                        if(drafts.edit().remove("text").remove("id").commit())captureDraft=""
                        page="home"
                    }
                    safely{edit(note)}
                    Toast.makeText(this,"Фотография сохранена",Toast.LENGTH_SHORT).show()
                }.onFailure{error->
                    if(camera&&cameraFile().exists()){
                        photoTarget=target
                        AlertDialog.Builder(this).setMessage((error.message?:"Не удалось сохранить фотографию")+". Исходный снимок сохранён для повторной попытки.")
                            .setNegativeButton("Закрыть",null).setPositiveButton("Повторить"){_,_->safely{importPhoto(null,true)}}.show()
                    }else notice(error.message?:"Не удалось сохранить фотографию")
                }
                val shared=pendingShare
                if(shared!=null){pendingShare=null;safely{flush();edit(vault.create(shared))}}
                releaseBackground()
            }
        }
    }
    private fun showPhoto(id:String,noteId:String) {
        flush()
        val bitmap=vault.photos.bitmap(id,1200)?:return notice("Изображение пока не загружено. Синхронизируйте библиотеку.")
        val image=ImageView(this).apply{setImageBitmap(bitmap);adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER}
        val content=column().apply{setPadding(dp(12),dp(8),dp(12),dp(8))}
        content.addView(image,LinearLayout.LayoutParams(MATCH_PARENT,WRAP_CONTENT))
        val dialog=AlertDialog.Builder(this).setTitle("Фотография")
            .setView(ScrollView(this).apply{addView(content)})
            .setNegativeButton("Закрыть",null).create()
        content.addView(button("Распознать текст"){dialog.dismiss();recognizePhoto(id,noteId)})
        content.addView(button("Убрать из заметки"){
            dialog.dismiss()
            AlertDialog.Builder(this).setMessage("Убрать изображение из этой заметки? История останется.")
                .setNegativeButton("Отмена",null).setPositiveButton("Убрать"){_,_->safely{
                    flush();val n=vault.read(noteId)
                    val updated=n.copy(text=PhotoStore.compose(PhotoStore.strip(n.text),PhotoStore.refs(n.text).filter{it!=id}))
                    vault.save(updated);edit(updated)
                }}.show()
        })
        dialog.setOnDismissListener{image.setImageDrawable(null);bitmap.recycle()}
        dialog.show()
    }
    private fun reviewOcr(note:Note,id:String,reply:org.json.JSONObject,local:Boolean){
        knowledgeUnchanged(note)
        check(reply.getString("id")==id)
        val text=reply.getString("text")
        if(text.isBlank()){notice("Читаемый текст не обнаружен. Фотография сохранена.");return}
        val field=EditText(this).apply{setText(text);minLines=4;maxLines=10;gravity=android.view.Gravity.TOP}
        AlertDialog.Builder(this).setTitle(if(local)"OCR · локально, проверьте текст" else if(reply.optBoolean("possiblyTruncated"))"OCR · возможно неполный текст" else "OCR · сервер, проверьте текст")
            .setView(field).setNegativeButton("Отмена",null)
            .setPositiveButton("Добавить в заметку"){_,_->safely{
                knowledgeUnchanged(note)
                editor?.setText(editor?.text.toString()+"\n\n"+field.text.toString())
                flush()
            }}.show()
    }
    private fun recognizePhoto(id:String,noteId:String){
        val note=knowledgeSource()?:return
        require(note.id==noteId&&id in PhotoStore.refs(note.text))
        when(mode("ocr")){
            "local" -> installLocalOcr {
                knowledgeUnchanged(note)
                localWork("Распознавание текста",{
                    val bytes=requireNotNull(vault.photos.read(id)){"Фото не найдено"}
                    org.json.JSONObject().put("id",id).put("text",localOcr.recognize(bytes))
                }){reviewOcr(note,id,it,true)}
            }
            "server" -> AlertDialog.Builder(this).setTitle("Распознавание текста")
                .setMessage("Передать сохранённую фотографию вашему серверу и модели OpenRouter? Запрос может быть платным.")
                .setNegativeButton("Отмена",null).setPositiveButton("Распознать"){_,_->
                    knowledgeRequest("/api/ai/ocr",org.json.JSONObject().put("id",id).put("consent",true),
                        prepare={api->vault.photos.push(PhotoStore.append("",id),{path,data,method->api.request(path,data,method)})}){reviewOcr(note,id,it,false)}
                }.show()
        }
    }

    private fun processingSettings(){
        val content=column().apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
        content.addView(label("Настройки этого устройства. Серверный режим требует отдельного разрешения перед отправкой данных.",14f))
        val controls=linkedMapOf<String,Spinner>()
        for((op,name) in ProcessingPrefs.operations){
            content.addView(label(name,15f).apply{setPadding(0,dp(12),0,0)})
            val spinner=Spinner(this).apply{
                adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,ProcessingPrefs.labels)
                setSelection(ProcessingPrefs.modes.indexOf(processing.mode(op)))
                minimumHeight=dp(48)
                contentDescription=name
            }
            controls[op]=spinner;content.addView(spinner)
        }
        content.addView(label("Локально: правила классификации, извлечение фраз, поиск и связи по словам, OCR Tesseract (русский + английский). Не языковая модель.",13f).apply{setTextColor(muted)})
        val dialog=AlertDialog.Builder(this).setTitle("Обработка заметок").setView(ScrollView(this).apply{addView(content)})
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить"){_,_->safely{
                processing.save(controls.mapValues{ProcessingPrefs.modes[it.value.selectedItemPosition]})
                notice("Настройки обработки сохранены.")
            }}
            .setNeutralButton("По умолчанию",null).create()
        content.addView(button("Настроить OpenRouter на сервере"){
            dialog.dismiss();showOpenRouterSettings()
        })
        content.addView(button("Языковые пакеты локального OCR"){
            dialog.dismiss();installLocalOcr()
        })
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{controls.values.forEach{it.setSelection(0)}}
    }
    private fun showOpenRouterSettings(){
        knowledgeRequest("/api/ai/config"){config->
            val content=column().apply{setPadding(dp(16),dp(8),dp(16),dp(8))}
            content.addView(label(if(config.getBoolean("configured"))"Ключ настроен на сервере" else "OpenRouter не подключён",16f))
            content.addView(label("Ключ не сохраняется в приложении. Изменения действуют на всех устройствах этого сервера.",13f).apply{setTextColor(muted)})
            content.addView(button("Получить API-ключ"){
                startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://openrouter.ai/keys")))
            })
            val key=EditText(this).apply{
                hint="Новый API-ключ (пусто — сохранить прежний)"
                setSingleLine();inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled=false
                if(android.os.Build.VERSION.SDK_INT>=26)importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO
            }
            content.addView(key)
            val models=linkedMapOf<String,EditText>()
            val names=linkedMapOf("model" to "Текст и классификация","embeddingModel" to "Эмбеддинги","visionModel" to "Фото и OCR")
            for((field,title) in names){
                content.addView(label(title,14f).apply{setPadding(0,dp(12),0,0)})
                val input=EditText(this).apply{setSingleLine();setText(config.getString(field));contentDescription=title;hint=config.getJSONObject("recommended").getString(field)}
                models[field]=input;content.addView(input)
            }
            content.addView(button("Рекомендуемые модели"){models.forEach{(name,field)->field.setText(config.getJSONObject("recommended").getString(name))}})
            content.addView(label("Пустое поле модели отключает соответствующие функции на сервере. Сохранение нового ключа проверяет его через OpenRouter. Тест ключа не отправляет заметки.",13f).apply{setTextColor(muted)})
            fun save(action:String){
                val request=org.json.JSONObject().put("revision",config.getString("revision")).put("keyAction",action)
                models.forEach{(name,field)->request.put(name,field.text.toString().trim())}
                if(action=="replace")request.put("apiKey",key.text.toString().trim())
                key.text.clear()
                knowledgeRequest("/api/ai/config",request,"PUT"){notice("Настройки OpenRouter сохранены на сервере. Перезапуск не нужен.")}
            }
            val dialog=AlertDialog.Builder(this).setTitle("OpenRouter · сервер").setView(ScrollView(this).apply{addView(content)})
                .setNegativeButton("Отмена",null).setPositiveButton("Сохранить"){_,_->safely{
                    save(if(key.text.toString().isBlank())"keep" else "replace")
                }}.create()
            content.addView(button("Проверить сохранённый ключ"){dialog.dismiss();knowledgeRequest("/api/ai/config/test",org.json.JSONObject()){notice("OpenRouter подтвердил ключ.")}})
            if(config.getBoolean("configured"))content.addView(button("Отключить ключ на сервере"){
                AlertDialog.Builder(this).setMessage("Отключить OpenRouter для всех устройств? Локальная обработка останется доступной.")
                    .setNegativeButton("Отмена",null).setPositiveButton("Отключить"){_,_->safely{dialog.dismiss();save("remove")}}.show()
            })
            dialog.setOnDismissListener{key.text.clear()}
            // Positive button captures the value before the dialog's dismiss callback.
            dialog.show()
        }
    }
    private fun localWork(message:String,operation:()->org.json.JSONObject,result:(org.json.JSONObject)->Unit){
        if(syncing)return
        val progress=AlertDialog.Builder(this).setTitle(message).setMessage("На этом устройстве…").setCancelable(false).create()
        progress.show();syncing=true
        worker.execute{
            val response=runCatching(operation)
            runOnUiThread{
                if(isDestroyed)return@runOnUiThread
                syncing=false;progress.dismiss()
                response.onSuccess{safely{result(it)}}.onFailure{notice(it.message?:"Не удалось выполнить локальную обработку")}
                val shared=pendingShare
                if(shared!=null){pendingShare=null;safely{flush();edit(vault.create(shared))}}
                releaseBackground()
            }
        }
    }
    private fun installLocalOcr(after:(()->Unit)?=null){
        if(localOcr.ready()){if(after!=null)after() else notice("Русский и английский пакеты OCR уже установлены.");return}
        AlertDialog.Builder(this).setTitle("Локальное распознавание")
            .setMessage("Загрузить языковые пакеты Tesseract (русский и английский, около 8 МБ) из официального репозитория? Фотографии не отправляются. После загрузки OCR работает без сети.")
            .setNegativeButton("Отмена",null).setPositiveButton("Загрузить"){_,_->
                localWork("Установка OCR",{localOcr.install();org.json.JSONObject()}){if(after!=null)after() else notice("Локальное OCR готово.")}
            }.show()
    }
    private fun mode(op:String):String?{
        val value=processing.mode(op)
        if(value=="off"){notice("Операция выключена. Измените режим в разделе «Обработка заметок».");return null}
        return value
    }
    private fun showSummary(){
        val note=knowledgeSource()?:return
        when(mode("summary")){
            "local" -> localWork("Краткое содержание",{org.json.JSONObject().put("summary",LocalProcessing.summary(note.text))}){reply->
                knowledgeUnchanged(note);notice("Извлечение ключевых фраз · локально\n\n"+reply.getString("summary"))
            }
            "server" -> AlertDialog.Builder(this).setMessage("Передать OpenRouter до 12 000 символов этой заметки? Возможна оплата.")
                .setNegativeButton("Отмена",null).setPositiveButton("Продолжить"){_,_->
                    knowledgeRequest("/api/summary",org.json.JSONObject().put("id",note.id).put("revision",KnowledgeApi.revision(note.text)).put("consent",true)){
                        knowledgeUnchanged(note);notice("Резюме OpenRouter\n\n"+it.getString("summary"))
                    }
                }.show()
        }
    }

    private fun knowledgeRequest(path: String, data: org.json.JSONObject? = null,
                                 method: String = if (data == null) "GET" else "POST",
                                 prepare: (KnowledgeApi)->Unit = {},
                                 result: (org.json.JSONObject) -> Unit) {
        if (syncing) return
        val connection = BackgroundSync.credentials(this) ?: return notice("Сначала подключите сервер.")
        val progress = AlertDialog.Builder(this).setTitle("Морошка · ИИ")
            .setMessage("Выполняется запрос…").setCancelable(false).create()
        progress.show()
        syncing = true
        worker.execute {
            val response = runCatching { val api=KnowledgeApi(connection.first,connection.second);prepare(api);api.request(path,data,method) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                syncing = false
                progress.dismiss()
                response.onSuccess { safely { result(it) } }
                    .onFailure { notice(it.message ?: "Не удалось выполнить запрос") }
                val shared = pendingShare
                if (shared != null) { pendingShare = null; safely { flush(); edit(vault.create(shared)) } }
                releaseBackground()
            }
        }
    }
    private fun knowledgeSource(): Note? {
        flush()
        val id = current ?: return null
        return vault.read(id)
    }
    private fun knowledgeUnchanged(note: Note) {
        flush()
        check(current == note.id && vault.read(note.id).text == note.text) { "Заметка изменилась. Повторите запрос." }
    }
    private fun reviewSuggestions(note:Note,reply:org.json.JSONObject,local:Boolean){
        knowledgeUnchanged(note)
        check(reply.getString("id")==note.id && reply.getString("revision")==KnowledgeApi.revision(note.text))
        val heading=reply.getString("title")
        val category=reply.getString("category")
        val array=reply.getJSONArray("tags")
        val tags=(0 until array.length()).map{array.getString(it)}
        KnowledgeApi.metadata(note.text,heading,tags,category)
        val choices=listOf("Заголовок: "+heading,"Тип: "+KnowledgeApi.labels.getValue(category))+tags.map{"#"+it}
        val selected=BooleanArray(choices.size)
        fun accept(all:Boolean){
            knowledgeUnchanged(note)
            val updated=KnowledgeApi.metadata(note.text,if(all||selected[0])heading else null,
                tags.filterIndexed{i,_->all||selected[i+2]},if(all||selected[1])category else null)
            editor?.setText(PhotoStore.strip(updated));flush()
        }
        AlertDialog.Builder(this).setTitle(if(local)"Предложения · локальные правила" else if(reply.optBoolean("truncated"))"Предложения ИИ · первые 12 000 символов" else "Предложения ИИ · сервер")
            .setMultiChoiceItems(choices.toTypedArray(),selected){_,i,v->selected[i]=v}
            .setNegativeButton("Отклонить",null)
            .setNeutralButton("Принять все"){_,_->safely{accept(true)}}
            .setPositiveButton("Принять выбранное"){_,_->safely{accept(false)}}.show()
    }
    private fun showAiSuggestions(){
        val note=knowledgeSource()?:return
        when(mode("metadata")){
            "local" -> localWork("Предложения",{LocalProcessing.metadata(note)}){reviewSuggestions(note,it,true)}
            "server" -> AlertDialog.Builder(this).setTitle("Предложения ИИ")
                .setMessage("Передать OpenRouter до 12 000 символов этой заметки? Запрос может быть платным. Сначала синхронизируйте её.")
                .setNegativeButton("Отмена",null).setPositiveButton("Анализировать"){_,_->
                    knowledgeRequest("/api/ai/analyze",org.json.JSONObject().put("id",note.id).put("revision",KnowledgeApi.revision(note.text)).put("consent",true)){reviewSuggestions(note,it,false)}
                }.show()
        }
    }
    private fun showSemanticSearch() {
        val selectedMode=mode("search")?:return
        if(selectedMode=="local"){
            val input=EditText(this).apply{hint="Слова или фраза";setSingleLine()}
            AlertDialog.Builder(this).setTitle("Расширенный поиск · локально").setView(input)
                .setMessage("Ранжирование по совпадению слов, без отправки запроса на сервер.")
                .setNegativeButton("Отмена",null).setPositiveButton("Найти"){_,_->safely{
                    val query=input.text.toString().trim();require(query.isNotBlank()){"Введите запрос"}
                    localWork("Поиск",{LocalProcessing.rank(query,vault.all())}){showKnowledgeResults(it,null,true)}
                }}.show()
            return
        }
        knowledgeRequest("/api/ai/status") { status ->
            val enabled=status.optBoolean("enabled")
            val pending=status.getJSONArray("pending")
            val actions=mutableListOf("Удалить векторный индекс")
            if(enabled){
                actions.add(0,"Смысловой поиск")
                if(pending.length()>0)actions.add(0,"Индексировать следующие "+pending.length()+" заметок")
            }
            AlertDialog.Builder(this).setTitle(if(enabled) "Индекс: "+status.getInt("indexed")+" из "+status.getInt("total")
                else "Модель эмбеддингов не настроена")
                .setItems(actions.toTypedArray()){_,i->safely{
                    when {
                        actions[i].startsWith("Индексировать") -> AlertDialog.Builder(this)
                            .setMessage("Передать OpenRouter до 12 000 символов каждой выбранной заметки? Возможна оплата.")
                            .setNegativeButton("Отмена",null).setPositiveButton("Индексировать"){_,_->
                                knowledgeRequest("/api/ai/index",org.json.JSONObject().put("notes",pending).put("consent",true)){ showSemanticSearch() }
                            }.show()
                        actions[i]=="Удалить векторный индекс" -> AlertDialog.Builder(this).setMessage("Удалить индекс сервера? Сами заметки останутся.")
                            .setNegativeButton("Отмена",null).setPositiveButton("Удалить"){_,_->
                                knowledgeRequest("/api/ai/index",null,"DELETE"){ notice("Индекс удалён.") }
                            }.show()
                        else -> {
                            val input=EditText(this).apply{hint="Поиск по смыслу";setSingleLine();filters=arrayOf(android.text.InputFilter.LengthFilter(1000))}
                            AlertDialog.Builder(this).setTitle("Смысловой запрос").setView(input)
                                .setMessage("Запрос будет передан OpenRouter для расчёта эмбеддинга.")
                                .setNegativeButton("Отмена",null).setPositiveButton("Найти"){_,_->safely{
                                    val query=input.text.toString().trim()
                                    require(query.isNotEmpty()){"Введите запрос"}
                                    knowledgeRequest("/api/ai/search",org.json.JSONObject().put("query",query).put("consent",true)){ reply ->
                                        showKnowledgeResults(reply,null)
                                    }
                                }}.show()
                        }
                    }
                }}.setNegativeButton("Закрыть",null).show()
        }
    }
    private fun showSemanticLinks() {
        val note=knowledgeSource() ?: return
        when(mode("links")){
            "local" -> localWork("Предложения связей",{LocalProcessing.rank(PhotoStore.strip(note.text),vault.all(),note.id)}){showKnowledgeResults(it,note,true)}
            "server" -> knowledgeRequest("/api/ai/related",org.json.JSONObject().put("id",note.id)
                .put("revision",KnowledgeApi.revision(note.text))){ reply -> showKnowledgeResults(reply,note) }
        }
    }
    private fun showKnowledgeResults(reply: org.json.JSONObject,source:Note?,local:Boolean=false) {
        val array=reply.getJSONArray("notes")
        if(array.length()==0)return notice(if(local)"Совпадений по словам нет." else "Похожих индексированных заметок пока нет.")
        val results=(0 until array.length()).map{array.getJSONObject(it)}
        val titles=results.map{it.getString("title")}
        AlertDialog.Builder(this).setTitle(if(source==null)"Результаты поиска" else "Предложения смысловых связей")
            .setItems(titles.toTypedArray()){_,i->safely{
                val row=results[i]
                val target=vault.all().firstOrNull{it.id==row.getString("id")}
                check(target!=null && KnowledgeApi.revision(target.text)==row.getString("revision")){"Целевая заметка изменилась. Повторите поиск."}
                if(source==null)edit(target)
                else {
                    knowledgeUnchanged(source)
                    AlertDialog.Builder(this).setMessage("Добавить вики-ссылку на «"+target.title+"»? "+if(local)"Сходство слов." else "Сходство эмбеддингов.")
                        .setNegativeButton("Отмена",null).setPositiveButton("Связать"){_,_->safely{
                            knowledgeUnchanged(source)
                            val actual=vault.read(target.id)
                            check(actual.text==target.text){"Целевая заметка изменилась."}
                            editor?.setText(PhotoStore.strip(KnowledgeApi.link(source.text,actual.title,vault.all().map{it.title},source.links)))
                            flush()
                        }}.show()
                }
            }}.setNegativeButton("Закрыть",null).show()
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
            field.setText(MarkdownMetadata.removeLink(field.text.toString(), links[index]))
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
        }, centerFirst = true)
        graph.background = design.graphPanel()
        graph.clipToOutline = true
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
        val epoch = SyncAccess.epoch()
        worker.execute {
            val result = runCatching { vault.sync(url, key, { SyncAccess.sameEpoch(epoch) }) }
            if (SyncAccess.sameEpoch(epoch)) {
                result.onSuccess { BackgroundSync.record(applicationContext,
                    if (it == 0) "Синхронизировано" else "Сохранены конфликтные копии: $it", true) }
                    .onFailure { BackgroundSync.record(applicationContext, "Последний обмен не завершён") }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                syncing = false; library()
                val shared = pendingShare
                if (shared != null) { pendingShare = null; safely { edit(vault.create(shared)) } }
                result.onSuccess {
                    if (it > 0) notice("Обе версии сохранены. Конфликтных копий: $it. Проверьте их и повторите синхронизацию.")
                    else Toast.makeText(this,"Синхронизировано",Toast.LENGTH_SHORT).show()
                }.onFailure { notice(it.message ?: "Сервер недоступен") }
                releaseBackground()
            }
        }
    }
    @Deprecated("Platform document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != RESULT_OK) {
            if(requestCode==9)cameraFile().delete()
            if(requestCode==8||requestCode==9)photoTarget=null
            return
        }
        SyncAccess.enter(syncOwner)
        if(requestCode==8||requestCode==9){safely{importPhoto(data?.data,requestCode==9)};return}
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
            } else if (requestCode == 6) {
                archiveWork("Сохранение архива…") {
                    val notes = vault.all()
                    val bytes = BackupArchive.encode(notes,vault.photos::read)
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes); it.flush() }
                    "Архив сохранён. Заметок: " + notes.size
                }
            } else if (requestCode == 7) {
                archiveWork("Восстановление архива…") {
                    val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { BackupArchive.read(it) }
                    val result = vault.restoreArchive(bytes)
                    "Добавлено: " + result.added + ". Уже есть: " + result.skipped +
                        ". Конфликтных копий: " + result.conflicts + "."
                }
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
            } else if (requestCode == 10) {
                val id = requireNotNull(exportId)
                exportId = null
                archiveWork("Сохранение заметки с вложениями…") {
                    val bytes = BackupArchive.encode(listOf(vault.read(id)),vault.photos::read)
                    requireNotNull(contentResolver.openOutputStream(uri,"wt")).use{it.write(bytes);it.flush()}
                    "Заметка и её вложения сохранены в переносимый архив."
                }
            } else if (requestCode == 2) {
                val note = vault.read(requireNotNull(exportId))
                requireNotNull(contentResolver.openOutputStream(uri,"wt")).use { it.write(note.text.toByteArray(Charsets.UTF_8)) }
                if(PhotoStore.refs(note.text).isNotEmpty())notice("Markdown содержит ссылки на фотографии. Для переноса файлов выберите «Экспорт заметки с вложениями» в меню редактора.")
                exportId = null
            }
        }
    }
}
