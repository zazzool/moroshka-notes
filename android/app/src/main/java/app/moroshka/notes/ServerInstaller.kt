package app.moroshka.notes

import android.content.Context
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/** Installs the bundled release over SSH. Password is held only for this operation. */
class ServerInstaller(private val context: Context, private val confirmHost: (String) -> Boolean) {
    private class LimitedOutput : OutputStream() {
        private val bytes = ByteArrayOutputStream()
        @Synchronized override fun write(value: Int) {
            if (bytes.size() < 32768) bytes.write(value)
        }
        @Synchronized fun text(): String = bytes.toString("UTF-8")
    }

    private fun command(session: Session, command: String, seconds: Long): String {
        val channel = session.openChannel("exec") as ChannelExec
        val output = LimitedOutput()
        val errors = LimitedOutput()
        channel.setCommand(command)
        channel.setOutputStream(output)
        channel.setErrStream(errors)
        channel.connect(15000)
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (!channel.isClosed) {
                if (Thread.currentThread().isInterrupted || System.nanoTime() > deadline) {
                    throw IllegalStateException("Установка прервалась или превысила время ожидания. Повторите попытку.")
                }
                Thread.sleep(200)
            }
            check(channel.exitStatus == 0) {
                val detail = errors.text().trim().takeLast(800)
                if (detail.isBlank()) "Ошибка на сервере (код ${channel.exitStatus})" else "Ошибка на сервере: $detail"
            }
            return output.text()
        } finally { channel.disconnect() }
    }

    fun install(host: String, password: ByteArray, sshPort: Int = 22, httpsPort: Int = 443, progress: (String) -> Unit): Pair<String, String> {
        require(sshPort in 1..65535 && httpsPort in 1..65535)
        require(host.matches(Regex("^(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z0-9-]{1,63}$"))) {
            "Укажите IPv4-адрес или полное доменное имя сервера."
        }
        val knownHosts = File(context.filesDir, "ssh_known_hosts").apply { if (!exists()) createNewFile() }
        val ssh = JSch().apply { setKnownHosts(knownHosts.absolutePath) }
        val session = ssh.getSession("root", host, sshPort)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", "password")
        session.setServerAliveInterval(15000)
        session.setServerAliveCountMax(3)
        session.setPassword(password)
        session.userInfo = object : UserInfo {
            override fun getPassphrase(): String? = null
            override fun getPassword(): String? = null
            override fun promptPassword(message: String): Boolean = false
            override fun promptPassphrase(message: String): Boolean = false
            override fun showMessage(message: String) {}
            override fun promptYesNo(message: String): Boolean = confirmHost(message)
        }
        var stage: String? = null
        var installationInProgress = false
        try {
            progress("Подключение по SSH…")
            session.connect(20000)
            stage = command(session, "mktemp -d /tmp/moroshka-mobile.XXXXXXXX", 15).trim()
            check(stage.matches(Regex("/tmp/moroshka-mobile\\.[A-Za-z0-9]{8}"))) { "Не удалось подготовить сервер" }
            val digest = MessageDigest.getInstance("SHA-256")
            val sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(15000)
            try {
                fun upload(path: String, destination: String) {
                    val children = context.assets.list(path) ?: emptyArray()
                    if (children.isNotEmpty()) {
                        sftp.mkdir(destination)
                        children.sorted().forEach { upload("$path/$it", "$destination/$it") }
                    } else {
                        digest.update(path.toByteArray(Charsets.UTF_8))
                        context.assets.open(path).use { input ->
                            sftp.put(input, destination)
                        }
                        context.assets.open(path).use { input ->
                            val buffer = ByteArray(16384)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                    }
                }
                progress("Передача приложения на сервер…")
                for (name in context.assets.list("server").orEmpty().sorted()) upload("server/$name", "$stage/$name")
            } finally { sftp.disconnect() }
            val revision = digest.digest().joinToString("") { "%02x".format(it) }.take(40)
            progress("Установка сервера и HTTPS. Это может занять несколько минут…")
            installationInProgress = true
            // The remote installation survives an SSH disconnect or Android process death.
            command(session, "nohup sh -c 'cd \"$stage\"; bash deploy/install.sh \"$host\" \"$revision\" > install.log 2>&1; printf \"%s\\n\" \"\$?\" > install.status' </dev/null >/dev/null 2>&1 &", 15)
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20)
            var status = ""
            while (status.isBlank() || status == "running") {
                check(System.nanoTime() < deadline) { "Сервер ещё устанавливается. Повторите подключение позже." }
                Thread.sleep(2000)
                status = command(session, "if [ -f '$stage/install.status' ]; then cat '$stage/install.status'; else echo running; fi", 15).trim()
            }
            installationInProgress = false
            check(status == "0") {
                val detail = command(session, "tail -c 1200 '$stage/install.log'", 15).trim()
                "Установка завершилась с ошибкой: $detail"
            }
            val reader = session.openChannel("sftp") as ChannelSftp
            reader.connect(15000)
            val token = try {
                reader.get("/etc/moroshka/keys.env").bufferedReader().useLines { lines ->
                    lines.firstOrNull { it.startsWith("MOROSHKA_TOKEN=") }?.substringAfter("=")
                }
            } finally { reader.disconnect() }
            require(!token.isNullOrBlank() && token.length >= 24) { "Сервер запущен, но не удалось прочитать ключ синхронизации" }
            progress("Проверка защищённого подключения…")
            val url = "https://$host" + if (httpsPort == 443) "" else ":$httpsPort"
            val check = URL("$url/api/notes").openConnection() as HttpsURLConnection
            try {
                check.instanceFollowRedirects = false
                check.connectTimeout = 15000
                check.readTimeout = 15000
                check.setRequestProperty("Authorization", "Bearer $token")
                check(check.responseCode == 200) { "Не удалось проверить сервер с телефона: HTTP ${check.responseCode}" }
            } finally { check.disconnect() }
            return url to token
        } finally {
            password.fill(0)
            if (session.isConnected && stage != null && !installationInProgress) {
                try { command(session, "rm -rf '$stage'", 15) } catch (_: Exception) {}
            }
            session.disconnect()
        }
    }
}
