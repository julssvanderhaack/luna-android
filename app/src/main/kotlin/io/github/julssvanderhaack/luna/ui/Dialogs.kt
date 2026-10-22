package io.github.julssvanderhaack.luna.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import io.github.julssvanderhaack.luna.fs.Mime
import io.github.julssvanderhaack.luna.fs.Tally
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun DeleteDialog(entries: List<FileEntry>, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(if (entries.size == 1) "¿Eliminar «${entries[0].name}»?" else "¿Eliminar ${entries.size} elementos?") },
        text = {
            Text(
                if (entries.any { it.isDir }) "Las carpetas se borran con todo lo que contienen. No se puede deshacer."
                else "No se puede deshacer.",
            )
        },
        confirmButton = { TextButton(onClick = onOk) { Text("Eliminar", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** "Abrir como": hand the file over as another type, for files with odd or missing extensions. */
@Composable
fun OpenAsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val options = listOf(
        Triple(Mime.As.TEXT, Icons.AutoMirrored.Rounded.TextSnippet, "Texto"),
        Triple(Mime.As.IMAGE, Icons.Rounded.Image, "Imagen"),
        Triple(Mime.As.AUDIO, Icons.Rounded.AudioFile, "Audio"),
        Triple(Mime.As.VIDEO, Icons.Rounded.Movie, "Vídeo"),
        Triple(Mime.As.PDF, Icons.Rounded.PictureAsPdf, "Documento PDF"),
        Triple(Mime.As.ANY, Icons.AutoMirrored.Rounded.InsertDriveFile, "Otro (todas las aplicaciones)"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Abrir como") },
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
            add("Nombre" to single.name)
            add("Tipo" to if (single.isDir) "Carpeta" else Mime.of(single.name))
        } else add("Selección" to "${entries.size} elementos")
        add("Ubicación" to (single?.parent ?: entries.first().parent))
        if (needsCount) add(
            "Contenido" to (tally?.let { "${plural(it.files, "archivo", "archivos")}, ${plural(it.folders - entries.count { e -> e.isDir }, "carpeta", "carpetas")} · ${formatSize(it.bytes)}" }
                ?: "Calculando…"),
        ) else add("Tamaño" to "${formatSize(single!!.size)} (${"%,d".format(single.size).replace(',', '.')} bytes)")
        if (single != null) add("Modificado" to formatFullDate(single.modified))
        if (single?.isLink == true) add("Enlace" to "Es un enlace simbólico")
        if (loc !is Loc.Remote) {
            val path = single?.path ?: entries.first().parent
            vm.luna.volumes.of(path)?.let { v -> add("Almacenamiento" to "${v.label} · ${formatSize(v.free)} libres de ${formatSize(v.total)}") }
            if (single != null && !File(single.path).canWrite()) add("Permisos" to "Solo lectura")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Propiedades") },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(k, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(110.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
fun HostKeyDialog(hostPort: String, fingerprint: String, changed: Boolean, onDismiss: () -> Unit, onTrust: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Warning, null, tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(if (changed) "¡La huella del servidor ha cambiado!" else "Servidor nuevo") },
        text = {
            Column {
                Text(
                    if (changed) "La clave de $hostPort no es la que se guardó. Puede que se haya reinstalado, " +
                        "o que alguien se esté haciendo pasar por él. Conéctate solo si sabes por qué ha cambiado."
                    else "Es la primera vez que te conectas a $hostPort. Comprueba que la huella coincide con la del servidor " +
                        "(ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub).",
                )
                Text(fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(if (changed) "Confiar en la nueva" else "Confiar y conectar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
                if (wrong) Text("Usuario o contraseña incorrectos", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = pw, onValueChange = { pw = it }, singleLine = true, label = { Text("Contraseña") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onOk(pw) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOk(pw) }) { Text("Conectar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
