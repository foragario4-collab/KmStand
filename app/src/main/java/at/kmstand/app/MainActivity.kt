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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.floor

class MainActivity : AppCompatActivity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var tvInt: TextView
    private lateinit var tvDec: TextView
    private lateinit var tvSince: TextView
    private lateinit var tvTrip: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDays: TextView
    private lateinit var tvClock: TextView
    private lateinit var btnMain: View
    private lateinit var btnMainIcon: ImageView
    private lateinit var btnMainText: TextView
    private lateinit var btnStop: View
    private lateinit var tvStatus: TextView
    private lateinit var chip: View
    private lateinit var chipDot: View
    private lateinit var chipText: TextView
    private lateinit var setupCard: View
    private lateinit var setupList: LinearLayout
    private lateinit var swDetect: SwitchCompat
    private lateinit var tripList: LinearLayout
    private lateinit var updText: TextView
    private lateinit var updAction: TextView
    private lateinit var pendCard: View
    private lateinit var pendText: TextView
    private lateinit var pendTitle: TextView
    private lateinit var pendYes: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var note: String? = null
    private var pendingStart = false

    private val liveListener: () -> Unit = { renderLive() }
    private val updListener: () -> Unit = { renderUpdate() }

    /** Jede Sekunde: Timer, Status und erkannte Fahrt aktualisieren. */
    private val ticker = object : Runnable {
        override fun run() {
            renderDuration()
            renderLive()
            renderPending()
            handler.postDelayed(this, 1000)
        }
    }

    private val basePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (pendingStart) {
            pendingStart = false
            if (Perms.hasFine(this)) startTrip()
            else if (Perms.hasCoarse(this)) note = "Bitte „Genauer Standort“ erlauben – mit „Ungefähr“ kann die App keine Kilometer messen."
        }
        TrackingService.ensure(this)
        renderAll()
    }

    private val bgPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        TrackingService.ensure(this)
        renderAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvInt = findViewById(R.id.odoInt)
        tvDec = findViewById(R.id.odoDec)
        tvSince = findViewById(R.id.since)
        tvTrip = findViewById(R.id.tripKm)
        tvSpeed = findViewById(R.id.speed)
        tvDays = findViewById(R.id.tvDays)
        tvClock = findViewById(R.id.tvClock)
        btnMain = findViewById(R.id.btnMain)
        btnMainIcon = findViewById(R.id.btnMainIcon)
        btnMainText = findViewById(R.id.btnMainText)
        btnStop = findViewById(R.id.btnStop)
        tvStatus = findViewById(R.id.status)
        chip = findViewById(R.id.chip)
        chipDot = findViewById(R.id.chipDot)
        chipText = findViewById(R.id.chipText)
        setupCard = findViewById(R.id.setupCard)
        setupList = findViewById(R.id.setupList)
        swDetect = findViewById(R.id.swDetect)
        tripList = findViewById(R.id.tripList)
        updText = findViewById(R.id.updText)
        updAction = findViewById(R.id.updAction)
        pendCard = findViewById(R.id.pendCard)
        pendText = findViewById(R.id.pendText)
        pendTitle = findViewById(R.id.pendTitle)
        pendYes = findViewById(R.id.pendYes)

        Notifs.createChannels(this)

        findViewById<TextView>(R.id.appVersion).text = "v" + appVersion()
        findViewById<View>(R.id.btnEdit).setOnClickListener { showEdit(false) }

        btnMain.clipToOutline = true
        btnStop.clipToOutline = true
        btnMain.setOnClickListener { onMain() }
        btnStop.setOnClickListener { onStopTrip() }

        pendYes.setOnClickListener {
            val m = Store.pendMeters(this)
            note = "${Fmt.dist(m)} zum Km-Stand dazugezählt."
            TrackingService.confirm(this)
        }
        findViewById<View>(R.id.pendNo).setOnClickListener {
            note = if (Store.isPendingInPause(this)) "Bleibt pausiert – diese Strecke wird nicht gezählt."
                   else "Fahrt wird nicht gezählt."
            TrackingService.reject(this)
        }

        swDetect.isChecked = Store.detectOn(this)
        swDetect.setOnCheckedChangeListener { _, checked ->
            Store.setDetectOn(this, checked)
            note = if (checked && !Perms.hasBackground(this))
                "Tipp: Standort „Immer zulassen“, damit die Erkennung auch bei geschlossener App läuft." else null
            TrackingService.ensure(this)
            renderAll()
        }

        updAction.setOnClickListener { onUpdateAction() }

        if (!Perms.hasFine(this)) requestBasePerms()
    }

    override fun onResume() {
        super.onResume()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(this)
        Store.tripPrefs(this).registerOnSharedPreferenceChangeListener(this)
        Live.listen(liveListener)
        Updater.listen(updListener)
        if (Perms.hasFine(this)) TrackingService.ensure(this) else Notifs.showIdle(this)
        if (Updater.shouldAutoCheck(this)) Updater.check(this, true)
        renderAll()
        renderUpdate()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        Store.tripPrefs(this).unregisterOnSharedPreferenceChangeListener(this)
        Live.unlisten(liveListener)
        Updater.unlisten(updListener)
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
        if (key == "trips") renderTrips() else { renderMain(); renderPending() }
    }

    // ---------- Aktionen ----------

    private fun requestBasePerms() {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        Store.markAsked(this, "base")
        basePerms.launch(list.toTypedArray())
    }

    /** Linker Knopf: Starten / Pause / Fortsetzen – je nach Zustand. */
    private fun onMain() {
        when {
            Store.isPending(this) -> TrackingService.confirm(this)
            !Store.isActive(this) -> if (!Store.hasOdo(this)) showEdit(true) else startTrip()
            Store.isPaused(this) -> { note = null; TrackingService.resume(this) }
            else -> TrackingService.pause(this)
        }
    }

    private fun onStopTrip() {
        if (!Store.isActive(this)) return
        val m = Store.activeMeters(this)
        val dur = Store.elapsedMs(this)
        TrackingService.stop(this)
        Live.error = null
        note = "Fahrt gespeichert: ${Fmt.dist(m)} in ${Fmt.duration(dur)}"
        renderAll()
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

    // ---------- App-Update ----------

    private fun onUpdateAction() {
        when (val s = Updater.state) {
            is Updater.State.Available -> Updater.download(this, s.r)
            is Updater.State.Ready -> {
                if (!s.file.exists()) { Updater.download(this, s.r); return }
                if (!Updater.canInstall(this)) {
                    Toast.makeText(this, "Erlaube „Apps installieren“ für Km-Stand und tippe dann nochmal auf Installieren.", Toast.LENGTH_LONG).show()
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                    } catch (_: Exception) { openAppSettings() }
                    return
                }
                try { Updater.install(this, s.file) } catch (e: Exception) {
                    Toast.makeText(this, "Installation konnte nicht geöffnet werden.", Toast.LENGTH_LONG).show()
                }
            }
            is Updater.State.Downloading, is Updater.State.Checking -> { }
            else -> Updater.check(this, false)
        }
    }

    private fun renderUpdate() {
        val cur = "Installiert: v${appVersion()}"
        when (val s = Updater.state) {
            is Updater.State.Idle -> upd(cur, "Prüfen", false)
            is Updater.State.Checking -> upd("Suche nach Updates …", null, false)
            is Updater.State.UpToDate -> upd("$cur\nDu hast die neueste Version.", "Prüfen", false)
            is Updater.State.Available -> {
                val mb = if (s.r.size > 0) String.format(Locale.GERMANY, " · %.1f MB", s.r.size / 1_000_000.0) else ""
                upd("Neu: ${s.r.name}$mb", "Laden", true)
            }
            is Updater.State.Downloading -> upd("Wird geladen … ${s.percent} %", null, false)
            is Updater.State.Ready -> upd("${s.r.name} ist bereit.", "Installieren", true)
            is Updater.State.Failed -> upd(s.msg, "Nochmal", false)
        }
    }

    private fun upd(text: String, action: String?, primary: Boolean) {
        updText.text = text
        if (action == null) { updAction.visibility = View.GONE; return }
        updAction.visibility = View.VISIBLE
        updAction.text = action
        if (primary) {
            updAction.setBackgroundResource(R.drawable.bg_btn_start)
            updAction.setTextColor(color(R.color.on_accent))
        } else {
            updAction.background = null
            updAction.setTextColor(color(R.color.link))
        }
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

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun renderAll() {
        renderMain()
        renderPending()
        renderLive()
        renderDuration()
        renderTrips()
    }

    private fun renderMain() {
        val odo = Store.odoM(this)
        val tenths = floor(odo / 100.0).toLong()
        tvInt.text = Fmt.int.format(tenths / 10)
        tvDec.text = ",${tenths % 10}"
        tvSince.text = if (Store.hasOdo(this)) "Eingestellt am ${Fmt.date(Store.setAt(this))}" else "Noch nicht eingestellt"

        val active = Store.isActive(this)
        val paused = Store.isPaused(this)
        val pending = Store.isPending(this)
        tvTrip.text = Fmt.km2(if (active) Store.activeMeters(this) else 0.0)

        when {
            pending && active -> mainButton(R.drawable.bg_btn_start, R.drawable.ic_play, "Fortsetzen", color(R.color.on_accent))
            pending -> mainButton(R.drawable.bg_btn_start, R.drawable.ic_play, "Fahrt starten", color(R.color.on_accent))
            !active -> mainButton(R.drawable.bg_btn_start, R.drawable.ic_play, "Fahrt starten", color(R.color.on_accent))
            paused -> mainButton(R.drawable.bg_btn_start, R.drawable.ic_play, "Fortsetzen", color(R.color.on_accent))
            else -> mainButton(R.drawable.bg_btn_glass, R.drawable.ic_pause, "Pause", color(R.color.ink))
        }
        btnStop.visibility = if (active) View.VISIBLE else View.GONE

        when {
            pending -> chip(R.drawable.bg_chip_pause, R.drawable.dot_pause, "Fahrt erkannt", R.color.warn)
            !active -> chip(R.drawable.bg_chip, R.drawable.dot_idle, "Bereit", R.color.muted)
            paused -> chip(R.drawable.bg_chip_pause, R.drawable.dot_pause, "Pausiert", R.color.warn)
            else -> chip(R.drawable.bg_chip_live, R.drawable.dot_live, "Aufzeichnung", R.color.go)
        }

        renderSetup()
    }

    private fun mainButton(bg: Int, icon: Int, text: String, textColor: Int) {
        btnMain.setBackgroundResource(bg)
        btnMainIcon.setImageResource(icon)
        btnMainText.text = text
        btnMainText.setTextColor(textColor)
        btnMain.contentDescription = text
    }

    private fun chip(bg: Int, dot: Int, text: String, textColor: Int) {
        chip.setBackgroundResource(bg)
        chipDot.setBackgroundResource(dot)
        chipText.text = text
        chipText.setTextColor(color(textColor))
    }

    /** Karte „Fahrt erkannt – mitzählen?“ mit Ja / Nein. */
    private fun renderPending() {
        if (!Store.isPending(this)) {
            pendCard.visibility = View.GONE
            return
        }
        pendCard.visibility = View.VISIBLE
        val m = Store.pendMeters(this)
        val start = Store.pendStart(this)
        val inPause = Store.isPendingInPause(this)
        pendTitle.text = if (inPause) "Fahrt erkannt – Pause beenden?" else "Fahrt erkannt – mitzählen?"
        pendYes.text = if (inPause) "Ja, fortsetzen" else "Ja, zählen"
        val span = if (Store.pendEnded(this))
            "${Fmt.time(start)}–${Fmt.time(Store.pendEnd(this))} · ${Fmt.dist(m)}"
        else
            "Seit ${Fmt.time(start)} · ${Fmt.dist(m)} · ${Fmt.duration(Store.pendElapsedMs(this))}"
        pendText.text = when {
            inPause -> "$span\nDeine Fahrt ist pausiert. Bei „Ja“ läuft sie weiter und die km kommen dazu, bei „Nein“ bleibt sie pausiert."
            Store.pendEnded(this) -> "$span\nSoll diese Fahrt zum Km-Stand dazugezählt werden?"
            else -> "$span\nZählt im Hintergrund mit. Bei „Nein“ wird nichts gezählt."
        }
    }

    /** Status-Zeile und Tempo – zeigt genau, woran es gerade hängt. */
    private fun renderLive() {
        val active = Store.isActive(this)
        val paused = Store.isPaused(this)
        tvSpeed.text = if (active && !paused && Live.running) Live.speed.toString() else "0"

        var c = R.color.muted
        val text: String = when {
            Store.isPending(this) -> { c = R.color.warn; "Fahrt erkannt – bitte oben bestätigen." }
            !active -> when {
                Store.isPending(this) -> { c = R.color.warn; "Fahrt erkannt – bitte oben bestätigen." }
                else -> note ?: when {
                    !Store.hasOdo(this) -> "Gib zuerst deinen aktuellen Kilometerstand ein."
                    Store.isIgnoring(this) -> "Diese Fahrt wird nicht gezählt."
                    Store.detectOn(this) && Live.running -> "Fahrterkennung aktiv – zählt mit, sobald du losfährst."
                    else -> ""
                }
            }
            paused -> if (Store.isIgnoring(this)) "Pausiert – diese Strecke wird nicht gezählt."
                      else if (Store.detectOn(this)) "Pausiert – fährst du los, fragt die App, ob die Fahrt weiterlaufen soll."
                      else "Pausiert – es werden keine Kilometer gezählt."
            Live.error != null -> { c = R.color.danger; Live.error ?: "" }
            !Perms.locationOn(this) || Live.gpsOff -> {
                c = R.color.danger; "Standort/GPS ist am Handy ausgeschaltet. Bitte einschalten."
            }
            !Live.running -> { c = R.color.warn; "Aufzeichnung wird gestartet …" }
            !Live.hasFix -> {
                c = R.color.warn
                val sats = if (Live.satsSeen == 0) "noch keine Satelliten" else "${Live.satsUsed} von ${Live.satsSeen} Satelliten"
                val base = if (Live.netFix) "Standort gefunden, warte auf GPS ($sats)" else "Suche GPS-Signal … ($sats)"
                val waited = SystemClock.elapsedRealtime() - Live.startedAt
                if (Live.startedAt > 0 && waited > 45_000)
                    "$base\nIm Gebäude findet GPS oft kein Signal – im Auto oder draußen klappt es."
                else base
            }
            Live.weak -> { c = R.color.warn; "Schwaches GPS-Signal (±${Live.acc} m)" }
            !Live.viaGps -> "Standort aktiv (Netz/WLAN) · ±${Live.acc} m"
            else -> "GPS aktiv · ±${Live.acc} m · ${Live.satsUsed} Satelliten"
        }
        if (tvStatus.text.toString() != text) tvStatus.text = text
        tvStatus.setTextColor(color(c))
    }

    private fun renderDuration() {
        val ms = Store.elapsedMs(this)
        val d = Fmt.days(ms)
        if (d > 0) {
            tvDays.visibility = View.VISIBLE
            tvDays.text = Fmt.daysText(d)
        } else {
            tvDays.visibility = View.GONE
        }
        tvClock.text = Fmt.clock(ms)
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
            item("Standort „Immer zulassen“", "Damit Messung und Fahrterkennung auch im Hintergrund laufen.") {
                if (Build.VERSION.SDK_INT >= 29 && !Store.wasAsked(this, "bg")) {
                    Store.markAsked(this, "bg"); bgPerm.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else openAppSettings()
            }
        }

        if (!Perms.hasNotif(this))
            item("Benachrichtigungen", "Für die Frage „Fahrt erkannt – mitzählen?“ und die Knöpfe.") {
                if (Build.VERSION.SDK_INT >= 33 && Store.wasAsked(this, "base") &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openAppSettings()
                else requestBasePerms()
            }

        if (!Perms.ignoresBattery(this))
            item("Akku-Optimierung aus", "Damit Android die Messung nicht beendet.", "Ändern") { askBattery() }

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
            val dur = t.optLong("dur", -1L).let { if (it < 0) end - start else it }
            v.findViewById<TextView>(R.id.tripWhen).text = "${Fmt.day(start)} · ${Fmt.time(start)}–${Fmt.time(end)}"
            v.findViewById<TextView>(R.id.meta).text = "Dauer ${Fmt.duration(dur)}"
            v.findViewById<TextView>(R.id.dist).text = Fmt.dist(t.optDouble("meters"))
            if (i == n - 1) v.findViewById<View>(R.id.divider).visibility = View.GONE
            tripList.addView(v)
        }
    }
}
