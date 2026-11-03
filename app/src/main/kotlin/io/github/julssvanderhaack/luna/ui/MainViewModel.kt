package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import android.app.Application
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.julssvanderhaack.luna.LunaApp
import io.github.julssvanderhaack.luna.fs.Answer
import io.github.julssvanderhaack.luna.fs.ArchiveFormat
import io.github.julssvanderhaack.luna.fs.Archives
import io.github.julssvanderhaack.luna.fs.Category
import io.github.julssvanderhaack.luna.fs.Conflict
import io.github.julssvanderhaack.luna.fs.ContentSearch
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.FileOps
import io.github.julssvanderhaack.luna.fs.ImageFormat
import io.github.julssvanderhaack.luna.fs.Images
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.LocalVfs
import io.github.julssvanderhaack.luna.fs.MediaQueries
import io.github.julssvanderhaack.luna.fs.NeedsException
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.Perms
import io.github.julssvanderhaack.luna.fs.Pgp
import io.github.julssvanderhaack.luna.fs.Progress
import io.github.julssvanderhaack.luna.fs.SearchFilter
import io.github.julssvanderhaack.luna.fs.SortOrder
import io.github.julssvanderhaack.luna.fs.Tally
import io.github.julssvanderhaack.luna.fs.Trash
import io.github.julssvanderhaack.luna.fs.TrashItem
import io.github.julssvanderhaack.luna.fs.Vfs
import io.github.julssvanderhaack.luna.fs.Visits
import io.github.julssvanderhaack.luna.fs.WrongPasswordException
import io.github.julssvanderhaack.luna.fs.fold
import io.github.julssvanderhaack.luna.fs.sortEntries
import io.github.julssvanderhaack.luna.remote.Found
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.settings.AppSettings
import io.github.julssvanderhaack.luna.settings.ServerConfig
import io.github.julssvanderhaack.luna.settings.ViewMode
import io.github.julssvanderhaack.luna.theme.Palette
import io.github.julssvanderhaack.luna.theme.ResolvedPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import java.io.IOException

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
    /** Null = a new server, filled in from [found] when it came from the network. */
    data class EditServer(val id: String?, val found: Found? = null) : Route
    data object Settings : Route
    data object Themes : Route
    data class ThemeEditor(val base: String, val editing: Boolean) : Route
    data object Trash : Route
    data object Keys : Route
    data class Usage(val loc: Loc) : Route

    /**
     * Luna's own photo viewer over [images], starting at [start]. Not a data class:
     * the back stack keys screens by toString, and a whole folder in there would be slow.
     */
    class Viewer(val loc: Loc, val images: List<FileEntry>, val start: Int) : Route {
        override fun toString() = "Viewer(${loc.key}|${images.getOrNull(start)?.path})"
    }

    /** Space-bar preview of desktop Luna: text, Markdown, PDF, archives, Office, songs; swipes through [files]. */
    class Preview(val loc: Loc, val files: List<FileEntry>, val start: Int) : Route {
        override fun toString() = "Preview(${loc.key}|${files.getOrNull(start)?.path})"
    }
}

/** Copied or cut files waiting for "Pegar". [vfsId] says which file system they're on. */
data class Clip(val vfsId: String, val entries: List<FileEntry>, val cut: Boolean)

/** A snackbar line, optionally with a button ("Deshacer"). */
data class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** A paste waiting for the user to say what to do with a name that's taken. */
class PendingConflict(val conflict: Conflict, val answer: CompletableDeferred<Answer>)

/** Something that can be undone (and then redone), like desktop Luna's Ctrl+Z. */
sealed interface Undo {
    val label: String

    /** Renames and moves: each first → second. Undoing moves them back. */
    data class Moved(override val label: String, val srcVfs: String, val dstVfs: String, val pairs: List<Pair<String, String>>) : Undo

    /** Copies: undoing deletes [created]; redoing copies [entries] into [dir] again. */
    data class Copied(override val label: String, val srcVfs: String, val entries: List<FileEntry>, val dstVfs: String, val dir: String, val created: List<String>) : Undo

    data class Trashed(override val label: String, val items: List<TrashItem>) : Undo

    enum class Made { FOLDER, FILE, ARCHIVE, EXTRACTED }

    /** A new folder, file, archive or extraction; only empty folders and files can be made again. */
    data class Created(override val label: String, val vfs: String, val path: String, val isDir: Boolean, val made: Made) : Undo
}

/** One tab: its own history of screens. */
class Tab(val id: Int) {
    val stack: SnapshotStateList<Route> = mutableStateListOf(Route.Home)
}

/** The usual top-level folders of an Android root file system. */
private val ROOT_DIRS = listOf(
    "acct", "apex", "bin", "cache", "config", "d", "data", "data_mirror", "debug_ramdisk", "dev", "etc",
    "linkerconfig", "metadata", "mnt", "odm", "odm_dlkm", "oem", "postinstall", "proc", "product",
    "sdcard", "second_stage_resources", "storage", "sys", "system", "system_dlkm", "system_ext", "vendor", "vendor_dlkm",
)

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
    val found = luna.discovery.found

    // ---- tabs ----

    private var nextTab = 1
    val tabs = mutableStateListOf(Tab(0))
    var tabIndex by mutableIntStateOf(0)
        private set
    val tab get() = tabs[tabIndex.coerceIn(0, tabs.lastIndex)]
    val stack get() = tab.stack

    fun newTab(start: Route? = null) {
        clearSelection()
        val t = Tab(nextTab++)
        if (start != null) t.stack.add(start)
        tabs.add(t)
        tabIndex = tabs.lastIndex
    }

    fun switchTab(i: Int) {
        clearSelection()
        tabIndex = i.coerceIn(0, tabs.lastIndex)
    }

    fun closeTab(i: Int) {
        if (tabs.size == 1) return
        clearSelection()
        tabs.removeAt(i)
        if (tabIndex >= i) tabIndex = (tabIndex - 1).coerceAtLeast(0)
    }

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

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)
    val messages: SharedFlow<Message> = _messages

    var conflict by mutableStateOf<PendingConflict?>(null)
        private set

    val undos = mutableStateListOf<Undo>()
    val redos = mutableStateListOf<Undo>()

    /** Paths selected in the current list. */
    var selection by mutableStateOf(emptySet<String>())

    /** "Selección múltiple": selection mode even with nothing picked yet, so taps select instead of open. */
    var selectMode by mutableStateOf(false)

    val selecting get() = selectMode || selection.isNotEmpty()

    fun clearSelection() {
        selection = emptySet()
        selectMode = false
    }

    init {
        viewModelScope.launch { themes.load() }
    }

    fun say(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(Message(text, action, onAction))
    }

    // ---- navigation ----

    fun open(r: Route) {
        clearSelection()
        stack.add(r)
    }

    fun back() {
        clearSelection()
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    fun home() {
        clearSelection()
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Opens a folder. Going up to one already in the history pops back to it instead of piling up. */
    fun browse(loc: Loc) {
        clearSelection()
        if (loc !is Loc.Media && loc.path.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { luna.visits.add(loc.key) }
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

    override fun onCleared() {
        luna.visits.save()
    }

    // ---- listing ----

    fun vfsIdOf(loc: Loc) = if (loc is Loc.Remote) "sftp:${loc.serverId}" else LocalVfs.id

    /** Blocking; may throw HostKeyException/AuthException for remote folders. */
    fun vfs(loc: Loc): Vfs = vfsById(vfsIdOf(loc))

    private fun vfsById(id: String): Vfs {
        if (id == LocalVfs.id) return LocalVfs
        val serverId = id.removePrefix("sftp:")
        val s = luna.servers.find(serverId) ?: throw IOException(tr("Ese servidor ya no está guardado"))
        return luna.remotes.connect(s)
    }

    /** The real folder of [loc] once connected (a remote "" becomes the server's start folder). */
    suspend fun resolve(loc: Loc): Loc = withContext(Dispatchers.IO) {
        if (loc is Loc.Remote && loc.path.isEmpty()) {
            val s = luna.servers.find(loc.serverId) ?: return@withContext loc
            loc.copy(path = luna.remotes.connect(s).home)
        } else loc
    }

    /** Last listing of each folder: shown at once when you come back, while a fresh one loads. */
    private val listings = LruCache<String, List<FileEntry>>(48)

    private fun listingKey(loc: Loc, s: AppSettings) = "${loc.key}|${s.showHidden}|${s.sort}"

    fun cachedList(loc: Loc, s: AppSettings): List<FileEntry>? = listings.get(listingKey(loc, s))

    suspend fun list(loc: Loc, s: AppSettings): List<FileEntry> = withContext(Dispatchers.IO) {
        listUncached(loc, s).also { listings.put(listingKey(loc, s), it) }
    }

    private fun listUncached(loc: Loc, s: AppSettings): List<FileEntry> =
        when (loc) {
            is Loc.Media -> MediaQueries.query(getApplication(), loc.category)
                .filter { File(it.path).exists() }
                .let { if (loc.category == Category.RECENT || loc.category.media) it else sortEntries(it, s.sort) }
            else -> {
                val listed = runCatching { vfs(loc).list(loc.path) }
                val known = if (loc is Loc.Local) mountedChildren(loc.path) else emptyList()
                val got = listed.getOrElse { if (known.isEmpty()) throw it else emptyList() }
                val all = got + known.filter { k -> got.none { it.name == k.name } }
                // the trash folder is reached from the menu, not by browsing into it
                val visible = all.filter { it.name != Trash.DIR && (s.showHidden || !it.hidden) }
                sortEntries(visible, s.sort)
            }
        }

    /**
     * Folders on the way to a mounted volume that sit directly in [dir]. Android
     * won't let apps list /storage/emulated, so browsing down from "/" would
     * dead-end before reaching internal storage or a USB drive without these.
     */
    private fun mountedChildren(dir: String): List<FileEntry> {
        val d = dir.trimEnd('/').ifEmpty { "/" }
        // apps can't list "/" either, but can stat what's in it; only offer what opens
        val rootDirs = if (d == "/") ROOT_DIRS.map { "/$it" }.filter { File(it).list() != null } else emptyList()
        return (rootDirs + volumes.value.map { it.path })
            .flatMap { p -> generateSequence(p) { if (it == "/") null else Paths.parent(it) }.toList() }
            .filter { it != "/" && Paths.parent(it) == d }
            .distinct()
            .map { File(it).let { f -> FileEntry(f.name, f.path, true, 0, f.lastModified()) } }
    }

    fun loadRecents() {
        viewModelScope.launch(Dispatchers.IO) {
            _recents.value = runCatching { MediaQueries.query(getApplication(), Category.RECENT, 60) }
                .getOrDefault(emptyList()).filter { File(it.path).exists() }.take(20)
        }
    }

    /**
     * Names containing [query] (accents and case ignored) anywhere below [root],
     * or files whose text contains it with [filter].contents; local folders only.
     */
    suspend fun search(root: String, query: String, showHidden: Boolean, filter: SearchFilter, onBatch: (List<FileEntry>) -> Unit) = withContext(Dispatchers.IO) {
        val q = fold(query.trim())
        if (q.isEmpty() && !filter.narrows) return@withContext
        val found = ArrayList<FileEntry>()
        val queue = ArrayDeque<File>().apply { add(File(root)) }
        var last = System.nanoTime()
        val now = System.currentTimeMillis()
        while (queue.isNotEmpty() && found.size < 500) {
            currentCoroutineContext().ensureActive()
            val dir = queue.removeFirst()
            // Android/data and Android/obb are other apps' and unreadable anyway
            if (dir.name == "Android" && dir.parentFile?.parentFile?.path == "/storage/emulated") continue
            if (dir.name == Trash.DIR) continue
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (!showHidden && f.name.startsWith('.')) continue
                val isDir = f.isDirectory
                if (isDir) queue.add(f)
                val e = if (filter.contents || filter.narrows) LocalVfs.entry(f) else null
                val hit = when {
                    filter.contents -> e != null && ContentSearch.worth(e) && filter.keeps(e, now) && (q.isEmpty() || ContentSearch.contains(f, q))
                    else -> fold(f.name).contains(q) && (e == null || filter.keeps(e, now))
                }
                if (hit) found += e ?: LocalVfs.entry(f)
                if (found.size >= 500) break
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

    /** "Ir a una carpeta": frequent folders, favourites, volumes and the folders in the home, for [query]. */
    suspend fun jumpCandidates(query: String): List<Loc> = withContext(Dispatchers.Default) {
        val names = HashMap<String, String>()
        val serverTitles = luna.servers.servers.value.associate { it.id to it.title }
        fun add(key: String) {
            val loc = locFromKey(key) ?: return
            if (loc is Loc.Media) return
            names[key] = when (loc) {
                is Loc.Remote -> "${serverTitles[loc.serverId] ?: return}:${loc.path}"
                else -> loc.path
            }
        }
        val scores = luna.visits.scores()
        scores.keys.forEach(::add)
        settings.value.favorites.forEach(::add)
        volumes.value.forEach { add(it.path) }
        // the first two levels of every volume, so a folder you've never opened can be found too
        for (v in volumes.value) File(v.path).listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') && it.name != "Android" }?.forEach { d ->
            add(d.path)
            d.listFiles()?.filter { it.isDirectory && !it.name.startsWith('.') }?.forEach { add(it.path) }
        }
        val boosted = scores + settings.value.favorites.associateWith { (scores[it] ?: 0.0) + 2.0 }
        Visits.rank(query, names, boosted).take(40).mapNotNull { locFromKey(it) }
    }

    fun forgetVisit(loc: Loc) = viewModelScope.launch(Dispatchers.IO) { luna.visits.forget(loc.key) }

    // ---- settings ----

    fun setTheme(name: String) = viewModelScope.launch { store.setTheme(name) }
    fun setDynamicColor(on: Boolean) = viewModelScope.launch { store.setDynamicColor(on) }
    fun setShowHidden(on: Boolean) = viewModelScope.launch { store.setShowHidden(on) }
    fun setSort(s: SortOrder) = viewModelScope.launch { store.setSort(s) }
    fun setGridMedia(on: Boolean) = viewModelScope.launch { store.setGridMedia(on) }
    fun setView(v: ViewMode) = viewModelScope.launch { store.setView(v) }
    fun setOpenInLuna(on: Boolean) = viewModelScope.launch { store.setOpenInLuna(on) }
    fun setUseTrash(on: Boolean) = viewModelScope.launch { store.setUseTrash(on) }
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
        luna.remotes.disconnect(s.id)
        luna.servers.save(server)
    }

    fun deleteServer(id: String) = viewModelScope.launch(Dispatchers.IO) {
        luna.remotes.disconnect(id)
        luna.servers.delete(id)
        store.setFavorites(settings.value.favorites.filter { !it.startsWith("sftp:$id:") })
    }

    fun disconnect(id: String) = viewModelScope.launch(Dispatchers.IO) { luna.remotes.disconnect(id) }

    fun trustHost(hostPort: String, fingerprint: String) {
        luna.servers.trust(hostPort, fingerprint)
    }

    fun typedPassword(serverId: String, pw: String) {
        luna.remotes.typedPasswords[serverId] = pw
    }

    /** A server found on the network: open it if it's saved already, else fill in a new one. */
    fun openFound(f: Found) {
        val saved = luna.servers.servers.value.firstOrNull { it.protocol == f.protocol && it.host == f.host && it.port == f.port }
        if (saved != null) browse(Loc.Remote(saved.id)) else open(Route.EditServer(null, f))
    }

    // ---- operations ----

    private suspend fun askConflict(c: Conflict): Answer = withContext(Dispatchers.Main) {
        val d = CompletableDeferred<Answer>()
        conflict = PendingConflict(c, d)
        try {
            d.await()
        } finally {
            conflict = null
        }
    }

    /** Runs a file operation on the app scope, with the progress card and a message on failure. */
    private fun run(block: suspend (FileOps) -> String?) {
        if (opJob?.isActive == true) {
            say(tr("Espera a que termine la operación en curso"))
            return
        }
        opJob = luna.scope.launch {
            val ops = FileOps({ p -> _progress.value = p }, ::askConflict)
            try {
                val msg = withContext(Dispatchers.IO) { block(ops) }
                msg?.let { say(it) }
            } catch (e: CancellationException) {
                say(tr("Cancelado"))
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
        conflict?.answer?.cancel()
        opJob?.cancel()
    }

    /** Tells MediaStore so Recientes, Imágenes, the gallery… see the change. */
    private fun scan(paths: Collection<String>) {
        val local = paths.filter { it.startsWith("/") }
        if (local.isEmpty()) return
        val expanded = local.flatMap { p -> File(p).let { f -> if (f.isDirectory) f.walkTopDown().map { it.path }.toList() else listOf(p) } }
        MediaScannerConnection.scanFile(getApplication(), expanded.take(5000).toTypedArray(), null, null)
    }

    private fun scanIfLocal(vfsId: String, paths: Collection<String>) {
        if (vfsId == LocalVfs.id) scan(paths)
    }

    /** Records [u] and offers "Deshacer" in the snackbar with [text]. */
    private fun done(u: Undo, text: String) {
        undos.add(u)
        if (undos.size > 50) undos.removeAt(0)
        redos.clear()
        say(text, tr("Deshacer")) { undo() }
    }

    fun copy(loc: Loc, entries: List<FileEntry>, cut: Boolean) {
        _clip.value = Clip(vfsIdOf(loc), entries, cut)
        clearSelection()
    }

    fun clearClip() {
        _clip.value = null
    }

    fun paste(dest: Loc) {
        val c = _clip.value ?: return
        if (c.cut) _clip.value = null
        transfer(c.vfsId, c.entries, vfsIdOf(dest), dest.path, c.cut)
    }

    /** "Copiar a…" / "Mover a…" and paste: [entries] from [srcId] into [dir] on [dstId]. */
    fun transfer(srcId: String, entries: List<FileEntry>, dstId: String, dir: String, move: Boolean) {
        clearSelection()
        run { ops ->
            val t = ops.transfer(vfsById(srcId), entries, vfsById(dstId), dir, move)
            scanIfLocal(dstId, t.created)
            if (move) scanIfLocal(srcId, t.pairs.map { it.first })
            val n = t.pairs.size
            when {
                n == 0 -> tr("No se ha pegado nada")
                t.replaced -> if (move) tr("%1 (se ha reemplazado algo: no se puede deshacer)", plural(n, "elemento movido", "elementos movidos"))
                else tr("%1 (se ha reemplazado algo: no se puede deshacer)", plural(n, "elemento copiado", "elementos copiados"))
                else -> {
                    val text = if (move) plural(n, "elemento movido", "elementos movidos") else plural(n, "elemento copiado", "elementos copiados")
                    withContext(Dispatchers.Main) {
                        done(
                            if (move) Undo.Moved(tr("Mover"), srcId, dstId, t.pairs)
                            else Undo.Copied(tr("Copiar"), srcId, entries, dstId, dir, t.created),
                            text,
                        )
                    }
                    null
                }
            }
        }
    }

    private fun rootOf(path: String): String? = luna.volumes.of(path)?.path

    /** Eliminar: to the trash for local files (unless turned off or [forever]), for good otherwise. */
    fun delete(loc: Loc, entries: List<FileEntry>, forever: Boolean = false) {
        clearSelection()
        val id = vfsIdOf(loc)
        val toTrash = !forever && settings.value.useTrash && id == LocalVfs.id && entries.all { rootOf(it.path) != null }
        run { ops ->
            if (toTrash) {
                val items = ops.trash(entries, ::rootOf)
                scan(entries.map { it.path })
                val text = if (entries.size == 1) tr("«%1» a la papelera", entries[0].name) else tr("%1 elementos a la papelera", entries.size)
                withContext(Dispatchers.Main) { done(Undo.Trashed(tr("Mover a la papelera"), items), text) }
                null
            } else {
                ops.delete(vfsById(id), entries)
                scanIfLocal(id, entries.map { it.path })
                if (entries.size == 1) tr("«%1» eliminado", entries[0].name) else "${entries.size} elementos eliminados"
            }
        }
    }

    fun canTrash(loc: Loc, entries: List<FileEntry>) =
        settings.value.useTrash && loc !is Loc.Remote && entries.all { rootOf(it.path) != null }

    fun rename(loc: Loc, e: FileEntry, newName: String) = renameMany(loc, listOf(e to newName), tr("Renombrar"))

    /** Renombrar en lote too: all at once, through temporary names so a↔b swaps work. */
    fun renameMany(loc: Loc, renames: List<Pair<FileEntry, String>>, label: String = tr("Renombrar en lote")) {
        clearSelection()
        val id = vfsIdOf(loc)
        run {
            val v = vfsById(id)
            val todo = renames.filter { (e, n) -> e.name != n }
            if (todo.isEmpty()) return@run null
            val sources = todo.map { it.first.path }.toSet()
            val targets = todo.map { (e, n) -> Paths.join(e.parent, n) }
            if (targets.toSet().size != targets.size) throw IOException(tr("Dos archivos acabarían con el mismo nombre"))
            for (t in targets) if (t !in sources && v.exists(t)) throw IOException(tr("Ya existe «%1»", Paths.name(t)))
            val swap = targets.any { it in sources }
            val pairs = ArrayList<Pair<String, String>>()
            if (swap) {
                val temps = todo.mapIndexed { i, (e, _) -> Paths.join(e.parent, ".luna-renombrando-$i-${e.name}") }
                todo.zip(temps).forEach { (r, tmp) -> if (!v.move(r.first.path, tmp)) throw IOException(tr("No se ha podido renombrar «%1»", r.first.name)) }
                temps.zip(targets).forEach { (tmp, t) -> if (!v.move(tmp, t)) throw IOException(tr("No se ha podido renombrar a «%1»", Paths.name(t))) }
            } else {
                todo.zip(targets).forEach { (r, t) ->
                    if (!v.move(r.first.path, t)) throw IOException(tr("No se ha podido renombrar «%1»", r.first.name))
                    pairs += r.first.path to t
                }
            }
            if (swap) pairs += todo.map { it.first.path }.zip(targets)
            scanIfLocal(id, sources + targets)
            withContext(Dispatchers.Main) {
                done(Undo.Moved(label, id, id, pairs), if (pairs.size == 1) tr("Renombrado a «%1»", Paths.name(pairs[0].second)) else "${pairs.size} renombrados")
            }
            null
        }
    }

    fun create(loc: Loc, name: String, folder: Boolean) {
        val id = vfsIdOf(loc)
        run {
            val v = vfsById(id)
            val target = Paths.join(loc.path, name)
            if (v.exists(target)) throw IOException(tr("Ya existe «%1»", name))
            if (folder) v.mkdir(target) else v.createFile(target)
            withContext(Dispatchers.Main) {
                undos.add(Undo.Created(if (folder) tr("Nueva carpeta") else tr("Nuevo archivo"), id, target, folder, if (folder) Undo.Made.FOLDER else Undo.Made.FILE))
                redos.clear()
            }
            null
        }
    }

    fun undo() {
        val u = undos.removeLastOrNull() ?: return
        run { ops ->
            val redo = reverse(ops, u, undo = true)
            withContext(Dispatchers.Main) {
                redo?.let { redos.add(it) }
                say("Deshecho: ${u.label.lowercase()}", if (redo != null) tr("Rehacer") else null) { redo() }
            }
            null
        }
    }

    fun redo() {
        val u = redos.removeLastOrNull() ?: return
        run { ops ->
            val again = reverse(ops, u, undo = false)
            withContext(Dispatchers.Main) {
                again?.let { undos.add(it) }
                say("Rehecho: ${u.label.lowercase()}", if (again != null) tr("Deshacer") else null) { undo() }
            }
            null
        }
    }

    /** Moves [from] on [fromId] to exactly [to] on [toId]. */
    private suspend fun relocate(ops: FileOps, fromId: String, from: String, toId: String, to: String) {
        val src = vfsById(fromId)
        val dst = vfsById(toId)
        if (dst.exists(to)) throw IOException(tr("Ya hay algo en «%1»: no se puede deshacer", Paths.name(to)))
        if (fromId == toId && src.move(from, to)) return
        val e = src.stat(from) ?: throw IOException(tr("«%1» ya no está", Paths.name(from)))
        val t = ops.transfer(src, listOf(e), dst, Paths.parent(to), move = true)
        val landed = t.created.singleOrNull() ?: return
        if (landed != to && !dst.move(landed, to)) throw IOException(tr("No se ha podido volver a llamar «%1»", Paths.name(to)))
    }

    /** Does the opposite of [u] (or [u] again) and returns what would reverse that. */
    private suspend fun reverse(ops: FileOps, u: Undo, undo: Boolean): Undo? = when (u) {
        is Undo.Moved -> {
            ops.begin(if (undo) tr("Deshaciendo") else tr("Rehaciendo"))
            val list = if (undo) u.pairs.reversed() else u.pairs
            for ((a, b) in list) if (undo) relocate(ops, u.dstVfs, b, u.srcVfs, a) else relocate(ops, u.srcVfs, a, u.dstVfs, b)
            scanIfLocal(u.srcVfs, u.pairs.map { it.first })
            scanIfLocal(u.dstVfs, u.pairs.map { it.second })
            u
        }
        is Undo.Copied -> if (undo) {
            val v = vfsById(u.dstVfs)
            ops.delete(v, u.created.mapNotNull { v.stat(it) })
            scanIfLocal(u.dstVfs, u.created)
            u
        } else {
            val t = ops.transfer(vfsById(u.srcVfs), u.entries, vfsById(u.dstVfs), u.dir, move = false)
            scanIfLocal(u.dstVfs, t.created)
            u.copy(created = t.created)
        }
        is Undo.Trashed -> if (undo) {
            ops.begin(tr("Restaurando"), u.items.size.toLong())
            u.items.forEach { Trash.restore(it, exact = true) }
            scan(u.items.map { it.originalPath })
            u
        } else {
            val items = ops.trash(u.items.mapNotNull { LocalVfs.stat(it.originalPath) }, ::rootOf)
            scan(u.items.map { it.originalPath })
            u.copy(items = items)
        }
        is Undo.Created -> {
            val v = vfsById(u.vfs)
            if (undo) {
                v.stat(u.path)?.let { e ->
                    if (e.isDir && v.list(u.path).isNotEmpty() && u.made == Undo.Made.FOLDER) throw IOException(tr("«%1» ya no está vacía: no se deshace", e.name))
                    v.delete(e)
                }
            } else if (u.isDir) v.mkdir(u.path) else if (u.made == Undo.Made.FILE) v.createFile(u.path) else return null
            scanIfLocal(u.vfs, listOf(u.path))
            if (!undo || u.made == Undo.Made.FOLDER || u.made == Undo.Made.FILE) u else null
        }
    }

    // ---- trash ----

    suspend fun trashItems(): List<TrashItem> = withContext(Dispatchers.IO) {
        volumes.value.flatMap { runCatching { Trash.list(it.path) }.getOrDefault(emptyList()) }.sortedByDescending { it.deleted }
    }

    fun restore(items: List<TrashItem>) = run {
        val back = items.map { Trash.restore(it) }
        scan(back)
        if (items.size == 1) tr("«%1» restaurado en %2", Paths.name(back[0]), Paths.parent(back[0])) else "${items.size} elementos restaurados"
    }

    fun purge(items: List<TrashItem>) = run { ops ->
        ops.begin(tr("Vaciando la papelera"), items.size.toLong())
        items.forEach { ops.current(it.entry.name); Trash.purge(it) }
        if (items.size == 1) tr("«%1» eliminado para siempre", items[0].entry.name) else tr("%1 elementos eliminados para siempre", items.size)
    }

    // ---- archives ----

    fun compress(loc: Loc, entries: List<FileEntry>, name: String, format: ArchiveFormat) {
        clearSelection()
        val id = vfsIdOf(loc)
        run { ops ->
            val v = vfsById(id)
            val dir = entries.first().parent
            val file = Paths.freeName(if (name.endsWith(format.ext, true)) name else name + format.ext, false) { v.exists(Paths.join(dir, it)) }
            val target = Paths.join(dir, file)
            ops.compress(v, entries, target, format)
            scanIfLocal(id, listOf(target))
            withContext(Dispatchers.Main) { done(Undo.Created(tr("Comprimir"), id, target, false, Undo.Made.ARCHIVE), tr("Creado «%1»", file)) }
            null
        }
    }

    fun extract(loc: Loc, e: FileEntry) {
        val id = vfsIdOf(loc)
        run { ops ->
            val v = vfsById(id)
            val local = fetch(ops, loc, e)
            val out = ops.extract(v, e, local)
            scanIfLocal(id, listOf(out))
            withContext(Dispatchers.Main) { done(Undo.Created(tr("Extraer"), id, out, true, Undo.Made.EXTRACTED), tr("Extraído en «%1»", Paths.name(out))) }
            null
        }
    }

    /** A local file for [e]: itself, or a fresh download into the cache with the progress card. */
    private suspend fun fetch(ops: FileOps, loc: Loc, e: FileEntry): File {
        if (loc !is Loc.Remote) return File(e.path)
        val target = File(getApplication<Application>().cacheDir, "sftp/${loc.serverId}/${e.path.trimStart('/')}")
        if (target.length() == e.size && target.lastModified() == e.modified) return target
        target.parentFile?.mkdirs()
        target.delete()
        ops.transfer(vfs(loc), listOf(e), LocalVfs, target.parent!!, move = false)
        return target
    }

    /** One member of an archive into the cache, then [then] with it (to open it). */
    fun extractMember(archive: File, path: String, then: (File) -> Unit) = run {
        val out = File(getApplication<Application>().cacheDir, "archivos/${archive.name}/$path")
        if (!out.exists()) Archives.extractOne(archive, path, out)
        withContext(Dispatchers.Main) { then(out) }
        null
    }

    // ---- encryption ----

    /**
     * «Cifrar…»: one file → «nombre.gpg», a folder or several → «nombre.tar.gpg»,
     * with [password] and/or for [recipients] (fingerprints in Luna's keyring).
     */
    fun encrypt(loc: Loc, entries: List<FileEntry>, name: String, password: String?, recipients: List<String>, deleteOriginals: Boolean) {
        clearSelection()
        val id = vfsIdOf(loc)
        run { ops ->
            val v = vfsById(id)
            val keys = recipients.map { luna.keyring.recipient(it) ?: throw IOException(tr("Una de las claves ya no puede cifrar")) }
            val dir = entries.first().parent
            val tarred = entries.size > 1 || entries[0].isDir
            val base = name.removeSuffix(".gpg").removeSuffix(".tar")
            val file = Paths.freeName(if (tarred) "$base.tar.gpg" else "$base.gpg", false) { v.exists(Paths.join(dir, it)) }
            val target = Paths.join(dir, file)
            ops.begin(tr("Cifrando"), ops.tally(v, entries).bytes)
            val compress = !tarred && entries[0].kind !in setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO, Kind.ARCHIVE, Kind.APK)
            try {
                v.openWrite(target).buffered().use { out ->
                    Pgp.encrypt(out, if (tarred) "$base.tar" else entries[0].name, entries[0].modified, password?.toCharArray(), keys, compress || tarred) { plain ->
                        if (tarred) {
                            val tar = Archives.tar(plain)
                            Archives.writeTar(v, entries, tar, ops)
                            tar.finish()
                        } else v.openRead(entries[0].path).use { ops.pump(it, plain) }
                    }
                }
            } catch (t: Throwable) {
                runCatching { v.deleteOne(target, false) }
                throw t
            }
            if (deleteOriginals) {
                if (id == LocalVfs.id && settings.value.useTrash && entries.all { rootOf(it.path) != null }) ops.trash(entries, ::rootOf)
                else ops.delete(v, entries)
                scanIfLocal(id, entries.map { it.path })
            }
            scanIfLocal(id, listOf(target))
            tr("Cifrado en «%1»", file)
        }
    }

    /** What opening [e] needs (a password, a secret key Luna lacks…), checked before asking. */
    suspend fun decryptNeeds(loc: Loc, e: FileEntry): NeedsException = withContext(Dispatchers.IO) {
        val f = mediaFile(loc, e) ?: throw IOException(tr("No se ha podido leer «%1»", e.name))
        f.inputStream().buffered().use { Pgp.needs(it, luna.keyring) }
    }

    /**
     * «Descifrar aquí»: next to [e], with the name stored inside; a .tar becomes
     * its folder again. [onWrong] runs when the password or passphrase was wrong.
     */
    fun decrypt(loc: Loc, e: FileEntry, password: String?, keyPassphrase: String?, onWrong: () -> Unit) {
        val id = vfsIdOf(loc)
        run { ops ->
            val v = vfsById(id)
            val src = fetch(ops, loc, e)
            val dir = e.parent
            var result = ""
            ops.begin(tr("Descifrando"), src.length())
            try {
                src.inputStream().buffered().use { input ->
                    Pgp.decrypt(input, password?.toCharArray(), luna.keyring, keyPassphrase?.toCharArray()) { inner, modified, plain ->
                        // only a name, never a path from inside the file
                        val stored = Paths.name(inner.replace('\\', '/')).takeIf { it.isNotBlank() && it != "/" && it != ".." }
                            ?: e.name.removeSuffix(".gpg").removeSuffix(".pgp").removeSuffix(".asc")
                        if (stored.endsWith(".tar", true) || e.name.endsWith(".tar.gpg", true)) {
                            result = untar(ops, v, dir, stored.removeSuffix(".tar"), plain)
                        } else {
                            val name = Paths.freeName(stored, false) { v.exists(Paths.join(dir, it)) }
                            val target = Paths.join(dir, name)
                            try {
                                v.openWrite(target).use { ops.pump(plain, it) }
                            } catch (t: Throwable) {
                                runCatching { v.deleteOne(target, false) }
                                throw t
                            }
                            if (modified > 0) runCatching { v.setModified(target, modified) }
                            result = target
                        }
                    }
                }
            } catch (w: WrongPasswordException) {
                withContext(Dispatchers.Main) { onWrong() }
                throw w
            }
            scanIfLocal(id, listOf(result))
            withContext(Dispatchers.Main) { done(Undo.Created(tr("Descifrar"), id, result, v.stat(result)?.isDir == true, Undo.Made.EXTRACTED), tr("Descifrado en «%1»", Paths.name(result))) }
            null
        }
    }

    /** A tar into a new folder [stem]; if everything was inside one folder of that name, that folder is the result. */
    private suspend fun untar(ops: FileOps, v: Vfs, dir: String, stem: String, input: java.io.InputStream): String {
        val out = Paths.join(dir, Paths.freeName(stem, true) { v.exists(Paths.join(dir, it)) })
        v.mkdir(out)
        try {
            Archives.readTar(input, v, out, ops)
        } catch (t: Throwable) {
            runCatching { v.delete(FileEntry(Paths.name(out), out, true, 0, 0)) }
            throw t
        }
        // carpeta.tar.gpg holds carpeta/…: don't leave carpeta/carpeta/…
        val inside = v.list(out)
        val only = inside.singleOrNull()
        if (only != null && only.isDir && only.name == stem) {
            val tmp = Paths.join(dir, ".luna-${System.nanoTime()}")
            if (v.move(only.path, tmp)) {
                v.deleteOne(out, true)
                if (v.move(tmp, out)) return out
                v.move(tmp, only.path)
            }
        }
        return out
    }

    fun importKeys(uri: Uri) = run {
        val n = getApplication<Application>().contentResolver.openInputStream(uri)?.use { luna.keyring.import(it) } ?: 0
        if (n == 1) tr("Clave importada") else "$n claves importadas"
    }

    fun deleteKey(fingerprint: String) = run {
        luna.keyring.delete(fingerprint)
        tr("Clave borrada")
    }

    // ---- images ----

    fun rotate(loc: Loc, entries: List<FileEntry>, degrees: Int) {
        clearSelection()
        run { ops ->
            ops.begin(tr("Girando"), entries.size.toLong())
            entries.forEach { ops.current(it.name); Images.rotate(File(it.path), degrees) }
            scan(entries.map { it.path })
            if (entries.size == 1) tr("«%1» girada", entries[0].name) else tr("%1 imágenes giradas", entries.size)
        }
    }

    fun resize(loc: Loc, entries: List<FileEntry>, maxSide: Int) {
        clearSelection()
        run { ops ->
            ops.begin(tr("Reduciendo"), entries.size.toLong())
            val made = entries.map { ops.current(it.name); Images.resize(File(it.path), maxSide) }
            scan(made.map { it.path })
            if (made.size == 1) tr("Creada «%1»", made[0].name) else "${made.size} copias reducidas"
        }
    }

    fun convert(loc: Loc, entries: List<FileEntry>, format: ImageFormat) {
        clearSelection()
        run { ops ->
            ops.begin(tr("Convirtiendo"), entries.size.toLong())
            val made = entries.map { ops.current(it.name); Images.convert(File(it.path), format) }
            scan(made.map { it.path })
            if (made.size == 1) tr("Creada «%1»", made[0].name) else tr("%1 imágenes convertidas a %2", made.size, format.label)
        }
    }

    // ---- properties ----

    suspend fun tally(loc: Loc, entries: List<FileEntry>): Tally = withContext(Dispatchers.IO) {
        FileOps({}).tally(vfs(loc), entries)
    }

    suspend fun permissions(loc: Loc, e: FileEntry): Perms? = withContext(Dispatchers.IO) {
        runCatching { vfs(loc).permissions(e.path) }.getOrNull()
    }

    /** chmod, on [e] or (with [recursive]) everything inside it too. */
    fun chmod(loc: Loc, e: FileEntry, mode: Int, recursive: Boolean) = run { ops ->
        val v = vfs(loc)
        ops.begin(tr("Cambiando permisos"))
        suspend fun apply(x: FileEntry) {
            currentCoroutineContext().ensureActive()
            ops.current(x.name)
            // what's inside: folders also get x wherever they get r, or they couldn't be entered
            val m = if (x.path != e.path && x.isDir) mode or ((mode and 0b100100100) shr 2) else mode
            v.chmod(x.path, m)
            if (recursive && x.isDir && !x.isLink) v.list(x.path).forEach { apply(it) }
        }
        apply(e)
        tr("Permisos cambiados")
    }

    /** "Qué ocupa espacio": each item in [loc] with its size (folders added up), biggest first. */
    suspend fun usage(loc: Loc, onPartial: (List<Pair<FileEntry, Long>>) -> Unit): Unit = withContext(Dispatchers.IO) {
        val v = vfs(loc)
        val kids = v.list(loc.path).filter { it.name != Trash.DIR }
        val sizes = kids.associateWith { if (it.isDir && !it.isLink) -1L else it.size }.toMutableMap()
        var last = 0L
        fun emit() {
            val snap = sizes.entries.map { it.key to it.value }.sortedByDescending { it.second }
            viewModelScope.launch(Dispatchers.Main) { onPartial(snap) }
        }
        emit()
        for (k in kids.filter { it.isDir && !it.isLink }) {
            currentCoroutineContext().ensureActive()
            sizes[k] = if (v === LocalVfs) localSize(File(k.path)) else FileOps({}).tally(v, listOf(k)).bytes
            if (System.nanoTime() - last > 200_000_000) {
                last = System.nanoTime()
                emit()
            }
        }
        emit()
    }

    private suspend fun localSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>().apply { add(dir) }
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val d = stack.removeLast()
            for (f in d.listFiles() ?: continue) {
                val a = runCatching {
                    java.nio.file.Files.readAttributes(f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                }.getOrNull() ?: continue
                if (a.isDirectory) stack.add(f) else total += a.size()
            }
        }
        return total
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
            val out = entries.map { e ->
                val target = File(getApplication<Application>().cacheDir, "sftp/${loc.serverId}/${e.path.trimStart('/')}")
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

    /**
     * A local copy of [e] for the viewer and Propiedades, without the progress card.
     * Remote files land in the cache and are fetched again only when they changed;
     * null when bigger than [maxBytes] or unreachable.
     */
    suspend fun mediaFile(loc: Loc, e: FileEntry, maxBytes: Long = Long.MAX_VALUE): File? = withContext(Dispatchers.IO) {
        if (loc !is Loc.Remote) return@withContext File(e.path)
        if (e.size > maxBytes) return@withContext null
        runCatching {
            val target = File(getApplication<Application>().cacheDir, "sftp/${loc.serverId}/${e.path.trimStart('/')}")
            if (target.length() != e.size || target.lastModified() != e.modified) {
                target.parentFile?.mkdirs()
                val part = File(target.path + ".part")
                vfs(loc).openRead(e.path).use { input -> part.outputStream().use { input.copyTo(it) } }
                part.renameTo(target)
                if (e.modified > 0) target.setLastModified(e.modified)
            }
            target
        }.getOrNull()
    }

    /** "Descargar": remote files to the phone's Download folder. */
    fun download(loc: Loc, entries: List<FileEntry>) = run { ops ->
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
        val t = ops.transfer(vfs(loc), entries, LocalVfs, dl, move = false)
        scan(t.created)
        tr("Descargado en Descargas")
    }

    fun reloadVolumes() = luna.volumes.refresh()
}
