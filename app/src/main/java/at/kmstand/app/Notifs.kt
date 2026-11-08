package at.kmstand.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notifs {
    const val CH_TRACK = "fahrt"
    const val CH_REMIND = "reminder"
    const val ID_TRACK = 1
    const val ID_START = 2
    const val ID_STOP = 3
    const val ID_IDLE = 4

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try { nm.deleteNotificationChannel("tracking") } catch (_: Exception) { }   // Kanal aus Version 1.0–1.2
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, "Fahrt", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Timer, Kilometer und Knöpfe für die laufende Fahrt"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
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

    private fun dismissPI(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 10 + id, Intent(c, ActionReceiver::class.java).putExtra("id", id), FLAGS
    )

    private fun col(c: Context, res: Int) = ContextCompat.getColor(c, res)

    private fun pill(c: Context, rv: RemoteViews, text: String, bg: Int, color: Int) {
        rv.setTextViewText(R.id.n_pill, "● $text")
        rv.setInt(R.id.n_pill, "setBackgroundResource", bg)
        rv.setTextColor(R.id.n_pill, col(c, color))
    }

    private fun button(c: Context, rv: RemoteViews, id: Int, text: String, bg: Int, textColor: Int, pi: PendingIntent) {
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setTextViewText(id, text)
        rv.setInt(id, "setBackgroundResource", bg)
        rv.setTextColor(id, textColor)
        rv.setOnClickPendingIntent(id, pi)
    }

    private fun clock(rv: RemoteViews, ms: Long) {
        val d = Fmt.days(ms)
        if (d > 0) {
            rv.setViewVisibility(R.id.n_days, View.VISIBLE)
            rv.setTextViewText(R.id.n_days, Fmt.daysText(d))
        } else {
            rv.setViewVisibility(R.id.n_days, View.GONE)
        }
        rv.setTextViewText(R.id.n_clock, Fmt.clock(ms))
    }

    /** Benachrichtigung während einer Fahrt (läuft oder pausiert). */
    fun tracking(c: Context): Notification {
        val paused = Store.isPaused(c)
        val ms = Store.elapsedMs(c)
        val km = Fmt.km2(Store.activeMeters(c))
        val full = Fmt.duration(ms)

        val small = RemoteViews(c.packageName, R.layout.notif_small)
        val big = RemoteViews(c.packageName, R.layout.notif_big)

        small.setTextViewText(R.id.n_title, "$full · $km km")
        clock(big, ms)

        if (paused) {
            small.setTextViewText(R.id.n_sub, "Pausiert")
            small.setTextColor(R.id.n_sub, col(c, R.color.warn))
            small.setInt(R.id.n_btn, "setBackgroundResource", R.drawable.n_circle_accent)
            small.setImageViewResource(R.id.n_btn, R.drawable.ic_play)
            small.setContentDescription(R.id.n_btn, "Fortsetzen")
            small.setOnClickPendingIntent(R.id.n_btn, resumePI(c))

            pill(c, big, "Pausiert", R.drawable.n_pill_pause, R.color.warn)
            big.setTextViewText(R.id.n_line, "$km km · pausiert")
            button(c, big, R.id.n_b1, "Fortsetzen", R.drawable.n_btn_accent, Color.WHITE, resumePI(c))
        } else {
            small.setTextViewText(R.id.n_sub, "Aufzeichnung · ${Live.speed} km/h")
            small.setTextColor(R.id.n_sub, col(c, R.color.muted))
            small.setInt(R.id.n_btn, "setBackgroundResource", R.drawable.n_circle_glass)
            small.setImageViewResource(R.id.n_btn, R.drawable.ic_pause)
            small.setContentDescription(R.id.n_btn, "Pause")
            small.setOnClickPendingIntent(R.id.n_btn, pausePI(c))

            pill(c, big, "Aufzeichnung", R.drawable.n_pill_go, R.color.go)
            big.setTextViewText(R.id.n_line, "$km km · ${Live.speed} km/h")
            button(c, big, R.id.n_b1, "Pause", R.drawable.n_btn_glass, col(c, R.color.ink), pausePI(c))
        }
        button(c, big, R.id.n_b2, "Beenden", R.drawable.n_btn_stop, Color.WHITE, stopPI(c))

        return NotificationCompat.Builder(c, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (paused) "Fahrt pausiert" else "Fahrt läuft")
            .setContentText("$full · $km km")
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

    @SuppressLint("MissingPermission")
    private fun post(c: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
    }

    /** Bereit-Benachrichtigung mit „Fahrt starten“ – erscheint, wenn keine Fahrt läuft. */
    fun showIdle(c: Context) {
        if (Store.isActive(c)) return
        val last = Store.lastTripMeters(c)
        val sub = if (last != null) "Letzte Fahrt gespeichert: ${Fmt.dist(last)}" else "Bereit für die nächste Fahrt"

        val small = RemoteViews(c.packageName, R.layout.notif_small)
        small.setTextViewText(R.id.n_title, "Bereit · Tippe zum Starten")
        small.setTextViewText(R.id.n_sub, sub)
        small.setTextColor(R.id.n_sub, col(c, R.color.muted))
        small.setInt(R.id.n_btn, "setBackgroundResource", R.drawable.n_circle_accent)
        small.setImageViewResource(R.id.n_btn, R.drawable.ic_play)
        small.setContentDescription(R.id.n_btn, "Fahrt starten")
        small.setOnClickPendingIntent(R.id.n_btn, startPI(c))

        val big = RemoteViews(c.packageName, R.layout.notif_big)
        pill(c, big, "Bereit", R.drawable.n_pill_idle, R.color.muted)
        clock(big, 0L)
        big.setTextViewText(R.id.n_line, sub)
        button(c, big, R.id.n_b1, "Fahrt starten", R.drawable.n_btn_accent, Color.WHITE, startPI(c))
        big.setViewVisibility(R.id.n_b2, View.GONE)

        val n = NotificationCompat.Builder(c, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Km-Stand bereit")
            .setContentText(sub)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openApp(c))
            .build()
        post(c, ID_IDLE, n)
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
