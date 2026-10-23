package io.github.julssvanderhaack.luna.ui

import android.content.Intent
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.Progress
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: MainViewModel) {
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    // the permission is granted in system Settings, so check again on return
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = Environment.isExternalStorageManager()
        vm.reloadVolumes()
    }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (!granted) {
            PermissionScreen()
            return@Surface
        }
        val route = vm.stack.last()
        BackHandler(enabled = vm.stack.size > 1) { vm.back() }
        val progress by vm.progress.collectAsStateWithLifecycle()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
        // only swipe to close: a swipe to open would fight the system back gesture and horizontal lists
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            drawerContent = { LunaDrawer(vm) { scope.launch { drawer.close() } } },
        ) {
        CompositionLocalProvider(LocalOpenDrawer provides { scope.launch { drawer.open() } }) {
        Box(Modifier.fillMaxSize()) {
            when (route) {
                Route.Home -> HomeScreen(vm)
                is Route.Browse -> BrowserScreen(vm, route.loc)
                Route.PhoneServer -> PhoneServerScreen(vm)
                is Route.EditServer -> EditServerScreen(vm, route.id)
                Route.Settings -> SettingsScreen(vm)
                Route.Themes -> ThemesScreen(vm)
                is Route.ThemeEditor -> ThemeEditorScreen(vm, route.base, route.editing)
            }
            Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp)) {
                progress?.let { ProgressCard(it, vm::cancelOp) }
                SnackbarHost(snack)
            }
        }
        }
        }
    }
}

@Composable
private fun ProgressCard(p: Progress, onCancel: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title + (p.fraction?.let { " · ${(it * 100).toInt()} %" } ?: "…"), style = MaterialTheme.typography.titleSmall)
                    if (p.current.isNotEmpty()) Text(
                        p.current, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancelar") }
            }
            val f = p.fraction
            if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(end = 12.dp))
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp))
        }
    }
}

@Composable
private fun PermissionScreen() {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(
            "Luna necesita ver tus archivos", style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            "Para explorar el almacenamiento, los pendrives y los discos USB, Android pide activar " +
                "«Permitir acceso para gestionar todos los archivos». Luna no envía nada a ningún sitio " +
                "salvo a los servidores que tú añadas.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:${context.packageName}".toUri())
            runCatching { context.startActivity(intent) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }) { Text("Abrir ajustes") }
    }
}
