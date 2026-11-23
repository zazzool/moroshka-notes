package app.moroshka.notes

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
    override fun onStart() {
        val result = runCatching {
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
