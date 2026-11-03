package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
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
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.FileCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.PhotoSizeSelectLarge
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.Transform
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.julssvanderhaack.luna.fs.Archives
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Images

sealed interface FileDialog {
    data class Rename(val entry: FileEntry) : FileDialog
    data class BatchRename(val entries: List<FileEntry>) : FileDialog
    data class Delete(val entries: List<FileEntry>, val forever: Boolean = false) : FileDialog
    data class OpenAs(val entry: FileEntry) : FileDialog
    data class Properties(val entries: List<FileEntry>) : FileDialog
    data class Compress(val entries: List<FileEntry>) : FileDialog
    data class Create(val folder: Boolean) : FileDialog
    data class Encrypt(val entries: List<FileEntry>) : FileDialog
    data class Decrypt(val entry: FileEntry) : FileDialog
    data class Resize(val entries: List<FileEntry>) : FileDialog
    data class Convert(val entries: List<FileEntry>) : FileDialog
    /** "Copiar a…" / "Mover a…": pick a folder anywhere. */
    data class Pick(val entries: List<FileEntry>, val move: Boolean) : FileDialog
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

val FileEntry.encrypted get() = !isDir && ext in setOf("gpg", "pgp")

/**
 * Tap on an entry: folders and photos open in Luna (swiping through the other
 * photos in [siblings]), and with "Abrir en Luna" on, texts, archives and
 * Office files go to the preview; encrypted ones ask for the password; the
 * rest open in their default app.
 */
fun openEntry(vm: MainViewModel, context: Context, loc: Loc, e: FileEntry, siblings: List<FileEntry> = listOf(e), actions: FileActions? = null) {
    when {
        e.isDir -> vm.browse(loc.at(e.path))
        ImageViewer.canShow(e) && !(loc is Loc.Remote && e.size > ImageViewer.MAX_REMOTE) -> {
            val images = siblings.filter { ImageViewer.canShow(it) }.ifEmpty { listOf(e) }
            vm.open(Route.Viewer(loc, images, images.indexOfFirst { it.path == e.path }.coerceAtLeast(0)))
        }
        e.encrypted && actions != null -> actions.dialog = FileDialog.Decrypt(e)
        vm.settings.value.openInLuna && Previews.opensInLuna(e) -> openPreview(vm, loc, e, siblings)
        else -> vm.localFiles(loc, listOf(e)) { Opener.open(context, it.first()) }
    }
}

fun openPreview(vm: MainViewModel, loc: Loc, e: FileEntry, siblings: List<FileEntry>) {
    val files = siblings.filter { Previews.canShow(it) }.ifEmpty { listOf(e) }
    vm.open(Route.Preview(loc, files, files.indexOfFirst { it.path == e.path }.coerceAtLeast(0)))
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ruta", text))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileActionsHost(
    vm: MainViewModel,
    loc: Loc,
    actions: FileActions,
    siblings: List<FileEntry> = emptyList(),
    onSelect: ((FileEntry) -> Unit)? = null,
) {
    val context = LocalContext.current
    val entries = actions.sheet
    if (entries != null && entries.isNotEmpty()) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        val remote = loc is Loc.Remote
        val single = entries.singleOrNull()
        val anyDir = entries.any { it.isDir }
        val flat = loc is Loc.Media
        val sameFolder = entries.map { it.parent }.distinct().size == 1
        val images = !remote && entries.all { Images.canEdit(it) }
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
                        SheetItem(Icons.Rounded.FolderOpen, tr("Abrir")) { go { vm.browse(loc.at(single.path)) } }
                        SheetItem(Icons.Rounded.Tab, tr("Abrir en una pestaña nueva")) { go { vm.newTab(Route.Browse(loc.at(single.path))) } }
                    } else {
                        SheetItem(Icons.AutoMirrored.Rounded.OpenInNew, tr("Abrir")) { go { openEntry(vm, context, loc, single, siblings.ifEmpty { listOf(single) }, actions) } }
                        if (Previews.canShow(single)) SheetItem(Icons.Rounded.Visibility, tr("Vista previa")) {
                            go { openPreview(vm, loc, single, siblings.ifEmpty { listOf(single) }) }
                        }
                        SheetItem(Icons.Rounded.Apps, tr("Abrir con…")) {
                            go { vm.localFiles(loc, entries) { Opener.open(context, it.first(), chooser = true) } }
                        }
                        SheetItem(Icons.Rounded.OpenWith, tr("Abrir como…")) { go { actions.dialog = FileDialog.OpenAs(single) } }
                    }
                }
                if (!anyDir) SheetItem(Icons.Rounded.Share, tr("Compartir")) {
                    go { vm.localFiles(loc, entries) { Opener.share(context, it) } }
                }
                if (onSelect != null && single != null) SheetItem(Icons.Rounded.CheckCircle, tr("Seleccionar")) { go { onSelect(single) } }
                if (remote) SheetItem(Icons.Rounded.Download, tr("Descargar en el móvil")) { go { vm.download(loc, entries) } }
                SheetItem(Icons.Rounded.ContentCopy, tr("Copiar")) { go { vm.copy(loc, entries, cut = false); vm.say(tr("Ve a otra carpeta y pulsa Pegar")) } }
                SheetItem(Icons.Rounded.ContentCut, tr("Cortar")) { go { vm.copy(loc, entries, cut = true); vm.say(tr("Ve a otra carpeta y pulsa Pegar para moverlo")) } }
                SheetItem(Icons.Rounded.FileCopy, tr("Copiar a…")) { go { actions.dialog = FileDialog.Pick(entries, move = false) } }
                SheetItem(Icons.AutoMirrored.Rounded.DriveFileMove, tr("Mover a…")) { go { actions.dialog = FileDialog.Pick(entries, move = true) } }
                if (single != null) SheetItem(Icons.Rounded.DriveFileRenameOutline, tr("Renombrar…")) { go { actions.dialog = FileDialog.Rename(single) } }
                else if (!flat) SheetItem(Icons.Rounded.DriveFileRenameOutline, tr("Renombrar en lote…")) { go { actions.dialog = FileDialog.BatchRename(entries) } }
                if (vm.canTrash(loc, entries)) {
                    SheetItem(Icons.Rounded.Delete, tr("Mover a la papelera"), danger = true) { go { vm.delete(loc, entries) } }
                    SheetItem(Icons.Rounded.DeleteForever, tr("Eliminar definitivamente…"), danger = true) { go { actions.dialog = FileDialog.Delete(entries, forever = true) } }
                } else SheetItem(Icons.Rounded.Delete, tr("Eliminar…"), danger = true) { go { actions.dialog = FileDialog.Delete(entries, forever = true) } }
                if (single != null && !single.isDir && Archives.canOpen(single.name)) {
                    SheetItem(Icons.Rounded.Unarchive, tr("Extraer aquí")) { go { vm.extract(loc.at(single.parent), single) } }
                }
                if (sameFolder && !flat) {
                    SheetItem(Icons.Rounded.FolderZip, tr("Comprimir…")) { go { actions.dialog = FileDialog.Compress(entries) } }
                }
                if (single != null && single.encrypted) SheetItem(Icons.Rounded.LockOpen, tr("Descifrar aquí")) { go { actions.dialog = FileDialog.Decrypt(single) } }
                else if (sameFolder) SheetItem(Icons.Rounded.Lock, tr("Cifrar…")) { go { actions.dialog = FileDialog.Encrypt(entries) } }
                if (images) {
                    SheetItem(Icons.AutoMirrored.Rounded.RotateLeft, tr("Girar a la izquierda")) { go { vm.rotate(loc, entries, 270) } }
                    SheetItem(Icons.AutoMirrored.Rounded.RotateRight, tr("Girar a la derecha")) { go { vm.rotate(loc, entries, 90) } }
                    SheetItem(Icons.Rounded.PhotoSizeSelectLarge, tr("Reducir tamaño…")) { go { actions.dialog = FileDialog.Resize(entries) } }
                    SheetItem(Icons.Rounded.Transform, tr("Convertir a…")) { go { actions.dialog = FileDialog.Convert(entries) } }
                }
                if (single != null && single.isDir) {
                    val fav = loc.at(single.path)
                    if (vm.isFavorite(fav)) SheetItem(Icons.Rounded.Star, tr("Quitar de favoritos")) { go { vm.toggleFavorite(fav) } }
                    else SheetItem(Icons.Rounded.StarBorder, tr("Añadir a favoritos")) { go { vm.toggleFavorite(fav) } }
                    SheetItem(Icons.Rounded.DonutLarge, tr("Qué ocupa espacio")) { go { vm.open(Route.Usage(fav)) } }
                }
                if (single != null && flat) SheetItem(Icons.AutoMirrored.Rounded.DriveFileMove, tr("Abrir la carpeta que lo contiene")) {
                    go { vm.browse(loc.at(single.parent)) }
                }
                if (single != null) SheetItem(Icons.Rounded.Link, tr("Copiar ruta")) {
                    go { copyText(context, single.path); vm.say(tr("Ruta copiada")) }
                }
                SheetItem(Icons.Rounded.Info, tr("Propiedades")) { go { actions.dialog = FileDialog.Properties(entries) } }
            }
        }
    }

    val close = { actions.dialog = null }
    when (val d = actions.dialog) {
        null -> {}
        is FileDialog.Rename -> NameDialog(
            title = tr("Renombrar"), initial = d.entry.name, selectStem = !d.entry.isDir, confirm = tr("Renombrar"),
            onDismiss = close,
        ) { vm.rename(loc.at(d.entry.parent), d.entry, it); close() }
        is FileDialog.BatchRename -> BatchRenameDialog(d.entries, onDismiss = close) { vm.renameMany(loc.at(d.entries.first().parent), it); close() }
        is FileDialog.Delete -> DeleteDialog(d.entries, forever = d.forever, onDismiss = close) {
            vm.delete(loc, d.entries, forever = d.forever); close()
        }
        is FileDialog.OpenAs -> OpenAsDialog(onDismiss = close) { mime ->
            close()
            vm.localFiles(loc, listOf(d.entry)) { Opener.open(context, it.first(), mime, chooser = true) }
        }
        is FileDialog.Properties -> PropertiesDialog(vm, loc, d.entries) { close() }
        is FileDialog.Compress -> CompressDialog(d.entries, onDismiss = close) { name, format -> vm.compress(loc, d.entries, name, format); close() }
        is FileDialog.Create -> NameDialog(
            title = if (d.folder) tr("Nueva carpeta") else tr("Nuevo archivo"), confirm = tr("Crear"),
            initial = if (d.folder) tr("Carpeta nueva") else tr("Archivo nuevo.txt"), selectStem = !d.folder,
            onDismiss = close,
        ) { vm.create(loc, it, d.folder); close() }
        is FileDialog.Encrypt -> EncryptDialog(vm, d.entries, onDismiss = close) { name, pw, keys, delete ->
            vm.encrypt(loc, d.entries, name, pw, keys, delete); close()
        }
        is FileDialog.Decrypt -> DecryptDialog(vm, loc, d.entry, onDismiss = close) { pw, keyPw ->
            close()
            vm.decrypt(loc, d.entry, pw, keyPw) { actions.dialog = FileDialog.Decrypt(d.entry) }
        }
        is FileDialog.Resize -> ResizeDialog(onDismiss = close) { vm.resize(loc, d.entries, it); close() }
        is FileDialog.Convert -> ConvertDialog(d.entries, onDismiss = close) { vm.convert(loc, d.entries, it); close() }
        is FileDialog.Pick -> FolderPickerDialog(vm, d.move, start = loc.takeIf { it !is Loc.Media }?.at(d.entries.first().parent), onDismiss = close) { dest ->
            close()
            vm.transfer(vm.vfsIdOf(loc), d.entries, vm.vfsIdOf(dest), dest.path, d.move)
        }
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
                single?.name ?: tr("%1 elementos", entries.size), style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                single == null -> formatSize(entries.filter { !it.isDir }.sumOf { it.size }).let { tr("%1 en archivos", it) }
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
