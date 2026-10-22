package io.github.julssvanderhaack.luna.server

import io.github.julssvanderhaack.luna.settings.ServerConfig
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.KeySetPublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator
import org.apache.sshd.server.auth.pubkey.RejectAllPublickeyAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.session.ServerSession
import org.apache.sshd.sftp.server.FileHandle
import org.apache.sshd.sftp.server.Handle
import org.apache.sshd.sftp.server.SftpEventListener
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.io.IOException
import java.nio.file.CopyOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.PublicKey

/**
 * The SFTP-only server behind [SftpServerService], kept apart from the
 * Android service so the JVM tests run the very same configuration.
 */
fun buildSshServer(cfg: ServerConfig, hostKey: File, listener: SessionListener? = null): SshServer =
    SshServer.setUpDefaultServer().apply {
        port = cfg.port
        keyPairProvider = SimpleGeneratorHostKeyProvider(hostKey.toPath())
        passwordAuthenticator = PasswordAuthenticator { u, p, _ -> u == cfg.user && cfg.password.isNotEmpty() && p == cfg.password }
        val keys = authorizedKeys(cfg.authorizedKeys)
        publickeyAuthenticator = if (keys.isEmpty()) RejectAllPublickeyAuthenticator.INSTANCE
        else KeySetPublickeyAuthenticator("luna", keys).let { auth -> PublickeyAuthenticator { u, k, s -> u == cfg.user && auth.authenticate(u, k, s) } }
        keyboardInteractiveAuthenticator = null
        fileSystemFactory = VirtualFileSystemFactory(File(cfg.root).toPath())
        subsystemFactories = listOf(SftpSubsystemFactory.Builder().build().also { if (cfg.readOnly) it.addSftpEventListener(ReadOnly) })
        listener?.let { addSessionListener(it) }
    }

/** Parses pasted authorized_keys text one line at a time, so a bad line doesn't lose the good ones. */
fun authorizedKeys(text: String): List<PublicKey> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith('#') }
    .mapNotNull { line ->
        runCatching {
            AuthorizedKeyEntry.parseAuthorizedKeyEntry(line, null)?.resolvePublicKey(null, PublicKeyEntryResolver.IGNORING)
        }.getOrNull()
    }
    .toList()

/** Refuses anything that would change a file when the server is read-only. */
private object ReadOnly : SftpEventListener {
    private fun no(): Nothing = throw IOException("Servidor de solo lectura")
    override fun opening(session: ServerSession, remoteHandle: String, localHandle: Handle) {
        if (localHandle is FileHandle) {
            val o = localHandle.openOptions
            if (StandardOpenOption.WRITE in o || StandardOpenOption.APPEND in o || StandardOpenOption.CREATE in o) no()
        }
    }
    override fun creating(session: ServerSession, path: Path, attrs: MutableMap<String, *>) = no()
    override fun moving(session: ServerSession, srcPath: Path, dstPath: Path, opts: MutableCollection<CopyOption>) = no()
    override fun removing(session: ServerSession, path: Path, isDirectory: Boolean) = no()
    override fun linking(session: ServerSession, source: Path, target: Path, symLink: Boolean) = no()
    override fun modifyingAttributes(session: ServerSession, path: Path, attrs: MutableMap<String, *>) = no()
}
