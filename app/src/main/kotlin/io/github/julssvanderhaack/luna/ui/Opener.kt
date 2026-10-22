package io.github.julssvanderhaack.luna.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.julssvanderhaack.luna.fs.Mime
import java.io.File

/** Hands files to other apps through Luna's FileProvider. */
object Opener {
    fun uri(context: Context, f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    /**
     * [mime] null = what the name says. [chooser] = always ask which app
     * ("Abrir con…"); otherwise Android uses the default app if there is one.
     */
    fun open(context: Context, f: File, mime: String? = null, chooser: Boolean = false) {
        val type = mime ?: Mime.of(f.name)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, f), type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(if (chooser) Intent.createChooser(intent, "Abrir con").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        } catch (e: ActivityNotFoundException) {
            // nothing claims this type: let the user pick from everything
            if (type != "*/*") open(context, f, "*/*", chooser = true)
            else Toast.makeText(context, "Ninguna aplicación puede abrir este archivo", Toast.LENGTH_SHORT).show()
        }
    }

    fun share(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = files.map { uri(context, it) }
        val types = files.map { Mime.of(it.name) }.distinct()
        val type = types.singleOrNull() ?: types.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = type
        // ClipData too, or the receiving app can't read the URIs on recent Android
        intent.clipData = ClipData.newUri(context.contentResolver, files[0].name, uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
