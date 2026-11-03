package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TableRows
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.Age
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.Mime
import io.github.julssvanderhaack.luna.fs.SearchFilter
import io.github.julssvanderhaack.luna.fs.SizeRange
import io.github.julssvanderhaack.luna.fs.SortBy
import io.github.julssvanderhaack.luna.fs.fold
import io.github.julssvanderhaack.luna.remote.AuthException
import io.github.julssvanderhaack.luna.remote.HostKeyException
import io.github.julssvanderhaack.luna.settings.AppSettings
import io.github.julssvanderhaack.luna.settings.ViewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface ListState {
    data object Loading : ListState
    data class Ready(val entries: List<FileEntry>) : ListState
    data class Failed(val message: String) : ListState
    data class HostKey(val e: HostKeyException) : ListState
    data class Password(val wrong: Boolean) : ListState
}

fun ViewMode.icon(): ImageVector = when (this) {
    ViewMode.LIST -> Icons.AutoMirrored.Rounded.ViewList
    ViewMode.COMPACT -> Icons.Rounded.ViewHeadline
    ViewMode.DETAILS -> Icons.Rounded.TableRows
    ViewMode.GRID -> Icons.Rounded.GridView
    ViewMode.TREE -> Icons.Rounded.AccountTree
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel, loc: Loc) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val tick by vm.tick.collectAsStateWithLifecycle()
    val clip by vm.clip.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val server = (loc as? Loc.Remote)?.let { l -> servers.firstOrNull { it.id == l.serverId } }
    val actions = remember(loc) { FileActions() }

    // coming back to a folder shows its last listing straight away; the fresh one replaces it
    var state by remember(loc) { mutableStateOf(vm.cachedList(loc, settings)?.let { ListState.Ready(it) } ?: ListState.Loading) }
    var attempt by remember(loc) { mutableIntStateOf(0) }
    var refreshing by remember(loc) { mutableStateOf(false) }
    var typedOnce by remember(loc) { mutableStateOf(false) }

    LaunchedEffect(loc, tick, settings.showHidden, settings.sort, attempt) {
        if (state !is ListState.Ready) state = ListState.Loading
        state = try {
            if (loc is Loc.Remote && loc.path.isEmpty()) {
                vm.replaceTop(Route.Browse(vm.resolve(loc)))
                return@LaunchedEffect
            }
            ListState.Ready(vm.list(loc, settings))
        } catch (e: CancellationException) {
            throw e
        } catch (e: HostKeyException) {
            ListState.HostKey(e)
        } catch (e: AuthException) {
            if (server?.password != null && !typedOnce) ListState.Failed(tr("El servidor no acepta el usuario, la contraseña o la clave guardados"))
            else ListState.Password(wrong = typedOnce)
        } catch (e: Exception) {
            ListState.Failed(e.message ?: e.javaClass.simpleName)
        }
        refreshing = false
    }

    // search: a filter on this folder, plus subfolders (and contents) for local ones
    var searching by remember(loc) { mutableStateOf(false) }
    var query by remember(loc) { mutableStateOf("") }
    var filter by remember(loc) { mutableStateOf(SearchFilter()) }
    var deep by remember(loc) { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(query, searching, filter) {
        deep = null
        if (!searching || loc !is Loc.Local) return@LaunchedEffect
        if (query.trim().length < 2 && !(filter.narrows && query.isBlank() && !filter.contents)) return@LaunchedEffect
        delay(300)
        vm.search(loc.path, query, settings.showHidden, filter) { deep = it }
    }

    val all = (state as? ListState.Ready)?.entries.orEmpty()
    val shown = remember(all, searching, query, deep, filter) {
        when {
            !searching -> all
            deep != null -> deep!!
            filter.contents && loc is Loc.Local -> emptyList()
            else -> fold(query.trim()).let { q -> all.filter { fold(it.name).contains(q) && filter.keeps(it) } }
        }
    }
    val flat = loc is Loc.Media || (searching && deep != null)
    val mediaGrid = loc is Loc.Media && loc.category.media
    val view = when {
        mediaGrid -> if (settings.gridMedia) ViewMode.GRID else ViewMode.LIST
        flat && settings.viewFiles == ViewMode.TREE -> ViewMode.LIST
        else -> settings.viewFiles
    }
    val selection = vm.selection
    val selecting = vm.selecting
    val selected = remember(shown, selection) { shown.filter { it.path in selection } }
    var jump by remember { mutableStateOf(false) }
    // the tree view shows what's inside open folders too: selecting those must work
    val treeKids = remember(loc) { mutableStateMapOf<String, List<FileEntry>>() }
    val selectable = remember(shown, treeKids.toMap()) { shown + treeKids.values.flatten() }
    val selectedAll = remember(selectable, selection) { selectable.filter { it.path in selection }.distinctBy { it.path } }

    BackHandler(enabled = selecting) { vm.clearSelection() }
    BackHandler(enabled = searching && !selecting) { searching = false; query = ""; filter = SearchFilter() }

    fun toggle(e: FileEntry) {
        vm.selection = if (e.path in selection) selection - e.path else selection + e.path
    }

    val title = when (loc) {
        is Loc.Media -> loc.category.title
        is Loc.Local -> vm.luna.volumes.of(loc.path)?.takeIf { it.path == loc.path }?.label ?: loc.path.substringAfterLast('/')
        is Loc.Remote -> if (loc.path == "/" || loc.path.isEmpty()) server?.title ?: "Servidor" else loc.path.substringAfterLast('/')
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            when {
                selecting -> TopAppBar(
                    title = {
                        Text(
                            when (selection.size) {
                                0 -> tr("Toca para seleccionar")
                                1 -> "1 seleccionado"
                                else -> "${selection.size} seleccionados"
                            },
                        )
                    },
                    navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Rounded.Close, tr("Salir de la selección")) } },
                    actions = {
                        IconButton(onClick = { vm.selection = shown.map { it.path }.toSet() }) { Icon(Icons.Rounded.SelectAll, tr("Seleccionar todo")) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
                searching -> Column {
                    SearchBar(query, { query = it }, local = loc is Loc.Local, contents = filter.contents) { searching = false; query = ""; filter = SearchFilter() }
                    SearchFilters(filter, canContents = loc is Loc.Local) { filter = it }
                }
                else -> TopAppBar(
                    title = {
                        Column {
                            Text(title.ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            if (loc is Loc.Remote && server != null) Text(
                                server.url, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    },
                    // back is the system gesture; the breadcrumbs go up; ☰ goes anywhere
                    navigationIcon = { DrawerButton() },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, tr("Buscar")) }
                        ViewMenu(vm, view, mediaGrid)
                        TabsButton(vm)
                        FolderMenu(vm, loc, settings, actions, onJump = { jump = true }, onSelectAll = { vm.selection = shown.map { it.path }.toSet() })
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            when {
                // nothing picked yet: no actions to offer
                selecting -> if (selectedAll.isNotEmpty()) SelectionBar(
                    canShare = selectedAll.none { it.isDir },
                    onCopy = { vm.copy(loc, selectedAll, cut = false) },
                    onCut = { vm.copy(loc, selectedAll, cut = true) },
                    onShare = { vm.localFiles(loc, selectedAll) { Opener.share(context, it) } },
                    onDelete = { if (vm.canTrash(loc, selectedAll)) vm.delete(loc, selectedAll) else actions.dialog = FileDialog.Delete(selectedAll, forever = true) },
                    onMore = { actions.sheet = selectedAll },
                )
                clip != null && loc !is Loc.Media -> PasteBar(clip!!.entries.size, clip!!.cut, vm::clearClip) { vm.paste(loc) }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!searching && loc !is Loc.Media) Breadcrumbs(vm, loc, server?.title)
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { refreshing = true; attempt++ },
                modifier = Modifier.fillMaxSize(),
            ) {
                when (val s = state) {
                    ListState.Loading -> Centered { DelayedSpinner() }
                    is ListState.Failed -> Centered {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(s.message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { attempt++ }) { Text(tr("Reintentar")) }
                                if (loc is Loc.Remote) TextButton(onClick = { vm.open(Route.EditServer(loc.serverId)) }) { Text(tr("Editar servidor")) }
                            }
                        }
                    }
                    is ListState.HostKey -> {
                        Centered { CircularProgressIndicator() }
                        HostKeyDialog(s.e.hostPort, s.e.fingerprint, s.e.changed, onDismiss = vm::back) {
                            vm.trustHost(s.e.hostPort, s.e.fingerprint)
                            state = ListState.Loading
                            attempt++
                        }
                    }
                    is ListState.Password -> {
                        Centered { CircularProgressIndicator() }
                        PasswordDialog(server?.title ?: tr("Servidor"), s.wrong, onDismiss = vm::back) {
                            vm.typedPassword((loc as Loc.Remote).serverId, it)
                            typedOnce = true
                            state = ListState.Loading
                            attempt++
                        }
                    }
                    is ListState.Ready -> {
                        if (shown.isEmpty()) {
                            Centered {
                                Text(
                                    when {
                                        searching && filter.contents && deep == null && loc is Loc.Local ->
                                            if (query.trim().length < 2) tr("Escribe lo que buscas dentro de los archivos") else tr("Buscando dentro de los archivos…")
                                        searching && query.isNotBlank() -> tr("Nada coincide con «%1»", query)
                                        searching && filter.narrows -> tr("Nada con esos filtros")
                                        loc is Loc.Media -> tr("No hay nada aquí todavía")
                                        else -> tr("Carpeta vacía")
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val onClick = { e: FileEntry -> if (selecting) toggle(e) else openEntry(vm, context, loc, e, shown, actions) }
                            val onLong = { e: FileEntry -> if (selecting) toggle(e) else actions.sheet = listOf(e) }
                            val menu = { e: FileEntry -> actions.sheet = listOf(e) }
                            val local = loc !is Loc.Remote
                            when (view) {
                                ViewMode.GRID -> EntryGrid(shown, selection, local, showNames = !mediaGrid, onClick, onLong)
                                ViewMode.TREE -> EntryTree(vm, loc, settings, shown, selection, local, treeKids, onClick, onLong, menu)
                                else -> EntryList(shown, selection, local, flat, view, onClick, onLong, menu)
                            }
                        }
                    }
                }
            }
        }
    }
    FileActionsHost(vm, loc, actions, siblings = shown, onSelect = { vm.selection = setOf(it.path) })
    if (jump) JumpDialog(vm) { jump = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, local: Boolean, contents: Boolean, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text(if (contents) tr("Texto dentro de los archivos") else if (local) tr("Buscar aquí y en subcarpetas") else tr("Filtrar esta carpeta")) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Cerrar búsqueda")) } },
        actions = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, tr("Borrar")) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** Desktop Luna's search options as chips: inside the files, modified since, size. */
@Composable
private fun SearchFilters(f: SearchFilter, canContents: Boolean, onChange: (SearchFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canContents) FilterChip(f.contents, { onChange(f.copy(contents = !f.contents)) }, { Text(tr("Dentro de los archivos")) })
        ChipMenu(f.age.label, f.age != Age.ANY, Age.entries.map { it.label }) { onChange(f.copy(age = Age.entries[it])) }
        ChipMenu(f.size.label, f.size != SizeRange.ANY, SizeRange.entries.map { it.label }) { onChange(f.copy(size = SizeRange.entries[it])) }
    }
}

@Composable
private fun ChipMenu(label: String, active: Boolean, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(active, { open = true }, { Text(label) }, trailingIcon = { Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(18.dp)) })
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem({ Text(o) }, { open = false; onPick(i) }) }
        }
    }
}

/**
 * Volume (or server) first, then each folder; tap one to jump there.
 * The pencil turns it into a path to type, like Ctrl+L on the desktop.
 */
@Composable
private fun Breadcrumbs(vm: MainViewModel, loc: Loc, serverTitle: String?) {
    val path = loc.path
    if (path.isEmpty()) return
    var editing by remember(loc) { mutableStateOf(false) }
    if (editing) {
        PathField(path, onCancel = { editing = false }) { typed ->
            editing = false
            val p = typed.trim().let { if (it.length > 1) it.trimEnd('/') else it }
            if (p.isEmpty() || p == path) return@PathField
            if (loc is Loc.Local && !java.io.File(p).isDirectory) vm.say(tr("No existe la carpeta «%1»", p))
            else vm.browse(loc.at(if (p.startsWith("/")) p else io.github.julssvanderhaack.luna.fs.Paths.join(path, p)))
        }
        return
    }
    val crumbs: List<Pair<String, String>> = when (loc) {
        is Loc.Local -> {
            val vol = vm.luna.volumes.of(path)
            val base = vol?.path ?: "/"
            val rest = path.removePrefix(base).split('/').filter { it.isNotEmpty() }
            // "/" first, so the whole tree is one tap away, then the volume by its name
            val head = if (vol == null) listOf("/" to "/") else listOf("/" to "/", vol.label to base)
            head + rest.runningFold(base) { acc, p -> "${acc.trimEnd('/')}/$p" }.drop(1).zip(rest) { full, name -> name to full }
        }
        else -> {
            val rest = path.split('/').filter { it.isNotEmpty() }
            listOf((serverTitle ?: "/") to "/") + rest.runningFold("") { acc, p -> "$acc/$p" }.drop(1).zip(rest) { full, name -> name to full }
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(path) { scroll.scrollTo(scroll.maxValue) }
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(scroll).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, (name, full) ->
                val last = i == crumbs.lastIndex
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { if (last) editing = true else vm.browse(loc.at(full)) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                )
                if (!last) Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(onClick = { editing = true }, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Edit, tr("Escribir una ruta"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PathField(path: String, onCancel: () -> Unit, onGo: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(path, TextRange(path.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler(onBack = onCancel)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value, { value = it }, singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo(value.text) }),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, tr("Cancelar")) }
    }
}

@Composable
private fun ViewMenu(vm: MainViewModel, view: ViewMode, mediaGrid: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { if (mediaGrid) vm.setGridMedia(view != ViewMode.GRID) else open = true }) { Icon(view.icon(), "Vista: ${view.label}") }
        DropdownMenu(open, { open = false }) {
            ViewMode.entries.forEach { m ->
                DropdownMenuItem(
                    { Text(m.label) }, { open = false; vm.setView(m) },
                    leadingIcon = { Icon(m.icon(), null) },
                    trailingIcon = { if (m == view) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}

@Composable
private fun FolderMenu(vm: MainViewModel, loc: Loc, s: AppSettings, actions: FileActions, onJump: () -> Unit, onSelectAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, tr("Más opciones")) }
        DropdownMenu(open, { open = false }) {
            if (loc !is Loc.Media) {
                DropdownMenuItem({ Text(tr("Nueva carpeta")) }, { open = false; actions.dialog = FileDialog.Create(folder = true) })
                DropdownMenuItem({ Text(tr("Nuevo archivo vacío")) }, { open = false; actions.dialog = FileDialog.Create(folder = false) })
                HorizontalDivider()
            }
            vm.undos.lastOrNull()?.let { u -> DropdownMenuItem({ Text("Deshacer: ${u.label.lowercase()}") }, { open = false; vm.undo() }) }
            vm.redos.lastOrNull()?.let { u -> DropdownMenuItem({ Text("Rehacer: ${u.label.lowercase()}") }, { open = false; vm.redo() }) }
            DropdownMenuItem({ Text(tr("Ir a una carpeta…")) }, { open = false; onJump() })
            DropdownMenuItem({ Text(tr("Nueva pestaña")) }, { open = false; vm.newTab(Route.Browse(loc)) })
            HorizontalDivider()
            DropdownMenuItem({ Text(tr("Selección múltiple")) }, { open = false; vm.selectMode = true })
            DropdownMenuItem({ Text(tr("Seleccionar todo")) }, { open = false; onSelectAll() })
            if (loc !is Loc.Media || !loc.category.media) {
                HorizontalDivider()
                Text(tr("Ordenar por"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                listOf(SortBy.NAME to tr("Nombre"), SortBy.DATE to tr("Fecha"), SortBy.SIZE to tr("Tamaño"), SortBy.TYPE to tr("Tipo")).forEach { (by, label) ->
                    DropdownMenuItem(
                        { Text(label) }, { vm.setSort(s.sort.copy(by = by)) },
                        leadingIcon = { RadioButton(selected = s.sort.by == by, onClick = null) },
                    )
                }
                DropdownMenuItem(
                    { Text(tr("Descendente")) }, { vm.setSort(s.sort.copy(descending = !s.sort.descending)) },
                    leadingIcon = { Checkbox(checked = s.sort.descending, onCheckedChange = null) },
                )
                DropdownMenuItem(
                    { Text(tr("Carpetas primero")) }, { vm.setSort(s.sort.copy(foldersFirst = !s.sort.foldersFirst)) },
                    leadingIcon = { Checkbox(checked = s.sort.foldersFirst, onCheckedChange = null) },
                )
            }
            if (loc !is Loc.Media) {
                DropdownMenuItem(
                    { Text(tr("Mostrar archivos ocultos")) }, { vm.setShowHidden(!s.showHidden) },
                    leadingIcon = { Checkbox(checked = s.showHidden, onCheckedChange = null) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    { Text(if (vm.isFavorite(loc)) tr("Quitar de favoritos") else tr("Añadir a favoritos")) },
                    { open = false; vm.toggleFavorite(loc) },
                )
                DropdownMenuItem({ Text(tr("Qué ocupa espacio")) }, { open = false; vm.open(Route.Usage(loc)) })
                DropdownMenuItem({ Text(tr("Propiedades de la carpeta")) }, {
                    open = false
                    actions.dialog = FileDialog.Properties(listOf(FileEntry(loc.path.substringAfterLast('/').ifEmpty { "/" }, loc.path, true, 0, java.io.File(loc.path).lastModified())))
                })
            }
            if (loc is Loc.Local) DropdownMenuItem({ Text(tr("Papelera")) }, { open = false; vm.open(Route.Trash) })
            if (loc is Loc.Remote) DropdownMenuItem({ Text(tr("Desconectar")) }, { open = false; vm.disconnect(loc.serverId); vm.home() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    e: FileEntry,
    sel: Boolean,
    local: Boolean,
    flat: Boolean,
    view: ViewMode,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    onMenu: (FileEntry) -> Unit,
    indent: Int = 0,
    lead: (@Composable () -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val compact = view == ViewMode.COMPACT
    Row(
        Modifier.fillMaxWidth()
            .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { onClick(e) },
                onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick(e) },
            )
            .padding(start = (if (lead != null) 4 else 16).dp + (20 * indent).dp, top = if (compact) 2.dp else 8.dp, bottom = if (compact) 2.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead?.invoke()
        Box {
            FileIcon(e, if (compact) 28.dp else if (view == ViewMode.DETAILS) 40.dp else 46.dp, local, corner = if (compact) 6.dp else 10.dp)
            if (sel) SelectedBadge(Modifier.align(Alignment.BottomEnd).size(if (compact) 14.dp else 20.dp))
        }
        Column(Modifier.weight(1f).padding(start = if (compact) 12.dp else 14.dp)) {
            Text(e.name, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (!compact) {
                val parts = buildList {
                    if (view == ViewMode.DETAILS) add(if (e.isDir) tr("Carpeta") else Mime.describe(e.name))
                    if (!e.isDir) add(formatSize(e.size))
                    add(if (view == ViewMode.DETAILS) formatFullDate(e.modified) else formatDate(e.modified))
                    if (flat) add(e.parent.substringAfterLast('/'))
                }.filter { it.isNotEmpty() }
                Text(
                    parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (view == ViewMode.DETAILS) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (compact) Text(
            if (e.isDir) "" else formatSize(e.size), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = { onMenu(e) }, modifier = if (compact) Modifier.size(36.dp) else Modifier) {
            Icon(Icons.Rounded.MoreVert, tr("Opciones de %1", e.name), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = if (compact) Modifier.size(18.dp) else Modifier)
        }
    }
}

@Composable
private fun EntryList(
    entries: List<FileEntry>,
    selection: Set<String>,
    local: Boolean,
    flat: Boolean,
    view: ViewMode,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    onMenu: (FileEntry) -> Unit,
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { e -> EntryRow(e, e.path in selection, local, flat, view, onClick, onLongClick, onMenu) }
    }
}

/**
 * Desktop Luna's tree view: folders open in place with their chevron, as
 * deep as you like; a tap on the name still enters the folder.
 */
@Composable
private fun EntryTree(
    vm: MainViewModel,
    loc: Loc,
    settings: AppSettings,
    entries: List<FileEntry>,
    selection: Set<String>,
    local: Boolean,
    kids: MutableMap<String, List<FileEntry>>,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    onMenu: (FileEntry) -> Unit,
) {
    val expanded = remember(loc) { mutableStateMapOf<String, Boolean>() }
    val scope = rememberCoroutineScope()
    val tick by vm.tick.collectAsStateWithLifecycle()
    // reload what's open after a change
    LaunchedEffect(tick, settings.showHidden, settings.sort) {
        for (p in expanded.filterValues { it }.keys) kids[p] = runCatching { vm.list(loc.at(p), settings) }.getOrDefault(emptyList())
    }
    val rows = remember(entries, expanded.toMap(), kids.toMap()) {
        buildList {
            fun add(list: List<FileEntry>, depth: Int) {
                for (e in list) {
                    add(e to depth)
                    if (e.isDir && expanded[e.path] == true) kids[e.path]?.let { add(it, depth + 1) }
                }
            }
            add(entries, 0)
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(rows, key = { it.first.path }) { (e, depth) ->
            EntryRow(
                e, e.path in selection, local, flat = false, view = ViewMode.COMPACT, onClick, onLongClick, onMenu, indent = depth,
                lead = {
                    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        if (e.isDir) {
                            val open = expanded[e.path] == true
                            IconButton(onClick = {
                                if (open) expanded[e.path] = false
                                else {
                                    expanded[e.path] = true
                                    if (kids[e.path] == null) scope.launch {
                                        kids[e.path] = runCatching { vm.list(loc.at(e.path), settings) }.getOrElse { emptyList() }
                                    }
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.KeyboardArrowRight, if (open) tr("Plegar") else tr("Desplegar"),
                                    modifier = Modifier.rotate(if (open) 90f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryGrid(
    entries: List<FileEntry>,
    selection: Set<String>,
    local: Boolean,
    showNames: Boolean,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (showNames) 104.dp else 96.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(if (showNames) 8.dp else 3.dp),
        verticalArrangement = Arrangement.spacedBy(if (showNames) 8.dp else 3.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(entries, key = { it.path }) { e ->
            val sel = e.path in selection
            Column(
                Modifier.clip(RoundedCornerShape(12.dp))
                    .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { onClick(e) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick(e) },
                    )
                    .padding(if (showNames) 4.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    // decoded at about the cell's width (3–4 columns on a phone), not more
                    FileIcon(e, if (showNames) 128.dp else 112.dp, local, square = true, corner = if (showNames) 10.dp else 4.dp)
                    if (e.kind == Kind.VIDEO) Icon(
                        Icons.Rounded.PlayCircle, null, tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.align(Alignment.Center).size(30.dp),
                    )
                    if (sel) SelectedBadge(Modifier.align(Alignment.TopEnd).padding(4.dp))
                }
                if (showNames) Text(
                    e.name, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectedBadge(modifier: Modifier) {
    Icon(
        Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp).then(modifier).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun SelectionBar(canShare: Boolean, onCopy: () -> Unit, onCut: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarButton(Icons.Rounded.ContentCopy, tr("Copiar"), onCopy)
            BarButton(Icons.Rounded.ContentCut, tr("Mover"), onCut)
            if (canShare) BarButton(Icons.Rounded.Share, tr("Compartir"), onShare)
            BarButton(Icons.Rounded.Delete, tr("Eliminar"), onDelete)
            BarButton(Icons.Rounded.MoreHoriz, tr("Más"), onMore)
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PasteBar(count: Int, cut: Boolean, onCancel: () -> Unit, onPaste: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (cut) tr("%1 para mover", plural(count, "elemento", "elementos")) else tr("%1 para copiar", plural(count, "elemento", "elementos")),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(tr("Cancelar")) }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onPaste) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (cut) tr("Mover aquí") else tr("Pegar aquí"))
            }
        }
    }
}

/** Most folders load in a blink: only show a spinner if it takes long enough to notice. */
@Composable
fun DelayedSpinner() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        show = true
    }
    if (show) CircularProgressIndicator()
}
