package at.kmstand.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/** Alle Daten werden dauerhaft auf dem Handy gespeichert. */
object Store {
    /** Kleine Datei: Km-Stand und laufende Fahrt (wird während der Fahrt laufend gespeichert). */
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("kmstand", Context.MODE_PRIVATE)

    /** Eigene Datei für die Fahrtenliste, damit sie nicht jede Sekunde neu geschrieben wird. */
    fun tripPrefs(c: Context): SharedPreferences = c.getSharedPreferences("kmstand_trips", Context.MODE_PRIVATE)

    private fun dbl(c: Context, k: String) = prefs(c).getString(k, null)?.toDoubleOrNull() ?: 0.0

    fun odoM(c: Context) = dbl(c, "odoM")
    fun hasOdo(c: Context) = prefs(c).getBoolean("hasOdo", false)
    fun setAt(c: Context) = prefs(c).getLong("setAt", 0L)
    fun isActive(c: Context) = prefs(c).getLong("activeStart", 0L) > 0L
    fun activeStart(c: Context) = prefs(c).getLong("activeStart", 0L)
    fun activeMeters(c: Context) = dbl(c, "activeMeters")
    private fun activeFrom(c: Context) = dbl(c, "activeFrom")

    fun autoStart(c: Context) = prefs(c).getBoolean("autoStart", false)
    fun setAutoStart(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoStart", v).apply()

    fun wasAsked(c: Context, key: String) = prefs(c).getBoolean("asked_$key", false)
    fun markAsked(c: Context, key: String) = prefs(c).edit().putBoolean("asked_$key", true).apply()

    @Synchronized
    fun setOdometer(c: Context, meters: Double) {
        val e = prefs(c).edit()
            .putString("odoM", meters.toString())
            .putBoolean("hasOdo", true)
            .putLong("setAt", System.currentTimeMillis())
        if (isActive(c)) e.putString("activeFrom", (meters - activeMeters(c)).toString())
        e.apply()
    }

    @Synchronized
    fun startTrip(c: Context) {
        if (isActive(c)) return
        prefs(c).edit()
            .putLong("activeStart", System.currentTimeMillis())
            .putString("activeFrom", odoM(c).toString())
            .putString("activeMeters", "0")
            .apply()
    }

    @Synchronized
    fun addDistance(c: Context, m: Double) {
        prefs(c).edit()
            .putString("odoM", (odoM(c) + m).toString())
            .putString("activeMeters", (activeMeters(c) + m).toString())
            .apply()
    }

    /** Beendet die Fahrt und speichert sie immer – egal wie kurz. */
    @Synchronized
    fun endTrip(c: Context): Double {
        if (!isActive(c)) return 0.0
        val meters = activeMeters(c)
        val trip = JSONObject()
            .put("start", activeStart(c))
            .put("end", System.currentTimeMillis())
            .put("fromM", activeFrom(c))
            .put("toM", odoM(c))
            .put("meters", meters)
        val old = trips(c)
        val arr = JSONArray().put(trip)
        for (i in 0 until min(old.length(), 999)) arr.put(old.get(i))
        tripPrefs(c).edit().putString("trips", arr.toString()).apply()
        prefs(c).edit()
            .putLong("activeStart", 0L)
            .putString("activeMeters", "0")
            .apply()
        return meters
    }

    fun trips(c: Context): JSONArray {
        migrate(c)
        return try { JSONArray(tripPrefs(c).getString("trips", "[]")) } catch (e: Exception) { JSONArray() }
    }

    /** Fahrten aus Version 1.0 in die neue Datei übernehmen. */
    private fun migrate(c: Context) {
        val old = prefs(c).getString("trips", null) ?: return
        if (!tripPrefs(c).contains("trips")) tripPrefs(c).edit().putString("trips", old).commit()
        prefs(c).edit().remove("trips").apply()
    }
}
