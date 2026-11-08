package at.kmstand.app

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Misst die Fahrt direkt über den GPS-Chip (ohne Google-Dienste) – auch im Hintergrund.
 * Steuerbar über App und Benachrichtigung: Start, Pause, Fortsetzen, Beenden.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "at.kmstand.app.START"
        const val ACTION_STOP = "at.kmstand.app.STOP"
        const val ACTION_PAUSE = "at.kmstand.app.PAUSE"
        const val ACTION_RESUME = "at.kmstand.app.RESUME"

        private fun startFg(c: Context, action: String?) {
            if (!Perms.hasFine(c)) {
                Live.error = "„Genauer Standort“ ist nicht erlaubt."
                Live.changed()
                return
            }
            try {
                val i = Intent(c, TrackingService::class.java)
                if (action != null) i.action = action
                ContextCompat.startForegroundService(c, i)
            } catch (e: Exception) {
                Live.error = "Aufzeichnung konnte nicht starten (${e.javaClass.simpleName})."
                Live.changed()
                Notifs.showStartReminder(c, Store.isActive(c))
            }
        }

        /** Neue Fahrt starten (oder pausierte fortsetzen). */
        fun start(c: Context) = startFg(c, ACTION_START)

        fun resume(c: Context) = startFg(c, ACTION_RESUME)

        /** Sicherstellen, dass eine laufende Fahrt auch gemessen wird – ändert nichts am Zustand. */
        fun ensure(c: Context) = startFg(c, null)

        fun pause(c: Context) {
            try {
                c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_PAUSE))
            } catch (e: Exception) {
                Store.pauseTrip(c)
            }
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Store.endTrip(c)
                Notifs.showIdle(c)
            }
        }
    }

    private lateinit var lm: LocationManager
    private val main = Handler(Looper.getMainLooper())
    private var listening = false
    private var last: Location? = null
    private var lastNotif = 0L
    private var lastGpsAt = 0L

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastGpsAt = SystemClock.elapsedRealtime()
            onLocation(location, true)
        }
        override fun onProviderEnabled(provider: String) { Live.gpsOff = false; Live.changed() }
        override fun onProviderDisabled(provider: String) { Live.gpsOff = true; Live.hasFix = false; Live.changed() }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Absicherung (ab Android 12): nur benutzt, wenn der GPS-Chip 5 Sekunden nichts liefert. */
    private val fusedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (SystemClock.elapsedRealtime() - lastGpsAt > 5000) onLocation(location, false)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** WLAN/Mobilfunk-Standort: nur zur Anzeige, zählt NICHT zu den Kilometern. */
    private val netListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!Live.netFix) { Live.netFix = true; Live.changed() }
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            Live.satsUsed = used
            Live.satsSeen = status.satelliteCount
            Live.changed()
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (!listening) return
            if (Live.hasFix && SystemClock.elapsedRealtime() - Live.lastFixAt > 10_000) {
                Live.hasFix = false
                Live.changed()
            }
            main.postDelayed(this, 5000)
        }
    }

    /** Aktualisiert den Timer in der Benachrichtigung jede Sekunde (bei Bildschirm aus nur selten). */
    private val notifTick = object : Runnable {
        override fun run() {
            refreshNotification()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Notifs.createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            Store.endTrip(this)
            Notifs.cancel(this, Notifs.ID_STOP)
            shutdown()
            Notifs.showIdle(this)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START, ACTION_RESUME ->
                if (Store.isActive(this)) Store.resumeTrip(this) else Store.startTrip(this)
            ACTION_PAUSE ->
                if (Store.isActive(this)) Store.pauseTrip(this)
        }

        if (!Store.isActive(this)) { stopSelf(); return START_NOT_STICKY }

        val err = goForeground()
        if (err != null) {
            Live.running = false
            Live.error = "Aufzeichnung konnte nicht starten ($err)."
            Live.changed()
            stopSelf()
            return START_NOT_STICKY
        }
        Notifs.cancel(this, Notifs.ID_START)
        Notifs.cancel(this, Notifs.ID_IDLE)

        if (Store.isPaused(this)) stopLocation() else startLocation()

        main.removeCallbacks(notifTick)
        lastNotif = 0L
        main.post(notifTick)
        return START_STICKY
    }

    private fun goForeground(): String? = try {
        val n = Notifs.tracking(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_TRACK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(Notifs.ID_TRACK, n)
        }
        null
    } catch (e: Exception) {
        e.javaClass.simpleName
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (listening) { Live.running = true; Live.changed(); return }
        if (!Perms.hasFine(this)) {
            Live.error = "„Genauer Standort“ ist nicht erlaubt."
            Live.changed()
            return
        }
        Live.reset()
        Live.startedAt = SystemClock.elapsedRealtime()
        Live.gpsOff = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        last = null          // nach Pause: Strecke während der Pause wird nicht gezählt
        lastGpsAt = 0L
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, gpsListener, Looper.getMainLooper())
            listening = true
            Live.running = true
        } catch (e: Exception) {
            Live.error = "GPS konnte nicht gestartet werden (${e.javaClass.simpleName})."
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, 1000L, 0f, fusedListener, Looper.getMainLooper())
                    listening = true
                    Live.running = true
                    Live.error = null
                }
            } catch (_: Exception) { }
        }
        try { lm.registerGnssStatusCallback(gnss, main) } catch (_: Exception) { }
        try {
            if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, netListener, Looper.getMainLooper())
            }
        } catch (_: Exception) { }
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 5000)
        Live.changed()
    }

    private fun onLocation(l: Location, fromGps: Boolean) {
        if (Store.isPaused(this)) return
        Live.hasFix = true
        Live.viaGps = fromGps
        Live.lastFixAt = SystemClock.elapsedRealtime()
        Live.gpsOff = false

        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 999.0
        Live.acc = acc.roundToInt()
        if (l.hasSpeed()) Live.speed = (l.speed * 3.6f).roundToInt()

        if (acc > 35) { Live.weak = true; Live.changed(); return }
        Live.weak = false

        val prev = last
        if (prev == null) { last = l; Live.changed(); return }

        val d = prev.distanceTo(l).toDouble()
        val dt = max((l.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9, 0.5)
        val v = d / dt

        // GPS-Sprung (über 250 km/h): Punkt verwerfen
        if (v > 70) { last = l; Live.changed(); return }

        // Stillstand: GPS-Zittern nicht als Kilometer zählen
        val standing = l.hasSpeed() && l.speed < 1.0f && d < 25
        val prevAcc = if (prev.hasAccuracy()) prev.accuracy.toDouble() else acc
        if (standing || d < max(6.0, (acc + prevAcc) / 3.0)) {
            if (!l.hasSpeed()) Live.speed = 0
            Live.changed()
            return
        }

        Store.addDistance(this, d)
        last = l
        if (!l.hasSpeed()) Live.speed = (v * 3.6).roundToInt()
        Live.changed()
    }

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        if (!Store.isActive(this)) return
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val now = SystemClock.elapsedRealtime()
        if (!interactive && now - lastNotif < 30_000) return
        lastNotif = now
        try {
            NotificationManagerCompat.from(this).notify(Notifs.ID_TRACK, Notifs.tracking(this))
        } catch (_: Exception) { }
    }

    private fun stopLocation() {
        if (listening) {
            try { lm.removeUpdates(gpsListener) } catch (_: Exception) { }
        }
        try { lm.removeUpdates(fusedListener) } catch (_: Exception) { }
        try { lm.removeUpdates(netListener) } catch (_: Exception) { }
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) { }
        listening = false
        last = null
        main.removeCallbacks(watchdog)
        Live.running = false
        Live.hasFix = false
        Live.speed = 0
        Live.changed()
    }

    private fun shutdown() {
        main.removeCallbacks(notifTick)
        stopLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(notifTick)
        stopLocation()
        super.onDestroy()
    }
}
