package io.github.julssvanderhaack.luna.ui

import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.julssvanderhaack.luna.LunaApp
import io.github.julssvanderhaack.luna.fs.Category
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.fs.MediaQueries
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.Progress
import io.github.julssvanderhaack.luna.fs.SortOrder
import io.github.julssvanderhaack.luna.fs.Tally
import io.github.julssvanderhaack.luna.fs.Vfs
import io.github.julssvanderhaack.luna.fs.fold
import io.github.julssvanderhaack.luna.fs.sortEntries
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.settings.AppSettings
import io.github.julssvanderhaack.luna.settings.ServerConfig
import io.github.julssvanderhaack.luna.theme.Palette
import io.github.julssvanderhaack.luna.theme.ResolvedPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where a file list comes from. */
sealed interface Loc {
    data class Local(val path: String) : Loc
    /** An empty [path] means the server's start folder, resolved on connect. */
    data class Remote(val serverId: String, val path: String = "") : Loc
    data class Media(val category: Category) : Loc
}

val Loc.path get() = when (this) {
    is Loc.Local -> path
    is Loc.Remote -> path
    is Loc.Media -> ""
}

/** The form favourites are stored in. */
val Loc.key get() = when (this) {
    is Loc.Local -> path
    is Loc.Remote -> "sftp:$serverId:$path"
    is Loc.Media -> "media:${category.name}"
}

fun locFromKey(key: String): Loc? = when {
    key.startsWith("sftp:") -> key.removePrefix("sftp:").split(':', limit = 2).takeIf { it.size == 2 }?.let { Loc.Remote(it[0], it[1]) }
    key.startsWith("media:") -> runCatching { Loc.Media(Category.valueOf(key.removePrefix("media:"))) }.getOrNull()
    else -> Loc.Local(key)
}

sealed interface Route {
    data object Home : Route
    data class Browse(val loc: Loc) : Route
    data object PhoneServer : Route
    /** Null = a new server. */
    data class EditServer(val id: String?) : Route
    data object Settings : Route
    data object Themes : Route
    data class ThemeEditor(val base: String, val editing: Boolean) : Route
}

/** Copied or cut files waiting for "Pegar". [vfsId] says which file system they're on. */
data class Clip(val vfsId: String, val entries: List<FileEntry>, val cut: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val luna = app as LunaApp
    val themes = luna.themes
    private val store = luna.settings

    val settings: StateFlow<AppSettings> = store.flow.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val palette: StateFlow<ResolvedPalette> = combine(themes.themes, settings) { list, s ->
        (list.firstOrNull { it.name == s.theme } ?: list.firstOrNull { it.name == AppSettings.DEFAULT_THEME }
            ?: Palette("default", emptyMap())).resolve()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Palette("default", emptyMap()).resolve())

    val volumes = luna.volumes.list
    val servers = luna.servers.servers

    val stack = mutableStateListOf<Route>(Route.Home)

    /** Bumped after anything that changes files, so open lists reload. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick

    private val _recents = MutableStateFlow<List<FileEntry>?>(null)
    val recents: StateFlow<List<FileEntry>?> = _recents

    private val _clip = MutableStateFlow<Clip?>(null)
    val clip: StateFlow<Clip?> = _clip

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress
    private var opJob: Job? = null

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    /** Paths selected in the current list; empty = not in selection mode. */
    var selection by mutableStateOf(emptySet<String>())

    init {
        viewModelScope.launch { themes.load() }
    }

    fun say(text: String) {
        _messages.tryEmit(text)
    }

    // ---- navigation ----

    fun open(r: Route) {
        selection = emptySet()
        stack.add(r)
    }

    fun back() {
        selection = emptySet()
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun home() {
        selection = emptySet()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Opens a folder. Going up to one already in the history pops back to it instead of piling up. */
    fun browse(loc: Loc) {
        selection = emptySet()
        val i = stack.indexOfLast { it is Route.Browse && it.loc == loc }
        if (i >= 0) {
            while (stack.size > i + 1) stack.removeAt(stack.lastIndex)
        } else {
            stack.add(Route.Browse(loc))
        }
    }

    fun replaceTop(r: Route) {
        stack[stack.lastIndex] = r
    }

    // ---- listing ----

    fun vfsIdOf(loc: Loc) = if (loc is Loc.Remote) "sftp:${loc.serverId}" else LocalVfs.id

    /** Blocking; may throw HostKeyException/AuthException for remote folders. */
    fun vfs(loc: Loc): Vfs = vfsById(vfsIdOf(loc))

    private fun vfsById(id: String): Vfs {
        if (id == LocalVfs.id) return LocalVfs
        val serverId = id.removePrefix("sftp:")
        val s = luna.servers.find(serverId) ?: throw java.io.IOException("Ese servidor ya no está guardado")
        return luna.sftp.connect(s)
    }

    /** The real folder of [loc] once connected (a remote "" becomes the server's start folder). */
    suspend fun resolve(loc: Loc): Loc = withContext(Dispatchers.IO) {
        if (loc is Loc.Remote && loc.path.isEmpty()) {
            val s = luna.servers.find(loc.serverId) ?: return@withContext loc
            loc.copy(path = luna.sftp.connect(s).home)
        } else loc
    }

    suspend fun list(loc: Loc, s: AppSettings): List<FileEntry> = withContext(Dispatchers.IO) {
        when (loc) {
            is Loc.Media -> MediaQueries.query(getApplication(), loc.category)
                .filter { File(it.path).exists() }
                .let { if (loc.category == Category.RECENT || loc.category.media) it else sortEntries(it, s.sort) }
            else -> {
                val all = vfs(loc).list(loc.path)
                sortEntries(if (s.showHidden) all else all.filter { !it.hidden }, s.sort)
            }
        }
    }

    fun loadRecents() {
        viewModelScope.launch(Dispatchers.IO) {
            _recents.value = runCatching { MediaQueries.query(getApplication(), Category.RECENT, 60) }
                .getOrDefault(emptyList()).filter { File(it.path).exists() }.take(20)
        }
    }

    /** Names containing [query] (accents and case ignored) anywhere below [root]; local folders only. */
    suspend fun search(root: String, query: String, showHidden: Boolean, onBatch: (List<FileEntry>) -> Unit) = withContext(Dispatchers.IO) {
        val q = fold(query.trim())
        if (q.isEmpty()) return@withContext
        val found = ArrayList<FileEntry>()
        val queue = ArrayDeque<File>().apply { add(File(root)) }
        var last = System.nanoTime()
        while (queue.isNotEmpty() && found.size < 500) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            // Android/data and Android/obb are other apps' and unreadable anyway
            if (dir.name == "Android" && dir.parentFile?.parentFile?.path == "/storage/emulated") continue
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (!showHidden && f.name.startsWith('.')) continue
                if (fold(f.name).contains(q)) found += LocalVfs.entry(f)
                if (f.isDirectory) queue.add(f)
            }
            if (System.nanoTime() - last > 250_000_000) {
                last = System.nanoTime()
                val snap = found.toList()
                withContext(Dispatchers.Main) { onBatch(snap) }
            }
        }
        val snap = found.toList()
        withContext(Dispatchers.Main) { onBatch(snap) }
    }

    // ---- settings ----

    fun setTheme(name: String) = viewModelScope.launch { store.setTheme(name) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { store.setDynamicColor(on) }
    fun setShowHidden(on: Boolean) = viewModelScope.launch { store.setShowHidden(on) }
    fun setSort(s: SortOrder) = viewModelScope.launch { store.setSort(s) }
    fun setGrid(media: Boolean, on: Boolean) = viewModelScope.launch { store.setGrid(media, on) }
    fun setServerConfig(c: ServerConfig) = viewModelScope.launch { store.setServer(c) }
    suspend fun serverConfig() = store.serverConfig()

    fun isFavorite(loc: Loc) = loc.key in settings.value.favorites

    fun toggleFavorite(loc: Loc) = viewModelScope.launch {
        val f = settings.value.favorites
        store.setFavorites(if (loc.key in f) f - loc.key else f + loc.key)
    }

    fun saveTheme(p: Palette, replacing: String?) = viewModelScope.launch {
        val name = themes.save(p, replacing)
        store.setTheme(name)
    }

    fun deleteTheme(name: String) = viewModelScope.launch {
        themes.delete(name)
        if (settings.value.theme == name) store.setTheme(AppSettings.DEFAULT_THEME)
    }

    fun importTheme(uri: Uri, fileName: String?, done: (String?) -> Unit) = viewModelScope.launch {
        val suggested = fileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && it != "colors" } ?: "importado"
        done(themes.import(uri, suggested))
    }

    fun exportTheme(p: Palette, uri: Uri) = viewModelScope.launch { themes.export(p, uri) }

    // ---- servers ----

    fun saveServer(s: Server, key: Uri?) = viewModelScope.launch(Dispatchers.IO) {
        var server = s
        if (key != null) {
            luna.servers.keyDir.mkdirs()
            getApplication<Application>().contentResolver.openInputStream(key)?.use { input ->
                luna.servers.keyFile(s.id).outputStream().use { input.copyTo(it) }
            }
            server = server.copy(hasKey = true)
        }
        luna.sftp.disconnect(s.id)
        luna.servers.save(server)
    }

    fun deleteServer(id: String) = viewModelScope.launch(Dispatchers.IO) {
        luna.sftp.disconnect(id)
        luna.servers.delete(id)
        store.setFavorites(settings.value.favorites.filter { !it.startsWith("sftp:$id:") })
    }

    fun disconnect(id: String) = viewModelScope.launch(Dispatchers.IO) { luna.sftp.disconnect(id) }

    fun trustHost(hostPort: String, fingerprint: String) {
        luna.servers.trust(hostPort, fingerprint)
    }

    fun typedPassword(serverId: String, pw: String) {
        luna.sftp.typedPasswords[serverId] = pw
    }

    // ---- operations ----

    /** Runs a file operation on the app scope, with the progress card and a message on failure. */
    private fun run(block: suspend (FileOps) -> String?) {
        if (opJob?.isActive == true) {
            say("Espera a que termine la operación en curso")
            return
        }
        opJob = luna.scope.launch {
            val ops = FileOps { p -> _progress.value = p }
            try {
                val msg = withContext(Dispatchers.IO) { block(ops) }
                msg?.let { say(it) }
            } catch (e: CancellationException) {
                say("Cancelado")
            } catch (e: Exception) {
                say(e.message ?: e.javaClass.simpleName)
            } finally {
                _progress.value = null
                _tick.value++
                loadRecents()
            }
        }
    }

    fun cancelOp() {
        opJob?.cancel()
    }

    /** Tells MediaStore so Recientes, Imágenes, the gallery… see the change. */
    private fun scan(paths: Collection<String>) {
        val local = paths.filter { it.startsWith("/") }
        if (local.isEmpty()) return
        val expanded = local.flatMap { p -> File(p).let { f -> if (f.isDirectory) f.walkTopDown().map { it.path }.toList() else listOf(p) } }
        MediaScannerConnection.scanFile(getApplication(), expanded.take(5000).toTypedArray(), null, null)
    }

    fun copy(loc: Loc, entries: List<FileEntry>, cut: Boolean) {
        _clip.value = Clip(vfsIdOf(loc), entries, cut)
        selection = emptySet()
    }

    fun clearClip() {
        _clip.value = null
    }

    fun paste(dest: Loc) {
        val c = _clip.value ?: return
        run { ops ->
            val src = vfsById(c.vfsId)
            val dst = vfs(dest)
            val made = ops.transfer(src, c.entries, dst, dest.path, c.cut)
            if (dst === LocalVfs) scan(made)
            if (c.cut) {
                if (src === LocalVfs) scan(c.entries.map { it.path })
                _clip.value = null
            }
            null
        }
    }

    fun delete(loc: Loc, entries: List<FileEntry>) {
        selection = emptySet()
        run { ops ->
            val v = vfs(loc)
            ops.delete(v, entries)
            if (v === LocalVfs) scan(entries.map { it.path })
            if (entries.size == 1) "«${entries[0].name}» eliminado" else "${entries.size} elementos eliminados"
        }
    }

    fun rename(loc: Loc, e: FileEntry, newName: String) {
        selection = emptySet()
        run {
            val v = vfs(loc)
            val target = Paths.join(e.parent, newName)
            if (v.exists(target)) throw java.io.IOException("Ya existe «$newName»")
            if (!v.move(e.path, target)) throw java.io.IOException("No se ha podido renombrar")
            if (v === LocalVfs) scan(listOf(e.path, target))
            null
        }
    }

    fun create(loc: Loc, name: String, folder: Boolean) = run {
        val v = vfs(loc)
        val target = Paths.join(loc.path, name)
        if (v.exists(target)) throw java.io.IOException("Ya existe «$name»")
        if (folder) v.mkdir(target) else v.createFile(target)
        null
    }

    fun compress(loc: Loc, entries: List<FileEntry>, name: String) {
        selection = emptySet()
        run { ops ->
            val v = vfs(loc)
            val dir = entries.first().parent
            val zipName = Paths.freeName(if (name.endsWith(".zip", true)) name else "$name.zip", false) { v.exists(Paths.join(dir, it)) }
            val target = Paths.join(dir, zipName)
            ops.zip(v, entries, target)
            if (v === LocalVfs) scan(listOf(target))
            "Creado «$zipName»"
        }
    }

    fun extract(loc: Loc, e: FileEntry) = run { ops ->
        val v = vfs(loc)
        val out = ops.unzip(v, e)
        if (v === LocalVfs) scan(listOf(out))
        "Extraído en «${Paths.name(out)}»"
    }

    suspend fun tally(loc: Loc, entries: List<FileEntry>): Tally = withContext(Dispatchers.IO) {
        FileOps {}.tally(vfs(loc), entries)
    }

    /**
     * Local files for [entries], downloading remote ones to the cache first
     * (with the progress card), then [then] on the main thread.
     */
    fun localFiles(loc: Loc, entries: List<FileEntry>, then: (List<File>) -> Unit) {
        if (loc !is Loc.Remote) {
            then(entries.map { File(it.path) })
            return
        }
        run { ops ->
            val dir = File(getApplication<Application>().cacheDir, "sftp/${loc.serverId}")
            val out = entries.map { e ->
                val target = File(dir, e.path.trimStart('/'))
                target.parentFile?.mkdirs()
                // a fresh download each time: the server copy may have changed
                target.delete()
                ops.transfer(vfs(loc), listOf(e), LocalVfs, target.parent!!, move = false)
                target
            }
            withContext(Dispatchers.Main) { then(out) }
            null
        }
    }

    /** "Descargar": remote files to the phone's Download folder. */
    fun download(loc: Loc, entries: List<FileEntry>) = run { ops ->
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        val made = ops.transfer(vfs(loc), entries, LocalVfs, dl, move = false)
        scan(made)
        "Descargado en Descargas"
    }

    fun reloadVolumes() = luna.volumes.refresh()
}
