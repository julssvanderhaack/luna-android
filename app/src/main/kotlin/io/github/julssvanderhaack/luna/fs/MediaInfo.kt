package io.github.julssvanderhaack.luna.fs

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** A titled group of rows for Propiedades; [geo] adds a "Ver en el mapa" link. */
data class InfoSection(val title: String, val rows: List<Pair<String, String>>, val geo: Pair<Double, Double>? = null)

/** What a photo, video or song says about itself: resolution, compression, codecs, EXIF, tags. */
object MediaInfo {
    private val ES = Locale.forLanguageTag("es-ES")

    /** Blocking; empty when the file isn't media or can't be read. */
    fun read(f: File): List<InfoSection> = runCatching {
        when (Kind.of(f.name)) {
            Kind.IMAGE -> image(f)
            Kind.VIDEO, Kind.AUDIO -> av(f)
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).filter { it.rows.isNotEmpty() }

    // ---------------------------------------------------------------- images

    private fun image(f: File): List<InfoSection> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var w = o.outWidth
        var h = o.outHeight
        var mime = o.outMimeType
        if (w <= 0) runCatching {
            // HEIF/AVIF on some phones: ImageDecoder knows the size even when BitmapFactory doesn't
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { _, info, _ ->
                w = info.size.width
                h = info.size.height
                mime = info.mimeType
                throw StopDecoding()
            }
        }
        val exif = runCatching { ExifInterface(f) }.getOrNull()
        val rows = ArrayList<Pair<String, String>>()
        if (w > 0 && h > 0) {
            val rotated = exif?.rotation() in setOf(90, 270)
            val (sw, sh) = if (rotated) h to w else w to h
            rows += "Resolución" to "$sw × $sh píxeles"
            rows += "Megapíxeles" to "${num(w.toLong() * h / 1e6, 1)} MP"
            if (exif != null && exif.rotation() != 0) rows += "Rotación" to "${exif.rotation()}° (guardada en EXIF)"
        }
        val head = header(f)
        rows += "Formato" to formatName(f, mime, head)
        compression(f, head)?.let { rows.addAll(it) }
        if (w > 0 && h > 0 && f.length() > 0) {
            val bpp = f.length() * 8.0 / (w.toLong() * h)
            val ratio = w.toLong() * h * 3.0 / f.length()
            rows += "Densidad" to "${num(bpp, 2)} bits por píxel" + (if (ratio >= 1.5) " · ${num(ratio, if (ratio < 10) 1 else 0)}:1 frente a sin comprimir" else "")
        }
        o.outColorSpace?.name?.let { rows += "Espacio de color" to it }
        val out = arrayListOf(InfoSection("Imagen", rows))
        if (exif != null) out += exifSections(exif)
        return out
    }

    private class StopDecoding : RuntimeException()

    private fun header(f: File): ByteArray = runCatching {
        f.inputStream().use { s -> ByteArray(64).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
    }.getOrDefault(ByteArray(0))

    private fun ByteArray.at(i: Int, s: String) = size >= i + s.length && (s.indices).all { this[i + it] == s[it].code.toByte() }

    private fun formatName(f: File, mime: String?, h: ByteArray): String = when {
        h.size > 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> "JPEG"
        h.at(1, "PNG") -> "PNG"
        h.at(0, "RIFF") && h.at(8, "WEBP") -> "WebP"
        h.at(0, "GIF8") -> "GIF"
        h.at(0, "BM") -> "BMP"
        h.at(4, "ftyp") && (h.at(8, "avif") || h.at(8, "avis")) -> "AVIF"
        h.at(4, "ftyp") -> "HEIF / HEIC"
        else -> mime?.substringAfter('/')?.uppercase() ?: f.name.substringAfterLast('.').uppercase()
    }

    /** "Compresión" and friends, read straight from the file's own headers. */
    private fun compression(f: File, h: ByteArray): List<Pair<String, String>>? = runCatching {
        when (formatName(f, null, h)) {
            "JPEG" -> jpeg(f)
            "PNG" -> png(f)
            "WebP" -> webp(f)
            "GIF" -> listOf("Compresión" to "LZW, sin pérdida (hasta 256 colores)")
            "BMP" -> listOf("Compresión" to "Normalmente sin comprimir")
            "HEIF / HEIC" -> listOf("Compresión" to "HEVC (H.265), con pérdida")
            "AVIF" -> listOf("Compresión" to "AV1, con pérdida")
            else -> null
        }
    }.getOrNull()

    /** IJG luminance table: the quality estimate compares the file's table with it. */
    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62, 18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )

    private fun jpeg(f: File): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        RandomAccessFile(f, "r").use { r ->
            r.seek(2)
            var quality: Int? = null
            while (r.filePointer < r.length() - 4) {
                if (r.read() != 0xFF) break
                var marker = r.read()
                while (marker == 0xFF) marker = r.read()
                if (marker == 0xD9 || marker == 0xDA) break   // end of image / start of scan: headers are over
                val len = r.readUnsignedShort()
                val start = r.filePointer
                when (marker) {
                    0xDB -> if (quality == null) {
                        val pq = r.read() shr 4
                        val q = IntArray(64) { if (pq == 0) r.read() else r.readUnsignedShort() }
                        val s = 100.0 * q.sum() / STD_LUMA.sum()
                        quality = (if (s <= 100) (200 - s) / 2 else 5000 / s).roundToInt().coerceIn(1, 100)
                    }
                    in 0xC0..0xCF -> if (marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                        val bits = r.read()
                        r.skipBytes(4)
                        val comps = r.read()
                        val factors = List(comps) { r.read(); val s = r.read(); r.read(); (s shr 4) to (s and 15) }
                        out += "Codificación" to when (marker) {
                            0xC0 -> "Secuencial (baseline)"
                            0xC1 -> "Secuencial extendida"
                            0xC2 -> "Progresiva"
                            0xC3 -> "Sin pérdida"
                            else -> "Aritmética"
                        }
                        out += "Color" to when (comps) {
                            1 -> "Escala de grises"
                            3 -> "Color (YCbCr)"
                            4 -> "CMYK"
                            else -> "$comps componentes"
                        }
                        if (comps >= 3) out += "Submuestreo" to subsampling(factors)
                        out += "Profundidad" to "$bits bits por canal"
                    }
                }
                r.seek(start + len - 2)
            }
            out.add(0, "Compresión" to (quality?.let { "JPEG con pérdida · calidad ≈ $it %" } ?: "JPEG con pérdida"))
        }
        return out
    }

    private fun subsampling(f: List<Pair<Int, Int>>): String {
        val (yh, yv) = f[0]
        val (ch, cv) = f[1]
        return when {
            yh == ch && yv == cv -> "4:4:4 (color completo)"
            yh == 2 * ch && yv == cv -> "4:2:2"
            yh == 2 * ch && yv == 2 * cv -> "4:2:0"
            yh == 4 * ch && yv == cv -> "4:1:1"
            yh == ch && yv == 2 * cv -> "4:4:0"
            else -> "${yh}×$yv / ${ch}×$cv"
        }
    }

    private fun png(f: File): List<Pair<String, String>> {
        RandomAccessFile(f, "r").use { r ->
            r.seek(24)
            val depth = r.read()
            val type = r.read()
            r.skipBytes(2)
            val interlace = r.read()
            // APNG says so in an acTL chunk before the image data
            var animated = false
            r.seek(8)
            while (r.filePointer < r.length() - 8) {
                val len = r.readInt()
                val name = ByteArray(4).also { r.readFully(it) }.toString(Charsets.US_ASCII)
                if (name == "acTL") animated = true
                if (name == "IDAT" || name == "IEND") break
                r.seek(r.filePointer + len + 4)
            }
            return listOfNotNull(
                "Compresión" to "Deflate, sin pérdida",
                "Color" to when (type) {
                    0 -> "Escala de grises"
                    2 -> "RGB"
                    3 -> "Paleta indexada"
                    4 -> "Escala de grises con transparencia"
                    6 -> "RGB con transparencia"
                    else -> "Tipo $type"
                },
                "Profundidad" to "$depth bits por canal",
                if (interlace == 1) "Entrelazado" to "Adam7" else null,
                if (animated) "Animación" to "Sí (APNG)" else null,
            )
        }
    }

    private fun webp(f: File): List<Pair<String, String>> {
        var lossy = false
        var lossless = false
        var animated = false
        var alpha = false
        RandomAccessFile(f, "r").use { r ->
            r.seek(12)
            while (r.filePointer < r.length() - 8) {
                val name = ByteArray(4).also { r.readFully(it) }.toString(Charsets.US_ASCII)
                val len = Integer.reverseBytes(r.readInt()).toLong() and 0xFFFFFFFFL
                val next = r.filePointer + len + (len and 1)
                when (name) {
                    "VP8X" -> r.read().let { flags -> animated = flags and 0x02 != 0; alpha = flags and 0x10 != 0 }
                    "VP8 " -> lossy = true
                    "VP8L" -> lossless = true
                    "ALPH" -> alpha = true
                    // animated: the frames carry the bitstream; the first one tells the kind
                    "ANMF" -> {
                        r.skipBytes(16)
                        when (ByteArray(4).also { r.readFully(it) }.toString(Charsets.US_ASCII)) {
                            "VP8 ", "ALPH" -> lossy = true
                            "VP8L" -> lossless = true
                        }
                    }
                }
                if (lossy || lossless) break
                r.seek(next)
            }
        }
        return listOfNotNull(
            "Compresión" to when {
                lossy && lossless -> "VP8 y VP8L (mixta)"
                lossless -> "VP8L, sin pérdida"
                else -> "VP8, con pérdida"
            },
            if (alpha) "Transparencia" to "Sí" else null,
            if (animated) "Animación" to "Sí" else null,
        )
    }

    private fun exifSections(x: ExifInterface): List<InfoSection> {
        fun s(tag: String) = x.getAttribute(tag)?.trim()?.trim('\u0000')?.takeIf { it.isNotBlank() }
        fun d(tag: String) = x.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() && it > 0 }
        fun i(tag: String) = x.getAttributeInt(tag, -1).takeIf { it >= 0 }

        val cam = ArrayList<Pair<String, String>>()
        val make = s(ExifInterface.TAG_MAKE)
        val model = s(ExifInterface.TAG_MODEL)
        val camera = when {
            model == null -> make
            make == null || model.startsWith(make, ignoreCase = true) -> model
            else -> "${make.replaceFirstChar { it.uppercase() }} $model"
        }
        camera?.let { cam += "Cámara" to it }
        listOfNotNull(s("LensMake"), s("LensModel")).distinct().joinToString(" ").takeIf { it.isNotEmpty() }
            ?.let { cam += "Objetivo" to it }
        exifDate(s(ExifInterface.TAG_DATETIME_ORIGINAL) ?: s(ExifInterface.TAG_DATETIME), s(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))
            ?.let { cam += "Fecha de captura" to it }
        d(ExifInterface.TAG_EXPOSURE_TIME)?.let { t ->
            cam += "Exposición" to if (t < 1) "1/${(1 / t).roundToInt()} s" else "${num(t, if (t < 10) 1 else 0)} s"
        }
        d(ExifInterface.TAG_F_NUMBER)?.let { cam += "Apertura" to "f/${num(it, 1)}" }
        (i("PhotographicSensitivity") ?:i(ExifInterface.TAG_ISO_SPEED_RATINGS))?.takeIf { it > 0 }?.let { cam += "ISO" to "$it" }
        d(ExifInterface.TAG_FOCAL_LENGTH)?.let { fl ->
            val eq = i(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 }
            cam += "Distancia focal" to "${num(fl, if (fl < 10) 1 else 0)} mm" + (eq?.let { " ($it mm equiv. 35 mm)" } ?: "")
        }
        x.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN).takeIf { !it.isNaN() && it != 0.0 }
            ?.let { cam += "Compensación" to "${if (it > 0) "+" else ""}${num(it, 1)} EV" }
        i(ExifInterface.TAG_EXPOSURE_PROGRAM)?.let { p ->
            listOf(null, "Manual", "Automático", "Prioridad a la apertura", "Prioridad a la velocidad", "Creativo", "Acción", "Retrato", "Paisaje")
                .getOrNull(p)?.let { cam += "Modo" to it }
        }
        i(ExifInterface.TAG_FLASH)?.let { cam += "Flash" to if (it and 1 == 1) "Disparado" else "No disparado" }
        i(ExifInterface.TAG_WHITE_BALANCE)?.let { cam += "Balance de blancos" to if (it == 0) "Automático" else "Manual" }
        d(ExifInterface.TAG_DIGITAL_ZOOM_RATIO)?.takeIf { it > 1 }?.let { cam += "Zoom digital" to "${num(it, 1)}×" }

        val more = ArrayList<Pair<String, String>>()
        d(ExifInterface.TAG_X_RESOLUTION)?.let { res ->
            val unit = if (i(ExifInterface.TAG_RESOLUTION_UNIT) == 3) "píxeles por cm" else "ppp"
            more += "Resolución de impresión" to "${res.roundToInt()} $unit"
        }
        s(ExifInterface.TAG_SOFTWARE)?.let { more += "Software" to it }
        s(ExifInterface.TAG_ARTIST)?.let { more += "Autor" to it }
        s(ExifInterface.TAG_COPYRIGHT)?.let { more += "Copyright" to it }
        (s(ExifInterface.TAG_IMAGE_DESCRIPTION) ?: s(ExifInterface.TAG_USER_COMMENT))?.let { more += "Descripción" to it }
        if (x.hasThumbnail()) more += "Miniatura incrustada" to "Sí"

        val out = arrayListOf(InfoSection("Cámara", cam), InfoSection("Más datos EXIF", more))
        x.latLon()?.let { (lat, lon) ->
            val rows = arrayListOf("Coordenadas" to "${num(lat, 5)}, ${num(lon, 5)}")
            x.getAltitude(Double.NaN).takeIf { !it.isNaN() }?.let { rows += "Altitud" to "${it.roundToInt()} m" }
            out += InfoSection("Ubicación", rows, geo = lat to lon)
        }
        return out
    }

    // the platform ExifInterface lacks androidx's rotationDegrees and latLong
    private fun ExifInterface.rotation() = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        3, 4 -> 180
        5, 6 -> 90
        7, 8 -> 270
        else -> 0
    }

    private fun ExifInterface.latLon(): Pair<Double, Double>? {
        val v = FloatArray(2)
        return if (getLatLong(v) && !(v[0] == 0f && v[1] == 0f)) v[0].toDouble() to v[1].toDouble() else null
    }

    private fun exifDate(v: String?, offset: String?): String? {
        v ?: return null
        val p = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val t = runCatching { p.parse(v) }.getOrNull() ?: return v
        val out = SimpleDateFormat("d 'de' MMMM 'de' yyyy, HH:mm:ss", ES).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return out.format(t) + (offset?.let { " (UTC$it)" } ?: "")
    }

    // ------------------------------------------------------- video and audio

    private fun av(f: File): List<InfoSection> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            fun k(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val durMs = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val general = ArrayList<Pair<String, String>>()
            durMs?.let { general += "Duración" to duration(it) }
            general += "Contenedor" to container(k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE), f)
            val bitrate = k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
                ?: durMs?.takeIf { it > 0 }?.let { f.length() * 8000 / it }
            bitrate?.let { general += "Bitrate total" to bitrate(it) }
            mp4Date(k(MediaMetadataRetriever.METADATA_KEY_DATE))?.let { general += "Fecha" to it }

            val tracks = tracks(f, k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            val tags = ArrayList<Pair<String, String>>()
            listOf(
                MediaMetadataRetriever.METADATA_KEY_TITLE to "Título",
                MediaMetadataRetriever.METADATA_KEY_ARTIST to "Artista",
                MediaMetadataRetriever.METADATA_KEY_ALBUM to "Álbum",
                MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST to "Artista del álbum",
                MediaMetadataRetriever.METADATA_KEY_COMPOSER to "Compositor",
                MediaMetadataRetriever.METADATA_KEY_WRITER to "Letra",
                MediaMetadataRetriever.METADATA_KEY_GENRE to "Género",
                MediaMetadataRetriever.METADATA_KEY_YEAR to "Año",
                MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER to "Pista",
                MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER to "Disco",
            ).forEach { (key, label) -> k(key)?.let { tags += label to it } }
            if (r.embeddedPicture != null) tags += "Carátula" to "Incluida"

            val out = arrayListOf(InfoSection(if (Kind.of(f.name) == Kind.VIDEO) "Vídeo" else "Audio", general))
            out += tracks
            out += InfoSection("Etiquetas", tags)
            k(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { iso6709(it) }?.let { (lat, lon) ->
                out += InfoSection("Ubicación", listOf("Coordenadas" to "${num(lat, 5)}, ${num(lon, 5)}"), geo = lat to lon)
            }
            return out
        } finally {
            runCatching { r.release() }
        }
    }

    private fun tracks(f: File, containerMime: String?): List<InfoSection> {
        val x = MediaExtractor()
        try {
            x.setDataSource(f.path)
            val formats = (0 until x.trackCount).map { x.getTrackFormat(it) }
            val kinds = formats.map { it.getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/') }
            return formats.mapIndexed { i, fmt ->
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                val kind = kinds[i]
                val nth = kinds.count { it == kind }.takeIf { it > 1 }?.let { " ${kinds.take(i + 1).count { k -> k == kind }}" } ?: ""
                // Android's FLAC extractor hands out decoded PCM: the container says what it really is
                val flac = mime == "audio/raw" && (containerMime?.contains("flac") == true || f.name.endsWith(".flac", true))
                val rows = arrayListOf("Códec" to codec(if (flac) "audio/flac" else mime))
                fun int(key: String) = if (fmt.containsKey(key)) runCatching { fmt.getInteger(key) }.getOrNull() else null
                when (kind) {
                    "video" -> {
                        val w = int(MediaFormat.KEY_WIDTH)
                        val h = int(MediaFormat.KEY_HEIGHT)
                        if (w != null && h != null) rows += "Resolución" to "$w × $h${quality(w, h)}"
                        frameRate(fmt)?.let { rows += "Fotogramas" to "${num(it, if (it % 1.0 == 0.0) 0 else 2)} por segundo" }
                        when (int(MediaFormat.KEY_COLOR_TRANSFER)) {
                            MediaFormat.COLOR_TRANSFER_ST2084 -> rows += "Rango dinámico" to "HDR10 (PQ)"
                            MediaFormat.COLOR_TRANSFER_HLG -> rows += "Rango dinámico" to "HDR (HLG)"
                        }
                        int(MediaFormat.KEY_ROTATION)?.takeIf { it != 0 }?.let { rows += "Rotación" to "$it°" }
                    }
                    "audio" -> {
                        int(MediaFormat.KEY_SAMPLE_RATE)?.let { rows += "Frecuencia" to "${num(it / 1000.0, if (it % 1000 == 0) 0 else 1)} kHz" }
                        int(MediaFormat.KEY_CHANNEL_COUNT)?.let { rows += "Canales" to channels(it) }
                        int("bits-per-sample")?.let { rows += "Profundidad" to "$it bits" }
                        int(MediaFormat.KEY_PCM_ENCODING)?.let { enc ->
                            mapOf(2 to 16, 3 to 8, 4 to 32, 21 to 24, 22 to 32)[enc]?.let { rows += "Profundidad" to "$it bits" }
                        }
                    }
                }
                int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { rows += "Bitrate" to bitrate(it.toLong()) }
                fmt.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it != "und" && it.isNotBlank() }?.let { lang ->
                    rows += "Idioma" to (Locale.forLanguageTag(lang).getDisplayLanguage(ES).takeIf { it.isNotBlank() && it != lang } ?: lang)
                }
                val title = when (kind) {
                    "video" -> "Pista de vídeo"
                    "audio" -> "Pista de audio"
                    "text", "application" -> "Subtítulos"
                    else -> "Pista"
                } + nth
                InfoSection(title, rows.distinctBy { it.first })
            }
        } catch (e: Exception) {
            return emptyList()
        } finally {
            x.release()
        }
    }

    private fun frameRate(fmt: MediaFormat): Double? {
        if (!fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return runCatching { fmt.getNumber(MediaFormat.KEY_FRAME_RATE)?.toDouble() }.getOrNull()?.takeIf { it > 0 }
    }

    private fun quality(w: Int, h: Int): String {
        val short = minOf(w, h)
        return when {
            short >= 2100 -> " (8K)"
            short >= 2000 || maxOf(w, h) >= 3800 -> " (4K)"
            short >= 1400 -> " (1440p)"
            short >= 1060 -> " (1080p)"
            short >= 700 -> " (720p)"
            short >= 470 -> " (480p)"
            else -> ""
        }
    }

    private fun channels(n: Int) = when (n) {
        1 -> "Mono"
        2 -> "Estéreo"
        6 -> "5.1 (6 canales)"
        8 -> "7.1 (8 canales)"
        else -> "$n canales"
    }

    private fun codec(mime: String) = when (mime) {
        "video/avc" -> "H.264 (AVC)"
        "video/hevc" -> "H.265 (HEVC)"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/mp4v-es" -> "MPEG-4 Part 2"
        "video/3gpp" -> "H.263"
        "video/mpeg2" -> "MPEG-2"
        "video/dolby-vision" -> "Dolby Vision"
        "audio/mp4a-latm" -> "AAC"
        "audio/mpeg" -> "MP3"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/flac" -> "FLAC (sin pérdida)"
        "audio/alac" -> "ALAC (sin pérdida)"
        "audio/raw" -> "PCM (sin comprimir)"
        "audio/3gpp" -> "AMR-NB"
        "audio/amr-wb" -> "AMR-WB"
        "audio/ac3" -> "Dolby Digital (AC-3)"
        "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
        "audio/ac4" -> "Dolby AC-4"
        "audio/g711-alaw" -> "G.711 A-law"
        "audio/g711-mlaw" -> "G.711 µ-law"
        "text/vtt" -> "WebVTT"
        "application/x-subrip" -> "SubRip (SRT)"
        "text/x-ssa" -> "SubStation Alpha (ASS)"
        "text/3gpp-tt" -> "3GPP Timed Text"
        else -> mime.substringAfter('/').removePrefix("x-").uppercase()
    }

    private fun container(mime: String?, f: File) = when (mime) {
        "video/mp4", "audio/mp4" -> "MP4"
        "video/quicktime" -> "QuickTime (MOV)"
        "video/x-matroska", "audio/x-matroska" -> "Matroska (MKV)"
        "video/webm", "audio/webm" -> "WebM"
        "video/3gpp", "audio/3gpp" -> "3GP"
        "video/avi", "video/x-msvideo" -> "AVI"
        "video/mp2ts" -> "MPEG-TS"
        "audio/mpeg" -> "MP3"
        "audio/flac", "audio/x-flac" -> "FLAC"
        "audio/ogg", "application/ogg" -> "Ogg"
        "audio/x-wav", "audio/wav" -> "WAV"
        "audio/aac", "audio/aac-adts" -> "AAC (ADTS)"
        "audio/amr" -> "AMR"
        else -> mime?.substringAfter('/')?.removePrefix("x-")?.uppercase() ?: f.name.substringAfterLast('.').uppercase()
    }

    private fun bitrate(bps: Long): String {
        val kbps = bps / 1000.0
        return if (kbps >= 10_000) "${num(kbps / 1000, 1)} Mb/s" else String.format(ES, "%,d kb/s", kbps.roundToInt())
    }

    private fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) String.format(ES, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(ES, "%d:%02d", s / 60, s % 60)
    }

    /** MP4 dates look like "20240501T123000.000Z"; 1904/1970 placeholders mean "no date". */
    private fun mp4Date(v: String?): String? {
        v ?: return null
        val t = listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss").firstNotNullOfOrNull { pat ->
            runCatching { SimpleDateFormat(pat, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v) }.getOrNull()
        } ?: return null
        if (t.time < 31_536_000_000L) return null
        return SimpleDateFormat("d 'de' MMMM 'de' yyyy, HH:mm", ES).format(t)
    }

    /** "+40.4168-003.7038/" → (40.4168, -3.7038). */
    private fun iso6709(v: String): Pair<Double, Double>? {
        val m = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(v) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return if (lat == 0.0 && lon == 0.0) null else lat to lon
    }

    private fun num(v: Double, decimals: Int) = String.format(ES, "%.${decimals}f", v)
}
