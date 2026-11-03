package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.KeyInfo
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Every volume's trash in one list, newest first: restore or delete for good. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrashScreen(vm: MainViewModel) {
    val tick by vm.tick.collectAsStateWithLifecycle()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    LaunchedEffect(tick) { items = vm.trashItems() }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var confirm by remember { mutableStateOf<List<TrashItem>?>(null) }
    val list = items.orEmpty()
    val chosen = list.filter { it.entry.path in picked }
    BackScaffold(
        title = tr("Papelera"),
        subtitle = items?.let { l -> if (l.isEmpty()) tr("Vacía") else "${plural(l.size, "elemento", "elementos")} · ${formatSize(l.sumOf { it.entry.size })}" },
        onBack = vm::back,
        actions = {
            if (picked.isNotEmpty()) {
                IconButton(onClick = { vm.restore(chosen); picked = emptySet() }) { Icon(Icons.Rounded.RestoreFromTrash, tr("Restaurar")) }
                IconButton(onClick = { confirm = chosen }) { Icon(Icons.Rounded.DeleteForever, tr("Eliminar para siempre")) }
                IconButton(onClick = { picked = list.map { it.entry.path }.toSet() }) { Icon(Icons.Rounded.SelectAll, tr("Seleccionar todo")) }
            } else if (list.isNotEmpty()) IconButton(onClick = { confirm = list }) { Icon(Icons.Rounded.DeleteSweep, tr("Vaciar la papelera")) }
        },
    ) { pad ->
        when {
            items == null -> Centered { DelayedSpinner() }
            list.isEmpty() -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
                    Text(tr("La papelera está vacía"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tr("Lo que eliminas del móvil o de un USB viene aquí, en la carpeta .Trash-luna de cada uno."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(list, key = { it.entry.path }) { t ->
                    val sel = t.entry.path in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (sel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .combinedClickable(
                                onClick = { picked = if (picked.isEmpty()) setOf(t.entry.path) else if (sel) picked - t.entry.path else picked + t.entry.path },
                                onLongClick = { picked = picked + t.entry.path },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FileIcon(t.entry, 46.dp, local = true)
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(Paths.name(t.originalPath), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${Paths.parent(t.originalPath)} · ${formatDate(t.deleted)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { vm.restore(listOf(t)) }) { Icon(Icons.Rounded.RestoreFromTrash, tr("Restaurar")) }
                    }
                }
            }
        }
    }
    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (c.size == list.size) tr("¿Vaciar la papelera?") else tr("¿Eliminar %1 para siempre?", plural(c.size, "elemento", "elementos"))) },
            text = { Text(tr("No se puede deshacer.")) },
            confirmButton = {
                TextButton(onClick = { vm.purge(c); picked = emptySet(); confirm = null }) { Text(tr("Eliminar"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(tr("Cancelar")) } },
        )
    }
}

/** Ajustes → Claves OpenPGP: public keys to encrypt for, your secret keys to decrypt with. */
@Composable
fun KeysScreen(vm: MainViewModel) {
    val tick by vm.tick.collectAsStateWithLifecycle()
    var keys by remember { mutableStateOf<List<KeyInfo>?>(null) }
    LaunchedEffect(tick) { keys = withContext(Dispatchers.IO) { runCatching { vm.luna.keyring.keys() }.getOrDefault(emptyList()) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importKeys(it) } }
    var deleting by remember { mutableStateOf<KeyInfo?>(null) }
    BackScaffold(
        tr("Claves OpenPGP"), vm::back,
        actions = { IconButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Add, tr("Importar claves")) } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                Text(
                    tr("Para cifrar para otras personas, importa sus claves públicas (gpg --armor --export correo > clave.asc). ") +
                        tr("Para abrir lo que te envían a ti, importa tu clave secreta (gpg --armor --export-secret-keys); ") +
                        tr("se guarda dentro de Luna, protegida con su frase."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (keys?.isEmpty() == true) item {
                TextButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.padding(horizontal = 12.dp)) { Text(tr("Importar un archivo de claves…")) }
            }
            items(keys.orEmpty(), key = { it.fingerprint }) { k ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Key, null, tint = if (k.hasSecret) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(k.userId, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            k.fingerprint.chunked(4).joinToString(" "), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            listOfNotNull(
                                if (k.hasSecret) tr("Tuya (pública y secreta)") else tr("Pública"),
                                if (!k.canEncrypt) tr("no sirve para cifrar") else null,
                                formatDate(k.created),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { deleting = k }) { Icon(Icons.Rounded.Delete, tr("Borrar")) }
                }
                HorizontalDivider(Modifier.padding(start = 56.dp))
            }
        }
    }
    deleting?.let { k ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(tr("¿Borrar la clave de %1?", k.userId)) },
            text = { Text(if (k.hasSecret) tr("Es una clave secreta: sin ella no podrás abrir lo cifrado para ti, salvo que la vuelvas a importar.") else tr("Podrás volver a importarla.")) },
            confirmButton = { TextButton(onClick = { vm.deleteKey(k.fingerprint); deleting = null }) { Text(tr("Borrar"), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Cancelar")) } },
        )
    }
}

/** julss.disk on the PC, here: what's in this folder sorted by size, with a bar each. Tap a folder to go deeper. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UsageScreen(vm: MainViewModel, loc: Loc) {
    var rows by remember(loc) { mutableStateOf<List<Pair<FileEntry, Long>>?>(null) }
    var error by remember(loc) { mutableStateOf<String?>(null) }
    var done by remember(loc) { mutableStateOf(false) }
    LaunchedEffect(loc) {
        runCatching { vm.usage(loc) { rows = it } }.onFailure { error = it.message ?: tr("No se puede leer") }
        done = true
    }
    val list = rows.orEmpty()
    val total = list.sumOf { it.second.coerceAtLeast(0) }
    val vol = if (loc is Loc.Local) vm.luna.volumes.of(loc.path) else null
    BackScaffold(
        title = tr("Qué ocupa espacio"),
        subtitle = listOfNotNull(
            loc.path, if (rows != null) formatSize(total) + if (!done) "…" else "" else null,
            vol?.let { tr("%1 libres en %2", formatSize(it.free), it.label) },
        ).joinToString(" · "),
        onBack = vm::back,
    ) { pad ->
        when {
            error != null -> Centered { Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            rows == null -> Centered { DelayedSpinner() }
            else -> LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(list, key = { it.first.path }) { (e, size) ->
                    val share = if (total > 0 && size > 0) size.toFloat() / total else 0f
                    Column(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (e.isDir) vm.open(Route.Usage(loc.at(e.path))) },
                                onLongClick = { vm.browse(loc.at(if (e.isDir) e.path else e.parent)) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FileIcon(e, 36.dp, loc !is Loc.Remote)
                            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            Text(
                                if (size < 0) "…" else "${formatSize(size)} · ${"%.0f".format(share * 100)} %",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            Modifier.padding(start = 48.dp, top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().background(e.kind.color()))
                        }
                    }
                }
                item {
                    Text(
                        tr("Toca una carpeta para ver qué hay dentro; mantén pulsado para abrirla."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            }
        }
    }
}
