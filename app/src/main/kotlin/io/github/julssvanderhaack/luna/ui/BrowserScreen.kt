package io.github.julssvanderhaack.luna.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.SortBy
import io.github.julssvanderhaack.luna.fs.fold
import io.github.julssvanderhaack.luna.remote.AuthException
import io.github.julssvanderhaack.luna.remote.HostKeyException
import io.github.julssvanderhaack.luna.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private sealed interface ListState {
    data object Loading : ListState
    data class Ready(val entries: List<FileEntry>) : ListState
    data class Failed(val message: String) : ListState
    data class HostKey(val e: HostKeyException) : ListState
    data class Password(val wrong: Boolean) : ListState
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
            if (server?.password != null && !typedOnce) ListState.Failed("El servidor no acepta el usuario, la contraseña o la clave guardados")
            else ListState.Password(wrong = typedOnce)
        } catch (e: Exception) {
            ListState.Failed(e.message ?: e.javaClass.simpleName)
        }
        refreshing = false
    }

    // search: a filter on this folder, plus subfolders for local ones
    var searching by remember(loc) { mutableStateOf(false) }
    var query by remember(loc) { mutableStateOf("") }
    var deep by remember(loc) { mutableStateOf<List<FileEntry>?>(null) }
    LaunchedEffect(query, searching) {
        deep = null
        if (!searching || query.trim().length < 2 || loc !is Loc.Local) return@LaunchedEffect
        delay(300)
        vm.search(loc.path, query, settings.showHidden) { deep = it }
    }

    val all = (state as? ListState.Ready)?.entries.orEmpty()
    val shown = remember(all, searching, query, deep) {
        when {
            !searching || query.isBlank() -> all
            deep != null -> deep!!
            else -> fold(query.trim()).let { q -> all.filter { fold(it.name).contains(q) } }
        }
    }
    val flat = loc is Loc.Media || (searching && deep != null)
    val mediaGrid = loc is Loc.Media && loc.category.media
    val grid = if (mediaGrid) settings.gridMedia else settings.gridFiles
    val selection = vm.selection
    val selecting = selection.isNotEmpty()
    val selected = remember(shown, selection) { shown.filter { it.path in selection } }

    BackHandler(enabled = selecting) { vm.selection = emptySet() }
    BackHandler(enabled = searching && !selecting) { searching = false; query = "" }

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
                    title = { Text("${selection.size} seleccionados") },
                    navigationIcon = { IconButton(onClick = { vm.selection = emptySet() }) { Icon(Icons.Rounded.Close, "Salir de la selección") } },
                    actions = {
                        IconButton(onClick = { vm.selection = shown.map { it.path }.toSet() }) { Icon(Icons.Rounded.SelectAll, "Seleccionar todo") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
                searching -> SearchBar(query, { query = it }, local = loc is Loc.Local) { searching = false; query = "" }
                else -> TopAppBar(
                    title = {
                        Column {
                            Text(title.ifEmpty { "/" }, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                            if (loc is Loc.Remote && server != null) Text(
                                "${server.user}@${server.hostPort}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            )
                        }
                    },
                    // back is the system gesture; the breadcrumbs go up; ☰ goes anywhere
                    navigationIcon = { DrawerButton() },
                    actions = {
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Buscar") }
                        IconButton(onClick = { vm.setGrid(mediaGrid, !grid) }) {
                            Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "Ver como lista" else "Ver como cuadrícula")
                        }
                        FolderMenu(vm, loc, settings, actions, onSelectAll = { vm.selection = shown.map { it.path }.toSet() })
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        bottomBar = {
            when {
                selecting -> SelectionBar(
                    canShare = selected.none { it.isDir },
                    onCopy = { vm.copy(loc, selected, cut = false) },
                    onCut = { vm.copy(loc, selected, cut = true) },
                    onShare = { vm.localFiles(loc, selected) { Opener.share(context, it) } },
                    onDelete = { actions.dialog = FileDialog.Delete(selected) },
                    onMore = { actions.sheet = selected },
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
                                Button(onClick = { attempt++ }) { Text("Reintentar") }
                                if (loc is Loc.Remote) TextButton(onClick = { vm.open(Route.EditServer(loc.serverId)) }) { Text("Editar servidor") }
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
                        PasswordDialog(server?.title ?: "Servidor", s.wrong, onDismiss = vm::back) {
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
                                        searching && query.isNotBlank() -> "Nada coincide con «$query»"
                                        loc is Loc.Media -> "No hay nada aquí todavía"
                                        else -> "Carpeta vacía"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            val onClick = { e: FileEntry -> if (selecting) toggle(e) else openEntry(vm, context, loc, e) }
                            val onMenu = { e: FileEntry -> if (selecting) toggle(e) else actions.sheet = listOf(e) }
                            if (grid) EntryGrid(shown, selection, loc !is Loc.Remote, showNames = !mediaGrid, onClick, onMenu)
                            else EntryList(shown, selection, loc !is Loc.Remote, flat, onClick, onMenu) { actions.sheet = listOf(it) }
                        }
                    }
                }
            }
        }
    }
    FileActionsHost(vm, loc, actions, onSelect = { vm.selection = setOf(it.path) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, local: Boolean, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        title = {
            TextField(
                value = query, onValueChange = onQuery, singleLine = true,
                placeholder = { Text(if (local) "Buscar aquí y en subcarpetas" else "Filtrar esta carpeta") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cerrar búsqueda") } },
        actions = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, "Borrar") } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** Volume (or server) first, then each folder; tap one to jump there. */
@Composable
private fun Breadcrumbs(vm: MainViewModel, loc: Loc, serverTitle: String?) {
    val path = loc.path
    if (path.isEmpty()) return
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
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        crumbs.forEachIndexed { i, (name, full) ->
            val last = i == crumbs.lastIndex
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !last) { vm.browse(loc.at(full)) }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
            )
            if (!last) Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FolderMenu(vm: MainViewModel, loc: Loc, s: AppSettings, actions: FileActions, onSelectAll: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Más opciones") }
        DropdownMenu(open, { open = false }) {
            if (loc !is Loc.Media) {
                DropdownMenuItem({ Text("Nueva carpeta") }, { open = false; actions.dialog = FileDialog.Create(folder = true) })
                DropdownMenuItem({ Text("Nuevo archivo vacío") }, { open = false; actions.dialog = FileDialog.Create(folder = false) })
                HorizontalDivider()
            }
            DropdownMenuItem({ Text("Seleccionar todo") }, { open = false; onSelectAll() })
            if (loc !is Loc.Media || !loc.category.media) {
                HorizontalDivider()
                Text("Ordenar por", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                listOf(SortBy.NAME to "Nombre", SortBy.DATE to "Fecha", SortBy.SIZE to "Tamaño", SortBy.TYPE to "Tipo").forEach { (by, label) ->
                    DropdownMenuItem(
                        { Text(label) }, { vm.setSort(s.sort.copy(by = by)) },
                        leadingIcon = { RadioButton(selected = s.sort.by == by, onClick = null) },
                    )
                }
                DropdownMenuItem(
                    { Text("Descendente") }, { vm.setSort(s.sort.copy(descending = !s.sort.descending)) },
                    leadingIcon = { Checkbox(checked = s.sort.descending, onCheckedChange = null) },
                )
                DropdownMenuItem(
                    { Text("Carpetas primero") }, { vm.setSort(s.sort.copy(foldersFirst = !s.sort.foldersFirst)) },
                    leadingIcon = { Checkbox(checked = s.sort.foldersFirst, onCheckedChange = null) },
                )
            }
            if (loc !is Loc.Media) {
                DropdownMenuItem(
                    { Text("Mostrar archivos ocultos") }, { vm.setShowHidden(!s.showHidden) },
                    leadingIcon = { Checkbox(checked = s.showHidden, onCheckedChange = null) },
                )
                HorizontalDivider()
                DropdownMenuItem(
                    { Text(if (vm.isFavorite(loc)) "Quitar de favoritos" else "Añadir a favoritos") },
                    { open = false; vm.toggleFavorite(loc) },
                )
                DropdownMenuItem({ Text("Propiedades de la carpeta") }, {
                    open = false
                    actions.dialog = FileDialog.Properties(listOf(FileEntry(loc.path.substringAfterLast('/').ifEmpty { "/" }, loc.path, true, 0, java.io.File(loc.path).lastModified())))
                })
            }
            if (loc is Loc.Remote) DropdownMenuItem({ Text("Desconectar") }, { open = false; vm.disconnect(loc.serverId); vm.home() })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryList(
    entries: List<FileEntry>,
    selection: Set<String>,
    local: Boolean,
    flat: Boolean,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    onMenu: (FileEntry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
        items(entries, key = { it.path }) { e ->
            val sel = e.path in selection
            Row(
                Modifier.fillMaxWidth()
                    .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .combinedClickable(
                        onClick = { onClick(e) },
                        onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick(e) },
                    )
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    FileIcon(e, 46.dp, local)
                    if (sel) SelectedBadge(Modifier.align(Alignment.BottomEnd))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(e.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val parts = buildList {
                        if (!e.isDir) add(formatSize(e.size))
                        add(formatDate(e.modified))
                        if (flat) add(e.parent.substringAfterLast('/'))
                    }.filter { it.isNotEmpty() }
                    Text(
                        parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onMenu(e) }) {
                    Icon(Icons.Rounded.MoreVert, "Opciones de ${e.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
        modifier = modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun SelectionBar(canShare: Boolean, onCopy: () -> Unit, onCut: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit, onMore: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarButton(Icons.Rounded.ContentCopy, "Copiar", onCopy)
            BarButton(Icons.Rounded.ContentCut, "Mover", onCut)
            if (canShare) BarButton(Icons.Rounded.Share, "Compartir", onShare)
            BarButton(Icons.Rounded.Delete, "Eliminar", onDelete)
            BarButton(Icons.Rounded.MoreHoriz, "Más", onMore)
        }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
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
                "${plural(count, "elemento", "elementos")} para ${if (cut) "mover" else "copiar"}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancelar") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onPaste) {
                Icon(Icons.Rounded.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (cut) "Mover aquí" else "Pegar aquí")
            }
        }
    }
}

/** Most folders load in a blink: only show a spinner if it takes long enough to notice. */
@Composable
private fun DelayedSpinner() {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        show = true
    }
    if (show) CircularProgressIndicator()
}
