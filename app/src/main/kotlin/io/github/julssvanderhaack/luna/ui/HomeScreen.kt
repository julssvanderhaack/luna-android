package io.github.julssvanderhaack.luna.ui

import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.R
import io.github.julssvanderhaack.luna.fs.Category
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Volume
import io.github.julssvanderhaack.luna.fs.VolumeType
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.server.SftpServerService
import io.github.julssvanderhaack.luna.theme.LocalPalette
import io.github.julssvanderhaack.luna.theme.compose

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val recents by vm.recents.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val serverState by SftpServerService.state.collectAsStateWithLifecycle()
    val recentActions = remember { FileActions() }
    val startServer = rememberServerStarter()
    LaunchedEffect(Unit) { vm.loadRecents() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.loadRecents() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { DrawerButton() },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.mipmap.ic_launcher_round), null, modifier = Modifier.size(30.dp))
                        Text("Luna", fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
                    }
                },
                actions = {
                    IconButton(onClick = { vm.browse(Loc.Local(Environment.getExternalStorageDirectory().path)) }) {
                        Icon(Icons.Rounded.Search, "Buscar en el almacenamiento interno")
                    }
                    IconButton(onClick = { vm.open(Route.Settings) }) { Icon(Icons.Rounded.Settings, "Ajustes") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                SectionTitle("Recientes") {
                    TextButton(onClick = { vm.browse(Loc.Media(Category.RECENT)) }) { Text("Ver todos") }
                }
                RecentStrip(recents, onOpen = { openEntry(vm, context, Loc.Media(Category.RECENT), it, recents.orEmpty()) }) { recentActions.sheet = listOf(it) }
            }
            item {
                SectionTitle("Accesos rápidos")
                Shortcuts(vm)
            }
            item { SectionTitle("Almacenamiento") }
            items(volumes, key = { it.path }) { v -> VolumeCard(v, onOpen = { vm.browse(Loc.Local(v.path)) }) {
                // apps can't unmount; Android's storage settings can
                vm.say("Android solo deja expulsar desde sus ajustes de almacenamiento")
                runCatching { context.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }
            } }
            item {
                NavRow(Icons.Rounded.AccountTree, "Raíz del sistema", "/ · todo el árbol de carpetas, como en el PC") { vm.browse(Loc.Local("/")) }
            }
            if (volumes.none { it.type != VolumeType.INTERNAL }) item {
                Text(
                    "Conecta un pendrive o un disco USB (con un adaptador OTG) y aparecerá aquí.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            val favs = settings.favorites.mapNotNull { k -> locFromKey(k)?.let { k to it } }
            if (favs.isNotEmpty()) {
                item { SectionTitle("Favoritos") }
                items(favs, key = { it.first }) { (_, loc) ->
                    val remote = (loc as? Loc.Remote)?.let { l -> servers.firstOrNull { it.id == l.serverId } }
                    NavRow(
                        Icons.Rounded.Star, loc.path.substringAfterLast('/').ifEmpty { "/" },
                        if (remote != null) "${remote.title} · ${loc.path}" else loc.path,
                        tint = LocalPalette.current.yellow.compose(),
                        onLongClick = { vm.toggleFavorite(loc); vm.say("Quitado de favoritos") },
                    ) { vm.browse(loc) }
                }
            }
            item { SectionTitle("Red") }
            items(servers.sortedBy { it.title.lowercase() }, key = { it.id }) { s -> ServerRow(vm, s) }
            item {
                NavRow(Icons.Rounded.Add, "Conectar a un servidor SFTP", "Tu PC, un NAS, una Raspberry…") { vm.open(Route.EditServer(null)) }
                PhoneServerRow(
                    running = serverState.running,
                    subtitle = when {
                        serverState.running -> serverState.addresses.firstOrNull()?.let { "Activo · sftp://${serverState.user}@$it:${serverState.port}" } ?: "Activo"
                        serverState.starting -> "Arrancando…"
                        serverState.error != null -> "Error: ${serverState.error}"
                        else -> "Para entrar en el móvil desde el PC"
                    },
                    onToggle = { on -> if (on) startServer() else SftpServerService.stop(context) },
                ) { vm.open(Route.PhoneServer) }
            }
        }
    }
    FileActionsHost(vm, Loc.Media(Category.RECENT), recentActions)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentStrip(recents: List<FileEntry>?, onOpen: (FileEntry) -> Unit, onMenu: (FileEntry) -> Unit) {
    val haptic = LocalHapticFeedback.current
    when {
        recents == null -> Spacer(Modifier.height(140.dp))
        recents.isEmpty() -> Text(
            "Aquí saldrán las fotos, descargas y documentos que hayas tocado últimamente.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        else -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(recents, key = { it.path }) { e ->
                Column(
                    Modifier.width(104.dp).clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { onOpen(e) }, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onMenu(e) }),
                ) {
                    FileIcon(e, 104.dp, local = true, corner = 12.dp)
                    Text(e.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Text(formatDate(e.modified), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Shortcuts(vm: MainViewModel) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        SHORTCUTS.filter { it.onHome }.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { s ->
                    val c = s.color()
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { vm.browse(s.loc()) }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(52.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(s.icon, null, tint = c, modifier = Modifier.size(26.dp))
                        }
                        Text(s.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeCard(v: Volume, onOpen: () -> Unit, onEject: () -> Unit) {
    val icon = when (v.type) {
        VolumeType.INTERNAL -> Icons.Rounded.PhoneAndroid
        VolumeType.USB -> Icons.Rounded.Usb
        VolumeType.SD -> Icons.Rounded.SdCard
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(v.label + if (v.readOnly) " (solo lectura)" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (v.total > 0) {
                    val used = v.total - v.free
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / v.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        drawStopIndicator = {},
                    )
                    Text(
                        "${formatSize(used)} usados de ${formatSize(v.total)} · ${formatSize(v.free)} libres",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (v.type != VolumeType.INTERNAL) IconButton(onClick = onEject) { Icon(Icons.Rounded.Eject, "Expulsar") }
            else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

@Composable
private fun ServerRow(vm: MainViewModel, s: Server) {
    var menu by remember { mutableStateOf(false) }
    Box {
        NavRow(Icons.Rounded.Dns, s.title, "${s.user}@${s.hostPort}", onLongClick = { menu = true }) { vm.browse(Loc.Remote(s.id)) }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text("Abrir") }, { menu = false; vm.browse(Loc.Remote(s.id)) })
            DropdownMenuItem({ Text("Editar…") }, { menu = false; vm.open(Route.EditServer(s.id)) })
            DropdownMenuItem({ Text("Desconectar") }, { menu = false; vm.disconnect(s.id) })
            DropdownMenuItem({ Text("Eliminar", color = MaterialTheme.colorScheme.error) }, { menu = false; vm.deleteServer(s.id) })
        }
    }
}

@Composable
private fun PhoneServerRow(running: Boolean, subtitle: String, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    NavRow(
        Icons.Rounded.Lan, "Compartir este móvil por SFTP", subtitle,
        tint = if (running) LocalPalette.current.green.compose() else MaterialTheme.colorScheme.primary,
        trailing = { Switch(checked = running, onCheckedChange = onToggle) },
        onClick = onClick,
    )
}

