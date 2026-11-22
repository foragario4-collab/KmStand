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
 * Misst Fahrten direkt über den GPS-Chip (ohne Google-Dienste) – auch im Hintergrund.
 *
 * Zustände:
 *  - Fahrt läuft      → zählt zum Km-Stand
 *  - Fahrt erkannt    → zählt „auf Vorrat“, bis du Ja oder Nein drückst
 *  - Erkennung        → wartet, bis du schneller als 12 km/h fährst
 *  - Nein gedrückt    → zählt nichts, bis das Auto ein paar Minuten steht
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "at.kmstand.app.START"
        const val ACTION_STOP = "at.kmstand.app.STOP"
        const val ACTION_PAUSE = "at.kmstand.app.PAUSE"
        const val ACTION_RESUME = "at.kmstand.app.RESUME"
        const val ACTION_CONFIRM = "at.kmstand.app.CONFIRM"
        const val ACTION_REJECT = "at.kmstand.app.REJECT"

        private const val TRACK_MS = 1_000L                 // GPS jede Sekunde während einer Fahrt
        private const val DETECT_MS = 10_000L               // GPS alle 10 Sekunden zum Erkennen
        private const val DETECT_SPEED = 12.0 / 3.6         // ab 12 km/h gilt es als Fahrt
        private const val SLOW_SPEED = 2.0                  // unter ca. 7 km/h gilt es als Stehen/Gehen
        private const val END_AFTER_MS = 5L * 60_000        // 5 Min. Stehen → erkannte Fahrt ist zu Ende
        private const val IGNORE_RESET_MS = 3L * 60_000     // nach „Nein“: 3 Min. Stehen → wieder erkennen

        private fun startFg(c: Context, action: String?) {
            if (!Perms.hasFine(c)) {
                if (Store.isActive(c)) {
                    Live.error = "„Genauer Standort“ ist nicht erlaubt."
                    Live.changed()
                }
                Notifs.showIdle(c)
                return
            }
            try {
                val i = Intent(c, TrackingService::class.java)
                if (action != null) i.action = action
                ContextCompat.startForegroundService(c, i)
            } catch (e: Exception) {
                if (Store.isActive(c)) {
                    Live.error = "Aufzeichnung konnte nicht starten (${e.javaClass.simpleName})."
                    Live.changed()
                    Notifs.showStartReminder(c, true)
                } else {
                    Notifs.showIdle(c)
                }
            }
        }

        fun start(c: Context) = startFg(c, ACTION_START)
        fun resume(c: Context) = startFg(c, ACTION_RESUME)
        fun confirm(c: Context) = startFg(c, ACTION_CONFIRM)

        /** Messdienst passend zum Zustand starten/aktualisieren (oder beenden, wenn nicht gebraucht). */
        fun ensure(c: Context) = startFg(c, null)

        fun pause(c: Context) {
            try {
                c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_PAUSE))
            } catch (e: Exception) {
                Store.pauseTrip(c)
            }
        }

        fun reject(c: Context) {
            try {
                c.startService(Intent(c, TrackingService::class.java).setAction(ACTION_REJECT))
            } catch (e: Exception) {
                Store.rejectPending(c)
                Notifs.cancel(c, Notifs.ID_ASK)
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
    private var curInterval = 0L
    private var last: Location? = null
    private var lastNotif = 0L
    private var lastGpsAt = 0L

    // Erkennung
    private var hits = 0
    private var slowSince = 0L
    private var slowWall = 0L
    private var metersAtSlow = 0.0

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

    /** Absicherung (ab Android 12): nur benutzt, wenn der GPS-Chip länger nichts liefert. */
    private val fusedListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (SystemClock.elapsedRealtime() - lastGpsAt > curInterval + 4000) onLocation(location, false)
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
            if (Live.hasFix && SystemClock.elapsedRealtime() - Live.lastFixAt > curInterval + 10_000) {
                Live.hasFix = false
                Live.changed()
            }
            main.postDelayed(this, 5000)
        }
    }

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
        when (intent?.action) {
            ACTION_STOP -> {
                Store.endTrip(this)
                Notifs.cancel(this, Notifs.ID_STOP)
            }
            ACTION_START, ACTION_RESUME -> when {
                Store.isActive(this) -> Store.resumeTrip(this)
                Store.isPending(this) -> Store.confirmPending(this)
                else -> Store.startTrip(this)
            }
            ACTION_PAUSE -> Store.pauseTrip(this)
            ACTION_CONFIRM -> Store.confirmPending(this)
            ACTION_REJECT -> Store.rejectPending(this)
        }
        return update()
    }

    /** Bringt Dienst, GPS und Benachrichtigungen in den passenden Zustand. */
    private fun update(): Int {
        val active = Store.isActive(this)
        val pending = Store.isPending(this)
        val err = goForeground()
        val needed = active || pending || (Store.detectOn(this) && Perms.hasFine(this))

        if (!needed || err != null) {
            if (err != null && active) {
                Live.running = false
                Live.error = "Aufzeichnung konnte nicht starten ($err)."
                Live.changed()
            }
            shutdown()
            if (!active) Notifs.showIdle(this)
            if (!pending) Notifs.cancel(this, Notifs.ID_ASK)
            return START_NOT_STICKY
        }

        Notifs.cancel(this, Notifs.ID_START)
        Notifs.cancel(this, Notifs.ID_IDLE)

        when {
            active && Store.isPaused(this) -> stopLocation()
            active || (pending && !Store.pendEnded(this)) -> startLocation(TRACK_MS)
            else -> startLocation(DETECT_MS)
        }

        if (pending) Notifs.showAsk(this) else Notifs.cancel(this, Notifs.ID_ASK)

        main.removeCallbacks(notifTick)
        lastNotif = 0L
        main.post(notifTick)
        return START_STICKY
    }

    private fun goForeground(): String? = try {
        val n = Notifs.foreground(this)
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
    private fun startLocation(interval: Long) {
        if (!Perms.hasFine(this)) {
            if (Store.isActive(this)) {
                Live.error = "„Genauer Standort“ ist nicht erlaubt."
                Live.changed()
            }
            return
        }
        if (listening && interval == curInterval) {
            Live.running = true
            Live.changed()
            return
        }
        val fresh = !listening
        if (listening) {
            try { lm.removeUpdates(gpsListener) } catch (_: Exception) { }
            try { lm.removeUpdates(fusedListener) } catch (_: Exception) { }
        }
        if (fresh) {
            Live.reset()
            Live.startedAt = SystemClock.elapsedRealtime()
            last = null
            lastGpsAt = 0L
            hits = 0
            slowSince = 0L
        }
        curInterval = interval
        Live.gpsOff = !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, gpsListener, Looper.getMainLooper())
            listening = true
            Live.running = true
        } catch (e: Exception) {
            if (Store.isActive(this)) Live.error = "GPS konnte nicht gestartet werden (${e.javaClass.simpleName})."
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.FUSED_PROVIDER, interval, 0f, fusedListener, Looper.getMainLooper())
                    listening = true
                    Live.running = true
                    Live.error = null
                }
            } catch (_: Exception) { }
        }
        if (fresh) {
            try { lm.registerGnssStatusCallback(gnss, main) } catch (_: Exception) { }
            try {
                if (lm.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15_000L, 0f, netListener, Looper.getMainLooper())
                }
            } catch (_: Exception) { }
            main.removeCallbacks(watchdog)
            main.postDelayed(watchdog, 5000)
        }
        Live.changed()
    }

    private fun onLocation(l: Location, fromGps: Boolean) {
        val now = SystemClock.elapsedRealtime()
        Live.hasFix = true
        Live.viaGps = fromGps
        Live.lastFixAt = now
        Live.gpsOff = false

        val acc = if (l.hasAccuracy()) l.accuracy.toDouble() else 999.0
        Live.acc = acc.roundToInt()

        val prev = last
        val d = prev?.distanceTo(l)?.toDouble() ?: 0.0
        val dt = if (prev != null) max((l.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9, 0.5) else 1.0
        val speed = when {
            l.hasSpeed() -> l.speed.toDouble()
            prev != null -> d / dt
            else -> 0.0
        }
        if (speed < 70) Live.speed = (speed * 3.6).roundToInt()

        when {
            Store.isActive(this) -> {
                if (!Store.isPaused(this)) {
                    val m = accept(l, prev, acc, d, dt)
                    if (m > 0) Store.addDistance(this, m)
                }
            }
            Store.isPending(this) -> onPending(l, prev, acc, d, dt, speed, now)
            Store.isIgnoring(this) -> {
                last = l
                if (speed < SLOW_SPEED) {
                    if (slowSince == 0L) slowSince = now
                    else if (now - slowSince > IGNORE_RESET_MS) {
                        Store.setIgnoring(this, false)
                        slowSince = 0L
                    }
                } else {
                    slowSince = 0L
                }
            }
            Store.detectOn(this) -> detect(l, prev, acc, d, dt, speed)
            else -> last = l
        }
        Live.changed()
    }

    /** Erkennung: zwei Messungen hintereinander schneller als 12 km/h → Fahrt erkannt. */
    private fun detect(l: Location, prev: Location?, acc: Double, d: Double, dt: Double, speed: Double) {
        if (acc <= 50 && speed >= DETECT_SPEED && speed < 70) {
            hits++
            if (hits < 2 || prev == null) { last = l; return }
            val m = accept(l, prev, acc, d, dt)
            hits = 0
            slowSince = 0L
            if (Store.autoStart(this)) {
                Store.startTrip(this)
                if (m > 0) Store.addDistance(this, m)
            } else {
                Store.startPending(this, m)
            }
            last = l
            update()
        } else {
            hits = 0
            last = l
        }
    }

    /** Erkannte Fahrt: zählt auf Vorrat mit und merkt, wann das Auto steht. */
    private fun onPending(l: Location, prev: Location?, acc: Double, d: Double, dt: Double, speed: Double, now: Long) {
        if (Store.pendEnded(this)) {
            last = l
            if (acc <= 50 && speed >= DETECT_SPEED && speed < 70) {   // weitergefahren
                Store.resumePending(this)
                slowSince = 0L
                update()
            }
            return
        }
        val m = accept(l, prev, acc, d, dt)
        if (m > 0) Store.addPending(this, m)

        if (speed < SLOW_SPEED) {
            if (slowSince == 0L) {
                slowSince = now
                slowWall = System.currentTimeMillis()
                metersAtSlow = Store.pendMeters(this)
            } else if (now - slowSince > END_AFTER_MS) {
                // Strecke, die danach zu Fuß dazukam, wird nicht gezählt
                Store.endPending(this, slowWall, metersAtSlow)
                slowSince = 0L
                update()
            }
        } else {
            slowSince = 0L
        }
    }

    /** Filter gegen GPS-Sprünge und Zittern. Gibt die zählbare Strecke in Metern zurück. */
    private fun accept(l: Location, prev: Location?, acc: Double, d: Double, dt: Double): Double {
        if (acc > 35) { Live.weak = true; return 0.0 }
        Live.weak = false
        if (prev == null) { last = l; return 0.0 }
        if (d / dt > 70) { last = l; return 0.0 }   // Sprung über 250 km/h
        val standing = l.hasSpeed() && l.speed < 1.0f && d < 25
        val prevAcc = if (prev.hasAccuracy()) prev.accuracy.toDouble() else acc
        if (standing || d < max(6.0, (acc + prevAcc) / 3.0)) return 0.0
        last = l
        return d
    }

    @SuppressLint("MissingPermission")
    private fun refreshNotification() {
        val active = Store.isActive(this)
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val every = when {
            active && interactive -> 1_000L
            interactive -> 5_000L
            else -> 30_000L
        }
        val now = SystemClock.elapsedRealtime()
        if (lastNotif != 0L && now - lastNotif < every - 100) return
        lastNotif = now
        try {
            NotificationManagerCompat.from(this).notify(Notifs.ID_TRACK, Notifs.foreground(this))
        } catch (_: Exception) { }
        if (Store.isPending(this)) Notifs.showAsk(this)
    }

    private fun stopLocation() {
        if (listening) {
            try { lm.removeUpdates(gpsListener) } catch (_: Exception) { }
        }
        try { lm.removeUpdates(fusedListener) } catch (_: Exception) { }
        try { lm.removeUpdates(netListener) } catch (_: Exception) { }
        try { lm.unregisterGnssStatusCallback(gnss) } catch (_: Exception) { }
        listening = false
        curInterval = 0L
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
