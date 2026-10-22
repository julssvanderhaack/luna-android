package io.github.julssvanderhaack.luna.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val ES = Locale.forLanguageTag("es-ES")

/** 1536 → "1,5 kB", as GNOME and desktop Luna count (powers of 1000). */
fun formatSize(bytes: Long): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var v = bytes / 1000.0
    var i = 0
    while (v >= 1000 && i < units.lastIndex) {
        v /= 1000
        i++
    }
    return if (v < 10) String.format(ES, "%.1f %s", v, units[i]) else String.format(ES, "%.0f %s", v, units[i])
}

/** Today: "14:32"; yesterday: "ayer"; this year: "12 oct"; otherwise "12 oct 2024". */
fun formatDate(millis: Long, now: Long = System.currentTimeMillis()): String {
    if (millis <= 0) return ""
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val n = Calendar.getInstance().apply { timeInMillis = now }
    val sameYear = c.get(Calendar.YEAR) == n.get(Calendar.YEAR)
    val dayDiff = n.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> SimpleDateFormat("HH:mm", ES).format(Date(millis))
        sameYear && dayDiff == 1 -> "ayer"
        sameYear -> SimpleDateFormat("d MMM", ES).format(Date(millis)).trimEnd('.')
        else -> SimpleDateFormat("d MMM yyyy", ES).format(Date(millis)).replace(".", "")
    }
}

fun formatFullDate(millis: Long): String =
    if (millis <= 0) "—" else SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm", ES).format(Date(millis))

fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"
