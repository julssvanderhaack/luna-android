package io.github.julssvanderhaack.luna.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Kind
import io.github.julssvanderhaack.luna.theme.LocalPalette
import io.github.julssvanderhaack.luna.theme.compose

fun Kind.icon(): ImageVector = when (this) {
    Kind.FOLDER -> Icons.Rounded.Folder
    Kind.IMAGE -> Icons.Rounded.Image
    Kind.VIDEO -> Icons.Rounded.Movie
    Kind.AUDIO -> Icons.Rounded.AudioFile
    Kind.PDF -> Icons.Rounded.PictureAsPdf
    Kind.TEXT -> Icons.AutoMirrored.Rounded.TextSnippet
    Kind.CODE -> Icons.Rounded.Code
    Kind.DOCUMENT -> Icons.Rounded.Description
    Kind.SHEET -> Icons.Rounded.TableChart
    Kind.SLIDES -> Icons.Rounded.Slideshow
    Kind.ARCHIVE -> Icons.Rounded.FolderZip
    Kind.APK -> Icons.Rounded.Android
    Kind.EBOOK -> Icons.Rounded.MenuBook
    Kind.FONT -> Icons.Rounded.FontDownload
    Kind.OTHER -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** Each kind takes a colour from the theme's terminal palette, so Luna looks like the rest of Omarchy. */
@Composable
fun Kind.color(): Color {
    val p = LocalPalette.current
    return when (this) {
        Kind.FOLDER -> MaterialTheme.colorScheme.primary
        Kind.IMAGE -> p.magenta.compose()
        Kind.VIDEO -> p.redKind.compose()
        Kind.AUDIO -> p.orange.compose()
        Kind.PDF -> p.redKind.compose()
        Kind.TEXT, Kind.CODE -> p.cyan.compose()
        Kind.DOCUMENT, Kind.EBOOK -> p.blue.compose()
        Kind.SHEET, Kind.APK -> p.green.compose()
        Kind.SLIDES, Kind.ARCHIVE -> p.yellow.compose()
        Kind.FONT, Kind.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Thumbnail if there is one, otherwise the kind's icon on a tinted tile. */
@Composable
fun FileIcon(e: FileEntry, size: Dp, local: Boolean, modifier: Modifier = Modifier, corner: Dp = 10.dp, square: Boolean = false) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceAtMost(512)
    val thumb = if (e.isDir) null else rememberThumb(e, px, local)
    val shape = RoundedCornerShape(corner)
    // square = fill the grid cell's width; size still picks the thumbnail resolution
    val box = if (square) modifier.fillMaxWidth().aspectRatio(1f) else modifier.size(size)
    if (thumb != null) {
        Image(thumb, null, contentScale = ContentScale.Crop, modifier = box.clip(shape))
    } else {
        val c = e.kind.color()
        Box(box.clip(shape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(e.kind.icon(), null, tint = c, modifier = Modifier.size(if (square) size * 0.45f else size * 0.55f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.foundation.layout.Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        if (subtitle != null) Text(
                            subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = bottomBar,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    androidx.compose.foundation.layout.Row(
        modifier.padding(start = 20.dp, end = 8.dp, top = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

@Composable
fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}
