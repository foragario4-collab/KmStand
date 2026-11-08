package at.kmstand.app

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
}
