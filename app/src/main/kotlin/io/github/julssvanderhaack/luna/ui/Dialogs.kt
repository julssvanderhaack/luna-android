package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.InfoSection
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.fs.MediaInfo
import io.github.julssvanderhaack.luna.fs.Mime
import io.github.julssvanderhaack.luna.fs.Perms
import io.github.julssvanderhaack.luna.fs.Tally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val BAD_NAME = Regex("[/\\u0000]")

/** Name field with the part before the extension selected, as in desktop Luna's rename. */
@Composable
fun NameDialog(title: String, initial: String, selectStem: Boolean, confirm: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val stemEnd = if (selectStem) initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = value.text.trim()
    val valid = name.isNotEmpty() && name != "." && name != ".." && !BAD_NAME.containsMatchIn(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                isError = name.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onOk(name) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onOk(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun DeleteDialog(entries: List<FileEntry>, forever: Boolean, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = {
            Text(
                when {
                    entries.size == 1 && forever -> tr("¿Eliminar «%1» definitivamente?", entries[0].name)
                    entries.size == 1 -> tr("¿Eliminar «%1»?", entries[0].name)
                    forever -> tr("¿Eliminar %1 elementos definitivamente?", entries.size)
                    else -> tr("¿Eliminar %1 elementos?", entries.size)
                },
            )
        },
        text = {
            Text(
                if (entries.any { it.isDir }) tr("Las carpetas se borran con todo lo que contienen. No se puede deshacer.")
                else tr("No se puede deshacer."),
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text(tr("Eliminar"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

/** "Abrir como": hand the file over as another type, for files with odd or missing extensions. */
@Composable
fun OpenAsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        Triple(Mime.As.TEXT, Icons.AutoMirrored.Rounded.TextSnippet, tr("Texto")),
        Triple(Mime.As.IMAGE, Icons.Rounded.Image, tr("Imagen")),
        Triple(Mime.As.AUDIO, Icons.Rounded.AudioFile, tr("Audio")),
        Triple(Mime.As.VIDEO, Icons.Rounded.Movie, tr("Vídeo")),
        Triple(Mime.As.PDF, Icons.Rounded.PictureAsPdf, tr("Documento PDF")),
        Triple(Mime.As.ANY, Icons.AutoMirrored.Rounded.InsertDriveFile, tr("Otro (todas las aplicaciones)")),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Abrir como")) },
        text = {
            Column {
                options.forEach { (kind, icon, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(kind.mime) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun PropertiesDialog(vm: MainViewModel, loc: Loc, entries: List<FileEntry>, onDismiss: () -> Unit) {
    val single = entries.singleOrNull()
    val needsCount = entries.any { it.isDir } || entries.size > 1
    val tally by produceState<Tally?>(null, entries) {
        value = if (needsCount) runCatching { vm.tally(loc, entries) }.getOrNull() else null
    }
    val rows = buildList {
        if (single != null) {
            add(tr("Nombre") to single.name)
            add(tr("Tipo") to if (single.isDir) tr("Carpeta") else Mime.of(single.name))
        } else add(tr("Selección") to "${entries.size} elementos")
        add(tr("Ubicación") to (single?.parent ?: entries.first().parent))
        if (needsCount) add(
            tr("Contenido") to (tally?.let { "${plural(it.files, "archivo", "archivos")}, ${plural(it.folders - entries.count { e -> e.isDir }, "carpeta", "carpetas")} · ${formatSize(it.bytes)}" }
                ?: tr("Calculando…")),
        ) else add(tr("Tamaño") to tr("%1 (%2 bytes)", formatSize(single!!.size), "%,d".format(single.size).replace(',', '.')))
        if (single != null) add(tr("Modificado") to formatFullDate(single.modified))
        if (single?.isLink == true) add(tr("Enlace") to tr("Es un enlace simbólico"))
        if (loc !is Loc.Remote) {
            val path = single?.path ?: entries.first().parent
            vm.luna.volumes.of(path)?.let { v -> add(tr("Almacenamiento") to tr("%1 · %2 libres de %3", v.label, formatSize(v.free), formatSize(v.total))) }
        }
    }
    val perms by produceState<Perms?>(null, single) { if (single != null) value = vm.permissions(loc, single) }
    var editPerms by remember { mutableStateOf(false) }
    // photos, videos and songs: what the file itself says (remote ones are fetched first, if not huge)
    val isMedia = single != null && !single.isDir && single.kind in setOf(Kind.IMAGE, Kind.VIDEO, Kind.AUDIO)
    val media by produceState<List<InfoSection>?>(null, single) {
        if (isMedia) value = withContext(Dispatchers.IO) {
            vm.mediaFile(loc, single, ImageViewer.MAX_REMOTE)?.let { MediaInfo.read(it) }.orEmpty()
        }
    }
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Propiedades")) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { (k, v) -> PropertyRow(k, v) }
                    perms?.let { p ->
                        PropertyRow(tr("Permisos"), "${p.text} (${p.octal})")
                        PropertyRow(tr("Propietario"), if (p.group.isNotEmpty() && p.group != p.owner) "${p.owner} · grupo ${p.group}" else p.owner)
                        if (p.canChange && single != null) TextButton(onClick = { editPerms = true }, contentPadding = PaddingValues(0.dp)) { Text(tr("Cambiar permisos…")) }
                    }
                    if (isMedia) when (val m = media) {
                        null -> Text(
                            tr("Leyendo los metadatos…"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                        )
                        else -> m.forEach { section ->
                            Text(
                                section.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
                            )
                            section.rows.forEach { (k, v) -> PropertyRow(k, v) }
                            section.geo?.let { (lat, lon) ->
                                TextButton(
                                    onClick = {
                                        val uri = "geo:$lat,$lon?q=$lat,$lon".toUri()
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                ) { Text(tr("Ver en el mapa")) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Cerrar")) } },
    )
    val p = perms
    if (editPerms && p != null && single != null) PermissionsDialog(single, p, onDismiss = { editPerms = false }) { mode, recursive ->
        editPerms = false
        vm.chmod(loc, single, mode, recursive)
        onDismiss()
    }
}

/** Desktop Luna's permissions tab: a box per bit, the octal value, the special bits, and "also inside". */
@Composable
private fun PermissionsDialog(e: FileEntry, p: Perms, onDismiss: () -> Unit, onOk: (Int, Boolean) -> Unit) {
    var mode by remember { mutableStateOf(p.mode and 0xFFF) }
    var octal by remember { mutableStateOf(p.octal) }
    var recursive by remember { mutableStateOf(false) }
    fun bit(b: Int) = mode and b != 0
    fun flip(b: Int) {
        mode = mode xor b
        octal = "%04o".format(mode)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Permisos de «%1»", e.name)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row {
                    Spacer(Modifier.width(96.dp))
                    listOf(tr("Leer"), tr("Escribir"), if (e.isDir) tr("Entrar") else tr("Ejecutar")).forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(64.dp))
                    }
                }
                listOf(tr("Propietario") to 6, tr("Grupo") to 3, tr("Otros") to 0).forEach { (who, shift) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(who, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                        listOf(4, 2, 1).forEach { b ->
                            Box(Modifier.width(64.dp)) { Checkbox(bit(b shl shift), { flip(b shl shift) }) }
                        }
                    }
                }
                listOf(0x800 to tr("Setuid"), 0x400 to tr("Setgid"), 0x200 to tr("Sticky")).forEach { (b, label) ->
                    Row(Modifier.fillMaxWidth().clickable { flip(b) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(bit(b), { flip(b) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedTextField(
                    octal, { t ->
                        octal = t.filter { it in '0'..'7' }.take(4)
                        octal.toIntOrNull(8)?.let { mode = it and 0xFFF }
                    },
                    singleLine = true, label = { Text(tr("Octal")) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (e.isDir) Row(Modifier.fillMaxWidth().clickable { recursive = !recursive }.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it })
                    Text(tr("También a todo lo que hay dentro"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(mode, recursive) }) { Text(tr("Aplicar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
private fun PropertyRow(k: String, v: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
        Text(v, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun HostKeyDialog(hostPort: String, fingerprint: String, changed: Boolean, onDismiss: () -> Unit, onTrust: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(if (changed) tr("¡La huella del servidor ha cambiado!") else tr("Servidor nuevo")) },
        text = {
            Column {
                Text(
                    if (changed) tr("La clave de %1 no es la que se guardó. Puede que se haya reinstalado, ", hostPort) +
                        tr("o que alguien se esté haciendo pasar por él. Conéctate solo si sabes por qué ha cambiado.")
                    else tr("Es la primera vez que te conectas a %1. Comprueba que la huella coincide con la del servidor ", hostPort) +
                        tr("(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub)."),
                )
                Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(if (changed) tr("Confiar en la nueva") else tr("Confiar y conectar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
fun PasswordDialog(title: String, wrong: Boolean, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (wrong) Text(tr("Usuario o contraseña incorrectos"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text(tr("Contraseña")) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onOk(pw) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(pw) }) { Text(tr("Conectar")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancelar")) } },
    )
}
