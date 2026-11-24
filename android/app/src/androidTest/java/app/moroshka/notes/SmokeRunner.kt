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
    override fun onStart() {
        val result = runCatching {
            if (args.containsKey("installFixture")) return@runCatching installFixture()
            if (args.containsKey("designFixture")) return@runCatching designFixture()
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
