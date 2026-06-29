package com.eladcohen.buswatch.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * routeId → public line number ("1", "5", "1א", "L1"), loaded once from the
 * bundled `assets/routes.tsv` (≈58 KB, 6.4k routes).
 *
 * The live feed already carries the line number per vehicle, but the timetable
 * (`/api/stopSchedule`) only carries `routeId` — so schedule-only departures
 * (a line with no bus broadcasting yet) need this map to be shown with their
 * real number, the way curlbus and Nearby Bus do.
 */
object RoutesDb {

    @Volatile private var map: Map<String, String> = emptyMap()
    val loaded: Boolean get() = map.isNotEmpty()

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val text = context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val m = HashMap<String, String>(8192)
        for (line in text.lineSequence()) {
            val t = line.indexOf('\t')
            if (t > 0 && t < line.length - 1) m[line.substring(0, t)] = line.substring(t + 1).trim()
        }
        map = m
    }

    fun lineFor(routeId: String): String? = map[routeId]

    private const val FILE_NAME = "routes.tsv"
}
