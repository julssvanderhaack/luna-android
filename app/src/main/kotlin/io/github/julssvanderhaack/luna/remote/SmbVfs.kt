package io.github.julssvanderhaack.luna.remote

import io.github.julssvanderhaack.luna.tr
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Paths
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * A Windows/Samba shared folder through smbj. Luna's paths are POSIX ("/a/b")
 * from the share's root; SMB wants "a\b". Bouncy Castle supplies the MD4 and
 * DES that NTLM needs and Android doesn't ship.
 */
class SmbVfs private constructor(
    override val server: Server,
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : RemoteVfs {
    override val id = "sftp:${server.id}"
    override val isOpen get() = connection.isConnected && share.isConnected
    override val home get() = server.startPath.ifBlank { "/" }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    private fun smb(path: String) = path.trim('/').replace('/', '\\')

    private fun missing(e: SMBApiException) = e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
        e.status == NtStatus.STATUS_NO_SUCH_FILE

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw IOException(
            when (e.status) {
                NtStatus.STATUS_ACCESS_DENIED -> tr("El servidor no te deja hacer eso (permiso denegado)")
                NtStatus.STATUS_OBJECT_NAME_COLLISION -> tr("Ya existe")
                NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> tr("La carpeta no está vacía")
                NtStatus.STATUS_SHARING_VIOLATION -> tr("Otro programa lo tiene abierto")
                NtStatus.STATUS_DISK_FULL -> tr("No queda espacio en el servidor")
                else -> e.status.name
            },
            e,
        )
    }

    override fun list(dir: String): List<FileEntry> = call {
        share.list(smb(dir)).filter { it.fileName != "." && it.fileName != ".." }.map {
            val isDir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            FileEntry(
                name = it.fileName,
                path = Paths.join(dir, it.fileName),
                isDir = isDir,
                size = if (isDir) 0 else it.endOfFile,
                modified = it.lastWriteTime.toEpochMillis(),
            )
        }
    }

    override fun stat(path: String): FileEntry? {
        if (smb(path).isEmpty()) return FileEntry("/", "/", true, 0, 0)
        return try {
            val info = share.getFileInformation(smb(path))
            val isDir = info.standardInformation.isDirectory
            FileEntry(Paths.name(path), path, isDir, if (isDir) 0 else info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis())
        } catch (e: SMBApiException) {
            if (missing(e)) null else throw IOException(e.status.name, e)
        }
    }

    override fun mkdir(path: String) = call { share.mkdir(smb(path)) }

    override fun createFile(path: String) = call {
        share.openFile(smb(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_CREATE, null).close()
    }

    override fun move(from: String, to: String): Boolean = call {
        share.open(
            smb(from), EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        ).use { it.rename(smb(to)) }
        true
    }

    override fun deleteOne(path: String, isDir: Boolean) = call { if (isDir) share.rmdir(smb(path), false) else share.rm(smb(path)) }

    override fun openRead(path: String): InputStream = call {
        val f = share.openFile(
            smb(path), EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        object : FilterInputStream(f.inputStream) {
            override fun close() {
                runCatching { super.close() }
                f.close()
            }
        }
    }

    override fun openWrite(path: String): OutputStream = call {
        val f = share.openFile(
            smb(path), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        object : FilterOutputStream(f.outputStream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                runCatching { super.close() }
                f.close()
            }
        }
    }

    override fun setModified(path: String, millis: Long) {
        val t = FileTime.ofEpochMillis(millis)
        share.setFileInformation(smb(path), FileBasicInformation(FileBasicInformation.DONT_SET, FileBasicInformation.DONT_SET, t, FileBasicInformation.DONT_SET, 0))
    }

    companion object {
        fun connect(s: Server, password: String?): SmbVfs {
            if (s.share.isBlank()) throw IOException(tr("Falta el nombre de la carpeta compartida (Editar servidor)"))
            val client = SMBClient(
                SmbConfig.builder().withSecurityProvider(BCSecurityProvider()).withTimeout(30, TimeUnit.SECONDS)
                    .withSoTimeout(60, TimeUnit.SECONDS).build(),
            )
            try {
                val conn = client.connect(s.host, s.port)
                // "DOMINIO\usuario" as on Windows; no user = guest
                val (domain, user) = s.user.split('\\', limit = 2).let { if (it.size == 2) it[0] to it[1] else "" to it[0] }
                val auth = if (user.isBlank()) AuthenticationContext.guest() else AuthenticationContext(user, password.orEmpty().toCharArray(), domain)
                val session = try {
                    conn.authenticate(auth)
                } catch (e: SMBApiException) {
                    if (e.status == NtStatus.STATUS_LOGON_FAILURE || e.status == NtStatus.STATUS_ACCESS_DENIED) throw AuthException()
                    throw IOException(e.status.name, e)
                }
                val share = try {
                    session.connectShare(s.share.trim('/', '\\')) as? DiskShare ?: throw IOException(tr("«%1» no es una carpeta compartida", s.share))
                } catch (e: SMBApiException) {
                    if (e.status == NtStatus.STATUS_BAD_NETWORK_NAME) throw IOException(tr("El servidor no tiene una carpeta compartida llamada «%1»", s.share))
                    if (e.status == NtStatus.STATUS_ACCESS_DENIED) throw AuthException()
                    throw IOException(e.status.name, e)
                }
                return SmbVfs(s, client, conn, session, share)
            } catch (e: Exception) {
                client.close()
                throw e
            }
        }
    }
}
