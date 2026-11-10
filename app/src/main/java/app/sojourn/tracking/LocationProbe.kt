package app.sojourn.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import app.sojourn.data.db.Source
import app.sojourn.data.geo.CountryLocator
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

data class Sighting(val country: String, val source: Source, val time: Long)

/**
 * Finds the current country as cheaply as possible: a recent location some other app already
 * paid for, else one coarse network fix, else the country of the mobile network. GPS is never
 * switched on; country-level accuracy doesn't need it.
 */
class LocationProbe(private val context: Context, private val locator: () -> CountryLocator) {
    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun locate(): Sighting? {
        if (!hasLocationPermission()) return null
        val location = recentLocation() ?: freshLocation() ?: return null
        val country = locator().locate(location.latitude, location.longitude) ?: return null
        return Sighting(country, Source.LOCATION, location.time)
    }

    /** The mobile network's country. Free, permissionless, and right whenever there's signal. */
    fun networkCountry(): Sighting? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        if (telephony.phoneType == TelephonyManager.PHONE_TYPE_NONE) return null
        val iso = telephony.networkCountryIso?.uppercase()?.takeIf { it.length == 2 } ?: return null
        return Sighting(iso, Source.NETWORK, System.currentTimeMillis())
    }

    @SuppressLint("MissingPermission")
    private fun recentLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val newest = manager.getProviders(true)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return null
        return newest.takeIf { System.currentTimeMillis() - it.time < FRESH_ENOUGH_MS }
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshLocation(): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        val executor = Executors.newSingleThreadExecutor()
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    runCatching {
                        LocationManagerCompat.getCurrentLocation(manager, provider, signal, executor) { continuation.resume(it) }
                    }.onFailure { continuation.resume(null) }
                }
            }
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val FRESH_ENOUGH_MS = 30 * 60 * 1000L
        const val FIX_TIMEOUT_MS = 30_000L
    }
}
