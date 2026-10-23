package io.github.julssvanderhaack.luna.ui

import android.os.Environment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.julssvanderhaack.luna.fs.Category
import io.github.julssvanderhaack.luna.theme.LocalPalette
import io.github.julssvanderhaack.luna.theme.compose

/** The quick-access places, shared by the home screen's grid and the side drawer. */
class Shortcut(
    val label: String,
    val icon: ImageVector,
    val color: @Composable () -> Color,
    /** Recientes already has its own strip on the home screen. */
    val onHome: Boolean = true,
    val loc: () -> Loc,
)

/** The user's "home": internal shared storage, /storage/emulated/0. */
val homeDir: String get() = Environment.getExternalStorageDirectory().path

val SHORTCUTS = listOf(
    Shortcut("Recientes", Icons.Rounded.History, { MaterialTheme.colorScheme.primary }, onHome = false) { Loc.Media(Category.RECENT) },
    Shortcut("Cámara", Icons.Rounded.CameraAlt, { LocalPalette.current.magenta.compose() }) { Loc.Media(Category.CAMERA) },
    Shortcut("Descargas", Icons.Rounded.Download, { LocalPalette.current.blue.compose() }) {
        Loc.Local(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path)
    },
    Shortcut("Imágenes", Icons.Rounded.Image, { LocalPalette.current.cyan.compose() }) { Loc.Media(Category.IMAGES) },
    Shortcut("Vídeos", Icons.Rounded.Movie, { LocalPalette.current.redKind.compose() }) { Loc.Media(Category.VIDEOS) },
    Shortcut("Audio", Icons.Rounded.AudioFile, { LocalPalette.current.orange.compose() }) { Loc.Media(Category.AUDIO) },
    Shortcut("Documentos", Icons.Rounded.Description, { LocalPalette.current.yellow.compose() }) { Loc.Media(Category.DOCUMENTS) },
    Shortcut("APK", Icons.Rounded.Android, { LocalPalette.current.green.compose() }) { Loc.Media(Category.APKS) },
    Shortcut("Comprimidos", Icons.Rounded.FolderZip, { MaterialTheme.colorScheme.primary }) { Loc.Media(Category.ARCHIVES) },
)
