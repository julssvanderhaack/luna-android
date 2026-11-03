package io.github.julssvanderhaack.luna.remote

import io.github.julssvanderhaack.luna.fs.Vfs
import java.util.concurrent.ConcurrentHashMap

/** A connected server, whatever its protocol. */
interface RemoteVfs : Vfs {
    val server: Server
    val isOpen: Boolean
    /** Where browsing starts. */
    val home: String
    fun close()
}

/**
 * One live connection per saved server, made on first use and reused until
 * it drops: SFTP through [Sftp] (host keys, private keys), SMB and FTP here.
 */
class Remotes(private val store: ServerStore) {
    val sftp = Sftp(store)
    private val others = ConcurrentHashMap<String, RemoteVfs>()

    /** Passwords typed for servers that don't save one; gone when the process dies. */
    val typedPasswords get() = sftp.typedPasswords

    /** Blocking. Throws [HostKeyException] (SFTP) or [AuthException] for the UI to handle. */
    fun connect(s: Server): RemoteVfs {
        if (s.protocol == Protocol.SFTP) return sftp.connect(s)
        others[s.id]?.takeIf { it.isOpen }?.let { return it }
        others.remove(s.id)?.close()
        val pw = s.password ?: typedPasswords[s.id]
        val vfs = when (s.protocol) {
            Protocol.SMB -> SmbVfs.connect(s, pw)
            else -> FtpVfs.connect(s, pw)
        }
        others[s.id] = vfs
        return vfs
    }

    fun disconnect(serverId: String) {
        sftp.disconnect(serverId)
        others.remove(serverId)?.close()
    }
}
