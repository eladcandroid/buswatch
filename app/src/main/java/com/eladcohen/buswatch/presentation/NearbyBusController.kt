package com.eladcohen.buswatch.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.eladcohen.buswatch.data.RoutesDb
import com.eladcohen.buswatch.data.StopStore
import com.eladcohen.buswatch.data.StopsDb
import com.eladcohen.buswatch.data.StopsUpdater
import com.eladcohen.buswatch.location.LocationProvider
import com.eladcohen.buswatch.model.BusMode
import com.eladcohen.buswatch.model.Stop
import com.eladcohen.buswatch.model.StopBoard
import com.eladcohen.buswatch.net.ArrivalsSource
import com.eladcohen.buswatch.net.LinkState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Drives the bus board:
 *  - NEARBY mode: the [NEARBY_COUNT] nearest stops (nearest-first), live,
 *  - FIXED mode: one user-chosen stop, ignoring GPS,
 *  - instant first paint from cache; stops snapshot refreshed in background.
 *
 * run() is meant to be hosted under repeatOnLifecycle(STARTED) so GPS + polling
 * stop when the app is backgrounded (screen off) — no battery drain.
 */
class NearbyBusController(
    private val context: Context,
    private val stopsDb: StopsDb,
    private val store: StopStore,
) {
    val boards = MutableStateFlow<List<StopBoard>>(emptyList())
    val link = MutableStateFlow(LinkState.CONNECTING)
    val statusText = MutableStateFlow("מאתר תחנה…")
    val mode = MutableStateFlow(BusMode.NEARBY)

    @Volatile private var current: List<Stop> = emptyList()
    @Volatile private var lastLoc: Location? = null
    @Volatile private var lastGpsAt = 0L
    @Volatile private var lastGpsLoc: Location? = null

    suspend fun run() = coroutineScope {
        mode.value = store.mode()

        // 1. Instant first paint: last known stop's live arrivals.
        store.last()?.let { cached ->
            current = listOf(cached)
            fetchStops(listOf(cached))
        }

        if (!hasLocationPermission()) {
            if (boards.value.isEmpty()) {
                statusText.value = "צריך הרשאת מיקום"
                link.value = LinkState.DISCONNECTED
            }
            return@coroutineScope
        }

        // 2. Background (deferred + low priority inside): refresh stops snapshot.
        launch { delay(REFRESH_DELAY_MS); StopsUpdater.maybeRefresh(context) }

        // 3. Load the stops index off the UI path.
        if (current.isEmpty()) statusText.value = "טוען תחנות…"
        runCatching { stopsDb.load() }
        runCatching { RoutesDb.load(context) } // routeId→line map for schedule backfill
        if (current.isEmpty()) statusText.value = "מאתר תחנות קרובות…"
        Log.i(TAG, "stops=${stopsDb.size} mode=${mode.value}")

        // 4. Follow location (off the main thread). FIXED keeps its stop.
        launch(Dispatchers.Default) {
            LocationProvider(context).updates().collect { loc ->
                lastLoc = loc
                if (mode.value == BusMode.FIXED) return@collect

                val nowEt = SystemClock.elapsedRealtime()
                if (loc.provider == LocationManager.GPS_PROVIDER) {
                    lastGpsAt = nowEt
                    lastGpsLoc = loc
                } else if (nowEt - lastGpsAt < GPS_PREFER_MS) {
                    // Prefer GPS over coarse network fixes — but only while the last GPS
                    // fix still reflects where we are. A network fix far from that GPS
                    // position means we've actually moved; otherwise a stale/replayed GPS
                    // fix would pin the board to an old spot (e.g. home) indefinitely.
                    val g = lastGpsLoc
                    if (g != null && distMeters(g.latitude, g.longitude, loc.latitude, loc.longitude) < GPS_DRIFT_M) {
                        return@collect
                    }
                }

                val near = stopsDb.nearestN(loc.latitude, loc.longitude, NEARBY_COUNT)
                if (near.isEmpty()) return@collect
                val cur = current
                val changed = when {
                    cur.isEmpty() -> true
                    cur.size < near.size -> true // expand the cached single stop to the full set
                    near.first().code == cur.first().code -> false // primary unchanged → stable
                    else -> distMeters(loc.latitude, loc.longitude, near.first().lat, near.first().lon) <
                        distMeters(loc.latitude, loc.longitude, cur.first().lat, cur.first().lon) - HYSTERESIS_M
                }
                if (changed) {
                    current = near
                    store.save(near.first())
                    Log.i(TAG, "nearby ${near.joinToString { it.code.toString() }}")
                    fetchStops(near)
                }
            }
        }

        // 5. Keep the current stops' arrivals fresh.
        while (isActive) {
            if (current.isNotEmpty()) fetchStops(current)
            delay(REFRESH_MS)
        }
    }

    /** Latest known device location, for distance-sorting search results. */
    fun lastLocation(): Location? = lastLoc

    /** The stops currently on the board — used to resolve a tapped card's coords. */
    fun currentStops(): List<Stop> = current

    /** Pin a specific stop chosen via search; GPS stops changing the board. */
    suspend fun selectFixed(stop: Stop) {
        mode.value = BusMode.FIXED
        store.saveMode(BusMode.FIXED)
        current = listOf(stop)
        store.save(stop)
        statusText.value = "מאתר תחנה…"
        fetchStops(listOf(stop))
    }

    /** Resume following the nearest stops. */
    suspend fun selectNearby() {
        mode.value = BusMode.NEARBY
        store.saveMode(BusMode.NEARBY)
        val loc = lastLoc ?: return
        val near = stopsDb.nearestN(loc.latitude, loc.longitude, NEARBY_COUNT)
        if (near.isEmpty()) return
        current = near
        store.save(near.first())
        fetchStops(near)
    }

    /** Fetch all stops' arrivals in parallel; one failure doesn't drop the rest. */
    private suspend fun fetchStops(stops: List<Stop>) = coroutineScope {
        val results = stops.map { s ->
            async {
                try {
                    ArrivalsSource.fetch(s.code.toString(), s.name) to true
                } catch (e: Exception) {
                    StopBoard(s.name, s.code.toString(), emptyList()) to false
                }
            }
        }.awaitAll()
        boards.value = results.map { it.first }
        link.value = when {
            results.any { it.second } -> LinkState.CONNECTED
            boards.value.isEmpty() -> LinkState.CONNECTING
            else -> LinkState.DISCONNECTED
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun distMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return EARTH_R_M * sqrt(dLat * dLat + dLon * dLon)
    }

    companion object {
        private const val TAG = "NearbyBus"
        private const val NEARBY_COUNT = 5
        private const val REFRESH_MS = 30_000L
        private const val REFRESH_DELAY_MS = 8_000L
        private const val HYSTERESIS_M = 40.0
        private const val EARTH_R_M = 6_371_000.0
        private const val GPS_PREFER_MS = 60_000L
        // A network fix farther than this from the last GPS fix means real movement,
        // so it overrides the GPS-preference window above.
        private const val GPS_DRIFT_M = 200.0
    }
}
