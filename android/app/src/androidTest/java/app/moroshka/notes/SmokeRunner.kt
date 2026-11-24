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
                check(find(root) { it.tag == "homeHero" } != null)
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
                val editor = requireNotNull(findEditor(activity.window.decorView))
                check(editor.text.toString().contains(fixture))
                activity.onBackPressed()
            }
            created = Vault(targetContext).all().single { it.title.startsWith(fixture) }
            runOnMainSync {
                val root = activity.window.decorView
                val search = find(root) { it is Button && it.text.toString() == "Поиск" } as Button
                check(search.performClick())
                val input = find(activity.window.decorView) { it is EditText && it.contentDescription == "Поиск по заметкам" } as EditText
                input.setText(fixture)
                check(find(activity.window.decorView) { it.contentDescription?.toString()?.startsWith(created!!.title) == true } != null)
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

    override fun onStart() {
        val result = runCatching {
            if (args.containsKey("installFixture")) return@runCatching installFixture()
            if (args.containsKey("designFixture")) return@runCatching designFixture()
            if (args.containsKey("backupFixture")) return@runCatching backupFixture()
            if (args.containsKey("historyFixture")) return@runCatching historyFixture()
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
