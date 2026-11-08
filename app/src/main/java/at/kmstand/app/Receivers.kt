package at.kmstand.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class TransitionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        for (e in result.transitionEvents) {
            if (e.activityType != DetectedActivity.IN_VEHICLE) continue
            if (e.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                if (Store.isActive(c)) continue
                if (Store.autoStart(c) && Perms.hasBackground(c)) TrackingService.start(c)
                else Notifs.showStartReminder(c, false)
            } else {
                if (Store.isActive(c)) Notifs.showStopReminder(c)
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        Notifs.cancel(c, intent.getIntExtra("id", 0))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Notifs.createChannels(c)
        Transitions.register(c)
        if (Store.isActive(c)) Notifs.showStartReminder(c, true) else Notifs.showIdle(c)
    }
}
