package dev.psychron.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Looper
import android.os.SystemClock

/**
 * The phone's position, from the platform's own location service.
 *
 * No Google Play services: LocationManager's fused provider where the phone has one
 * (Android 12 and later), GNSS and the network provider where it does not. One fix
 * every two seconds, the window's rate; a window takes the latest fix and only if it
 * is under ten seconds old, because a position from a minute ago is where the phone
 * was, not where it is.
 *
 * Altitude is converted to mean sea level on the device. GNSS measures height over
 * the WGS84 ellipsoid, which over Spain sits about 50 m below the sea-level surface a
 * barometer's reduction assumes; Android 14 ships the geoid model to correct it.
 */
class LocationTracker(private val context: Context, private val looper: Looper) : LocationListener {

    data class Fix(val lat: Double, val lon: Double, val accM: Double, val altMslM: Double?,
                   val altAccM: Double?, val speedMs: Double?)

    private val lm = context.getSystemService(LocationManager::class.java)
    @Volatile private var latest: Location? = null
    private val converter = if (Build.VERSION.SDK_INT >= 34) AltitudeConverter() else null

    val available: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun start() {
        if (!available) return
        val providers = if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            listOf(LocationManager.FUSED_PROVIDER)
        } else {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.allProviders.contains(it) }
        }
        for (p in providers) {
            @Suppress("MissingPermission")
            lm.requestLocationUpdates(p, INTERVAL_MS, 0f, this, looper)
        }
    }

    fun stop() = runCatching { lm.removeUpdates(this) }

    override fun onLocationChanged(location: Location) {
        // A worse fix does not replace a better one from the same moment: the network
        // provider's 100 m guess arriving a second after a 4 m GNSS fix is not news.
        val previous = latest
        if (previous != null && ageNanos(previous) < 3_000_000_000L &&
            location.accuracy > previous.accuracy * 2) return
        if (Build.VERSION.SDK_INT >= 34 && converter != null && location.hasAltitude() && !location.hasMslAltitude()) {
            // Off the main thread by construction (the looper is the sensor thread),
            // which the converter requires: it may load the geoid model from disk.
            runCatching { converter?.addMslAltitudeToLocation(context, location) }
        }
        latest = location
    }

    /** The latest fix if it is recent enough to describe this window. */
    fun current(): Fix? {
        val l = latest ?: return null
        if (ageNanos(l) > MAX_AGE_NANOS) return null
        val msl = Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()
        return Fix(
            lat = l.latitude,
            lon = l.longitude,
            accM = l.accuracy.toDouble(),
            altMslM = if (msl) l.mslAltitudeMeters else null,
            altAccM = if (msl && l.hasMslAltitudeAccuracy()) l.mslAltitudeAccuracyMeters.toDouble() else null,
            speedMs = if (l.hasSpeed()) l.speed.toDouble() else null,
        )
    }

    private fun ageNanos(l: Location) = SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos

    // Required by older platforms, where these are not default methods.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private companion object {
        const val INTERVAL_MS = 2000L
        const val MAX_AGE_NANOS = 10_000_000_000L
    }
}
