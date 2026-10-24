package io.github.julssvanderhaack.luna.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Which pictures Luna shows itself; the rest (SVG, TIFF, camera RAW…) still go to another app. */
object ImageViewer {
    private val EXTS = buildSet {
        addAll(listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "ico", "dng"))
        if (android.os.Build.VERSION.SDK_INT >= 31) add("avif")
    }

    fun canShow(e: FileEntry) = !e.isDir && e.ext in EXTS

    /** Bigger downloads aren't worth it just to look: those open in another app. */
    const val MAX_REMOTE = 100_000_000L
}

private sealed interface Pic {
    data object Loading : Pic
    data class Still(val bitmap: ImageBitmap) : Pic
    data class Animated(val drawable: Drawable) : Pic
    data class Failed(val message: String) : Pic
}

@Composable
fun ViewerScreen(vm: MainViewModel, r: Route.Viewer) {
    val context = LocalContext.current
    val tick by vm.tick.collectAsStateWithLifecycle()
    // deleted or renamed from the ⋮ menu: drop out of the viewer
    val images = remember(tick) { if (r.loc is Loc.Remote) r.images else r.images.filter { File(it.path).exists() } }
    if (images.isEmpty()) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val pager = rememberPagerState(initialPage = r.start.coerceIn(0, images.lastIndex)) { images.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val actions = remember { FileActions() }
    val current = images[pager.currentPage.coerceIn(0, images.lastIndex)]

    // black screen, light bar icons, and the bars go away with the buttons
    val activity = LocalActivity.current
    val bars = remember(activity) { activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) } }
    DisposableEffect(bars, chrome) {
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) bars?.show(WindowInsetsCompat.Type.systemBars()) else bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {}
    }
    DisposableEffect(bars) {
        onDispose {
            bars?.show(WindowInsetsCompat.Type.systemBars())
            val light = !vm.palette.value.dark
            bars?.isAppearanceLightStatusBars = light
            bars?.isAppearanceLightNavigationBars = light
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { images[it].path },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val e = images[page]
            ZoomableImage(
                vm, r.loc, e,
                active = page == pager.currentPage,
                onTap = { chrome = !chrome },
                onZoomed = { zoomed = it },
                onOpenElsewhere = { vm.localFiles(r.loc, listOf(e)) { Opener.open(context, it.first(), chooser = true) } },
            )
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Volver", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(current.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    if (images.size > 1) Text(
                        "${pager.currentPage + 1} de ${images.size}", color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { actions.dialog = FileDialog.Properties(listOf(current)) }) { Icon(Icons.Rounded.Info, "Propiedades", tint = Color.White) }
                IconButton(onClick = { vm.localFiles(r.loc, listOf(current)) { Opener.share(context, it) } }) {
                    Icon(Icons.Rounded.Share, "Compartir", tint = Color.White)
                }
                IconButton(onClick = { actions.sheet = listOf(current) }) { Icon(Icons.Rounded.MoreVert, "Más", tint = Color.White) }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                "${formatFullDate(current.modified)} · ${formatSize(current.size)}",
                color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
    FileActionsHost(vm, r.loc, actions)
}

@Composable
private fun ZoomableImage(
    vm: MainViewModel,
    loc: Loc,
    e: FileEntry,
    active: Boolean,
    onTap: () -> Unit,
    onZoomed: (Boolean) -> Unit,
    onOpenElsewhere: () -> Unit,
) {
    val context = LocalContext.current
    // enough pixels to zoom in a bit, without a 50-megapixel photo eating all the memory
    val maxPx = remember {
        context.resources.displayMetrics.let { (max(it.widthPixels, it.heightPixels) * 1.5f).toInt() }.coerceIn(1024, 4096)
    }
    val pic by produceState<Pic>(Pic.Loading, e.path, e.modified) {
        value = withContext(Dispatchers.IO) { load(vm, loc, e, maxPx) }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // a page that slides away goes back to fit
    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    val isZoomed = scale > 1.01f
    LaunchedEffect(isZoomed, active) { if (active) onZoomed(isZoomed) }

    val content: IntSize = when (val p = pic) {
        is Pic.Still -> IntSize(p.bitmap.width, p.bitmap.height)
        is Pic.Animated -> IntSize(p.drawable.intrinsicWidth, p.drawable.intrinsicHeight)
        else -> IntSize.Zero
    }

    /** Keeps the picture's edges from leaving the screen. */
    fun clamp(o: Offset, s: Float): Offset {
        if (box.width == 0 || content.width <= 0 || content.height <= 0) return Offset.Zero
        val fit = min(box.width.toFloat() / content.width, box.height.toFloat() / content.height)
        val maxX = max(0f, (content.width * fit * s - box.width) / 2)
        val maxY = max(0f, (content.height * fit * s - box.height) / 2)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(e.path) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { p ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            // the tapped spot stays under the finger
                            val s = 2.5f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            scale = s
                            offset = clamp((p - c) * (1 - s), s)
                        }
                    },
                )
            }
            .pointerInput(e.path) {
                // pinch anywhere; one finger pans only when zoomed, otherwise the pager gets the swipe
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = false)
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            val c = Offset(size.width / 2f, size.height / 2f)
                            if (centroid.isSpecified()) {
                                val q = (centroid - c - offset) / scale
                                offset = clamp(centroid + pan - c - q * newScale, newScale)
                            }
                            scale = newScale
                            if (scale <= 1.01f) offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        when (val p = pic) {
            Pic.Loading -> DelayedSpinner()
            is Pic.Still -> Image(p.bitmap, e.name, contentScale = ContentScale.Fit, modifier = layer)
            is Pic.Animated -> AndroidView(
                factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                update = { v ->
                    v.setImageDrawable(p.drawable)
                    (p.drawable as? AnimatedImageDrawable)?.start()
                },
                modifier = layer,
            )
            is Pic.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.BrokenImage, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(56.dp))
                Text(p.message, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp))
                TextButton(onClick = onOpenElsewhere) { Text("Abrir con otra aplicación") }
            }
        }
    }
}

private fun Offset.isSpecified() = !x.isNaN() && !y.isNaN()

private suspend fun load(vm: MainViewModel, loc: Loc, e: FileEntry, maxPx: Int): Pic {
    val f = vm.mediaFile(loc, e, ImageViewer.MAX_REMOTE)
        ?: return Pic.Failed(if (e.size > ImageViewer.MAX_REMOTE) "Es demasiado grande para verla desde el servidor" else "No se ha podido descargar")
    return try {
        val src = ImageDecoder.createSource(f)
        val sizer = ImageDecoder.OnHeaderDecodedListener { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxPx) {
                val k = maxPx.toFloat() / longest
                decoder.setTargetSize(max(1, (w * k).toInt()), max(1, (h * k).toInt()))
            }
        }
        if (e.ext == "gif" || e.ext == "webp") {
            when (val d = ImageDecoder.decodeDrawable(src, sizer)) {
                is AnimatedImageDrawable -> Pic.Animated(d)
                is android.graphics.drawable.BitmapDrawable -> Pic.Still(d.bitmap.asImageBitmap())
                else -> Pic.Animated(d)
            }
        } else {
            Pic.Still(ImageDecoder.decodeBitmap(src, sizer).also { it.prepareToDraw() }.asImageBitmap())
        }
    } catch (t: Throwable) {
        if (t is OutOfMemoryError) System.gc()
        Pic.Failed("Luna no puede mostrar esta imagen")
    }
}
