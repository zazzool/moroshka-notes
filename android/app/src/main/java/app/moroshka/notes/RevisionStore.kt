package app.moroshka.notes

import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Immutable, content-addressed snapshots. Vault serializes all local mutations. */
internal class RevisionStore(private val root: File) {
    data class Version(val revision: String, val archivedAt: Long)
    private val idPattern = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")
    private val hashPattern = Regex("[0-9a-f]{64}")
    private fun folder(id: String): File {
        require(idPattern.matches(id))
        return File(root, id)
    }
    private fun path(id: String, revision: String): File {
        require(hashPattern.matches(revision))
        return File(folder(id), "$revision.md")
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun checked(file: File, revision: String): ByteArray {
        val bytes = AtomicFile(file).readFully()
        check(hash(bytes) == revision) { "Контрольная сумма редакции не совпадает" }
        return bytes
    }

    fun record(id: String, bytes: ByteArray) {
        val revision = hash(bytes)
        val file = path(id, revision)
        if (file.exists() || File(file.path + ".bak").exists()) {
            checked(file, revision)
            return
        }
        val parent = requireNotNull(file.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Не удалось создать каталог истории" }
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
        // If archiving fails, the caller must not replace the live note.
        checked(file, revision)
    }

    fun list(id: String): List<Version> = folder(id).listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".md") && hashPattern.matches(it.name.removeSuffix(".md")) }
        .map { Version(it.name.removeSuffix(".md"), it.lastModified()) }
        .sortedWith(compareByDescending<Version> { it.archivedAt }.thenBy { it.revision })

    fun read(id: String, revision: String): String = checked(path(id, revision), revision).toString(Charsets.UTF_8)
}
