package io.github.julssvanderhaack.luna.ui

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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.theme.ThemeRepository

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
    BackScaffold("Ajustes", vm::back) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionTitle("Apariencia")
            NavRow(
                Icons.Rounded.Palette, "Tema",
                if (s.dynamicColor) "Colores del fondo de pantalla" else ThemeRepository.displayName(s.theme),
            ) { vm.open(Route.Themes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow("Colores del fondo de pantalla", "Material You: ignora el tema elegido", s.dynamicColor, vm::setDynamicColor)
            }
            SectionTitle("Archivos")
            SwitchRow("Mostrar archivos ocultos", "Los que empiezan por un punto", s.showHidden, vm::setShowHidden)
            SwitchRow("Carpetas primero", null, s.sort.foldersFirst) { vm.setSort(s.sort.copy(foldersFirst = it)) }
            SectionTitle("Red")
            NavRow(Icons.Rounded.Lan, "Servidor SFTP del móvil", "Usuario, contraseña, claves y qué carpeta ve el PC") { vm.open(Route.PhoneServer) }
            SectionTitle("Acerca de")
            NavRow(Icons.Rounded.Info, "Luna $version", "Explorador de archivos · GPL-3.0 · versión de escritorio en github.com/julssvanderhaack/luna") {}
        }
    }
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
