package io.github.julssvanderhaack.luna.ui

import io.github.julssvanderhaack.luna.tr
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.util.Xml
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.ArchiveItem
import io.github.julssvanderhaack.luna.fs.Archives
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.fs.Kind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** What Luna's preview shows, and which of those a tap opens in Luna rather than another app. */
object Previews {
    val OFFICE = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp")

    fun canShow(e: FileEntry) = !e.isDir && (
        e.kind in setOf(Kind.TEXT, Kind.CODE, Kind.PDF, Kind.AUDIO) || e.ext in OFFICE ||
            (e.kind == Kind.ARCHIVE || e.ext in setOf("apk", "epub", "cbz", "jar")) && Archives.canOpen(e.name)
        )

    fun opensInLuna(e: FileEntry) = !e.isDir && (
        e.kind == Kind.TEXT || e.kind == Kind.CODE || e.ext in OFFICE || e.kind == Kind.ARCHIVE && Archives.canOpen(e.name)
        )

    /** Bigger than this isn't worth fetching from a server just to look. */
    const val MAX_REMOTE = 200_000_000L

    /** The picture an Office or LibreOffice file carries of its first page, if it saved one. */
    fun officeCover(f: File, maxPx: Int): Bitmap? = runCatching {
        ZipFile(f).use { z ->
            val entry = listOf("docProps/thumbnail.jpeg", "docProps/thumbnail.jpg", "docProps/thumbnail.png", "Thumbnails/thumbnail.png")
                .firstNotNullOfOrNull { z.getEntry(it) } ?: return null
            val bytes = z.getInputStream(entry).use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= maxPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    /** The text of a Word, Excel, PowerPoint or LibreOffice file, paragraph by paragraph. */
    fun officeText(f: File, max: Int = 200_000): String = runCatching {
        ZipFile(f).use { z ->
            val parts = when (f.extension.lowercase()) {
                "docx" -> listOf("word/document.xml")
                "xlsx" -> listOf("xl/sharedStrings.xml")
                "pptx" -> z.entries().asSequence().map { it.name }.filter { it.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                    .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }.toList()
                else -> listOf("content.xml")
            }
            val sb = StringBuilder()
            for (name in parts) {
                val e = z.getEntry(name) ?: continue
                z.getInputStream(e).use { input ->
                    val p = Xml.newPullParser()
                    p.setInput(input, "UTF-8")
                    while (p.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT && sb.length < max) {
                        when (p.eventType) {
                            org.xmlpull.v1.XmlPullParser.TEXT -> sb.append(p.text)
                            org.xmlpull.v1.XmlPullParser.END_TAG -> when (p.name) {
                                // a paragraph, a spreadsheet cell, an ODF paragraph or heading
                                "p", "si", "h" -> sb.append('\n')
                                "tab" -> sb.append('\t')
                            }
                            org.xmlpull.v1.XmlPullParser.START_TAG -> if (p.name == "br" || p.name == "line-break") sb.append('\n')
                        }
                    }
                }
                if (parts.size > 1) sb.append("\n— — —\n")
            }
            sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
        }
    }.getOrDefault("")
}

@Composable
fun PreviewScreen(vm: MainViewModel, r: Route.Preview) {
    val context = LocalContext.current
    val files = r.files
    val pager = rememberPagerState(initialPage = r.start.coerceIn(0, files.lastIndex)) { files.size }
    val current = files[pager.currentPage.coerceIn(0, files.lastIndex)]
    val actions = remember { FileActions() }
    BackScaffold(
        title = current.name,
        subtitle = listOfNotNull(
            formatSize(current.size).takeIf { it.isNotEmpty() }, formatDate(current.modified),
            if (files.size > 1) "${pager.currentPage + 1} de ${files.size}" else null,
        ).joinToString(" · "),
        onBack = vm::back,
        actions = {
            IconButton(onClick = { vm.localFiles(r.loc, listOf(current)) { Opener.open(context, it.first(), chooser = true) } }) { Icon(Icons.Rounded.Apps, tr("Abrir con…")) }
            IconButton(onClick = { actions.sheet = listOf(current) }) { Icon(Icons.Rounded.MoreVert, tr("Más")) }
        },
    ) { pad ->
        HorizontalPager(pager, key = { files[it].path }, modifier = Modifier.padding(pad).fillMaxSize()) { page ->
            val e = files[page]
            val file by produceState<Result<File?>?>(null, e) { value = runCatching { vm.mediaFile(r.loc, e, Previews.MAX_REMOTE) } }
            when (val f = file) {
                null -> Centered { DelayedSpinner() }
                else -> {
                    val local = f.getOrNull()
                    if (local == null) Centered { Text(f.exceptionOrNull()?.message ?: tr("Demasiado grande para la vista previa: ábrelo con otra app"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else when {
                        e.kind == Kind.PDF -> PdfPreview(local)
                        e.kind == Kind.AUDIO -> AudioPreview(vm, e, local, active = page == pager.currentPage)
                        e.ext in Previews.OFFICE -> OfficePreview(local)
                        e.kind == Kind.TEXT || e.kind == Kind.CODE -> TextPreview(local, markdown = e.ext in setOf("md", "markdown"))
                        else -> ArchivePreview(vm, r.loc, e, local)
                    }
                }
            }
        }
    }
    FileActionsHost(vm, r.loc, actions, siblings = files)
}

@Composable
private fun TextPreview(f: File, markdown: Boolean) {
    val text by produceState<String?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val max = 2_000_000
                val bytes = f.inputStream().use { it.readNBytes(max) }
                bytes.decodeToString() + if (f.length() > max) tr("\n\n… (%1 más: ábrelo con otra app para verlo entero)", formatSize(f.length() - max)) else ""
            }.getOrElse { it.message ?: tr("No se puede leer") }
        }
    }
    val t = text ?: return Centered { DelayedSpinner() }
    if (markdown) {
        val blocks = remember(t) { Markdown.parse(t) }
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), modifier = Modifier.fillMaxSize()) {
                items(blocks) { MarkdownBlock(it) }
            }
        }
    } else {
        // one item per line: a long log scrolls without laying out all of it
        val lines = remember(t) { t.lines() }
        SelectionContainer {
            LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), modifier = Modifier.fillMaxSize()) {
                items(lines.size) { i ->
                    Text(lines[i].ifEmpty { " " }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}

/** Enough Markdown for READMEs and notes: headings, lists, quotes, code, bold, italics, links. */
object Markdown {
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Para(val text: String) : Block
        data class Item(val marker: String, val text: String, val depth: Int) : Block
        data class Quote(val text: String) : Block
        data class Code(val text: String) : Block
        data object Rule : Block
    }

    fun parse(src: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += Block.Para(para.toString().trim())
            para.clear()
        }
        val lines = src.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") || t.startsWith("~~~") -> {
                    flush()
                    val fence = t.take(3)
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence)) code.appendLine(lines[i++])
                    out += Block.Code(code.toString().trimEnd())
                }
                t.isEmpty() -> flush()
                Regex("^#{1,6}\\s").containsMatchIn(t) -> {
                    flush()
                    out += Block.Heading(t.takeWhile { it == '#' }.length, t.trimStart('#').trim().trimEnd('#').trim())
                }
                Regex("^([-*_])\\s*\\1\\s*\\1[\\s\\-*_]*$").matches(t) -> {
                    flush()
                    out += Block.Rule
                }
                Regex("^[-*+]\\s+").containsMatchIn(t) -> {
                    flush()
                    val depth = (line.length - line.trimStart().length) / 2
                    val task = Regex("^[-*+]\\s+\\[([ xX])]\\s+").find(t)
                    if (task != null) out += Block.Item(if (task.groupValues[1].isBlank()) "☐" else "☑", t.substring(task.range.last + 1), depth)
                    else out += Block.Item("•", t.replaceFirst(Regex("^[-*+]\\s+"), ""), depth)
                }
                Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> {
                    flush()
                    val depth = (line.length - line.trimStart().length) / 2
                    out += Block.Item(t.takeWhile { it.isDigit() } + ".", t.replaceFirst(Regex("^\\d+[.)]\\s+"), ""), depth)
                }
                t.startsWith(">") -> {
                    flush()
                    out += Block.Quote(t.trimStart('>', ' '))
                }
                // tables: shown as monospace rows, minus the |---| line
                t.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        if (!Regex("^\\|[\\s:|-]+\\|?$").matches(row)) rows += row.trim('|').split('|').joinToString("  │  ") { it.trim() }
                        i++
                    }
                    out += Block.Code(rows.joinToString("\n"))
                    continue
                }
                else -> para.append(t).append(' ')
            }
            i++
        }
        flush()
        return out
    }

    /** **bold**, *italic* / _italic_, `code`, [text](url), ![alt](img) */
    fun inline(s: String, code: SpanStyle, link: SpanStyle): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            val rest = s.substring(i)
            val m = when {
                rest.startsWith("**") || rest.startsWith("__") -> Regex("^(\\*\\*|__)(.+?)\\1").find(rest)?.also {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline(it.groupValues[2], code, link)) }
                }
                rest.startsWith("`") -> Regex("^`([^`]+)`").find(rest)?.also { withStyle(code) { append(it.groupValues[1]) } }
                rest.startsWith("![") -> Regex("^!\\[([^]]*)]\\([^)]*\\)").find(rest)?.also { withStyle(link) { append("🖼 " + it.groupValues[1]) } }
                rest.startsWith("[") -> Regex("^\\[([^]]+)]\\(([^)]*)\\)").find(rest)?.also { withStyle(link) { append(it.groupValues[1]) } }
                rest.startsWith("*") || rest.startsWith("_") -> Regex("^([*_])(\\S.*?)\\1(?![*_\\w])").find(rest)?.also {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline(it.groupValues[2], code, link)) }
                }
                else -> null
            }
            if (m != null) i += m.value.length else append(s[i++])
        }
    }
}

@Composable
private fun MarkdownBlock(b: Markdown.Block) {
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = MaterialTheme.colorScheme.surfaceContainerHigh)
    val link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline)
    val ty = MaterialTheme.typography
    when (b) {
        is Markdown.Block.Heading -> Text(
            Markdown.inline(b.text, code, link),
            style = when (b.level) { 1 -> ty.headlineSmall; 2 -> ty.titleLarge; 3 -> ty.titleMedium; else -> ty.titleSmall },
            fontWeight = FontWeight.SemiBold, color = if (b.level <= 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (b.level <= 2) 16.dp else 10.dp, bottom = 4.dp),
        )
        is Markdown.Block.Para -> Text(Markdown.inline(b.text, code, link), style = ty.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
        is Markdown.Block.Item -> Row(Modifier.padding(start = (8 + 16 * b.depth).dp, top = 2.dp, bottom = 2.dp)) {
            Text(b.marker, style = ty.bodyLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
            Text(Markdown.inline(b.text, code, link), style = ty.bodyLarge)
        }
        is Markdown.Block.Quote -> Row(Modifier.padding(vertical = 4.dp)) {
            Box(Modifier.size(width = 3.dp, height = 22.dp).background(MaterialTheme.colorScheme.primary))
            Text(Markdown.inline(b.text, code, link), style = ty.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
        }
        is Markdown.Block.Code -> Text(
            b.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).horizontalScroll(rememberScrollState()).padding(12.dp),
        )
        Markdown.Block.Rule -> HorizontalDivider(Modifier.padding(vertical = 10.dp))
    }
}

/** Every page, rendered at the screen's width as it scrolls into view. */
@Composable
private fun PdfPreview(f: File) {
    val renderer = remember(f) { runCatching { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) } }
    DisposableEffect(renderer) { onDispose { renderer.getOrNull()?.let { r -> runCatching { r.close() } } } }
    val r = renderer.getOrNull() ?: return Centered { Text(tr("No se puede abrir el PDF (¿tiene contraseña?)"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    val lock = remember { Mutex() }
    val width = with(LocalDensity.current) { (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp).roundToPx() }.coerceAtMost(1600)
    val ratios = remember(r) { (0 until r.pageCount).map { i -> r.openPage(i).use { it.height.toFloat() / it.width } } }
    LazyColumn(
        contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        itemsIndexed(ratios) { i, ratio ->
            val page by produceState<ImageBitmap?>(null, i) {
                value = withContext(Dispatchers.IO) {
                    lock.withLock {
                        runCatching {
                            r.openPage(i).use { p ->
                                val b = createBitmap(width, (width * ratio).toInt().coerceAtLeast(1))
                                Canvas(b).drawColor(android.graphics.Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b.asImageBitmap()
                            }
                        }.getOrNull()
                    }
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(Color.White)) {
                page?.let { Image(it, tr("Página %1", i + 1), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun OfficePreview(f: File) {
    val cover by produceState<ImageBitmap?>(null, f) { value = withContext(Dispatchers.IO) { Previews.officeCover(f, 1200)?.asImageBitmap() } }
    val text by produceState<String?>(null, f) { value = withContext(Dispatchers.IO) { Previews.officeText(f) } }
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            cover?.let {
                Image(
                    it, tr("Portada"), contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).clip(RoundedCornerShape(6.dp)).background(Color.White),
                )
                Spacer(Modifier.height(16.dp))
            }
            when (val t = text) {
                null -> DelayedSpinner()
                "" -> if (cover == null) Text(tr("No hay texto que enseñar: ábrelo con otra app"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Text(t, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

/** A tree you walk into like folders; tap a file to open just that one, without extracting the rest. */
@Composable
private fun ArchivePreview(vm: MainViewModel, loc: Loc, e: FileEntry, f: File) {
    val context = LocalContext.current
    val items by produceState<Result<List<ArchiveItem>>?>(null, f) { value = withContext(Dispatchers.IO) { runCatching { Archives.list(f) } } }
    var dir by remember(f) { mutableStateOf("") }
    val all = items?.getOrNull()
    if (items == null) return Centered { DelayedSpinner() }
    if (all == null) return Centered { Text(items!!.exceptionOrNull()?.message ?: tr("No se puede abrir"), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    // folders that only exist as prefixes of their files count too
    val children = remember(all, dir) {
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val map = LinkedHashMap<String, ArchiveItem>()
        for (it in all) {
            if (!it.path.startsWith(prefix) || it.path == dir) continue
            val rest = it.path.removePrefix(prefix)
            val name = rest.substringBefore('/')
            val isDir = '/' in rest || it.isDir
            if (map[name]?.isDir != true) map[name] = if (isDir) ArchiveItem(prefix + name, true, 0, it.modified) else it
        }
        map.values.sortedWith(compareByDescending<ArchiveItem> { it.isDir }.thenComparator { a, b -> io.github.julssvanderhaack.luna.fs.naturalCompare(a.name, b.name) })
    }
    val total = remember(all) { all.filter { !it.isDir }.let { files -> tr("%1 · %2 sin comprimir", plural(files.size, "archivo", "archivos"), formatSize(files.sumOf { it.size.coerceAtLeast(0) })) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(total, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Button(onClick = { vm.extract(loc.at(e.parent), e) }) {
                Icon(Icons.Rounded.Unarchive, null, modifier = Modifier.size(18.dp))
                Text(tr("Extraer todo"), modifier = Modifier.padding(start = 6.dp))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val parts = dir.split('/').filter { it.isNotEmpty() }
            Text(
                e.name, style = MaterialTheme.typography.labelLarge,
                color = if (parts.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { dir = "" }.padding(6.dp),
            )
            parts.forEachIndexed { i, p ->
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    p, style = MaterialTheme.typography.labelLarge,
                    color = if (i == parts.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { dir = parts.take(i + 1).joinToString("/") }.padding(6.dp),
                )
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            items(children, key = { it.path }) { it ->
                val fake = FileEntry(it.name, it.path, it.isDir, it.size, it.modified)
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (it.isDir) dir = it.path
                        else vm.extractMember(f, it.path) { out -> Opener.open(context, out) }
                    }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (it.isDir) Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                    else FileIcon(fake, 36.dp, local = false)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!it.isDir) Text(
                            listOf(if (it.size >= 0) formatSize(it.size) else "", formatDate(it.modified)).filter { s -> s.isNotEmpty() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Like desktop Luna: 30 seconds from the middle of the song, fading in and out. */
@Composable
private fun AudioPreview(vm: MainViewModel, e: FileEntry, f: File, active: Boolean) {
    val context = LocalContext.current
    val meta by produceState<Triple<String?, String?, ImageBitmap?>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { m ->
                    m.setDataSource(f.path)
                    val art = m.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    val artist = listOfNotNull(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST), m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                        .joinToString(" · ").ifEmpty { null }
                    Triple(m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), artist, art)
                }
            }.getOrNull() ?: Triple(null, null, null)
        }
    }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableFloatStateOf(0f) }
    val player = remember(f) { runCatching { MediaPlayer().apply { setDataSource(f.path); prepare() } }.getOrNull() }
    DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
    val clip = 30_000
    LaunchedEffect(player, active, playing) {
        val p = player ?: return@LaunchedEffect
        if (!active || !playing) {
            runCatching { if (p.isPlaying) p.pause() }
            return@LaunchedEffect
        }
        val dur = p.duration.coerceAtLeast(0)
        val start = if (dur > clip) (dur - clip) / 2 else 0
        val end = minOf(dur, start + clip)
        if (p.currentPosition < start || p.currentPosition >= end - 200) p.seekTo(start)
        p.setVolume(0f, 0f)
        p.start()
        while (isActive) {
            val pos = p.currentPosition
            val v = when {
                pos - start < 250 -> (pos - start) / 250f
                end - pos < 2000 -> (end - pos) / 2000f
                else -> 1f
            }.coerceIn(0f, 1f)
            p.setVolume(v, v)
            position = ((pos - start).toFloat() / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            if (pos >= end - 60 || !p.isPlaying) {
                p.pause()
                playing = false
                position = 0f
                p.seekTo(start)
                break
            }
            delay(50)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val m = meta
        Box(Modifier.fillMaxWidth(0.8f).aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(e.kind.color().copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            if (m?.third != null) Image(m.third!!, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(e.kind.icon(), null, tint = e.kind.color(), modifier = Modifier.size(96.dp))
        }
        Text(m?.first ?: e.name.substringBeforeLast('.'), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
        m?.second?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (player == null) Text(tr("Android no sabe reproducir este archivo"), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 16.dp))
        else {
            LinearProgressIndicator(progress = { position }, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { playing = !playing }, modifier = Modifier.size(56.dp)) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) tr("Pausa") else tr("Escuchar"))
                }
                Text(
                    tr("Fragmento de 30 s de la mitad · %1", formatDuration(player.duration.toLong())),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp),
                )
            }
            Button(onClick = { vm.localFiles(Loc.Local(f.parent ?: "/"), listOf(e.copy(path = f.path))) { Opener.open(context, it.first()) } }, modifier = Modifier.padding(top = 16.dp)) {
                Text(tr("Escuchar entera en otra app"))
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
