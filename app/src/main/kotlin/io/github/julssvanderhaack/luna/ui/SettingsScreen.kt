package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Context
import android.content.Intent
import io.github.julssvanderhaack.luna.I18n
import io.github.julssvanderhaack.luna.theme.ThemeRepository

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    var askLanguage by remember { mutableStateOf(false) }
    if (askLanguage) LanguageDialog(context) { askLanguage = false }
    BackScaffold(tr("Ajustes"), vm::back) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle(tr("Apariencia"))
            NavRow(
                Icons.Rounded.Palette, tr("Tema"),
                if (s.dynamicColor) tr("Colores del fondo de pantalla") else ThemeRepository.displayName(s.theme),
            ) { vm.open(Route.Themes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(tr("Colores del fondo de pantalla"), tr("Material You: ignora el tema elegido"), s.dynamicColor, vm::setDynamicColor)
            }
            val chosen = remember { I18n.chosen(context) }
            NavRow(Icons.Rounded.Language, tr("Idioma"), tr(I18n.LANGUAGES.first { it.first == chosen }.second)) { askLanguage = true }
            SectionTitle(tr("Archivos"))
            SwitchRow(tr("Mostrar archivos ocultos"), tr("Los que empiezan por un punto"), s.showHidden, vm::setShowHidden)
            SwitchRow(tr("Carpetas primero"), null, s.sort.foldersFirst) { vm.setSort(s.sort.copy(foldersFirst = it)) }
            SwitchRow(tr("Usar la papelera"), tr("Eliminar mueve a la papelera del móvil o del USB, y se puede deshacer"), s.useTrash, vm::setUseTrash)
            SwitchRow(tr("Abrir en Luna"), tr("Textos, Markdown, comprimidos y documentos de Office se abren en la vista previa de Luna"), s.openInLuna, vm::setOpenInLuna)
            NavRow(Icons.Rounded.Delete, tr("Papelera"), tr("Restaurar o vaciar")) { vm.open(Route.Trash) }
            SectionTitle(tr("Cifrado"))
            NavRow(Icons.Rounded.Key, tr("Claves OpenPGP"), tr("Las públicas de otras personas y las tuyas, para cifrar y descifrar como gpg")) { vm.open(Route.Keys) }
            SectionTitle(tr("Red"))
            NavRow(Icons.Rounded.Lan, tr("Servidor SFTP del móvil"), tr("Usuario, contraseña, claves y qué carpeta ve el PC")) { vm.open(Route.PhoneServer) }
            SectionTitle(tr("Acerca de"))
            NavRow(Icons.Rounded.Info, tr("Luna %1", version), tr("Explorador de archivos · GPL-3.0 · versión de escritorio en github.com/julssvanderhaack/luna")) {}
        }
    }
}

/** Picking a language restarts Luna: every text is looked up once, when it's drawn or made. */
@Composable
private fun LanguageDialog(context: Context, onDismiss: () -> Unit) {
    val chosen = remember { I18n.chosen(context) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Idioma")) },
        text = {
            Column {
                I18n.LANGUAGES.forEach { (code, name) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onDismiss()
                            if (code != chosen) {
                                I18n.choose(context, code)
                                val restart = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                context.startActivity(restart)
                                Runtime.getRuntime().exit(0)
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(code == chosen, null)
                        Text(if (code.isEmpty()) tr(name) else name, Modifier.padding(start = 12.dp))
                    }
                }
                Text(
                    tr("Luna se reinicia para cambiar de idioma"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(tr("Cancelar")) } },
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
