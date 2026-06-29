package com.eladcohen.buswatch.net

import com.eladcohen.buswatch.data.RoutesDb
import com.eladcohen.buswatch.model.Arrival
import com.eladcohen.buswatch.model.LiveBus
import com.eladcohen.buswatch.model.StopBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Fallback live source: KavNav's open feed (same MoT SIRI data as curlbus, same
 * author — Elad Alfassa), used when curlbus.app is down. No key, no auth.
 *
 * To match curlbus's board exactly we merge two of KavNav's endpoints:
 *   • `/api/realtime?stopCode=…` — vehicles broadcasting GPS; each onward-call
 *     carries a live ETA for the stop. These are the 🟢 realtime arrivals.
 *   • `/api/stopSchedule?stopCode=…` — the timetable, used to backfill the next
 *     departure of any line that has no bus live yet (so a stop in a lull still
 *     shows "next bus 19:26" instead of "no buses"). Line numbers for these come
 *     from [RoutesDb] (the timetable only carries routeId).
 *
 * Per line: a live arrival wins; the schedule only fills lines with nothing live
 * and the "next" slot — so the same bus is never shown twice. The timetable is
 * fetched once per stop per day and cached; only the live feed is hit each poll.
 *
 * Good-citizen note: a personal project, not an SLA'd API — we poll no faster
 * than the board's 30 s cadence and send a contactable User-Agent.
 */
object KavNavClient {

    private const val SCHED_WINDOW_MIN = 90

    suspend fun fetch(stopCode: String, stopName: String): StopBoard = withContext(Dispatchers.IO) {
        buildBoard(stopCode, stopName, getRealtime(stopCode))
    }

    suspend fun fetchStop(stopCode: String, stopName: String = ""): Pair<StopBoard, List<LiveBus>> =
        withContext(Dispatchers.IO) {
            val body = getRealtime(stopCode)
            buildBoard(stopCode, stopName, body) to parseBuses(stopCode, body)
        }

    // ---- HTTP ---------------------------------------------------------------

    private fun getRealtime(stopCode: String): String =
        httpGet("https://kavnav.com/api/realtime?stopCode=${enc(stopCode)}")

    private fun getSchedule(stopCode: String, date: String): String =
        httpGet("https://kavnav.com/api/stopSchedule?stopCode=${enc(stopCode)}&date=$date")

    private fun httpGet(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 6000
            readTimeout = 6000
        }
        return try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ---- Live (realtime) ----------------------------------------------------

    private data class LiveVisit(val line: String, val dest: String, val minutes: Int, val hasLoc: Boolean)

    private fun etaForStop(trip: JSONObject, stopCode: String): String? {
        val calls = trip.optJSONObject("onwardCalls")?.optJSONArray("calls") ?: return null
        for (i in 0 until calls.length()) {
            val c = calls.getJSONObject(i)
            if (c.optString("stopCode") == stopCode) return c.optString("eta").ifBlank { null }
        }
        return null
    }

    private fun minutesUntilEta(etaStr: String, now: OffsetDateTime): Int? = runCatching {
        max(0L, ChronoUnit.SECONDS.between(now, OffsetDateTime.parse(etaStr))).toInt() / 60
    }.getOrNull()

    private fun parseLive(stopCode: String, body: String): List<LiveVisit> {
        val vehicles = JSONObject(body).optJSONArray("vehicles") ?: return emptyList()
        val now = OffsetDateTime.now()
        val out = ArrayList<LiveVisit>()
        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val trip = v.optJSONObject("trip") ?: continue
            val line = trip.optJSONObject("gtfsInfo")?.optString("routeNumber").orEmpty()
            if (line.isBlank()) continue
            val etaStr = etaForStop(trip, stopCode) ?: continue
            val minutes = minutesUntilEta(etaStr, now) ?: continue
            val dest = trip.optJSONObject("gtfsInfo")?.optString("headsign").orEmpty()
            val hasLoc = v.optJSONObject("geo")?.optJSONObject("location") != null
            out.add(LiveVisit(line, dest, minutes, hasLoc))
        }
        return out
    }

    // ---- Schedule (timetable backfill) -------------------------------------

    /** A scheduled departure, keyed by absolute seconds-into-the-day (may exceed 86400). */
    private data class SchedDep(val line: String, val dest: String, val sod: Int)
    private data class CachedSched(val date: String, val deps: List<SchedDep>)

    private val schedCache = ConcurrentHashMap<String, CachedSched>()

    /** Today's timetable for the stop, parsed once and cached (line numbers via [RoutesDb]). */
    private fun scheduleFor(stopCode: String): List<SchedDep> {
        if (!RoutesDb.loaded) return emptyList()
        val today = LocalDate.now().toString()
        schedCache[stopCode]?.let { if (it.date == today) return it.deps }
        val deps = runCatching { parseSchedule(stopCode, today) }.getOrDefault(emptyList())
        schedCache[stopCode] = CachedSched(today, deps)
        return deps
    }

    private fun parseSchedule(stopCode: String, today: String): List<SchedDep> {
        val body = getSchedule(stopCode, today)
        val stops = JSONObject(body).optJSONArray("stopSchedule") ?: return emptyList()
        val dowField = when (LocalDate.parse(today).dayOfWeek.value) { // 1=Mon … 7=Sun
            1 -> "monday"; 2 -> "tuesday"; 3 -> "wednesday"; 4 -> "thursday"
            5 -> "friday"; 6 -> "saturday"; else -> "sunday"
        }
        val out = ArrayList<SchedDep>()
        for (s in 0 until stops.length()) {
            val trips = stops.getJSONObject(s).optJSONArray("trips") ?: continue
            for (t in 0 until trips.length()) {
                val trip = trips.getJSONObject(t)
                if (!trip.optBoolean(dowField, false)) continue
                val start = trip.optString("startDate")
                val end = trip.optString("endDate")
                if (start.isNotEmpty() && today < start) continue
                if (end.isNotEmpty() && today > end) continue
                val line = RoutesDb.lineFor(trip.optString("routeId")) ?: continue
                val sod = secondsOfDay(trip.optString("departureTime")) ?: continue
                out.add(SchedDep(line, trip.optString("headsign"), sod))
            }
        }
        return out
    }

    /** "HH:MM:SS" → seconds; tolerates GTFS hours ≥ 24 (after-midnight service). */
    private fun secondsOfDay(t: String): Int? {
        val p = t.split(":")
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = if (p.size > 2) p[2].toIntOrNull() ?: 0 else 0
        return h * 3600 + m * 60 + s
    }

    // ---- Merge --------------------------------------------------------------

    private fun buildBoard(stopCode: String, stopName: String, realtimeBody: String): StopBoard {
        val live = parseLive(stopCode, realtimeBody).groupBy { it.line }

        // Upcoming scheduled departures within the window, by line.
        val nowSod = LocalTime.now().toSecondOfDay()
        val sched = scheduleFor(stopCode)
            .mapNotNull { d ->
                val mins = (d.sod - nowSod) / 60
                if (d.sod > nowSod && mins <= SCHED_WINDOW_MIN) d to mins else null
            }
            .groupBy({ it.first.line }, { it })

        val arrivals = (live.keys + sched.keys).distinct().mapNotNull { line ->
            val liveVisits = live[line]?.sortedBy { it.minutes }.orEmpty()
            val schedMins = sched[line]?.map { it.second }?.sorted().orEmpty()
            when {
                liveVisits.isNotEmpty() -> {
                    val first = liveVisits.first()
                    val next = liveVisits.getOrNull(1)?.minutes
                        ?: schedMins.firstOrNull { it > first.minutes }
                    Arrival(
                        line = line,
                        destination = first.dest,
                        area = "",
                        etaMinutes = first.minutes,
                        realtime = true,
                        nextMinutes = next,
                        hasGps = liveVisits.any { it.hasLoc },
                    )
                }
                schedMins.isNotEmpty() -> {
                    val dep = sched[line]!!.minByOrNull { it.second }!!.first
                    Arrival(
                        line = line,
                        destination = dep.dest,
                        area = "",
                        etaMinutes = schedMins.first(),
                        realtime = false,
                        nextMinutes = schedMins.getOrNull(1),
                        hasGps = false,
                    )
                }
                else -> null
            }
        }.sortedBy { it.etaMinutes }

        return StopBoard(
            stopName = stopName.ifBlank { "תחנה" },
            stopCode = stopCode,
            arrivals = arrivals,
        )
    }

    // ---- Live bus positions (map markers) — realtime only -------------------

    private fun parseBuses(stopCode: String, body: String): List<LiveBus> {
        val vehicles = JSONObject(body).optJSONArray("vehicles") ?: return emptyList()
        val now = OffsetDateTime.now()
        val out = ArrayList<LiveBus>(vehicles.length())
        for (i in 0 until vehicles.length()) {
            val v = vehicles.getJSONObject(i)
            val trip = v.optJSONObject("trip") ?: continue
            val line = trip.optJSONObject("gtfsInfo")?.optString("routeNumber").orEmpty()
            if (line.isBlank()) continue
            val geo = v.optJSONObject("geo") ?: continue
            val loc = geo.optJSONObject("location") ?: continue
            val lat = loc.optDouble("lat", Double.NaN)
            val lon = loc.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || (lat == 0.0 && lon == 0.0)) continue
            val minutes = etaForStop(trip, stopCode)?.let { minutesUntilEta(it, now) } ?: 0
            val heading = if (geo.optDouble("speed", 0.0) > 0.0 && geo.has("bearing")) {
                geo.optDouble("bearing")
            } else null
            out.add(LiveBus(line, lat, lon, minutes, v.optString("vehicleId"), heading))
        }
        return out
    }

    private const val USER_AGENT = "BusWatch/0.2.4 (Wear OS; eladc.android@gmail.com)"
}
