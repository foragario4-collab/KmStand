package at.kmstand.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/** Alle Daten werden dauerhaft auf dem Handy gespeichert. */
object Store {
    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("kmstand", Context.MODE_PRIVATE)
    fun tripPrefs(c: Context): SharedPreferences = c.getSharedPreferences("kmstand_trips", Context.MODE_PRIVATE)

    private fun dbl(c: Context, k: String) = prefs(c).getString(k, null)?.toDoubleOrNull() ?: 0.0

    fun odoM(c: Context) = dbl(c, "odoM")
    fun hasOdo(c: Context) = prefs(c).getBoolean("hasOdo", false)
    fun setAt(c: Context) = prefs(c).getLong("setAt", 0L)
    fun isActive(c: Context) = prefs(c).getLong("activeStart", 0L) > 0L
    fun activeStart(c: Context) = prefs(c).getLong("activeStart", 0L)
    fun activeMeters(c: Context) = dbl(c, "activeMeters")
    private fun activeFrom(c: Context) = dbl(c, "activeFrom")

    fun isPaused(c: Context) = isActive(c) && prefs(c).getLong("pausedAt", 0L) > 0L

    /** Reine Fahrzeit in Millisekunden – Pausen werden nicht mitgezählt. */
    fun elapsedMs(c: Context): Long {
        if (!isActive(c)) return 0L
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val pausedAt = p.getLong("pausedAt", 0L)
        val pausedTotal = p.getLong("pausedTotal", 0L)
        val running = if (pausedAt > 0L) now - pausedAt else 0L
        return max(0L, now - activeStart(c) - pausedTotal - running)
    }

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
            .putLong("pausedAt", 0L)
            .putLong("pausedTotal", 0L)
            .apply()
    }

    @Synchronized
    fun pauseTrip(c: Context) {
        if (!isActive(c) || isPaused(c)) return
        prefs(c).edit().putLong("pausedAt", System.currentTimeMillis()).apply()
    }

    @Synchronized
    fun resumeTrip(c: Context) {
        if (!isPaused(c)) return
        val p = prefs(c)
        val add = max(0L, System.currentTimeMillis() - p.getLong("pausedAt", 0L))
        p.edit()
            .putLong("pausedTotal", p.getLong("pausedTotal", 0L) + add)
            .putLong("pausedAt", 0L)
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
            .put("dur", elapsedMs(c))
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
            .putLong("pausedAt", 0L)
            .putLong("pausedTotal", 0L)
            .apply()
        return meters
    }

    fun trips(c: Context): JSONArray {
        migrate(c)
        return try { JSONArray(tripPrefs(c).getString("trips", "[]")) } catch (e: Exception) { JSONArray() }
    }

    fun lastTripMeters(c: Context): Double? {
        val t = trips(c).optJSONObject(0) ?: return null
        return t.optDouble("meters", 0.0)
    }

    private fun migrate(c: Context) {
        val old = prefs(c).getString("trips", null) ?: return
        if (!tripPrefs(c).contains("trips")) tripPrefs(c).edit().putString("trips", old).commit()
        prefs(c).edit().remove("trips").apply()
    }
}
