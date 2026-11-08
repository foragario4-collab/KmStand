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
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Misst die Fahrt direkt über den GPS-Chip des Handys (ohne Google-Dienste) –
 * auch im Hintergrund und bei ausgeschaltetem Bildschirm.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "at.kmstand.app.START"
        const val ACTION_STOP = "at.kmstand.app.STOP"

        fun start(c: Context) {
            if (!Perms.hasFine(c)) {
                Live.error = "„Genauer Standort“ ist nicht erlaubt."
                Live.changed()
                return
            }
            try {
                ContextCompat.startForegroundService(
                    c, Intent(c, TrackingService::class.java).setAction(ACTION_START)
                )
            } catch (e: Exception) {
                Live.error = "Aufzeichnung konnte nicht starten (${e.javaClass.simpleName})."
                Live.changed()
                Notifs.showStartReminder(c, Store.isActive(c))
            }
        }

        fun stop(c: Context) {
            try {
                c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_STOP))
            } catch (e: Exception) {
                Store.endTrip(c)
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

    /**
     * Absicherung (ab Android 12): kombinierter Standort des Systems.
     * Wird nur benutzt, wenn der GPS-Chip seit 5 Sekunden nichts geliefert hat.
     */
    private val fusedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (SystemClock.elapsedRealtime() - lastGpsAt > 5000) onLocation(location, false)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** WLAN/Mobilfunk-Standort: nur zur Anzeige, wird NICHT zu den Kilometern gezählt. */
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

    /** Erkennt, wenn das GPS-Signal abreißt (z. B. Tunnel, Garage). */
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

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        Notifs.createChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.endTrip(this)
            Notifs.cancel(this, Notifs.ID_STOP)
            shutdown()
            return START_NOT_STICKY
        }

        if (!Store.isActive(this)) {
            if (intent?.action == ACTION_START) Store.startTrip(this)
            else { stopSelf(); return START_NOT_STICKY }   // System-Neustart ohne laufende Fahrt
        }

        val err = goForeground()
        if (err != null) {
            Live.running = false
            Live.error = "Aufzeichnung konnte nicht starten ($err)."
            Live.changed()
            stopSelf()
            return START_NOT_STICKY
        }
        Notifs.cancel(this, Notifs.ID_START)
        startLocation()
        return START_STICKY
    }

    private fun goForeground(): String? = try {
        val n = Notifs.tracking(this, Store.activeMeters(this))
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
        last = null
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
        Live.hasFix = true
        Live.viaGps = fromGps
        Live.lastFixAt = SystemClock.elapsedRealtime()
        Live.gpsOff = false

        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 999.0
        Live.acc = acc.roundToInt()
        if (l.hasSpeed()) Live.speed = (l.speed * 3.6f).roundToInt()

        // Ungenaue Punkte nicht zählen
        if (acc > 35) { Live.weak = true; Live.changed(); return }
        Live.weak = false

        val prev = last
        if (prev == null) { last = l; Live.changed(); return }

        val d = prev.distanceTo(l).toDouble()
        val dt = max((l.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9, 0.5)
        val v = d / dt

        // GPS-Sprung (über 250 km/h): Punkt verwerfen
        if (v > 70) { last = l; Live.changed(); return }

        // Stillstand (z. B. am Taxistand): GPS-Zittern nicht als Kilometer zählen
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
        updateNotification()
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 5000) return
        lastNotif = now
        try {
            NotificationManagerCompat.from(this)
                .notify(Notifs.ID_TRACK, Notifs.tracking(this, Store.activeMeters(this)))
        } catch (_: SecurityException) { }
    }

    private fun stopLocation() {
        if (listening) {
            try { lm.removeUpdates(gpsListener) } catch (_: Exception) { }
        }
        try { lm.removeUpdates(fusedListener) } catch (_: Exception) { }
        try { lm.removeUpdates(netListener) } catch (_: Exception) { }
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) { }
        listening = false
        main.removeCallbacks(watchdog)
        Live.running = false
        Live.hasFix = false
        Live.speed = 0
        Live.changed()
    }

    private fun shutdown() {
        stopLocation()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopLocation()
        super.onDestroy()
    }
}
