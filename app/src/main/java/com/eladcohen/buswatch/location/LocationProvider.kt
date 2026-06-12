package com.eladcohen.buswatch.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Emits the watch's location using the platform [LocationManager] (no GMS
 * dependency). Sends last-known immediately, then GPS + network updates.
 * Caller must hold ACCESS_FINE_LOCATION.
 */
class LocationProvider(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun updates(): Flow<Location> = callbackFlow {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (last != null) trySend(last)

        val listener = LocationListener { trySend(it) }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                lm.requestLocationUpdates(provider, MIN_TIME_MS, MIN_DIST_M, listener, Looper.getMainLooper())
            }
        }
        awaitClose { lm.removeUpdates(listener) }
    }

    companion object {
        private const val MIN_TIME_MS = 10_000L
        private const val MIN_DIST_M = 25f
    }
}
