package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.julssvanderhaack.luna.fs.Paths

/** What a tab's last screen is called, for the tab list. */
fun routeTitle(vm: MainViewModel, r: Route): String = when (r) {
    Route.Home -> tr("Inicio")
    is Route.Browse -> when (val l = r.loc) {
        is Loc.Media -> l.category.title
        is Loc.Local -> vm.luna.volumes.of(l.path)?.takeIf { it.path == l.path }?.label ?: Paths.name(l.path)
        is Loc.Remote -> vm.luna.servers.find(l.serverId)?.let { s -> if (l.path.isEmpty() || l.path == "/") s.title else "${Paths.name(l.path)} · ${s.title}" } ?: "Servidor"
    }
    is Route.Viewer -> r.images.getOrNull(r.start)?.name ?: tr("Foto")
    is Route.Preview -> r.files.getOrNull(r.start)?.name ?: tr("Vista previa")
    Route.Trash -> tr("Papelera")
    is Route.Usage -> tr("Espacio · %1", Paths.name(r.loc.path))
    Route.Settings, Route.Themes, is Route.ThemeEditor, Route.Keys -> tr("Ajustes")
    Route.PhoneServer -> tr("Servidor SFTP del móvil")
    is Route.EditServer -> tr("Servidor")
}

/** The tab counter of a mobile browser: shown once there's more than one tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsButton(vm: MainViewModel) {
    var open by remember { mutableStateOf(false) }
    if (vm.tabs.size > 1) IconButton(onClick = { open = true }) {
        Box(
            Modifier.size(22.dp).border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) { Text("${vm.tabs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
    }
    if (open) TabsSheet(vm) { open = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(vm: MainViewModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Pestañas"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.newTab(); onClose() }) {
                    Icon(Icons.Rounded.Add, null, modifier = Modifier.size(18.dp))
                    Text(tr("Nueva"), modifier = Modifier.padding(start = 6.dp))
                }
            }
            vm.tabs.forEachIndexed { i, t ->
                val r = t.stack.last()
                val current = i == vm.tabIndex
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { vm.switchTab(i); onClose() }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val loc = (r as? Route.Browse)?.loc
                    Icon(
                        when {
                            r == Route.Home -> Icons.Rounded.SpaceDashboard
                            loc is Loc.Remote -> Icons.Rounded.Dns
                            else -> Icons.Rounded.Folder
                        },
                        null, tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(routeTitle(vm, r), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal)
                        loc?.path?.takeIf { it.isNotEmpty() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (vm.tabs.size > 1) IconButton(onClick = { vm.closeTab(i); if (vm.tabs.size == 1) onClose() }) { Icon(Icons.Rounded.Close, tr("Cerrar pestaña")) }
                }
            }
        }
    }
}
