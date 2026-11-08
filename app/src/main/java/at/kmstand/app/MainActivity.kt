package at.kmstand.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var tvInt: TextView
    private lateinit var tvDec: TextView
    private lateinit var tvSince: TextView
    private lateinit var tvTrip: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnGo: TextView
    private lateinit var tvStatus: TextView
    private lateinit var chip: View
    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var setupCard: View
    private lateinit var setupList: LinearLayout
    private lateinit var swAuto: SwitchCompat
    private lateinit var tripList: LinearLayout

    private val handler = Handler(Looper.getMainLooper())
    private var note: String? = null
    private var pendingStart = false

    private val liveListener: () -> Unit = { renderLive() }

    /** Jede Sekunde: Dauer und Status aktualisieren. */
    private val ticker = object : Runnable {
        override fun run() {
            renderDuration()
            renderLive()
            handler.postDelayed(this, 1000)
        }
    }

    private val basePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Transitions.register(this)
        if (pendingStart) {
            pendingStart = false
            if (Perms.hasFine(this)) startTrip()
            else if (Perms.hasCoarse(this)) note = "Bitte „Genauer Standort“ erlauben – mit „Ungefähr“ kann die App keine Kilometer messen."
        }
        renderAll()
    }

    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { renderAll() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInt = findViewById(R.id.odoInt)
        tvDec = findViewById(R.id.odoDec)
        tvSince = findViewById(R.id.since)
        tvTrip = findViewById(R.id.tripKm)
        tvSpeed = findViewById(R.id.speed)
        tvDuration = findViewById(R.id.duration)
        btnGo = findViewById(R.id.btnGo)
        tvStatus = findViewById(R.id.status)
        chip = findViewById(R.id.chip)
        chipDot = findViewById(R.id.chipDot)
        chipText = findViewById(R.id.chipText)
        setupCard = findViewById(R.id.setupCard)
        setupList = findViewById(R.id.setupList)
        swAuto = findViewById(R.id.swAuto)
        tripList = findViewById(R.id.tripList)

        Notifs.createChannels(this)

        findViewById<TextView>(R.id.appVersion).text = "v" + appVersion()

        findViewById<View>(R.id.btnEdit).setOnClickListener { showEdit(false) }
        btnGo.setOnClickListener { onGo() }

        swAuto.isChecked = Store.autoStart(this)
        swAuto.setOnCheckedChangeListener { _, checked ->
            Store.setAutoStart(this, checked)
            note = if (checked && !Perms.hasBackground(this))
                "Für den Automatik-Modus brauchst du Standort „Immer zulassen“." else null
            renderAll()
        }

        if (!Perms.hasFine(this)) requestBasePerms()
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(this)
        Store.tripPrefs(this).registerOnSharedPreferenceChangeListener(this)
        Live.listen(liveListener)
        Transitions.register(this)
        // Fahrt läuft laut Speicher, aber die Messung nicht (z. B. nach Absturz/Neustart) → neu starten
        if (Store.isActive(this) && !Live.running) TrackingService.start(this)
        renderAll()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        Store.tripPrefs(this).unregisterOnSharedPreferenceChangeListener(this)
        Live.unlisten(liveListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "trips") renderTrips() else renderMain()
    }

    // ---------- Aktionen ----------

    private fun requestBasePerms() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 29) list += Manifest.permission.ACTIVITY_RECOGNITION
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        Store.markAsked(this, "base")
        basePerms.launch(list.toTypedArray())
    }

    private fun onGo() {
        if (Store.isActive(this)) {
            val m = Store.activeMeters(this)
            TrackingService.stop(this)
            Live.error = null
            note = "Fahrt gespeichert: ${Fmt.dist(m)}"
            renderAll()
            return
        }
        if (!Store.hasOdo(this)) { showEdit(true); return }
        startTrip()
    }

    private fun startTrip() {
        if (!Perms.hasFine(this)) { pendingStart = true; requestBasePerms(); return }
        if (!Perms.locationOn(this)) {
            note = "Der Standort ist am Handy ausgeschaltet. Bitte zuerst einschalten."
            renderAll()
            openLocationSettings()
            return
        }
        note = null
        Live.error = null
        TrackingService.start(this)
    }

    private fun showEdit(thenStart: Boolean) {
        val input = EditText(this).apply {
            keyListener = DigitsKeyListener.getInstance("0123456789.,")
            setRawInputType(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            hint = "z. B. 84520"
            textSize = 24f
            if (Store.hasOdo(this@MainActivity)) setText(Fmt.plainKm(Store.odoM(this@MainActivity)))
            setSelectAllOnFocus(true)
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }

        AlertDialog.Builder(this)
            .setTitle("Kilometerstand eingeben")
            .setMessage("Den Stand vom Tacho deines Fahrzeugs. Gefahrene Kilometer werden ab jetzt automatisch dazugezählt.")
            .setView(box)
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Speichern") { _, _ ->
                val v = input.text.toString().replace(",", ".").toDoubleOrNull()
                if (v != null && v >= 0) {
                    Store.setOdometer(this, v * 1000.0)
                    note = "Kilometerstand gespeichert"
                    renderAll()
                    if (thenStart) startTrip()
                }
            }
            .show()
        input.requestFocus()
    }

    @Suppress("DEPRECATION")
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openLocationSettings() {
        try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { openAppSettings() }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------- Anzeige ----------

    private fun renderAll() {
        renderMain()
        renderLive()
        renderDuration()
        renderTrips()
    }

    /** Km-Stand, Fahrt-Kilometer, Button, Status-Badge, Einrichtung. */
    private fun renderMain() {
        val odo = Store.odoM(this)
        val tenths = floor(odo / 100.0).toLong()
        tvInt.text = Fmt.int.format(tenths / 10)
        tvDec.text = ",${tenths % 10}"
        tvSince.text = if (Store.hasOdo(this)) "Eingestellt am ${Fmt.date(Store.setAt(this))}" else "Noch nicht eingestellt"

        val active = Store.isActive(this)
        tvTrip.text = Fmt.km2(if (active) Store.activeMeters(this) else 0.0)

        btnGo.text = if (active) "Fahrt beenden" else "Fahrt starten"
        btnGo.setBackgroundResource(if (active) R.drawable.bg_btn_stop else R.drawable.bg_btn_start)
        btnGo.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (active) R.drawable.ic_stop else R.drawable.ic_play, 0, 0, 0
        )

        chip.setBackgroundResource(if (active) R.drawable.bg_chip_live else R.drawable.bg_chip)
        chipDot.setBackgroundResource(if (active) R.drawable.dot_live else R.drawable.dot_idle)
        chipText.text = if (active) "Aufzeichnung" else "Bereit"
        chipText.setTextColor(ContextCompat.getColor(this, if (active) R.color.go else R.color.muted))

        swAuto.isEnabled = Perms.hasPlayServices(this)
        renderSetup()
    }

    /** Status-Zeile und Tempo – zeigt genau, woran es gerade hängt. */
    private fun renderLive() {
        val active = Store.isActive(this)
        tvSpeed.text = if (active && Live.running) Live.speed.toString() else "0"

        var color = R.color.muted
        val text: String = when {
            !active -> note ?: if (!Store.hasOdo(this)) "Gib zuerst deinen aktuellen Kilometerstand ein." else ""
            Live.error != null -> { color = R.color.stop; Live.error ?: "" }
            !Perms.locationOn(this) || Live.gpsOff -> {
                color = R.color.stop; "Standort/GPS ist am Handy ausgeschaltet. Bitte einschalten."
            }
            !Live.running -> { color = R.color.warn; "Aufzeichnung wird gestartet …" }
            !Live.hasFix -> {
                color = R.color.warn
                val sats = if (Live.satsSeen == 0) "noch keine Satelliten" else "${Live.satsUsed} von ${Live.satsSeen} Satelliten"
                val base = if (Live.netFix) "Standort gefunden, warte auf GPS ($sats)" else "Suche GPS-Signal … ($sats)"
                val waited = SystemClock.elapsedRealtime() - Live.startedAt
                if (Live.startedAt > 0 && waited > 45_000)
                    "$base\nIm Gebäude findet GPS oft kein Signal – im Auto oder draußen klappt es."
                else base
            }
            Live.weak -> { color = R.color.warn; "Schwaches GPS-Signal (±${Live.acc} m)" }
            !Live.viaGps -> "Standort aktiv (Netz/WLAN) · ±${Live.acc} m"
            else -> "GPS aktiv · ±${Live.acc} m · ${Live.satsUsed} Satelliten"
        }
        if (tvStatus.text.toString() != text) tvStatus.text = text
        tvStatus.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun renderDuration() {
        val s = if (Store.isActive(this)) max(0L, (System.currentTimeMillis() - Store.activeStart(this)) / 1000) else 0L
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        tvDuration.text = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    private fun renderSetup() {
        setupList.removeAllViews()
        fun item(title: String, sub: String, label: String = "Erlauben", action: (() -> Unit)? = null) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_setup, setupList, false)
            v.findViewById<TextView>(R.id.title).text = title
            v.findViewById<TextView>(R.id.sub).text = sub
            val a = v.findViewById<TextView>(R.id.action)
            if (action == null) a.visibility = View.GONE
            else { a.text = label; a.setOnClickListener { action() } }
            setupList.addView(v)
        }

        if (!Perms.locationOn(this))
            item("Standort einschalten", "Der Standort ist am Handy ausgeschaltet.", "Einschalten") { openLocationSettings() }

        if (!Perms.hasFine(this)) {
            if (Perms.hasCoarse(this))
                item("Genauer Standort", "Du hast nur „Ungefähr“ erlaubt. Zum Messen braucht die App „Genau“.") {
                    if (!Store.wasAsked(this, "fine")) { Store.markAsked(this, "fine"); requestBasePerms() }
                    else openAppSettings()
                }
            else
                item("Standort", "Damit Kilometer gemessen werden können.") {
                    if (Store.wasAsked(this, "base") && !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) openAppSettings()
                    else requestBasePerms()
                }
        } else if (!Perms.hasBackground(this)) {
            item("Standort „Immer zulassen“", "Für Messung im Hintergrund und den Automatik-Modus.") {
                if (Build.VERSION.SDK_INT >= 29 && !Store.wasAsked(this, "bg")) {
                    Store.markAsked(this, "bg"); bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else openAppSettings()
            }
        }

        if (Perms.hasPlayServices(this) && !Perms.hasActivity(this))
            item("Fahrterkennung", "Erkennt, wenn du losfährst, und erinnert dich.") {
                if (Build.VERSION.SDK_INT >= 29 && Store.wasAsked(this, "base") &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.ACTIVITY_RECOGNITION)) openAppSettings()
                else requestBasePerms()
            }

        if (!Perms.hasNotif(this))
            item("Benachrichtigungen", "Für Erinnerungen und die Anzeige während der Fahrt.") {
                if (Build.VERSION.SDK_INT >= 33 && Store.wasAsked(this, "base") &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openAppSettings()
                else requestBasePerms()
            }

        if (!Perms.ignoresBattery(this))
            item("Akku-Optimierung aus", "Damit Android die Messung nicht beendet.", "Ändern") { askBattery() }

        if (!Perms.hasPlayServices(this))
            item("Fahrterkennung nicht verfügbar", "Dein Handy hat keine Google-Dienste. Messen klappt trotzdem – starte die Fahrt dann selbst.")

        setupCard.visibility = if (setupList.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun renderTrips() {
        tripList.removeAllViews()
        val trips = Store.trips(this)
        if (trips.length() == 0) {
            tripList.addView(LayoutInflater.from(this).inflate(R.layout.item_empty, tripList, false))
            return
        }
        val n = minOf(trips.length(), 50)
        for (i in 0 until n) {
            val t = trips.getJSONObject(i)
            val v = LayoutInflater.from(this).inflate(R.layout.item_trip, tripList, false)
            val start = t.optLong("start")
            val end = t.optLong("end")
            v.findViewById<TextView>(R.id.tripWhen).text = "${Fmt.day(start)}  ${Fmt.time(start)}–${Fmt.time(end)}"
            v.findViewById<TextView>(R.id.meta).text = "${Fmt.km1(t.optDouble("fromM"))} → ${Fmt.km1(t.optDouble("toM"))} km"
            v.findViewById<TextView>(R.id.dist).text = Fmt.dist(t.optDouble("meters"))
            if (i == n - 1) v.findViewById<View>(R.id.divider).visibility = View.GONE
            tripList.addView(v)
        }
    }
}
