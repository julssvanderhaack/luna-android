package io.github.julssvanderhaack.luna.remote

import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.Vfs
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.keyverifier.ServerKeyVerifier
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.FilePasswordProvider
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.apache.sshd.sftp.common.SftpException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** The server's key isn't the one we know (or we know none): the user decides. */
class HostKeyException(val hostPort: String, val fingerprint: String, val changed: Boolean) :
    IOException(if (changed) "La huella del servidor ha cambiado" else "Servidor desconocido")

class AuthException : IOException("Usuario o contraseña incorrectos")

/**
 * One SSH client for the whole app, one SFTP session per server, reused
 * until it drops. Host keys are trusted on first use, like OpenSSH, but
 * only after the user has seen the fingerprint.
 */
class Sftp(private val store: ServerStore) {
    private val sessions = ConcurrentHashMap<String, SftpVfs>()
    /** Passwords typed for servers that don't save one; gone when the process dies. */
    val typedPasswords = ConcurrentHashMap<String, String>()
    private val pendingKeys = ConcurrentHashMap<String, Pair<String, Boolean>>()

    private val client: SshClient by lazy {
        SshClient.setUpDefaultClient().apply {
            serverKeyVerifier = ServerKeyVerifier { session, _, key ->
                val hp = session.getAttribute(HOST_PORT) ?: return@ServerKeyVerifier false
                val fp = KeyUtils.getFingerPrint(key)
                val known = store.knownFingerprint(hp)
                if (known == fp) true else {
                    pendingKeys[hp] = fp to (known != null)
                    false
                }
            }
            start()
        }
    }

    fun connected(serverId: String): SftpVfs? = sessions[serverId]?.takeIf { it.isOpen }

    /** Blocking. Throws [HostKeyException] or [AuthException] for the UI to handle. */
    fun connect(s: Server): SftpVfs {
        connected(s.id)?.let { return it }
        sessions.remove(s.id)?.close()
        val session: ClientSession = client.connect(s.user, s.host, s.port).verify(Duration.ofSeconds(12)).session
        session.setAttribute(HOST_PORT, "${s.host}:${s.port}")
        try {
            val pw = s.password ?: typedPasswords[s.id]
            if (s.hasKey) {
                store.keyFile(s.id).inputStream().use { input ->
                    SecurityUtils.loadKeyPairIdentities(
                        null, NamedResource.ofName(s.title), input,
                        FilePasswordProvider.of(s.keyPassphrase.orEmpty()),
                    )?.forEach { session.addPublicKeyIdentity(it) }
                }
            }
            if (!pw.isNullOrEmpty()) session.addPasswordIdentity(pw)
            try {
                session.auth().verify(Duration.ofSeconds(20))
            } catch (e: Exception) {
                pendingKeys.remove("${s.host}:${s.port}")?.let { (fp, changed) -> throw HostKeyException("${s.host}:${s.port}", fp, changed) }
                throw AuthException()
            }
            val sftp = SftpClientFactory.instance().createSftpClient(session)
            val vfs = SftpVfs(s, session, sftp)
            sessions[s.id] = vfs
            return vfs
        } catch (e: Exception) {
            session.close(true)
            pendingKeys.remove("${s.host}:${s.port}")?.let { (fp, changed) -> throw HostKeyException("${s.host}:${s.port}", fp, changed) }
            throw e
        }
    }

    fun disconnect(serverId: String) {
        sessions.remove(serverId)?.close()
    }

    companion object {
        private val HOST_PORT = org.apache.sshd.common.AttributeRepository.AttributeKey<String>()
    }
}

class SftpVfs(val server: Server, private val session: ClientSession, private val sftp: SftpClient) : Vfs {
    override val id = "sftp:${server.id}"
    val isOpen get() = session.isOpen && sftp.isOpen

    /** Where browsing starts: the configured folder, or the user's home on the server. */
    val home: String by lazy { server.startPath.ifBlank { runCatching { sftp.canonicalPath(".") }.getOrDefault("/") } }

    fun close() {
        runCatching { sftp.close() }
        runCatching { session.close(true) }
    }

    private fun entry(name: String, path: String, a: SftpClient.Attributes): FileEntry {
        var attrs = a
        // links get the type and size of what they point to, like `ls -L`
        if (a.isSymbolicLink) runCatching { sftp.stat(path) }.getOrNull()?.let { attrs = it }
        return FileEntry(
            name = name,
            path = path,
            isDir = attrs.isDirectory,
            size = if (attrs.isDirectory) 0 else attrs.size,
            modified = attrs.modifyTime?.toMillis() ?: 0,
            isLink = a.isSymbolicLink,
        )
    }

    override fun list(dir: String): List<FileEntry> =
        sftp.readDir(dir).filter { it.filename != "." && it.filename != ".." }
            .map { entry(it.filename, Paths.join(dir, it.filename), it.attributes) }

    override fun stat(path: String): FileEntry? = try {
        entry(Paths.name(path), path, sftp.lstat(path))
    } catch (e: SftpException) {
        if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE) null else throw e
    }

    override fun mkdir(path: String) = sftp.mkdir(path)

    override fun createFile(path: String) {
        sftp.write(path).close()
    }

    override fun move(from: String, to: String): Boolean {
        sftp.rename(from, to)
        return true
    }

    override fun deleteOne(path: String, isDir: Boolean) = if (isDir) sftp.rmdir(path) else sftp.remove(path)

    override fun openRead(path: String): InputStream = sftp.read(path)
    override fun openWrite(path: String): OutputStream = sftp.write(path)

    override fun setModified(path: String, millis: Long) {
        val a = sftp.stat(path)
        val t = java.nio.file.attribute.FileTime.fromMillis(millis)
        sftp.setStat(path, SftpClient.Attributes().modifyTime(t).accessTime(a.accessTime ?: t))
    }
}
