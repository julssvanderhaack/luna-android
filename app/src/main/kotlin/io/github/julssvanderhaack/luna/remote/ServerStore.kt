package io.github.julssvanderhaack.luna.remote

import io.github.julssvanderhaack.luna.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Protocol(val label: String, val scheme: String, val defaultPort: Int) {
    SFTP(tr("SFTP (SSH)"), "sftp", 22),
    SMB(tr("SMB (Windows, NAS)"), "smb", 445),
    FTP("FTP", "ftp", 21),
    FTPS(tr("FTPS (FTP con TLS)"), "ftps", 21),
}

/** A saved server. The private key of an SFTP one, if any, is a copy in files/keys/<id>. */
data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    /** Null = ask every time (kept in memory until the app closes). */
    val password: String? = null,
    val hasKey: Boolean = false,
    val keyPassphrase: String? = null,
    /** Empty = the user's home folder on the server. */
    val startPath: String = "",
    val protocol: Protocol = Protocol.SFTP,
    /** SMB only: the shared folder ("Público", "fotos"…); Windows can't list them to other clients without RPC. */
    val share: String = "",
) {
    val title get() = name.ifBlank { if (user.isBlank()) host else "$user@$host" }
    val hostPort get() = if (port == protocol.defaultPort) host else "$host:$port"
    /** sftp://user@host:port/, as the PC would write it. */
    val url get() = "${protocol.scheme}://${if (user.isBlank()) "" else "$user@"}$hostPort/${if (protocol == Protocol.SMB) share else ""}"

    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("host", host); put("port", port); put("user", user)
        password?.let { put("password", it) }
        put("hasKey", hasKey)
        keyPassphrase?.let { put("keyPassphrase", it) }
        put("startPath", startPath)
        put("protocol", protocol.name)
        put("share", share)
    }

    companion object {
        fun fromJson(o: JSONObject) = Server(
            id = o.getString("id"),
            name = o.optString("name"),
            host = o.getString("host"),
            port = o.optInt("port", 22),
            user = o.optString("user"),
            password = if (o.has("password")) o.getString("password") else null,
            hasKey = o.optBoolean("hasKey"),
            keyPassphrase = if (o.has("keyPassphrase")) o.getString("keyPassphrase") else null,
            startPath = o.optString("startPath"),
            protocol = runCatching { Protocol.valueOf(o.optString("protocol")) }.getOrDefault(Protocol.SFTP),
            share = o.optString("share"),
        )
    }
}

/**
 * Saved servers and the host keys the user has accepted, as JSON in the
 * app's private storage (not backed up: allowBackup is off).
 */
class ServerStore(dir: File) {
    private val file = File(dir, "servers.json")
    private val hostsFile = File(dir, "known_hosts.json")
    val keyDir = File(dir, "keys")
    private val _servers = MutableStateFlow(load())
    val servers: StateFlow<List<Server>> = _servers

    fun find(id: String) = _servers.value.firstOrNull { it.id == id }

    fun keyFile(id: String) = File(keyDir, id)

    @Synchronized
    fun save(s: Server) {
        val list = _servers.value.filter { it.id != s.id } + s
        write(list)
    }

    @Synchronized
    fun delete(id: String) {
        keyFile(id).delete()
        write(_servers.value.filter { it.id != id })
    }

    private fun write(list: List<Server>) {
        file.writeText(JSONArray(list.map { it.toJson() }).toString(1))
        _servers.value = list
    }

    private fun load(): List<Server> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { Server.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun knownFingerprint(hostPort: String): String? = hosts().optString(hostPort).ifEmpty { null }

    @Synchronized
    fun trust(hostPort: String, fingerprint: String) {
        hostsFile.writeText(hosts().put(hostPort, fingerprint).toString(1))
    }

    private fun hosts() = runCatching { JSONObject(hostsFile.readText()) }.getOrDefault(JSONObject())
}
