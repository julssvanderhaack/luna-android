package io.github.julssvanderhaack.luna.settings

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.julssvanderhaack.luna.fs.SortBy
import io.github.julssvanderhaack.luna.fs.SortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.store by preferencesDataStore("settings")

/** The phone's own SFTP server, for the PC to connect to. */
data class ServerConfig(
    val port: Int = 2222,
    val user: String = "luna",
    val password: String = "",
    /** OpenSSH authorized_keys lines; with any, the PC can log in without the password. */
    val authorizedKeys: String = "",
    /** What the PC sees as "/". */
    val root: String = Environment.getExternalStorageDirectory().path,
    val readOnly: Boolean = false,
)

data class AppSettings(
    val theme: String = DEFAULT_THEME,
    val dynamicColor: Boolean = false,
    val showHidden: Boolean = false,
    val sort: SortOrder = SortOrder(),
    /** Grid or list, remembered apart for photo/video shortcuts and for ordinary folders. */
    val gridMedia: Boolean = true,
    val gridFiles: Boolean = false,
    /** Folder paths ("sftp:<id>:<path>" for remote ones), in the order they were added. */
    val favorites: List<String> = emptyList(),
    val server: ServerConfig = ServerConfig(),
) {
    companion object {
        const val DEFAULT_THEME = "cobalt2"
    }
}

class Settings(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val hidden = booleanPreferencesKey("show_hidden")
        val sortBy = stringPreferencesKey("sort_by")
        val sortDesc = booleanPreferencesKey("sort_desc")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val gridMedia = booleanPreferencesKey("grid_media")
        val gridFiles = booleanPreferencesKey("grid_files")
        val favorites = stringPreferencesKey("favorites")
        val port = intPreferencesKey("server_port")
        val user = stringPreferencesKey("server_user")
        val password = stringPreferencesKey("server_password")
        val keys = stringPreferencesKey("server_keys")
        val root = stringPreferencesKey("server_root")
        val readOnly = booleanPreferencesKey("server_read_only")
    }

    val flow: Flow<AppSettings> = context.store.data.map { p ->
        val d = ServerConfig()
        AppSettings(
            theme = p[K.theme] ?: AppSettings.DEFAULT_THEME,
            dynamicColor = p[K.dynamic] ?: false,
            showHidden = p[K.hidden] ?: false,
            sort = SortOrder(
                by = p[K.sortBy]?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME,
                descending = p[K.sortDesc] ?: false,
                foldersFirst = p[K.foldersFirst] ?: true,
            ),
            gridMedia = p[K.gridMedia] ?: true,
            gridFiles = p[K.gridFiles] ?: false,
            favorites = p[K.favorites]?.lines()?.filter { it.isNotBlank() } ?: emptyList(),
            server = ServerConfig(
                port = p[K.port] ?: d.port,
                user = p[K.user] ?: d.user,
                password = p[K.password] ?: "",
                authorizedKeys = p[K.keys] ?: "",
                root = p[K.root] ?: d.root,
                readOnly = p[K.readOnly] ?: false,
            ),
        )
    }

    private suspend fun set(block: (MutablePreferences) -> Unit) {
        context.store.edit { block(it) }
    }

    suspend fun setTheme(name: String) = set { it[K.theme] = name }
    suspend fun setDynamicColor(on: Boolean) = set { it[K.dynamic] = on }
    suspend fun setShowHidden(on: Boolean) = set { it[K.hidden] = on }
    suspend fun setSort(s: SortOrder) = set {
        it[K.sortBy] = s.by.name
        it[K.sortDesc] = s.descending
        it[K.foldersFirst] = s.foldersFirst
    }
    suspend fun setGrid(media: Boolean, on: Boolean) = set { it[if (media) K.gridMedia else K.gridFiles] = on }
    suspend fun setFavorites(list: List<String>) = set { it[K.favorites] = list.joinToString("\n") }

    suspend fun setServer(c: ServerConfig) = set {
        it[K.port] = c.port
        it[K.user] = c.user
        it[K.password] = c.password
        it[K.keys] = c.authorizedKeys
        it[K.root] = c.root
        it[K.readOnly] = c.readOnly
    }

    /** The server config, with a random password made up the first time. */
    suspend fun serverConfig(): ServerConfig {
        val c = flow.first().server
        if (c.password.isNotEmpty()) return c
        val withPw = c.copy(password = newPassword())
        setServer(withPw)
        return withPw
    }

    companion object {
        /** Easy to type on a PC: no look-alikes (0/O, 1/l/I). */
        fun newPassword(): String {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            val r = SecureRandom()
            return (1..10).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
