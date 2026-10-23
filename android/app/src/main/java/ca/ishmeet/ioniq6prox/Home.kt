package ca.ishmeet.ioniq6prox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.CancellationSignal

/**
 * "Am I at home?" — by the home Wi-Fi's name or by distance from the home spot.
 *
 * Android only reveals the Wi-Fi name and location to apps with location permission, and to a
 * background service only with "Allow all the time".
 */
object Home {
    const val RADIUS_M = 150f
    private const val FRESH_MS = 15 * 60_000L

    /** Automatic: on as soon as a home Wi-Fi name or home location is saved. Nothing to switch on. */
    fun enabled(p: Prefs) = p.homeWifi.isNotBlank() || p.homeLat != null

    fun hasLocation(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(c: Context) = c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Wi-Fi name without the quotes Android adds, or null when unknown / not on Wi-Fi. */
    fun clean(ssid: String?): String? {
        val s = ssid?.trim()?.removeSurrounding("\"") ?: return null
        return if (s.isBlank() || s == WifiManager.UNKNOWN_SSID) null else s
    }

    @Suppress("DEPRECATION")
    fun currentWifi(c: Context): String? = try {
        clean(c.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid)
    } catch (_: Exception) { null }

    fun isHomeWifi(p: Prefs, ssid: String?): Boolean =
        ssid != null && p.homeWifi.isNotBlank() && ssid.trim().equals(p.homeWifi.trim(), ignoreCase = true)

    /** Metres from the home spot, or null if no home spot is set. */
    fun distance(p: Prefs, loc: Location): Float? {
        val lat = p.homeLat ?: return null
        val lon = p.homeLon ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, loc.latitude, loc.longitude, out)
        return out[0]
    }

    /** Inside the home circle, allowing for some of the fix's own inaccuracy. */
    fun inside(p: Prefs, loc: Location): Boolean {
        val d = distance(p, loc) ?: return false
        return d <= RADIUS_M + loc.accuracy.coerceAtMost(100f) / 2
    }

    /** Asks for a fresh fix (Android gives up on its own after about 30 s). cb runs on the main thread. */
    fun locate(c: Context, cb: (Location?) -> Unit) {
        val lm = c.getSystemService(LocationManager::class.java)
        if (lm == null || !hasLocation(c)) { cb(null); return }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { try { lm.hasProvider(it) && lm.isProviderEnabled(it) } catch (_: Exception) { false } }
        if (provider == null) { cb(null); return }
        try {
            lm.getCurrentLocation(provider, CancellationSignal(), c.mainExecutor) { loc -> cb(loc ?: lastKnown(c)) }
        } catch (_: Exception) { cb(lastKnown(c)) }
    }

    /** The newest location the phone already has, if recent. No GPS work. */
    fun lastKnown(c: Context): Location? {
        val lm = c.getSystemService(LocationManager::class.java) ?: return null
        if (!hasLocation(c)) return null
        return try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }
                .filter { System.currentTimeMillis() - it.time < FRESH_MS }
                .maxByOrNull { it.time }
        } catch (_: Exception) { null }
    }

    /**
     * Quick check with what the phone already knows: true = home, false = somewhere else,
     * null = can't tell (not on Wi-Fi and no recent location).
     */
    fun where(c: Context, p: Prefs, wifi: String?): Boolean? {
        if (!enabled(p)) return false
        val w = wifi ?: currentWifi(c)
        if (isHomeWifi(p, w)) return true
        val loc = lastKnown(c)
        if (loc != null && p.homeLat != null) return inside(p, loc)
        return if (w != null) false else null   // on some other Wi-Fi → not home
    }

    /** "43.65107, -79.34702" → pair, or null if it doesn't parse. */
    fun parse(s: String): Pair<Double, Double>? {
        val parts = s.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    fun describe(meters: Float): String = if (meters < 1000) "${meters.toInt()} m" else String.format("%.1f km", meters / 1000)
}
