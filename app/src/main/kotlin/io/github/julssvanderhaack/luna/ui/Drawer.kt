package io.github.julssvanderhaack.luna.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SpaceDashboard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.R
import io.github.julssvanderhaack.luna.fs.VolumeType
import io.github.julssvanderhaack.luna.server.SftpServerService
import io.github.julssvanderhaack.luna.theme.LocalPalette
import io.github.julssvanderhaack.luna.theme.compose

/** Opens the side drawer; screens put it behind their ☰ button. */
val LocalOpenDrawer = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun DrawerButton() {
    val open = LocalOpenDrawer.current
    IconButton(onClick = open) { Icon(Icons.Rounded.Menu, "Menú") }
}

/**
 * Every place in one list, like a desktop sidebar: the quick-access
 * shortcuts, the home folder, the root of the file system, each volume,
 * favourites and servers. Picking one starts a fresh history from it.
 */
@Composable
fun LunaDrawer(vm: MainViewModel, close: () -> Unit) {
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val server by SftpServerService.state.collectAsStateWithLifecycle()
    val route = vm.stack.last()
    val here = (route as? Route.Browse)?.loc

    fun go(loc: Loc) {
        close()
        vm.home()
        vm.browse(loc)
    }
    fun open(r: Route) {
        close()
        vm.home()
        if (r != Route.Home) vm.open(r)
    }

    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.width(300.dp).fillMaxHeight(),
    ) {
        LazyColumn(Modifier.padding(horizontal = 12.dp)) {
            item {
                Row(Modifier.padding(start = 16.dp, top = 20.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.mipmap.ic_launcher_round), null, modifier = Modifier.size(36.dp))
                    Text("Luna", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
                }
                Item(Icons.Rounded.SpaceDashboard, "Inicio", selected = route == Route.Home) { open(Route.Home) }
            }

            item { Header("Lugares") }
            item {
                Item(Icons.Rounded.Home, "Carpeta personal", volumes.firstOrNull { it.type == VolumeType.INTERNAL }?.let { "Interno · ${formatSize(it.free)} libres" } ?: homeDir, selected = here == Loc.Local(homeDir)) { go(Loc.Local(homeDir)) }
                Item(Icons.Rounded.AccountTree, "Raíz", "/", selected = here == Loc.Local("/")) { go(Loc.Local("/")) }
            }
            items(volumes.filter { it.type != VolumeType.INTERNAL }, key = { it.path }) { v ->
                Item(
                    if (v.type == VolumeType.USB) Icons.Rounded.Usb else Icons.Rounded.SdCard, v.label,
                    "${formatSize(v.free)} libres", selected = here == Loc.Local(v.path),
                ) { go(Loc.Local(v.path)) }
            }

            item { Header("Accesos rápidos") }
            items(SHORTCUTS, key = { it.label }) { s ->
                val loc = s.loc()
                Item(s.icon, s.label, tint = s.color(), selected = here == loc) { go(loc) }
            }

            val favs = settings.favorites.mapNotNull { k -> locFromKey(k)?.let { k to it } }
            if (favs.isNotEmpty()) {
                item { Header("Favoritos") }
                items(favs, key = { "fav:" + it.first }) { (_, loc) ->
                    Item(
                        Icons.Rounded.Star, loc.path.substringAfterLast('/').ifEmpty { "/" }, loc.path,
                        tint = LocalPalette.current.yellow.compose(), selected = here == loc,
                    ) { go(loc) }
                }
            }

            item { Header("Red") }
            items(servers.sortedBy { it.title.lowercase() }, key = { "srv:" + it.id }) { s ->
                Item(Icons.Rounded.Dns, s.title, "${s.user}@${s.hostPort}", selected = (here as? Loc.Remote)?.serverId == s.id) { go(Loc.Remote(s.id)) }
            }
            item {
                Item(
                    Icons.Rounded.Lan, "Servidor SFTP del móvil", if (server.running) "Activo" else "Detenido",
                    tint = if (server.running) LocalPalette.current.green.compose() else MaterialTheme.colorScheme.onSurfaceVariant,
                    selected = route == Route.PhoneServer,
                ) { open(Route.PhoneServer) }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Item(Icons.Rounded.Settings, "Ajustes", selected = route == Route.Settings) { open(Route.Settings) }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Item(
    icon: ImageVector,
    label: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else tint) },
        label = {
            androidx.compose.foundation.layout.Column {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(
                    subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
