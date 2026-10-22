package io.github.julssvanderhaack.luna.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.remote.Server
import io.github.julssvanderhaack.luna.server.SftpServerService
import io.github.julssvanderhaack.luna.server.authorizedKeys
import io.github.julssvanderhaack.luna.settings.ServerConfig
import io.github.julssvanderhaack.luna.settings.Settings
import io.github.julssvanderhaack.luna.theme.LocalPalette
import io.github.julssvanderhaack.luna.theme.compose

@Composable
fun EditServerScreen(vm: MainViewModel, id: String?) {
    val context = LocalContext.current
    val original = remember(id) { id?.let { vm.luna.servers.find(it) } ?: Server() }
    var name by remember { mutableStateOf(original.name) }
    var host by remember { mutableStateOf(original.host) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var user by remember { mutableStateOf(original.user) }
    var password by remember { mutableStateOf(original.password.orEmpty()) }
    var savePassword by remember { mutableStateOf(id == null || original.password != null) }
    var showPw by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf<Uri?>(null) }
    var keyName by remember { mutableStateOf(if (original.hasKey) "Clave guardada" else null) }
    var passphrase by remember { mutableStateOf(original.keyPassphrase.orEmpty()) }
    var startPath by remember { mutableStateOf(original.startPath) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            key = uri
            keyName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Clave"
        }
    }
    val valid = host.isNotBlank() && user.isNotBlank() && port.toIntOrNull() in 1..65535

    BackScaffold(
        if (id == null) "Nuevo servidor SFTP" else "Editar servidor", vm::back,
        actions = {
            TextButton(enabled = valid, onClick = {
                vm.saveServer(
                    original.copy(
                        name = name.trim(), host = host.trim(), port = port.toInt(), user = user.trim(),
                        password = if (savePassword) password else null,
                        keyPassphrase = passphrase.ifEmpty { null },
                        hasKey = original.hasKey && keyName != null,
                        startPath = startPath.trim(),
                    ),
                    key,
                )
                vm.back()
            }) { Text("Guardar") }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Field("Servidor (IP o nombre)", host, { host = it }, KeyboardType.Uri)
            Field("Puerto", port, { port = it.filter(Char::isDigit) }, KeyboardType.Number)
            Field("Usuario", user, { user = it })
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Contraseña") }, singleLine = true,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPw = !showPw }) {
                        Icon(if (showPw) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Mostrar")
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row(Modifier.clickable { savePassword = !savePassword }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(savePassword, { savePassword = it })
                Text("Guardar la contraseña en el móvil (si no, la pide al conectar)", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Clave privada (opcional)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(
                "Copia al móvil tu ~/.ssh/id_ed25519 (o id_rsa) del PC y elígela aquí. Luna guarda una copia privada.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Rounded.Key, null, modifier = Modifier.size(18.dp))
                    Text(keyName ?: "Elegir archivo de clave", modifier = Modifier.padding(start = 8.dp))
                }
                if (keyName != null) TextButton(onClick = { key = null; keyName = null }) { Text("Quitar") }
            }
            if (keyName != null) OutlinedTextField(
                value = passphrase, onValueChange = { passphrase = it }, label = { Text("Frase de la clave (si tiene)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Field("Nombre (opcional)", name, { name = it })
            Field("Carpeta inicial (vacío = tu carpeta personal)", startPath, { startPath = it }, KeyboardType.Uri)
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

/** Starts the server, asking for the notification permission first on Android 13+ (it's how you stop it). */
@Composable
fun rememberServerStarter(): () -> Unit {
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { SftpServerService.start(context) }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        else SftpServerService.start(context)
    }
}

@Composable
fun PhoneServerScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val state by SftpServerService.state.collectAsStateWithLifecycle()
    val volumes by vm.volumes.collectAsStateWithLifecycle()
    val start = rememberServerStarter()
    var cfg by remember { mutableStateOf<ServerConfig?>(null) }
    var dirty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cfg = vm.serverConfig() }
    fun edit(c: ServerConfig) {
        cfg = c
        dirty = true
    }
    fun copy(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("sftp", text))
        vm.say("Copiado")
    }

    BackScaffold(
        "Servidor SFTP del móvil", vm::back,
        actions = {
            TextButton(enabled = dirty && (cfg?.port ?: 0) in 1024..65535, onClick = {
                cfg?.let { vm.setServerConfig(it) }
                dirty = false
                if (state.running) vm.say("Guardado. Detén y vuelve a iniciar el servidor para aplicarlo")
            }) { Text("Guardar") }
        },
    ) { pad ->
        val c = cfg ?: return@BackScaffold
        val portOk = c.port in 1024..65535
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                when {
                                    state.running -> "Activo"
                                    state.starting -> "Arrancando…"
                                    else -> "Detenido"
                                },
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                color = if (state.running) LocalPalette.current.green.compose() else MaterialTheme.colorScheme.onSurface,
                            )
                            if (state.running) Text(
                                if (state.clients == 0) "Nadie conectado" else plural(state.clients, "conexión", "conexiones"),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.running) Button(
                            onClick = { SftpServerService.stop(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        ) { Text("Detener") }
                        else Button(onClick = { if (dirty) { vm.setServerConfig(c); dirty = false }; start() }, enabled = !state.starting && portOk) { Text("Iniciar") }
                    }
                    state.error?.let { Text("No se ha podido iniciar: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    if (state.running) {
                        if (state.addresses.isEmpty()) Text(
                            "El móvil no está en ninguna red Wi-Fi. Conéctalo a la misma red que el PC.",
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp),
                        )
                        if (state.addresses.isNotEmpty()) {
                            Text("Desde el PC (Nautilus, Luna o el gestor de archivos):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                            state.addresses.forEach { ip -> CopyLine("sftp://${state.user}@$ip:${state.port}/", ::copy) }
                            Text("Desde una terminal:", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                            state.addresses.forEach { ip -> CopyLine("sftp -P ${state.port} ${state.user}@$ip", ::copy) }
                        }
                        Text("Huella del móvil (la verás al conectar la primera vez):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        Text(state.fingerprint, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionTitle("Acceso", Modifier.padding(start = 0.dp))
            Field("Usuario", c.user, { edit(c.copy(user = it.filter { ch -> !ch.isWhitespace() })) })
            OutlinedTextField(
                value = c.password, onValueChange = { edit(c.copy(password = it)) }, label = { Text("Contraseña") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { copy(c.password) }) { Icon(Icons.Rounded.ContentCopy, "Copiar") }
                        IconButton(onClick = { edit(c.copy(password = Settings.newPassword())) }) { Icon(Icons.Rounded.Refresh, "Nueva contraseña") }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = if (c.port == 0) "" else c.port.toString(),
                onValueChange = { edit(c.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0)) },
                label = { Text("Puerto") }, singleLine = true, isError = !portOk,
                // Android apps can't listen below 1024
                supportingText = { if (!portOk) Text("Entre 1024 y 65535") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            OutlinedTextField(
                value = c.authorizedKeys, onValueChange = { edit(c.copy(authorizedKeys = it)) },
                label = { Text("Claves públicas autorizadas (opcional)") },
                placeholder = { Text("ssh-ed25519 AAAA… julss@pc") },
                supportingText = {
                    val n = authorizedKeys(c.authorizedKeys).size
                    Text(if (c.authorizedKeys.isBlank()) "Pega el contenido de ~/.ssh/id_ed25519.pub del PC para entrar sin contraseña" else plural(n, "clave válida", "claves válidas"))
                },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 2, maxLines = 6,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )

            SectionTitle("Qué ve el PC", Modifier.padding(start = 0.dp))
            volumes.forEach { v ->
                Row(Modifier.fillMaxWidth().clickable { edit(c.copy(root = v.path)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(c.root == v.path, { edit(c.copy(root = v.path)) })
                    Column {
                        Text(v.label)
                        Text(v.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (volumes.none { it.path == c.root }) Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(true, null)
                Text(c.root, style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth().clickable { edit(c.copy(readOnly = !c.readOnly)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Solo lectura")
                    Text("El PC puede ver y copiar, pero no cambiar ni borrar nada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(c.readOnly, { edit(c.copy(readOnly = it)) })
            }
            Text(
                "Solo SFTP: no hay terminal ni redirección de puertos. El servidor sigue activo con la pantalla apagada " +
                    "hasta que lo detengas desde aquí o desde la notificación.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun CopyLine(text: String, copy: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = { copy(text) }) { Icon(Icons.Rounded.ContentCopy, "Copiar") }
    }
}
