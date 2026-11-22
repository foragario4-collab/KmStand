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

    // ---------- Km-Stand ----------
    fun odoM(c: Context) = dbl(c, "odoM")
    fun hasOdo(c: Context) = prefs(c).getBoolean("hasOdo", false)
    fun setAt(c: Context) = prefs(c).getLong("setAt", 0L)

    // ---------- Laufende Fahrt ----------
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

    // ---------- Erkannte Fahrt (noch nicht bestätigt) ----------
    /** Erkannte Fahrt wartet auf Ja/Nein – ohne Fahrt oder während einer pausierten Fahrt. */
    fun isPending(c: Context) = prefs(c).getLong("pendStart", 0L) > 0L && (!isActive(c) || isPaused(c))

    /** Erkannte Fahrt gehört zu einer pausierten Fahrt („Pause beenden?“). */
    fun isPendingInPause(c: Context) = isPending(c) && isActive(c)
    fun pendStart(c: Context) = prefs(c).getLong("pendStart", 0L)
    fun pendMeters(c: Context) = dbl(c, "pendMeters")
    fun pendEnd(c: Context) = prefs(c).getLong("pendEnd", 0L)
    fun pendEnded(c: Context) = pendEnd(c) > 0L

    fun pendElapsedMs(c: Context): Long {
        if (!isPending(c)) return 0L
        val end = if (pendEnded(c)) pendEnd(c) else System.currentTimeMillis()
        return max(0L, end - pendStart(c))
    }

    /** Nach „Nein“: bis das Auto steht, nicht nochmal fragen. */
    fun isIgnoring(c: Context) = prefs(c).getBoolean("ignoring", false)
    fun setIgnoring(c: Context, v: Boolean) = prefs(c).edit().putBoolean("ignoring", v).apply()

    // ---------- Einstellungen ----------
    fun detectOn(c: Context) = prefs(c).getBoolean("detectOn", true)
    fun setDetectOn(c: Context, v: Boolean) = prefs(c).edit().putBoolean("detectOn", v).apply()

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

    private fun clearPendingKeys(e: SharedPreferences.Editor) = e
        .putLong("pendStart", 0L)
        .putString("pendMeters", "0")
        .putLong("pendEnd", 0L)

    @Synchronized
    fun startTrip(c: Context) {
        if (isActive(c)) return
        val e = prefs(c).edit()
            .putLong("activeStart", System.currentTimeMillis())
            .putString("activeFrom", odoM(c).toString())
            .putString("activeMeters", "0")
            .putLong("pausedAt", 0L)
            .putLong("pausedTotal", 0L)
            .putBoolean("ignoring", false)
        clearPendingKeys(e).apply()
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
        val e = p.edit()
            .putLong("pausedTotal", p.getLong("pausedTotal", 0L) + add)
            .putLong("pausedAt", 0L)
            .putBoolean("ignoring", false)
        clearPendingKeys(e).apply()
    }

    @Synchronized
    fun addDistance(c: Context, m: Double) {
        prefs(c).edit()
            .putString("odoM", (odoM(c) + m).toString())
            .putString("activeMeters", (activeMeters(c) + m).toString())
            .apply()
    }

    // ---------- Erkannte Fahrt ----------

    @Synchronized
    fun startPending(c: Context, meters: Double) {
        if ((isActive(c) && !isPaused(c)) || isPending(c)) return
        prefs(c).edit()
            .putLong("pendStart", System.currentTimeMillis())
            .putString("pendMeters", max(0.0, meters).toString())
            .putLong("pendEnd", 0L)
            .putBoolean("ignoring", false)
            .apply()
    }

    @Synchronized
    fun addPending(c: Context, m: Double) {
        if (!isPending(c)) return
        prefs(c).edit().putString("pendMeters", (pendMeters(c) + m).toString()).apply()
    }

    /** Auto steht seit einer Weile: erkannte Fahrt endet dort, wo es langsam wurde. */
    @Synchronized
    fun endPending(c: Context, endAt: Long, meters: Double) {
        if (!isPending(c)) return
        prefs(c).edit()
            .putLong("pendEnd", max(endAt, pendStart(c)))
            .putString("pendMeters", max(0.0, meters).toString())
            .apply()
    }

    @Synchronized
    fun resumePending(c: Context) {
        if (!isPending(c)) return
        prefs(c).edit().putLong("pendEnd", 0L).apply()
    }

    /**
     * „Ja“: die erkannten km kommen zum Km-Stand dazu.
     *  - Ohne Fahrt: daraus wird eine Fahrt (läuft weiter oder wird gleich gespeichert, wenn sie vorbei ist).
     *  - Während einer Pause: die Pause endet beim Losfahren, die km kommen zur Fahrt dazu.
     *    Ist die erkannte Strecke schon vorbei, bleibt die Fahrt ab dort wieder pausiert.
     */
    @Synchronized
    fun confirmPending(c: Context) {
        if (!isPending(c)) return
        val m = pendMeters(c)
        val start = pendStart(c)
        val end = pendEnd(c)
        val p = prefs(c)

        if (isActive(c)) {
            val pausedAt = p.getLong("pausedAt", start)
            val pausedTotal = p.getLong("pausedTotal", 0L) + max(0L, start - pausedAt)
            val e = p.edit()
                .putString("odoM", (odoM(c) + m).toString())
                .putString("activeMeters", (activeMeters(c) + m).toString())
                .putLong("pausedTotal", pausedTotal)
                .putLong("pausedAt", if (end > 0L) end else 0L)
                .putBoolean("ignoring", false)
            clearPendingKeys(e).commit()
            return
        }

        val from = odoM(c)
        val e = p.edit()
            .putString("odoM", (from + m).toString())
            .putLong("activeStart", start)
            .putString("activeFrom", from.toString())
            .putString("activeMeters", m.toString())
            .putLong("pausedAt", 0L)
            .putLong("pausedTotal", 0L)
            .putBoolean("ignoring", false)
        clearPendingKeys(e).commit()
        if (end > 0L) endTrip(c, end)
    }

    /** „Nein“: nichts wird gezählt. Fährt man noch, fragt die App bei dieser Fahrt nicht nochmal. */
    @Synchronized
    fun rejectPending(c: Context) {
        if (!isPending(c)) return
        val stillMoving = !pendEnded(c)
        val e = prefs(c).edit().putBoolean("ignoring", stillMoving)
        clearPendingKeys(e).apply()
    }

    /** Beendet die Fahrt und speichert sie immer – egal wie kurz. */
    @Synchronized
    fun endTrip(c: Context, endAt: Long? = null): Double {
        if (!isActive(c)) return 0.0
        val meters = activeMeters(c)
        val end = endAt ?: System.currentTimeMillis()
        val dur = if (endAt != null) max(0L, endAt - activeStart(c) - prefs(c).getLong("pausedTotal", 0L)) else elapsedMs(c)
        val trip = JSONObject()
            .put("start", activeStart(c))
            .put("end", end)
            .put("dur", dur)
            .put("fromM", activeFrom(c))
            .put("toM", odoM(c))
            .put("meters", meters)
        val old = trips(c)
        val arr = JSONArray().put(trip)
        for (i in 0 until min(old.length(), 999)) arr.put(old.get(i))
        tripPrefs(c).edit().putString("trips", arr.toString()).apply()
        val e = prefs(c).edit()
            .putLong("activeStart", 0L)
            .putString("activeMeters", "0")
            .putLong("pausedAt", 0L)
            .putLong("pausedTotal", 0L)
            .putBoolean("ignoring", false)
        clearPendingKeys(e).apply()
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
