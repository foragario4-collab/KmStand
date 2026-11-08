package at.kmstand.app

/** Live-Werte der laufenden Messung. Nur im Speicher, damit nichts unnötig auf den Speicher geschrieben wird. */
object Live {
    var running = false      // Dienst läuft und hört auf GPS
    var hasFix = false       // Es kommen gerade Positionen an
    var viaGps = false       // letzte Position kam direkt vom GPS-Chip
    var netFix = false       // Standort per WLAN/Mobilfunk gefunden (nur zur Anzeige)
    var gpsOff = false
    var speed = 0
    var acc = 0
    var weak = false
    var satsUsed = 0
    var satsSeen = 0
    var error: String? = null
    var lastFixAt = 0L
    var startedAt = 0L

    private val listeners = mutableListOf<() -> Unit>()
    fun listen(l: () -> Unit) { if (l !in listeners) listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }
    fun changed() { listeners.toList().forEach { it() } }

    fun reset() {
        hasFix = false; viaGps = false; netFix = false; gpsOff = false; speed = 0; acc = 0; weak = false
        satsUsed = 0; satsSeen = 0; error = null; lastFixAt = 0L; startedAt = 0L
    }
}
