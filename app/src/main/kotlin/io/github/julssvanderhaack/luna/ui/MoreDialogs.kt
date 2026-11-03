package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.Answer
import io.github.julssvanderhaack.luna.fs.ArchiveFormat
import io.github.julssvanderhaack.luna.fs.Choice
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.ImageFormat
import io.github.julssvanderhaack.luna.fs.KeyInfo
import io.github.julssvanderhaack.luna.fs.NeedsException
import io.github.julssvanderhaack.luna.fs.Paths
import io.github.julssvanderhaack.luna.fs.VolumeType
import io.github.julssvanderhaack.luna.fs.naturalCompare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun Choices(content: @Composable () -> Unit) = Column(Modifier.verticalScroll(rememberScrollState())) { content() }

@Composable
private fun RadioLine(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun CheckLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun CompressDialog(entries: List<FileEntry>, onDismiss: () -> Unit, onOk: (String, ArchiveFormat) -> Unit) {
    var name by remember { mutableStateOf(entries.singleOrNull()?.let { if (it.isDir) it.name else it.name.substringBeforeLast('.') } ?: tr("Archivo")) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    val valid = name.isNotBlank() && '/' !in name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Comprimir")) },
        text = {
            Choices {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Nombre")) }, suffix = { Text(format.ext) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                ArchiveFormat.entries.forEach { f -> RadioLine(f.label, format == f) { format = f } }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onOk(name.trim(), format) }) { Text(tr("Comprimir")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Desktop Luna's batch rename: replace a piece of text, or a base name with numbers. */
@Composable
fun BatchRenameDialog(entries: List<FileEntry>, onDismiss: () -> Unit, onOk: (List<Pair<FileEntry, String>>) -> Unit) {
    var numbered by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var base by remember { mutableStateOf(entries.first().name.substringBeforeLast('.').trimEnd { it.isDigit() || it == ' ' || it == '_' || it == '-' }.ifEmpty { tr("Archivo") }) }
    var start by remember { mutableStateOf("1") }
    val sorted = remember(entries) { entries.sortedWith { a, b -> naturalCompare(a.name, b.name) } }
    val result = remember(numbered, find, replace, base, start, sorted) {
        if (numbered) {
            val first = start.toIntOrNull() ?: 1
            val digits = (first + sorted.size - 1).toString().length.coerceAtLeast(2)
            sorted.mapIndexed { i, e ->
                val ext = if (e.isDir || '.' !in e.name) "" else "." + e.name.substringAfterLast('.')
                e to "$base ${(first + i).toString().padStart(digits, '0')}$ext"
            }
        } else sorted.map { e -> e to if (find.isEmpty()) e.name else e.name.replace(find, replace) }
    }
    val names = result.map { it.second }
    val valid = names.toSet().size == names.size && names.all { it.isNotBlank() && '/' !in it && it != "." && it != ".." } && result.any { (e, n) -> e.name != n }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Renombrar %1 elementos", entries.size)) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!numbered, { numbered = false }, { Text(tr("Reemplazar texto")) })
                    FilterChip(numbered, { numbered = true }, { Text(tr("Numerar")) })
                }
                if (numbered) {
                    OutlinedTextField(base, { base = it }, singleLine = true, label = { Text(tr("Nombre")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(start, { start = it.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text(tr("Empezar en")) }, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(find, { find = it }, singleLine = true, label = { Text(tr("Buscar")) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(replace, { replace = it }, singleLine = true, label = { Text(tr("Reemplazar por")) }, modifier = Modifier.fillMaxWidth())
                }
                Text(tr("Quedará así:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(result) { (e, n) ->
                        Text(
                            if (e.name == n) n else "${e.name} → $n", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (e.name == n) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                if (names.toSet().size != names.size) Text(tr("Dos acabarían con el mismo nombre"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onOk(result) }) { Text(tr("Renombrar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** «Comprimir y cifrar…» of desktop Luna: a password, or the public keys of other people (or both). */
@Composable
fun EncryptDialog(vm: MainViewModel, entries: List<FileEntry>, onDismiss: () -> Unit, onOk: (String, String?, List<String>, Boolean) -> Unit) {
    val tarred = entries.size > 1 || entries[0].isDir
    var name by remember { mutableStateOf(entries.singleOrNull()?.name ?: tr("Archivo")) }
    var forPeople by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var deleteOriginals by remember { mutableStateOf(false) }
    val keys by produceState<List<KeyInfo>?>(null) { value = withContext(Dispatchers.IO) { runCatching { vm.luna.keyring.keys() }.getOrDefault(emptyList()) } }
    val ok = name.isNotBlank() && if (forPeople) picked.isNotEmpty() else pw.length >= 4 && pw == pw2
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Lock, null) },
        title = { Text(if (tarred) tr("Comprimir y cifrar") else tr("Cifrar")) },
        text = {
            Choices {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(tr("Nombre")) }, suffix = { Text(if (tarred) ".tar.gpg" else ".gpg") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.size(8.dp))
                RadioLine(tr("Con una contraseña"), !forPeople) { forPeople = false }
                RadioLine(tr("Para estas personas (sus claves públicas)"), forPeople) { forPeople = true }
                if (!forPeople) {
                    OutlinedTextField(
                        pw, { pw = it }, singleLine = true, label = { Text(tr("Contraseña")) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        pw2, { pw2 = it }, singleLine = true, label = { Text(tr("Repítela")) }, visualTransformation = PasswordVisualTransformation(),
                        isError = pw2.isNotEmpty() && pw != pw2, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        tr("AES-256, como gpg -c: se abre con Luna, gpg o Kleopatra. Si la olvidas no hay forma de recuperarlo."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                    )
                } else when (val k = keys) {
                    null -> CircularProgressIndicator(Modifier.padding(16.dp))
                    else -> {
                        val usable = k.filter { it.canEncrypt }
                        if (usable.isEmpty()) Text(
                            tr("No hay claves públicas en Luna. Impórtalas en Ajustes → Claves OpenPGP (un .asc de gpg --export)."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        usable.forEach { key ->
                            CheckLine(key.userId + if (key.hasSecret) " (tuya)" else "", key.fingerprint in picked) {
                                picked = if (it) picked + key.fingerprint else picked - key.fingerprint
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                CheckLine(if (entries.size == 1) tr("Borrar el original después") else tr("Borrar los originales después"), deleteOriginals) { deleteOriginals = it }
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = { onOk(name.trim(), if (forPeople) null else pw, if (forPeople) picked.toList() else emptyList(), deleteOriginals) }) { Text(tr("Cifrar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** Checks what the file needs first: a password, the passphrase of one of your keys, or a key Luna doesn't have. */
@Composable
fun DecryptDialog(vm: MainViewModel, loc: Loc, e: FileEntry, onDismiss: () -> Unit, onOk: (String?, String?) -> Unit) {
    val needs by produceState<Result<NeedsException>?>(null, e) { value = runCatching { vm.decryptNeeds(loc, e) } }
    var pw by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val n = needs?.getOrNull()
    val missingKey = n != null && !n.password && n.keys.isNotEmpty()
    LaunchedEffect(n) { if (n != null && !missingKey) runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(tr("Descifrar «%1»", e.name)) },
        text = {
            Column {
                when {
                    needs == null -> CircularProgressIndicator()
                    needs!!.isFailure -> Text(needs!!.exceptionOrNull()?.message ?: tr("No se puede leer"), color = MaterialTheme.colorScheme.error)
                    missingKey -> Text(
                        tr("Está cifrado para %1. Importa esa clave secreta en Ajustes → Claves OpenPGP para abrirlo.", n!!.keys.joinToString(", ")),
                    )
                    else -> {
                        Text(
                            if (n!!.password) tr("Escribe la contraseña con la que se cifró.") else tr("Está cifrado para una de tus claves: escribe su frase (vacía si no tiene)."),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedTextField(
                            pw, { pw = it }, singleLine = true, label = { Text(if (n.password) tr("Contraseña") else tr("Frase de la clave")) },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onOk(if (n.password) pw else null, if (n.password) null else pw) }),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (n != null && !missingKey) TextButton(onClick = { onOk(if (n.password) pw else null, if (n.password) null else pw) }) { Text(tr("Descifrar")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (missingKey) tr("Cerrar") else tr("Cancelar")) } },
    )
}

@Composable
fun ResizeDialog(onDismiss: () -> Unit, onOk: (Int) -> Unit) {
    val sizes = listOf(3840 to "3840 px (4K)", 2560 to "2560 px", 1920 to "1920 px (Full HD)", 1280 to "1280 px", 800 to "800 px", 400 to "400 px")
    var side by remember { mutableStateOf(1920) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Reducir tamaño")) },
        text = {
            Choices {
                Text(tr("Una copia con el lado largo como mucho de:"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                sizes.forEach { (px, label) -> RadioLine(label, side == px) { side = px } }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(side) }) { Text(tr("Reducir")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun ConvertDialog(entries: List<FileEntry>, onDismiss: () -> Unit, onOk: (ImageFormat) -> Unit) {
    val exts = entries.map { it.ext.replace("jpeg", "jpg") }.toSet()
    var format by remember { mutableStateOf(ImageFormat.entries.firstOrNull { it.ext !in exts } ?: ImageFormat.JPG) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Convertir a")) },
        text = {
            Choices {
                Text(tr("Se crea una copia en ese formato; el original no cambia."), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 4.dp))
                ImageFormat.entries.forEach { f -> RadioLine(f.label, format == f) { format = f } }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(format) }) { Text(tr("Convertir")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** A name that's taken where you paste: replace (merge folders), keep both, or skip; optionally for every clash. */
@Composable
fun ConflictDialog(p: PendingConflict, onCancel: () -> Unit) {
    val c = p.conflict
    var forAll by remember(p) { mutableStateOf(false) }
    fun pick(choice: Choice) = p.answer.complete(Answer(choice, forAll))
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
        title = { Text(if (c.incoming.isDir) tr("Ya hay una carpeta «%1»", c.incoming.name) else tr("Ya existe «%1»", c.incoming.name)) },
        text = {
            Column {
                if (!c.incoming.isDir) {
                    Text(tr("Aquí: %1 · %2", formatSize(c.existing.size), formatFullDate(c.existing.modified)), style = MaterialTheme.typography.bodyMedium)
                    Text(tr("Nuevo: %1 · %2", formatSize(c.incoming.size), formatFullDate(c.incoming.modified)), style = MaterialTheme.typography.bodyMedium)
                } else Text(tr("«Combinar» mete lo nuevo dentro de la que ya está y pregunta por cada archivo repetido."), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.size(8.dp))
                CheckLine(if (c.incoming.isDir) tr("Lo mismo con las demás carpetas") else tr("Lo mismo con los demás archivos"), forAll) { forAll = it }
                Spacer(Modifier.size(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { pick(Choice.REPLACE) }) { Text(if (c.incoming.isDir) tr("Combinar") else tr("Reemplazar")) }
                    TextButton(onClick = { pick(Choice.KEEP_BOTH) }) { Text(tr("Mantener ambos")) }
                    TextButton(onClick = { pick(Choice.SKIP) }) { Text(tr("Saltar")) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("Cancelar todo")) } },
    )
}

/**
 * A folder anywhere (internal, USB, any saved server) for "Copiar a…" and
 * "Mover a…": starts at [start], browses folders only.
 */
@Composable
fun FolderPickerDialog(vm: MainViewModel, move: Boolean, start: Loc?, onDismiss: () -> Unit, onPick: (Loc) -> Unit) {
    var at by remember { mutableStateOf(start) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val here = at
    val list by produceState<Result<List<FileEntry>>?>(null, here) {
        value = null
        if (here != null) value = runCatching { vm.resolve(here).let { r -> if (r != here) { at = r; return@produceState }; vm.list(r, settings).filter { it.isDir } } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(if (move) tr("Mover a") else tr("Copiar a"))
                Text(
                    when (here) {
                        null -> tr("Elige un sitio")
                        is Loc.Remote -> "${servers.firstOrNull { it.id == here.serverId }?.title ?: tr("Servidor")}:${here.path}"
                        else -> here.path
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (here == null) {
                    items(volumes) { v ->
                        PickRow(if (v.type == VolumeType.INTERNAL) Icons.Rounded.Home else if (v.type == VolumeType.USB) Icons.Rounded.Usb else Icons.Rounded.SdCard, v.label) { at = Loc.Local(v.path) }
                    }
                    items(settings.favorites.mapNotNull { locFromKey(it) }.filter { it !is Loc.Media }) { f ->
                        PickRow(Icons.Rounded.Star, Paths.name(f.path)) { at = f }
                    }
                    items(servers) { s -> PickRow(Icons.Rounded.Dns, s.title) { at = Loc.Remote(s.id) } }
                } else {
                    item {
                        PickRow(Icons.Rounded.ArrowUpward, tr("Subir")) {
                            val vol = (here as? Loc.Local)?.let { vm.luna.volumes.of(it.path) }
                            at = when {
                                here is Loc.Local && (vol == null || vol.path == here.path) -> null
                                here is Loc.Remote && (here.path == "/" || here.path.isEmpty()) -> null
                                else -> here.at(Paths.parent(here.path))
                            }
                        }
                    }
                    when (val l = list) {
                        null -> item { DelayedSpinner() }
                        else -> l.fold(
                            onSuccess = { dirs -> items(dirs, key = { it.path }) { d -> PickRow(Icons.Rounded.Folder, d.name, arrow = true) { at = here.at(d.path) } } },
                            onFailure = { err -> item { Text(err.message ?: tr("No se puede abrir"), color = MaterialTheme.colorScheme.error) } },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = here != null && here.path.isNotEmpty(), onClick = { here?.let(onPick) }) { Text(if (move) tr("Mover aquí") else tr("Copiar aquí")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
private fun PickRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, arrow: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
        if (arrow) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Ctrl+P of desktop Luna: type bits of a path, in any order; frequent folders first. */
@Composable
fun JumpDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val servers by vm.servers.collectAsStateWithLifecycle()
    val results by produceState<List<Loc>?>(null, q) {
        delay(if (q.isEmpty()) 0 else 120)
        value = vm.jumpCandidates(q)
    }
    fun go(l: Loc) {
        onDismiss()
        vm.browse(l)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Ir a una carpeta")) },
        text = {
            Column {
                OutlinedTextField(
                    q, { q = it }, singleLine = true, placeholder = { Text(tr("fotos viaje, /storage/…")) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val typed = q.trim()
                        if (typed.startsWith("/") && java.io.File(typed).isDirectory) go(Loc.Local(typed))
                        else results?.firstOrNull()?.let(::go)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                    items(results.orEmpty(), key = { it.key }) { l ->
                        Row(Modifier.fillMaxWidth().clickable { go(l) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (l is Loc.Remote) Icons.Rounded.Dns else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(Paths.name(l.path), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (l is Loc.Remote) "${servers.firstOrNull { it.id == l.serverId }?.title}:${l.path}" else l.path,
                                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (results?.isEmpty() == true) Text(tr("Nada coincide"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
}
