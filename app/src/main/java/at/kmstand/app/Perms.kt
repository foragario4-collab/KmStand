package at.kmstand.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

object Perms {
    private fun has(c: Context, p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    fun hasFine(c: Context) = has(c, Manifest.permission.ACCESS_FINE_LOCATION)
    fun hasCoarse(c: Context) = has(c, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackground(c: Context) =
        if (Build.VERSION.SDK_INT >= 29) has(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) else hasFine(c)

    fun hasActivity(c: Context) =
        if (Build.VERSION.SDK_INT >= 29) has(c, Manifest.permission.ACTIVITY_RECOGNITION) else true

    fun hasNotif(c: Context) =
        if (Build.VERSION.SDK_INT >= 33) has(c, Manifest.permission.POST_NOTIFICATIONS) else true

    fun ignoresBattery(c: Context): Boolean {
        val pm = c.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(c.packageName)
    }

    /** Ist der Standort am Handy überhaupt eingeschaltet? */
    fun locationOn(c: Context): Boolean {
        val lm = c.getSystemService(LocationManager::class.java) ?: return true
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Google-Dienste werden nur noch für die Fahrterkennung gebraucht, nicht fürs Messen. */
    @Suppress("DEPRECATION")
    fun hasPlayServices(c: Context): Boolean = try {
        c.packageManager.getPackageInfo("com.google.android.gms", 0); true
    } catch (e: Exception) { false }
}
