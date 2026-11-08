package at.kmstand.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifs {
    const val CH_TRACK = "tracking"
    const val CH_REMIND = "reminder"
    const val ID_TRACK = 1
    const val ID_START = 2
    const val ID_STOP = 3

    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_TRACK, "Fahrtaufzeichnung", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Wird angezeigt, solange eine Fahrt aufgezeichnet wird" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Erinnert dich, die Aufzeichnung zu starten oder zu beenden" }
        )
    }

    private fun openApp(c: Context) = PendingIntent.getActivity(
        c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS
    )

    private fun startPI(c: Context) = PendingIntent.getForegroundService(
        c, 1, Intent(c, TrackingService::class.java).setAction(TrackingService.ACTION_START), FLAGS
    )

    fun stopPI(c: Context): PendingIntent = PendingIntent.getService(
        c, 2, Intent(c, TrackingService::class.java).setAction(TrackingService.ACTION_STOP), FLAGS
    )

    private fun dismissPI(c: Context, id: Int) = PendingIntent.getBroadcast(
        c, 10 + id, Intent(c, ActionReceiver::class.java).putExtra("id", id), FLAGS
    )

    fun tracking(c: Context, meters: Double): Notification =
        NotificationCompat.Builder(c, CH_TRACK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Fahrt wird aufgezeichnet")
            .setContentText("${Fmt.dist(meters)} in dieser Fahrt · Stand ${Fmt.km1(Store.odoM(c))} km")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(c))
            .addAction(0, "Fahrt beenden", stopPI(c))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    @SuppressLint("MissingPermission")
    private fun post(c: Context, id: Int, n: Notification) {
        try { NotificationManagerCompat.from(c).notify(id, n) } catch (_: SecurityException) { }
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
