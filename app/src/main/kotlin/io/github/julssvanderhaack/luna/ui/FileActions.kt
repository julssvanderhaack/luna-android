package io.github.julssvanderhaack.luna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.julssvanderhaack.luna.fs.FileEntry

sealed interface FileDialog {
    data class Rename(val entry: FileEntry) : FileDialog
    data class Delete(val entries: List<FileEntry>) : FileDialog
    data class OpenAs(val entry: FileEntry) : FileDialog
    data class Properties(val entries: List<FileEntry>) : FileDialog
    data class Compress(val entries: List<FileEntry>) : FileDialog
    data class Create(val folder: Boolean) : FileDialog
}

/** The long-press sheet and the dialogs it opens, shared by every file list. */
@Stable
class FileActions {
    /** What the sheet is about; null = closed. */
    var sheet by mutableStateOf<List<FileEntry>?>(null)
    var dialog by mutableStateOf<FileDialog?>(null)
}

/** A folder in the same place as [this] (a file from Recientes opens its real folder). */
fun Loc.at(path: String): Loc = when (this) {
    is Loc.Remote -> copy(path = path)
    else -> Loc.Local(path)
}

/** Tap on an entry: folders open in Luna, files in their default app. */
fun openEntry(vm: MainViewModel, context: Context, loc: Loc, e: FileEntry) {
    if (e.isDir) vm.browse(loc.at(e.path))
    else vm.localFiles(loc, listOf(e)) { Opener.open(context, it.first()) }
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ruta", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsHost(vm: MainViewModel, loc: Loc, actions: FileActions, onSelect: ((FileEntry) -> Unit)? = null) {
    val context = LocalContext.current
    val entries = actions.sheet
    if (entries != null && entries.isNotEmpty()) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val remote = loc is Loc.Remote
        val single = entries.singleOrNull()
        val anyDir = entries.any { it.isDir }
        val flat = loc is Loc.Media
        fun go(block: () -> Unit) {
            actions.sheet = null
            block()
        }
        ModalBottomSheet(onDismissRequest = { actions.sheet = null }, sheetState = state, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
                SheetHeader(entries, !remote)
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (single != null) {
                    if (single.isDir) {
                        SheetItem(Icons.Rounded.FolderOpen, "Abrir") { go { vm.browse(loc.at(single.path)) } }
                    } else {
                        SheetItem(Icons.AutoMirrored.Rounded.OpenInNew, "Abrir") { go { openEntry(vm, context, loc, single) } }
                        SheetItem(Icons.Rounded.Apps, "Abrir con…") {
                            go { vm.localFiles(loc, entries) { Opener.open(context, it.first(), chooser = true) } }
                        }
                        SheetItem(Icons.Rounded.OpenWith, "Abrir como…") { go { actions.dialog = FileDialog.OpenAs(single) } }
                    }
                }
                if (!anyDir) SheetItem(Icons.Rounded.Share, "Compartir") {
                    go { vm.localFiles(loc, entries) { Opener.share(context, it) } }
                }
                if (onSelect != null && single != null) SheetItem(Icons.Rounded.CheckCircle, "Seleccionar") { go { onSelect(single) } }
                if (remote) SheetItem(Icons.Rounded.Download, "Descargar en el móvil") { go { vm.download(loc, entries) } }
                SheetItem(Icons.Rounded.ContentCopy, "Copiar") { go { vm.copy(loc, entries, cut = false); vm.say("Ve a otra carpeta y pulsa Pegar") } }
                SheetItem(Icons.Rounded.ContentCut, "Cortar") { go { vm.copy(loc, entries, cut = true); vm.say("Ve a otra carpeta y pulsa Pegar para moverlo") } }
                if (single != null) SheetItem(Icons.Rounded.DriveFileRenameOutline, "Renombrar…") { go { actions.dialog = FileDialog.Rename(single) } }
                SheetItem(Icons.Rounded.Delete, "Eliminar…", danger = true) { go { actions.dialog = FileDialog.Delete(entries) } }
                if (!remote) {
                    if (single != null && !single.isDir && single.ext == "zip") {
                        SheetItem(Icons.Rounded.Unarchive, "Extraer aquí") { go { vm.extract(loc.at(single.parent), single) } }
                    }
                    if (entries.map { it.parent }.distinct().size == 1) {
                        SheetItem(Icons.Rounded.FolderZip, "Comprimir…") { go { actions.dialog = FileDialog.Compress(entries) } }
                    }
                }
                if (single != null && single.isDir) {
                    val fav = loc.at(single.path)
                    if (vm.isFavorite(fav)) SheetItem(Icons.Rounded.Star, "Quitar de favoritos") { go { vm.toggleFavorite(fav) } }
                    else SheetItem(Icons.Rounded.StarBorder, "Añadir a favoritos") { go { vm.toggleFavorite(fav) } }
                }
                if (single != null && flat) SheetItem(Icons.AutoMirrored.Rounded.DriveFileMove, "Abrir la carpeta que lo contiene") {
                    go { vm.browse(loc.at(single.parent)) }
                }
                if (single != null) SheetItem(Icons.Rounded.Link, "Copiar ruta") {
                    go { copyText(context, single.path); vm.say("Ruta copiada") }
                }
                SheetItem(Icons.Rounded.Info, "Propiedades") { go { actions.dialog = FileDialog.Properties(entries) } }
            }
        }
    }

    when (val d = actions.dialog) {
        null -> {}
        is FileDialog.Rename -> NameDialog(
            title = "Renombrar", initial = d.entry.name, selectStem = !d.entry.isDir, confirm = "Renombrar",
            onDismiss = { actions.dialog = null },
        ) { vm.rename(loc.at(d.entry.parent), d.entry, it); actions.dialog = null }
        is FileDialog.Delete -> DeleteDialog(d.entries, onDismiss = { actions.dialog = null }) {
            vm.delete(loc, d.entries); actions.dialog = null
        }
        is FileDialog.OpenAs -> OpenAsDialog(onDismiss = { actions.dialog = null }) { mime ->
            actions.dialog = null
            vm.localFiles(loc, listOf(d.entry)) { Opener.open(context, it.first(), mime, chooser = true) }
        }
        is FileDialog.Properties -> PropertiesDialog(vm, loc, d.entries) { actions.dialog = null }
        is FileDialog.Compress -> NameDialog(
            title = "Comprimir", confirm = "Comprimir",
            initial = (d.entries.singleOrNull()?.let { if (it.isDir) it.name else it.name.substringBeforeLast('.') } ?: "Archivo") + ".zip",
            selectStem = true, onDismiss = { actions.dialog = null },
        ) { vm.compress(loc, d.entries, it); actions.dialog = null }
        is FileDialog.Create -> NameDialog(
            title = if (d.folder) "Nueva carpeta" else "Nuevo archivo", confirm = "Crear",
            initial = if (d.folder) "Carpeta nueva" else "Archivo nuevo.txt", selectStem = !d.folder,
            onDismiss = { actions.dialog = null },
        ) { vm.create(loc, it, d.folder); actions.dialog = null }
    }
}

@Composable
private fun SheetHeader(entries: List<FileEntry>, local: Boolean) {
    val single = entries.singleOrNull()
    Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (single != null) FileIcon(single, 48.dp, local)
        else FileIcon(FileEntry("", "", entries.all { it.isDir }, 0, 0), 48.dp, false)
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                single?.name ?: "${entries.size} elementos", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                single == null -> formatSize(entries.filter { !it.isDir }.sumOf { it.size }).let { "$it en archivos" }
                single.isDir -> formatDate(single.modified)
                else -> listOf(formatSize(single.size), formatDate(single.modified)).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, text: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 20.dp))
    }
}
