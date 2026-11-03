package io.github.julssvanderhaack.luna.remote

import io.github.julssvanderhaack.luna.tr
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.Perms
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * FTP, or FTPS with explicit TLS (AUTH TLS, then PROT P for the data too).
 * An FTP control connection does one thing at a time, so listings share one
 * and each download or upload opens its own, letting you browse while a copy runs.
 */
class FtpVfs private constructor(override val server: Server, private val password: String?) : RemoteVfs {
    override val id = "sftp:${server.id}"
    private var control: FTPClient = open()
    private var mlsd = true

    override val isOpen get() = synchronized(this) { control.isConnected }
    override val home: String by lazy { server.startPath.ifBlank { runCatching { call { it.printWorkingDirectory() } }.getOrNull() ?: "/" } }

    override fun close() = synchronized(this) { quit(control) }

    private fun quit(c: FTPClient) {
        runCatching { c.logout() }
        runCatching { c.disconnect() }
    }

    private fun open(): FTPClient {
        val c = if (server.protocol == Protocol.FTPS) FTPSClient("TLS", false) else FTPClient()
        c.controlEncoding = "UTF-8"
        c.connectTimeout = 15_000
        c.setDefaultTimeout(30_000)
        c.setControlKeepAliveTimeout(Duration.ofSeconds(60))
        try {
            c.connect(server.host, server.port)
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw IOException(c.replyString.trim())
            val user = server.user.ifBlank { "anonymous" }
            if (!c.login(user, password ?: if (user == "anonymous") "luna@" else "")) throw AuthException()
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P")
            }
            c.sendCommand("OPTS UTF8 ON")
            c.enterLocalPassiveMode()
            c.setFileType(FTP.BINARY_FILE_TYPE)
            return c
        } catch (e: Exception) {
            quit(c)
            throw e
        }
    }

    /** Runs on the shared control connection, reconnecting once if the server dropped it. */
    private fun <T> call(block: (FTPClient) -> T): T = synchronized(this) {
        try {
            if (!control.isConnected) control = open()
            block(control)
        } catch (e: org.apache.commons.net.ftp.FTPConnectionClosedException) {
            control = open()
            block(control)
        } catch (e: java.net.SocketException) {
            control = open()
            block(control)
        }
    }

    private fun check(ok: Boolean, c: FTPClient) {
        if (!ok) throw IOException(c.replyString?.trim()?.ifEmpty { null } ?: tr("El servidor FTP lo ha rechazado"))
    }

    private fun entry(dir: String, f: FTPFile) = FileEntry(
        name = f.name,
        path = Paths.join(dir, f.name),
        isDir = f.isDirectory,
        size = if (f.isDirectory) 0 else f.size.coerceAtLeast(0),
        modified = f.timestamp?.timeInMillis ?: 0,
        isLink = f.isSymbolicLink,
    )

    override fun list(dir: String): List<FileEntry> = call { c ->
        // MLSD gives exact dates and types; very old servers only know LIST
        val files = if (mlsd) {
            runCatching { c.mlistDir(dir) }.getOrNull()?.takeIf { FTPReply.isPositiveCompletion(c.replyCode) }
                ?: run { mlsd = false; c.listFiles(dir) }
        } else c.listFiles(dir)
        if (files == null) throw IOException(c.replyString.trim())
        files.filter { it.name != "." && it.name != ".." && !it.name.isNullOrEmpty() }.map { entry(dir, it) }
    }

    override fun stat(path: String): FileEntry? {
        if (path == "/" || path.isEmpty()) return FileEntry("/", "/", true, 0, 0)
        return call { c ->
            if (mlsd) runCatching { c.mlistFile(path) }.getOrNull()?.let { return@call entry(Paths.parent(path), it.apply { name = Paths.name(path) }) }
            null
        } ?: runCatching { list(Paths.parent(path)).firstOrNull { it.name == Paths.name(path) } }.getOrNull()
    }

    override fun mkdir(path: String) = call { check(it.makeDirectory(path), it) }

    override fun createFile(path: String) = call { check(it.storeFile(path, java.io.ByteArrayInputStream(ByteArray(0))), it) }

    override fun move(from: String, to: String): Boolean = call { it.rename(from, to).also { ok -> check(ok, it) } }

    override fun deleteOne(path: String, isDir: Boolean) = call { check(if (isDir) it.removeDirectory(path) else it.deleteFile(path), it) }

    override fun openRead(path: String): InputStream {
        val c = open()
        val s = c.retrieveFileStream(path) ?: run {
            val msg = c.replyString.trim()
            quit(c)
            throw IOException(msg)
        }
        return object : FilterInputStream(s) {
            override fun close() {
                runCatching { super.close() }
                runCatching { c.completePendingCommand() }
                quit(c)
            }
        }
    }

    override fun openWrite(path: String): OutputStream {
        val c = open()
        val s = c.storeFileStream(path) ?: run {
            val msg = c.replyString.trim()
            quit(c)
            throw IOException(msg)
        }
        return object : FilterOutputStream(s) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                runCatching { super.close() }
                val ok = runCatching { c.completePendingCommand() }.getOrDefault(false)
                quit(c)
                if (!ok) throw IOException(tr("El servidor FTP no ha confirmado el archivo"))
            }
        }
    }

    override fun setModified(path: String, millis: Long) {
        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        call { it.setModificationTime(path, fmt.format(Date(millis))) }
    }

    override fun permissions(path: String): Perms? = null

    companion object {
        fun connect(s: Server, password: String?) = FtpVfs(s, password)
    }
}
