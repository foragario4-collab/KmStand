package at.kmstand.app

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object Fmt {
    private val de = Locale.GERMANY
    val int: NumberFormat = NumberFormat.getIntegerInstance(de)
    fun km1(m: Double) = String.format(de, "%,.1f", m / 1000.0)
    fun km2(m: Double) = String.format(de, "%,.2f", m / 1000.0)
    fun dist(m: Double) = if (m < 1000) "${m.roundToInt()} m" else "${km2(m)} km"
    fun plainKm(m: Double) = String.format(Locale.US, "%.1f", m / 1000.0)
    fun date(t: Long): String = SimpleDateFormat("dd.MM.yyyy", de).format(Date(t))
    fun day(t: Long): String = SimpleDateFormat("EE dd.MM.", de).format(Date(t))
    fun time(t: Long): String = SimpleDateFormat("HH:mm", de).format(Date(t))

    /** Ganze Tage einer Dauer. */
    fun days(ms: Long): Int = (max(0L, ms / 1000) / 86400).toInt()

    fun daysText(d: Int) = if (d == 1) "1 Tag" else "$d Tage"

    /** Std:Min:Sek innerhalb des Tages, z. B. 0:12:47 */
    fun clock(ms: Long): String {
        val s = max(0L, ms / 1000) % 86400
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    /** Komplett, z. B. „0:12:47“ oder „1 Tag 2:05:33“ */
    fun duration(ms: Long): String {
        val d = days(ms)
        return if (d > 0) "${daysText(d)} ${clock(ms)}" else clock(ms)
    }
}
