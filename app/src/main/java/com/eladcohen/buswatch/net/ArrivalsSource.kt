package com.eladcohen.buswatch.net

import android.os.SystemClock
import com.eladcohen.buswatch.model.LiveBus
import com.eladcohen.buswatch.model.StopBoard

/**
 * The single entry point for live arrivals, with automatic failover.
 *
 * Primary is [CurlbusClient]; [KavNavClient] is the fallback. We switch to the
 * fallback when curlbus either throws (it's been 502 for a while — the outage
 * that motivated this) OR returns an empty board while the fallback has data.
 *
 * A tiny circuit breaker avoids paying curlbus's 6 s timeout on every poll
 * during a full outage: after a failure we skip curlbus for [COOLDOWN_MS] and
 * go straight to KavNav, retrying curlbus only once the cooldown lapses.
 */
object ArrivalsSource {

    private const val COOLDOWN_MS = 120_000L
    @Volatile private var curlbusBlockedUntil = 0L

    private fun curlbusReady(): Boolean = SystemClock.elapsedRealtime() >= curlbusBlockedUntil
    private fun tripBreaker() { curlbusBlockedUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS }

    suspend fun fetch(stopCode: String, stopName: String): StopBoard {
        if (curlbusReady()) {
            try {
                val board = CurlbusClient.fetch(stopCode, stopName)
                if (board.arrivals.isNotEmpty()) return board
                // curlbus up but empty: prefer the fallback only if it actually has buses.
                return runCatching { KavNavClient.fetch(stopCode, stopName) }
                    .getOrNull()?.takeIf { it.arrivals.isNotEmpty() } ?: board
            } catch (e: Exception) {
                tripBreaker()
            }
        }
        // Breaker open (or curlbus just failed): KavNav directly. May throw → caller handles.
        return KavNavClient.fetch(stopCode, stopName)
    }

    suspend fun fetchStop(stopCode: String, stopName: String = ""): Pair<StopBoard, List<LiveBus>> {
        if (curlbusReady()) {
            try {
                val res = CurlbusClient.fetchStop(stopCode, stopName)
                if (res.first.arrivals.isNotEmpty()) return res
                return runCatching { KavNavClient.fetchStop(stopCode, stopName) }
                    .getOrNull()?.takeIf { it.first.arrivals.isNotEmpty() } ?: res
            } catch (e: Exception) {
                tripBreaker()
            }
        }
        return KavNavClient.fetchStop(stopCode, stopName)
    }
}
