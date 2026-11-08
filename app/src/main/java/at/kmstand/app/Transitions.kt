package at.kmstand.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

/** Android-Fahrterkennung: meldet, wenn du ins Auto steigst oder aussteigst. */
object Transitions {
    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!Perms.hasActivity(c) || !Perms.hasPlayServices(c)) return
        val list = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER).build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT).build()
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getBroadcast(c, 100, Intent(c, TransitionReceiver::class.java), flags)
        try {
            ActivityRecognition.getClient(c).requestActivityTransitionUpdates(ActivityTransitionRequest(list), pi)
        } catch (_: Exception) { }
    }
}
