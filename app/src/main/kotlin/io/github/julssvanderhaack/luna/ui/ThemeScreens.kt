package io.github.julssvanderhaack.luna.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.julssvanderhaack.luna.fs.FileEntry
import io.github.julssvanderhaack.luna.theme.Palette
import io.github.julssvanderhaack.luna.theme.ResolvedPalette
import io.github.julssvanderhaack.luna.theme.Rgb
import io.github.julssvanderhaack.luna.theme.LunaTheme
import io.github.julssvanderhaack.luna.theme.ThemeRepository
import io.github.julssvanderhaack.luna.theme.compose
import kotlinx.coroutines.launch

@Composable
fun ThemesScreen(vm: MainViewModel) {
    val themes by vm.themes.themes.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf<Palette?>(null) }
    val importFailed = "Ese archivo no tiene colores de tema"
    val imported = "Tema «%1\$s» importado"

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val fileName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        vm.importTheme(uri, fileName) { name ->
            if (name != null) vm.setTheme(name)
            scope.launch { snack.showSnackbar(if (name == null) importFailed else imported.format(ThemeRepository.displayName(name))) }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/toml")) { uri ->
        val p = exporting
        if (uri != null && p != null) vm.exportTheme(p, uri)
        exporting = null
    }

    BackScaffold(
        "Temas", vm::back,
        actions = {
            IconButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                Icon(Icons.Rounded.FileDownload, "Importar colors.toml")
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(themes, key = { (if (it.builtIn) "b:" else "u:") + it.name }) { p ->
                    ThemeCard(
                        palette = p,
                        selected = p.name == settings.theme && !settings.dynamicColor,
                        onApply = {
                            vm.setTheme(p.name)
                            if (settings.dynamicColor) vm.setDynamicColor(false)
                        },
                        onEdit = { vm.open(Route.ThemeEditor(p.name, editing = !p.builtIn)) },
                        onCopy = { vm.open(Route.ThemeEditor(p.name, editing = false)) },
                        onExport = { exporting = p; exporter.launch("${p.name}.toml") },
                        onDelete = if (p.builtIn) null else ({ vm.deleteTheme(p.name) }),
                    )
                }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ThemeCard(
    palette: Palette,
    selected: Boolean,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val r = remember(palette) { palette.resolve() }
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = r.background.compose(),
        border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onApply),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ThemeRepository.displayName(palette.name), color = r.foreground.compose(),
                    style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selected) Icon(Icons.Rounded.Check, null, tint = r.accent.compose(), modifier = Modifier.size(18.dp))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.MoreVert, "Más opciones", tint = r.foregroundDim.compose())
                    }
                    DropdownMenu(menu, { menu = false }) {
                        if (onDelete != null) {
                            DropdownMenuItem({ Text("Editar") }, { menu = false; onEdit() }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        }
                        DropdownMenuItem({ Text("Duplicar y editar") }, { menu = false; onCopy() }, leadingIcon = { Icon(Icons.Rounded.Palette, null) })
                        DropdownMenuItem({ Text("Exportar colors.toml") }, { menu = false; onExport() }, leadingIcon = { Icon(Icons.Rounded.FileUpload, null) })
                        if (onDelete != null) {
                            DropdownMenuItem({ Text("Borrar") }, { menu = false; onDelete() }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                        }
                    }
                }
            }
            Text(
                if (palette.builtIn) "Omarchy" else "Tuyo",
                color = r.foregroundDim.compose(), style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(r.accent, r.foreground, r.selection, r.backgroundLight, r.muted, r.red).forEach {
                    Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(it.compose()))
                }
            }
        }
    }
}

private val KEY_LABELS = mapOf(
    "accent" to "Acento",
    "background" to "Fondo",
    "dark_background" to "Fondo oscuro",
    "lighter_background" to "Fondo claro",
    "foreground" to "Texto",
    "dark_foreground" to "Texto secundario",
    "selection" to "Selección",
    "muted" to "Bordes",
    "red" to "Error",
)

/** Colour the editor shows for [key] when the file doesn't set it: what the app is actually using. */
private fun ResolvedPalette.effective(key: String): Rgb = when (key) {
    "accent" -> accent
    "background" -> background
    "dark_background" -> backgroundDark
    "lighter_background" -> backgroundLight
    "foreground" -> foreground
    "dark_foreground" -> foregroundDim
    "selection" -> selection
    "muted" -> muted
    else -> red
}

/** The whole editor is drawn with the palette being edited, so it previews itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeEditorScreen(vm: MainViewModel, base: String, editing: Boolean) {
    val original = remember(base) { vm.themes.find(base) }
    if (original == null) {
        LaunchedEffect(Unit) { vm.back() }
        return
    }
    val copySuffix = "(copia)"
    var draft by remember(base) { mutableStateOf(original.copy(builtIn = false)) }
    var name by remember(base) {
        mutableStateOf(ThemeRepository.displayName(original.name) + if (editing) "" else " $copySuffix")
    }
    var picking by remember { mutableStateOf<String?>(null) }
    val resolved = remember(draft) { draft.resolve() }

    LunaTheme(resolved, dynamic = false) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            BackScaffold(
                if (editing) "Editar tema" else "Nuevo tema", vm::back,
                actions = {
                    TextButton(onClick = {
                        vm.saveTheme(draft.copy(name = name), replacing = if (editing) original.name else null)
                        vm.back()
                    }) { Text("Guardar") }
                },
            ) { pad ->
                Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nombre") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("dark" to "Oscuro", "light" to "Claro").forEachIndexed { i, (mode, label) ->
                            SegmentedButton(
                                selected = resolved.dark == (mode == "dark"),
                                onClick = { draft = draft.with("mode", mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Palette.EDITABLE.forEach { key ->
                        val c = resolved.effective(key)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { picking = key }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(c.compose())
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                            )
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(KEY_LABELS.getValue(key), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    key + if (draft.values[key] == null) " · " + "calculado" else "",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(c.hex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Preview()
                }
            }
        }

        picking?.let { key ->
            ColorPickerDialog(
                initial = resolved.effective(key),
                swatches = draft.swatches(),
                onDismiss = { picking = null },
                onPick = { draft = draft.with(key, it.hex()); picking = null },
            )
        }
    }

}

/** A sample of the real widgets in the edited colours. */
@Composable
private fun Preview() {
    val cs = MaterialTheme.colorScheme
    Text("Vista previa", style = MaterialTheme.typography.titleSmall, color = cs.primary)
    Spacer(Modifier.height(8.dp))
    Surface(shape = RoundedCornerShape(12.dp), color = cs.surfaceContainerLow, border = BorderStroke(1.dp, cs.outlineVariant)) {
        Column {
            listOf(
                FileEntry("Fotos de Luna", "/a", true, 0, 0) to "12 elementos",
                FileEntry("IMG_2026.jpg", "/b", false, 0, 0) to "3,1 MB · ayer",
                FileEntry("factura.pdf", "/c", false, 0, 0) to "180 kB · 12 oct",
                FileEntry("canción.flac", "/d", false, 0, 0) to "28 MB · 3 sept",
            ).forEachIndexed { i, (e, sub) ->
                Row(
                    Modifier.fillMaxWidth().background(if (i == 1) cs.secondaryContainer else Color.Transparent).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileIcon(e, 36.dp, local = false)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.name, color = if (i == 1) cs.onSecondaryContainer else cs.onSurface, fontWeight = if (i == 1) FontWeight.SemiBold else FontWeight.Normal)
                        Text(sub, color = if (i == 1) cs.onSecondaryContainer.copy(alpha = 0.75f) else cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}) { Text("Pegar aquí") }
                FilledTonalButton(onClick = {}) { Text("Cancelar") }
                Text("Error", color = cs.error, style = MaterialTheme.typography.bodySmall)
            }
            Slider(value = 0.4f, onValueChange = {}, modifier = Modifier.padding(horizontal = 12.dp))
        }
    }
}

/** HSV sliders + hex field + the palette's own colours as shortcuts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerDialog(initial: Rgb, swatches: List<Rgb>, onDismiss: () -> Unit, onPick: (Rgb) -> Unit) {
    val hsv0 = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(0xff000000.toInt() or initial.value, it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    val current = hsv(h, s, v)
    var hex by remember { mutableStateOf(initial.hex()) }
    fun set(c: Rgb) {
        val a = FloatArray(3)
        android.graphics.Color.colorToHSV(0xff000000.toInt() or c.value, a)
        h = a[0]; s = a[1]; v = a[2]
        hex = c.hex()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(current) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(current.compose()).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)))
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { t ->
                            hex = t
                            Rgb.parse(t)?.takeIf { t.trimStart('#').length == 6 }?.let { c -> set(c); hex = t }
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                LabeledSlider("Tono", h, 0f..360f) { h = it; hex = hsv(h, s, v).hex() }
                LabeledSlider("Saturación", s, 0f..1f) { s = it; hex = hsv(h, s, v).hex() }
                LabeledSlider("Brillo", v, 0f..1f) { v = it; hex = hsv(h, s, v).hex() }
                if (swatches.isNotEmpty()) {
                    Text("Colores de la paleta", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        swatches.forEach { c ->
                            Box(
                                Modifier.size(28.dp).clip(CircleShape).background(c.compose())
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { set(c) },
                            )
                        }
                    }
                }
            }
        },
    )
}

private fun hsv(h: Float, s: Float, v: Float) = Rgb(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xffffff)

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
