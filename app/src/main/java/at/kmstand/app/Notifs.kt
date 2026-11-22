package at.kmstand.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.floor

object Notifs {
    const val CH_TRACK = "fahrt"
    const val CH_REMIND = "reminder"
    const val CH_ASK = "erkannt"
    const val ID_TRACK = 1
    const val ID_START = 2
    const val ID_STOP = 3
    const val ID_IDLE = 4
    const val ID_ASK = 5

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try { nm.deleteNotificationChannel("tracking") } catch (_: Exception) { }
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, "Fahrt", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Km-Stand, Timer und Knöpfe"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ASK, "Erkannte Fahrten", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Fragt, ob eine erkannte Fahrt zum Km-Stand zählen soll" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Erinnert dich, die Aufzeichnung zu starten oder zu beenden" }
        )
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS
    )

    private fun svc(c: Context, req: Int, action: String, foreground: Boolean): PendingIntent {
        val i = Intent(c, TrackingService::class.java).setAction(action)
        return if (foreground) PendingIntent.getForegroundService(c, req, i, FLAGS)
        else PendingIntent.getService(c, req, i, FLAGS)
    }

    private fun startPI(c: Context) = svc(c, 1, TrackingService.ACTION_START, true)
    fun stopPI(c: Context) = svc(c, 2, TrackingService.ACTION_STOP, false)
    private fun pausePI(c: Context) = svc(c, 5, TrackingService.ACTION_PAUSE, false)
    private fun resumePI(c: Context) = svc(c, 6, TrackingService.ACTION_RESUME, true)
    private fun confirmPI(c: Context) = svc(c, 7, TrackingService.ACTION_CONFIRM, true)
    private fun rejectPI(c: Context) = svc(c, 8, TrackingService.ACTION_REJECT, false)

    private fun dismissPI(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 10 + id, Intent(c, ActionReceiver::class.java).putExtra("id", id), FLAGS
    )

    private fun col(c: Context, res: Int) = ContextCompat.getColor(c, res)

    private fun pill(c: Context, rv: RemoteViews, text: String, bg: Int, color: Int) {
        rv.setTextViewText(R.id.n_pill, "● $text")
        rv.setInt(R.id.n_pill, "setBackgroundResource", bg)
        rv.setTextColor(R.id.n_pill, col(c, color))
    }

    private fun button(rv: RemoteViews, id: Int, text: String, bg: Int, textColor: Int, pi: PendingIntent) {
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setTextViewText(id, text)
        rv.setInt(id, "setBackgroundResource", bg)
        rv.setTextColor(id, textColor)
        rv.setOnClickPendingIntent(id, pi)
    }

    private fun smallButton(rv: RemoteViews, bg: Int, icon: Int, desc: String, pi: PendingIntent) {
        rv.setInt(R.id.n_btn, "setBackgroundResource", bg)
        rv.setImageViewResource(R.id.n_btn, icon)
        rv.setContentDescription(R.id.n_btn, desc)
        rv.setOnClickPendingIntent(R.id.n_btn, pi)
    }

    private fun stats(rv: RemoteViews, trip: String, tripLabel: String, speed: Int, time: String) {
        rv.setTextViewText(R.id.n_trip, trip)
        rv.setTextViewText(R.id.n_trip_label, tripLabel)
        rv.setTextViewText(R.id.n_speed, speed.toString())
        rv.setTextViewText(R.id.n_time, time)
    }

    /** Km-Stand groß, Nachkommastelle und „km“ kleiner und grau – z. B. 84.532,7 km */
    private fun odoText(c: Context): CharSequence {
        val tenths = floor(Store.odoM(c) / 100.0).toLong()
        val main = Fmt.int.format(tenths / 10)
        val tail = ",${tenths % 10} km"
        val sp = SpannableString(main + tail)
        sp.setSpan(RelativeSizeSpan(0.6f), main.length, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(ForegroundColorSpan(col(c, R.color.muted)), main.length, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private fun odoPlain(c: Context) = "${Fmt.km1(Store.odoM(c))} km"

    /** Benachrichtigung des Messdienstes – je nach Zustand. */
    fun foreground(c: Context): Notification = if (Store.isActive(c)) tracking(c) else idle(c, true)

    /** Während einer Fahrt: Km-Stand groß, darunter km, km/h und Zeit. */
    fun tracking(c: Context): Notification {
        val paused = Store.isPaused(c)
        val full = Fmt.duration(Store.elapsedMs(c))
        val km = Fmt.km2(Store.activeMeters(c))
        val speed = if (paused) 0 else Live.speed

        val small = RemoteViews(c.packageName, R.layout.notif_small)
        val big = RemoteViews(c.packageName, R.layout.notif_big)
        small.setTextViewText(R.id.n_title, odoText(c))
        big.setTextViewText(R.id.n_odo, odoText(c))
        stats(big, km, "Gefahren (km)", speed, full)

        if (paused) {
            small.setTextViewText(R.id.n_sub, "Pausiert · $km km · $full")
            small.setTextColor(R.id.n_sub, col(c, R.color.warn))
            smallButton(small, R.drawable.n_circle_accent, R.drawable.ic_play, "Fortsetzen", resumePI(c))
            pill(c, big, "Pausiert", R.drawable.n_pill_pause, R.color.warn)
            button(big, R.id.n_b1, "Fortsetzen", R.drawable.n_btn_accent, Color.WHITE, resumePI(c))
        } else {
            small.setTextViewText(R.id.n_sub, "$km km · $speed km/h · $full")
            small.setTextColor(R.id.n_sub, col(c, R.color.muted))
            smallButton(small, R.drawable.n_circle_glass, R.drawable.ic_pause, "Pause", pausePI(c))
            pill(c, big, "Aufzeichnung", R.drawable.n_pill_go, R.color.go)
            button(big, R.id.n_b1, "Pause", R.drawable.n_btn_glass, col(c, R.color.ink), pausePI(c))
        }
        button(big, R.id.n_b2, "Beenden", R.drawable.n_btn_stop, Color.WHITE, stopPI(c))

        return NotificationCompat.Builder(c, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(odoPlain(c))
            .setContentText("$km km · $speed km/h · $full")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(c))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Ohne laufende Fahrt: Bereit / Fahrterkennung aktiv / Fahrt erkannt. */
    fun idle(c: Context, ongoing: Boolean): Notification {
        val pending = Store.isPending(c)
        val small = RemoteViews(c.packageName, R.layout.notif_small)
        val big = RemoteViews(c.packageName, R.layout.notif_big)
        small.setTextViewText(R.id.n_title, odoText(c))
        big.setTextViewText(R.id.n_odo, odoText(c))

        val sub: String
        if (pending) {
            val m = Store.pendMeters(c)
            sub = "Fahrt erkannt · ${Fmt.dist(m)} – mitzählen?"
            pill(c, big, "Fahrt erkannt", R.drawable.n_pill_pause, R.color.warn)
            stats(big, Fmt.km2(m), "Erkannt (km)", if (Store.pendEnded(c)) 0 else Live.speed,
                Fmt.duration(Store.pendElapsedMs(c)))
            button(big, R.id.n_b1, "Ja, zählen", R.drawable.n_btn_accent, Color.WHITE, confirmPI(c))
            button(big, R.id.n_b2, "Nein", R.drawable.n_btn_glass, col(c, R.color.ink), rejectPI(c))
            smallButton(small, R.drawable.n_circle_accent, R.drawable.ic_play, "Ja, zählen", confirmPI(c))
            small.setTextColor(R.id.n_sub, col(c, R.color.warn))
        } else {
            val last = Store.lastTripMeters(c)
            val detecting = ongoing && Store.detectOn(c)
            sub = if (detecting) "Fahrterkennung aktiv · Tippe zum Starten" else "Bereit · Tippe zum Starten"
            if (detecting) pill(c, big, "Fahrterkennung aktiv", R.drawable.n_pill_go, R.color.go)
            else pill(c, big, "Bereit", R.drawable.n_pill_idle, R.color.muted)
            stats(big, if (last != null) Fmt.km2(last) else "–", "Letzte Fahrt (km)", 0, Fmt.duration(0L))
            button(big, R.id.n_b1, "Fahrt starten", R.drawable.n_btn_accent, Color.WHITE, startPI(c))
            big.setViewVisibility(R.id.n_b2, View.GONE)
            smallButton(small, R.drawable.n_circle_accent, R.drawable.ic_play, "Fahrt starten", startPI(c))
            small.setTextColor(R.id.n_sub, col(c, R.color.muted))
        }
        small.setTextViewText(R.id.n_sub, sub)

        val b = NotificationCompat.Builder(c, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(odoPlain(c))
            .setContentText(sub)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(c))
        if (ongoing) {
            b.setCategory(NotificationCompat.CATEGORY_SERVICE)
            b.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: Exception) { }
    }

    /** „Bereit“-Benachrichtigung, wenn der Messdienst nicht läuft. */
    fun showIdle(c: Context) {
        if (Store.isActive(c)) return
        post(c, ID_IDLE, idle(c, false))
    }

    /** Pop-up: „Fahrt erkannt – mitzählen?“ mit Ja / Nein. Meldet sich nur beim ersten Mal mit Ton. */
    fun showAsk(c: Context) {
        if (!Store.isPending(c)) { cancel(c, ID_ASK); return }
        val m = Store.pendMeters(c)
        val start = Store.pendStart(c)
        val text = if (Store.pendEnded(c))
            "Fahrt ${Fmt.time(start)}–${Fmt.time(Store.pendEnd(c))} · ${Fmt.dist(m)}. Zum Km-Stand dazuzählen?"
        else
            "Seit ${Fmt.time(start)} · ${Fmt.dist(m)} · ${Fmt.duration(Store.pendElapsedMs(c))}. Zählt im Hintergrund mit."
        val n = NotificationCompat.Builder(c, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fahrt erkannt – mitzählen?")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(c))
            .addAction(0, "Ja, zählen", confirmPI(c))
            .addAction(0, "Nein", rejectPI(c))
            .build()
        post(c, ID_ASK, n)
    }

    fun showStartReminder(c: Context, resume: Boolean) {
        val n = NotificationCompat.Builder(c, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (resume) "Fahrt läuft noch" else "Fahrt erkannt")
            .setContentText(if (resume) "Aufzeichnung fortsetzen?" else "Du fährst gerade. Aufzeichnung starten?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .addAction(0, "Starten", startPI(c))
            .addAction(0, "Ignorieren", dismissPI(c, ID_START))
            .build()
        post(c, ID_START, n)
    }

    fun showStopReminder(c: Context) {
        val n = NotificationCompat.Builder(c, CH_REMIND)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fahrt beendet?")
            .setContentText("Du scheinst nicht mehr zu fahren. Aufzeichnung beenden?")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .addAction(0, "Beenden", stopPI(c))
            .addAction(0, "Weiterfahren", dismissPI(c, ID_STOP))
            .build()
        post(c, ID_STOP, n)
    }

    fun cancel(c: Context, id: Int) = NotificationManagerCompat.from(c).cancel(id)
}
