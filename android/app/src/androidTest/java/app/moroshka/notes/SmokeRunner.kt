package app.moroshka.notes

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Button
import android.content.res.Configuration
import android.graphics.Color
import android.speech.RecognizerIntent
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Run on a disposable debug installation. Optional HTTPS fixture is local to the emulator. */
class SmokeRunner : Instrumentation() {
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        args = arguments
        start()
    }
    private fun findEditor(view: View): EditText? {
        if (view is EditText && view.contentDescription == "Текст Markdown") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findEditor(view.getChildAt(i))?.let { return it }
        }
        return null
    }
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), predicate)?.let { return it }
        }
        return null
    }

    private fun designFixture(): String {
        val draftPreferences = targetContext.getSharedPreferences("capture_draft", 0)
        val originalDraft = draftPreferences.getString("text", null)
        val originalDraftID = draftPreferences.getString("id", null)
        val fixture = "Дизайн " + java.util.UUID.randomUUID()
        val target = Vault(targetContext).create("# Цель " + fixture + "\n")
        var created: Note? = null
        val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        try {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(targetContext.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                val palette = Design(targetContext.createConfigurationContext(config))
                fun contrast(a: Int, b: Int): Double {
                    val x = Color.luminance(a).toDouble()
                    val y = Color.luminance(b).toDouble()
                    return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
                }
                check(contrast(palette.text, palette.surface) >= 4.5)
                check(contrast(palette.muted, palette.background) >= 4.5)
                check(palette.gradient.all { contrast(Color.WHITE, it) >= 4.5 })
            }
            check(targetContext.resources.getFont(R.font.manrope) != null)
            check(targetContext.getDrawable(R.mipmap.moroshka_launcher) is android.graphics.drawable.AdaptiveIconDrawable)
            for (asset in listOf(R.drawable.cloudberry, R.drawable.landscape_cloudberry,
                R.drawable.landscape_dawn, R.drawable.landscape_sunset, R.drawable.landscape_night,
                R.drawable.illustration_ideas, R.drawable.illustration_knowledge,
                R.drawable.illustration_connections, R.drawable.illustration_balance, R.drawable.illustration_search)) {
                check(targetContext.getDrawable(asset) != null)
            }
            val source = "# Исходный\n\nТекст #тег\n\n\u0060\u0060\u0060\n#код\n\u0060\u0060\u0060"
            check(MarkdownMetadata.tags(source) == listOf("тег"))
            check("#код" in MarkdownMetadata.remove(source, "тег"))
            check("Текст #тег" in MarkdownMetadata.rename(source, "Новое"))
            val codeOnly = "\u0060\u0060\u0060\n# Код\n\u0060\u0060\u0060"
            check(MarkdownMetadata.rename(codeOnly, "Название") == "# Название\n\n" + codeOnly)
            check("новый" in MarkdownMetadata.tags(MarkdownMetadata.add(source, "новый")))
            val withWhitespace = source + "  \n"
            check(MarkdownMetadata.add(withWhitespace, "новый").startsWith(withWhitespace))

            runOnMainSync {
                val root = activity.window.decorView
                check(find(root) { it.tag == "homeHero" } == null)
                val capture=requireNotNull(find(root){it.tag=="bottomCapture"})
                val pos=IntArray(2);capture.getLocationOnScreen(pos)
                check(pos[1]>=root.height/2){"Capture must be in the lower half"}
                val field = find(root) { it is EditText && it.contentDescription == "Текст новой заметки" } as EditText
                field.setText(fixture)
                val resultMethod = activity.javaClass.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Intent::class.java)
                resultMethod.isAccessible = true
                resultMethod.invoke(activity, 5, Activity.RESULT_CANCELED, null)
                check(field.text.toString() == fixture)
                val speech = Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf("голос"))
                resultMethod.invoke(activity, 5, Activity.RESULT_OK, speech)
                val updated = find(activity.window.decorView) { it is EditText && it.contentDescription == "Текст новой заметки" } as EditText
                check(updated.text.toString().contains("голос"))
                updated.setText(fixture + " [[" + target.title + "]] #тест")
                val save = find(activity.window.decorView) { it is Button && it.text.toString() == "Сохранить" } as Button
                check(save.performClick())
                check(find(activity.window.decorView){it.tag=="bottomCapture"}!=null)
                check(find(activity.window.decorView){it.contentDescription?.toString()?.contains(fixture)==true}!=null)
            }
            created = Vault(targetContext).all().single { it.title.startsWith(fixture) }
            runOnMainSync {
                val root = activity.window.decorView
                val search = find(root) { it is Button && it.text.toString() == "Поиск" } as Button
                check(search.performClick())
                val input = find(activity.window.decorView) { it is EditText && it.contentDescription == "Поиск по заметкам" } as EditText
                input.setText(fixture)
                check(find(activity.window.decorView) { it.contentDescription?.toString()?.contains(created!!.title) == true } != null)
                val links = find(activity.window.decorView) { it is Button && it.text.toString() == "Связи" } as Button
                check(links.performClick())
                check(find(activity.window.decorView) { it is KnowledgeGraph } != null)
            }
            return "Design-and-capture-ok"
        } finally {
            created?.let { Vault(targetContext).remove(listOf(it.id)) }
            Vault(targetContext).remove(listOf(target.id))
            val edit = draftPreferences.edit()
            if (originalDraft == null) edit.remove("text") else edit.putString("text", originalDraft)
            if (originalDraftID == null) edit.remove("id") else edit.putString("id", originalDraftID)
            edit.commit()
            runOnMainSync { activity.finish() }
        }
    }

    private fun installFixture(): String {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        val der = Base64.decode(args.getString("fixtureCa"), Base64.DEFAULT)
        store.setCertificateEntry("fixture", CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()))
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val original = HttpsURLConnection.getDefaultSSLSocketFactory()
        val hosts = java.io.File(targetContext.filesDir, "ssh_known_hosts")
        hosts.delete()
        var prompts = 0
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            var rejectedHost = 0
            val rejected = ServerInstaller(targetContext) { rejectedHost++; false }
            check(runCatching { rejected.install("127.0.0.1", "fixture-password".toByteArray(), 22222, 18444) {} }.isFailure)
            check(rejectedHost == 1)
            val installer = ServerInstaller(targetContext) { prompts++; true }
            val started = java.util.concurrent.CountDownLatch(1)
            val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
            val thread = Thread {
                try {
                    installer.install("127.0.0.1", "fixture-password".toByteArray(), 22222, 18444) { message ->
                        if (message.startsWith("Установка")) started.countDown()
                    }
                } catch (_: Exception) {
                    interrupted.set(true)
                }
            }
            thread.start()
            check(started.await(60, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(3000)
            thread.interrupt()
            thread.join(20000)
            check(interrupted.get() && !thread.isAlive) { "Interrupted installation did not stop waiting" }
            Thread.sleep(9000)
            val first = installer.install("127.0.0.1", "fixture-password".toByteArray(), 22222, 18444) {}
            check(first.first == "https://127.0.0.1:18444" && first.second.length >= 24)
            val second = installer.install("127.0.0.1", "fixture-password".toByteArray(), 22222, 18444) {}
            check(first == second)
            check(prompts == 1) { "Host key should be approved once" }
            val wrongPassword = runCatching { installer.install("127.0.0.1", "incorrect".toByteArray(), 22222, 18444) {} }
            check(wrongPassword.isFailure)
            return "SSH-install-ok"
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(original)
        }
    }
    private fun backupFixture(): String {
        val id = "11111111-1111-1111-1111-111111111111"
        val original = Note(id, "# Архив\n\n[[Лес]]\n- [ ] Задача")
        val archive = BackupArchive.encode(listOf(original))
        val verified = BackupArchive.decode(archive)
        check(verified.single().note == original)
        val current = Note(id, "# Новая правка")
        val plan = BackupArchive.plan(verified, listOf(current))
        check(plan.additions.size == 1 && plan.conflicts == 1)
        check(plan.additions.single().text == original.text && plan.additions.single().id != id)
        val repeated = BackupArchive.plan(verified, listOf(current) + plan.additions)
        check(repeated.additions.isEmpty() && repeated.skipped == 1)
        check(BackupArchive.plan(verified, emptyList(), setOf(id)).additions.single().id != id)
        val tampered = org.json.JSONObject(String(archive, Charsets.UTF_8))
        tampered.getJSONArray("notes").getJSONObject(0).put("text", "corrupted")
        check(runCatching { BackupArchive.decode(tampered.toString().toByteArray()) }.isFailure)
        check(runCatching { BackupArchive.encode(listOf(original, original)) }.isFailure)
        return "Portable-backup-ok"
    }

    private fun historyFixture(): String {
        val vault = Vault(targetContext)
        val first = vault.create("# История\n\nПервый текст", false)
        val next = first.copy(text = "# История\n\nВторой текст")
        vault.save(next, false)
        vault.save(next, false)
        val version = vault.versions(first.id).single()
        check(vault.versionText(first.id, version.revision) == first.text)
        check(vault.read(first.id).text == next.text)
        vault.save(first, false)
        vault.save(next, false)
        check(vault.versions(first.id).size == 2)
        val restored = vault.create(vault.versionText(first.id, version.revision), false)
        check(restored.id != first.id && restored.text == first.text)
        check(vault.read(first.id).text == next.text)
        vault.remove(listOf(first.id))
        val deleted = vault.trashEntries().single { it.name == first.id + ".md" }
        check(vault.trashText(deleted.name) == next.text)
        val fromTrash = vault.create(vault.trashText(deleted.name), false)
        check(fromTrash.id != first.id && fromTrash.text == next.text)
        check(vault.all().none { it.id == first.id })
        check(vault.trashEntries().any { it.name == deleted.name })
        check(vault.versions(first.id).size == 2)
        check(runCatching { vault.trashText("../connection") }.isFailure)
        check(runCatching { vault.versionText(first.id, "../bad") }.isFailure)
        return "History-and-trash-ok"
    }

    private fun syncGateFixture(): String {
        val owner = Any()
        val other = Any()
        val first = requireNotNull(SyncAccess.backgroundLease())
        check(SyncAccess.backgroundAllowed(first))
        SyncAccess.enter(owner)
        try {
            check(SyncAccess.backgroundLease() == null)
            check(!SyncAccess.backgroundAllowed(first))
            SyncAccess.enter(other)
            SyncAccess.leave(owner)
            check(SyncAccess.backgroundLease() == null)
        } finally {
            SyncAccess.leave(owner)
            SyncAccess.leave(other)
        }
        val next = requireNotNull(SyncAccess.backgroundLease())
        check(next != first && !SyncAccess.backgroundAllowed(first))
        check(SyncAccess.backgroundAllowed(next))
        val vault = Vault(targetContext)
        check(runCatching { vault.sync("https://localhost:1", "fixture", { false }) }
            .exceptionOrNull() is SyncDeferred)
        return "Automatic-sync-gate-ok"
    }

    private fun incrementalFixture(): String {
        val text = "# Передаваемая редакция"
        val revision = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()
        fun item(id: String) = org.json.JSONObject().put("id",id).put("revision",revision)
        val manifest = org.json.JSONObject().put("version",1)
            .put("notes",org.json.JSONArray(listOf(item(first),item(second))))
            .put("deleted",org.json.JSONArray())
        val calls = mutableListOf<String>()
        val result = SyncPull.snapshot({ path, data, method ->
            calls.add(path)
            if (path == "/api/sync/manifest") manifest
            else {
                check(path == "/api/sync/read" && method == "POST")
                val batch = requireNotNull(data).getJSONArray("notes")
                check(batch.length() == 1 && batch.getJSONObject(0).getString("id") == second)
                org.json.JSONObject().put("notes",org.json.JSONArray(listOf(item(second).put("text",text))))
            }
        },mapOf(first to revision),{})
        check(calls == listOf("/api/sync/manifest","/api/sync/read"))
        val rows = result.getJSONArray("notes")
        check(!rows.getJSONObject(0).has("text") && rows.getJSONObject(1).getString("text") == text)
        val fallback = SyncPull.snapshot({ path, _, _ ->
            if (path == "/api/sync/manifest") throw SyncHttpError(404)
            check(path == "/api/notes")
            org.json.JSONObject().put("notes",org.json.JSONArray())
        },emptyMap(),{})
        check(fallback.getJSONArray("notes").length() == 0)
        return "Incremental-sync-ok"
    }

    private fun knowledgeFixture(): String {
        val text="# Original\n\nИсходник #manual"
        val changed=KnowledgeApi.metadata(text,"Новое",listOf("manual","tag"),"idea")
        check(changed.startsWith("# Новое\n\nИсходник #manual"))
        check("tag" in MarkdownMetadata.tags(changed) && "type-idea" in MarkdownMetadata.tags(changed))
        check(KnowledgeApi.metadata(text,null,emptyList(),null)==text)
        val code="~~~\n# original"
        check(KnowledgeApi.metadata(code,null,listOf("tag"),null)=="#tag\n\n"+code)
        check(runCatching{KnowledgeApi.metadata(text,"bad\nheading",emptyList(),null)}.isFailure)
        check(runCatching{KnowledgeApi.link(text,"Same",listOf("same","Same"),emptyList())}.isFailure)
        check(KnowledgeApi.link(text,"Target",listOf("Target"),emptyList())=="[[Target]]\n\n"+text)
        val ticks = "\u0060"
        val example = listOf(ticks.repeat(4)+"js","# Fake","#type-task [[Fake]]","- [ ] Fake",
            ticks.repeat(3),ticks.repeat(4)+"not closing","# Still fake",ticks.repeat(4),
            "# Real","Body "+ticks+"#fake [[Fake]]"+ticks+" #real [[Target]]",
            "    #fake [[Fake]]","- [ ] Real task","#type-task").joinToString("\n")
        check(MarkdownMetadata.title(example)=="Real")
        check(MarkdownMetadata.links(example)==listOf("Target"))
        check(MarkdownMetadata.tags(example)==listOf("real","type-task"))
        check(MarkdownMetadata.proseLines(example).filter{it.second.startsWith("- [ ]")}.size==1)
        val changedExample=KnowledgeApi.metadata(example,"New",listOf("REAL","new"),"idea")
        check(MarkdownMetadata.title(changedExample)=="New")
        check(changedExample.contains("#type-task [[Fake]]"))
        check(MarkdownMetadata.tags(changedExample)==listOf("real","new","type-idea"))
        check(MarkdownMetadata.removeLink(example,"Target").contains(ticks+"#fake [[Fake]]"+ticks))
        return "Knowledge-metadata-ok"
    }

    private fun recoveryFixture(): String {
        val vault = Vault(targetContext)
        val live = vault.create("# Live\n\nCurrent text", false)
        val old = live.copy(text = "# Old\n\nArchived text")
        val before = vault.all().size
        vault.restoreDeleted(old)
        vault.restoreDeleted(old)
        check(vault.read(live.id) == live)
        val archive = vault.trashEntries().single { it.name.startsWith(live.id + ".") }
        check(archive.name.endsWith(".archived") && vault.trashText(archive.name) == old.text)
        check(vault.restoreFolderNotes(listOf(old)).added == 1)
        check(vault.restoreFolderNotes(listOf(old)).added == 0)
        check(vault.all().size == before + 1 && vault.read(live.id) == live)
        val removed = vault.create("# Removed locally", false)
        vault.remove(listOf(removed.id))
        check(vault.restoreFolderNotes(listOf(removed)).added == 1)
        check(vault.restoreFolderNotes(listOf(removed)).added == 0)
        check(vault.all().none { it.id == removed.id })
        return "Recovery-ok"
    }

    private fun photoFixture():String {
        val vault=Vault(targetContext)
        val bitmap=android.graphics.Bitmap.createBitmap(120,80,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val output=java.io.ByteArrayOutputStream()
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
        bitmap.recycle()
        val id=vault.photos.importBytes(output.toByteArray())
        val bytes=requireNotNull(vault.photos.read(id))
        PhotoStore.verify(id,bytes)
        val original="# Фото\n\nОписание"
        val text=PhotoStore.append(original,id)
        check(PhotoStore.refs(text)==listOf(id) && PhotoStore.strip(text)==original)
        val note=vault.create(text,false)
        val archive=BackupArchive.encode(listOf(note),vault.photos::read)
        val decoded=BackupArchive.decodeBundle(archive)
        check(decoded.notes.single().note==note&&decoded.images.getValue(id).contentEquals(bytes))
        check(vault.restoreArchive(archive).skipped==1)
        val activity=startActivitySync(Intent(targetContext,MainActivity::class.java)
            .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitForIdleSync()
        runOnMainSync{
            val editor=requireNotNull(findEditor(activity.window.decorView))
            check(editor.text.toString()==original)
            check(find(activity.window.decorView){it.contentDescription=="Открыть фотографию"}!=null)
            editor.setText(original+"\n\nПравка")
            activity.onBackPressed()
            activity.finish()
        }
        check(vault.all().any{PhotoStore.strip(it.text).endsWith("Правка")&&id in PhotoStore.refs(it.text)})
        return "Photo-capture-storage-archive-ok"
    }

    private fun processingFixture():String {
        val prefs=ProcessingPrefs(targetContext)
        check(ProcessingPrefs.operations.keys.all{prefs.mode(it)=="local"})
        prefs.save(ProcessingPrefs.operations.keys.associateWith{if(it=="ocr")"server" else "off"})
        check(ProcessingPrefs(targetContext).mode("ocr")=="server"&&prefs.mode("metadata")=="off")
        val note=Note(java.util.UUID.randomUUID().toString(),"# Старое\n\nПроект сна. Сон и память.\n- [ ] Задача")
        val proposal=LocalProcessing.metadata(note)
        check(proposal.getString("category")=="task")
        check(LocalProcessing.summary(note.text).contains("Сон"))
        val unrelated=Note(java.util.UUID.randomUUID().toString(),"# Лес\n\nДеревья")
        val ranked=LocalProcessing.rank("сон",listOf(unrelated,note)).getJSONArray("notes")
        check(ranked.length()==1&&ranked.getJSONObject(0).getString("id")==note.id)
        val engine=LocalOcr(targetContext)
        check(engine.ready())
        val bitmap=android.graphics.Bitmap.createBitmap(1200,300,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val canvas=android.graphics.Canvas(bitmap)
        val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
            color=android.graphics.Color.BLACK;textSize=64f;typeface=android.graphics.Typeface.DEFAULT
        }
        canvas.drawText("HELLO WORLD",60f,110f,paint)
        canvas.drawText("ПРИВЕТ МИР",60f,220f,paint)
        val output=java.io.ByteArrayOutputStream()
        check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,output));bitmap.recycle()
        val recognized=engine.recognize(output.toByteArray()).uppercase(java.util.Locale.ROOT)
        check("HELLO" in recognized&&"ПРИВЕТ" in recognized){"OCR не распознал тестовый текст: "+recognized}
        return "Local-processing-and-Cyrillic-OCR-ok"
    }

    override fun onStart() {
        val result = runCatching {
            if (args.containsKey("installFixture")) return@runCatching installFixture()
            if (args.containsKey("designFixture")) return@runCatching designFixture()
            if (args.containsKey("backupFixture")) return@runCatching backupFixture()
            if (args.containsKey("historyFixture")) return@runCatching historyFixture()
            if (args.containsKey("syncGateFixture")) return@runCatching syncGateFixture()
            if (args.containsKey("incrementalFixture")) return@runCatching incrementalFixture()
            if (args.containsKey("knowledgeFixture")) return@runCatching knowledgeFixture()
            if (args.containsKey("photoFixture")) return@runCatching photoFixture()
            if (args.containsKey("processingFixture")) return@runCatching processingFixture()
            if (args.containsKey("recoveryFixture")) return@runCatching recoveryFixture()
            val title = "Native " + java.util.UUID.randomUUID()
            val text = "# $title\n\n[[Лес]]\n- [ ] Исследовать"
            val activity = startActivitySync(Intent(targetContext, MainActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val edited = "$text\n\nNative edit"
            runOnMainSync {
                val editor = requireNotNull(findEditor(activity.window.decorView))
                check(editor.text.toString() == text)
                editor.setText(edited)
                activity.onBackPressed()
            }
            val vault = Vault(targetContext)
            val note = vault.all().single { it.title == title }
            check(note.text == edited && note.links == listOf("Лес"))
            var removed = false
            if (args.containsKey("fixtureCa")) {
                val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
                val der = Base64.decode(args.getString("fixtureCa"), Base64.DEFAULT)
                store.setCertificateEntry("local-fixture", CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()))
                val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
                val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
                val original = HttpsURLConnection.getDefaultSSLSocketFactory()
                try {
                    HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
                    check(vault.sync("https://localhost:18443", requireNotNull(args.getString("fixtureKey"))) == 0)
                    vault.remove(listOf(note.id))
                    removed = true
                    check(vault.sync("https://localhost:18443", requireNotNull(args.getString("fixtureKey"))) == 0)
                } finally {
                    HttpsURLConnection.setDefaultSSLSocketFactory(original)
                }
            }
            if (!removed) vault.remove(listOf(note.id))
            check(vault.all().none { it.id == note.id })
            check(java.io.File(targetContext.filesDir, "trash/${note.id}.md").readText() == edited)
            runOnMainSync { activity.finish() }
            note.id
        }
        val output = Bundle()
        result.onSuccess { output.putString("noteId", it) }
            .onFailure { output.putString("error", it.stackTraceToString()) }
        finish(if (result.isSuccess) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
