package com.eladcohen.buswatch.data

import android.content.Context
import com.eladcohen.buswatch.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos

/**
 * In-memory index of all MoT stops. Loaded once from the refreshed copy in
 * filesDir if present, else the bundled `assets/stops.tsv`.
 *
 * Memory-lean layout: parallel primitive arrays for the search hot path
 * (code/lat/lon) plus a single concatenated name string + offset array — so
 * there are a handful of objects instead of ~30k String instances.
 */
class StopsDb(private val context: Context) {

    @Volatile private var loaded = false
    private var codes = IntArray(0)
    private var lats = FloatArray(0)
    private var lons = FloatArray(0)
    private var names = ""
    private var nameStart = IntArray(0) // size n+1, char offsets into `names`

    val size: Int get() = if (loaded) codes.size else 0

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        parse(readSource())
        loaded = true
    }

    /** Refreshed file in filesDir wins over the bundled snapshot. */
    private fun readSource(): String {
        val f = File(context.filesDir, FILE_NAME)
        return if (f.exists() && f.length() > 100) {
            f.readText(Charsets.UTF_8)
        } else {
            context.assets.open(FILE_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun parse(text: String) {
        val len = text.length
        var n = 0
        for (k in 0 until len) if (text[k] == '\n') n++
        if (len > 0 && text[len - 1] != '\n') n++

        val cs = IntArray(n)
        val la = FloatArray(n)
        val lo = FloatArray(n)
        val starts = IntArray(n + 1)
        val sb = StringBuilder(len / 2)

        var i = 0
        var idx = 0
        while (i < len && idx < n) {
            var j = i
            while (j < len && text[j] != '\n') j++
            val t1 = text.indexOf('\t', i)
            val t2 = if (t1 in i until j) text.indexOf('\t', t1 + 1) else -1
            val t3 = if (t2 in (t1 + 1) until j) text.indexOf('\t', t2 + 1) else -1
            if (t1 in i until j && t2 in (t1 + 1) until j && t3 in (t2 + 1) until j) {
                val code = text.substring(i, t1).toIntOrNull()
                val lat = text.substring(t1 + 1, t2).toFloatOrNull()
                val lon = text.substring(t2 + 1, t3).toFloatOrNull()
                if (code != null && lat != null && lon != null) {
                    cs[idx] = code
                    la[idx] = lat
                    lo[idx] = lon
                    starts[idx] = sb.length
                    sb.append(text, t3 + 1, j)
                    idx++
                }
            }
            i = j + 1
        }
        starts[idx] = sb.length

        codes = if (idx == n) cs else cs.copyOf(idx)
        lats = if (idx == n) la else la.copyOf(idx)
        lons = if (idx == n) lo else lo.copyOf(idx)
        nameStart = if (idx == n) starts else starts.copyOf(idx + 1)
        names = sb.toString()
    }

    /** Nearest stop to a coordinate, or null if not loaded / empty. */
    fun nearest(lat: Double, lon: Double): Stop? {
        if (!loaded || codes.isEmpty()) return null
        val cosLat = cos(Math.toRadians(lat))
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in codes.indices) {
            val dLat = lats[i] - lat
            val dLon = (lons[i] - lon) * cosLat
            val d = dLat * dLat + dLon * dLon
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        if (best < 0) return null
        return stopAt(best)
    }

    /** The [n] nearest stops to a coordinate, ordered nearest-first. */
    fun nearestN(lat: Double, lon: Double, n: Int): List<Stop> {
        if (!loaded || codes.isEmpty()) return emptyList()
        val k = minOf(n, codes.size)
        val cosLat = cos(Math.toRadians(lat))
        val bestIdx = IntArray(k) { -1 }
        val bestD = DoubleArray(k) { Double.MAX_VALUE }
        for (i in codes.indices) {
            val dLat = lats[i] - lat
            val dLon = (lons[i] - lon) * cosLat
            val d = dLat * dLat + dLon * dLon
            if (d < bestD[k - 1]) {
                var p = k - 1
                while (p > 0 && bestD[p - 1] > d) {
                    bestD[p] = bestD[p - 1]; bestIdx[p] = bestIdx[p - 1]; p--
                }
                bestD[p] = d; bestIdx[p] = i
            }
        }
        return bestIdx.filter { it >= 0 }.map { stopAt(it) }
    }

    /**
     * Stops whose code starts with the query (numeric) or whose name contains it.
     * When [nearLat]/[nearLon] are given, results are sorted nearest-first so a
     * search like "הרצל" surfaces the stops in the user's town before others.
     */
    fun search(query: String, nearLat: Double? = null, nearLon: Double? = null, limit: Int = 20): List<Stop> {
        if (!loaded) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val numeric = q.all { it.isDigit() }
        val matches = ArrayList<Stop>()
        for (i in codes.indices) {
            val match = if (numeric) {
                codes[i].toString().startsWith(q)
            } else {
                names.substring(nameStart[i], nameStart[i + 1]).contains(q, ignoreCase = true)
            }
            if (match) matches.add(stopAt(i))
        }
        if (nearLat != null && nearLon != null) {
            val cosLat = cos(Math.toRadians(nearLat))
            matches.sortBy { s ->
                val dLat = s.lat - nearLat
                val dLon = (s.lon - nearLon) * cosLat
                dLat * dLat + dLon * dLon
            }
        }
        return if (matches.size > limit) ArrayList(matches.subList(0, limit)) else matches
    }

    private fun stopAt(i: Int): Stop = Stop(
        code = codes[i],
        lat = lats[i].toDouble(),
        lon = lons[i].toDouble(),
        name = names.substring(nameStart[i], nameStart[i + 1]),
    )

    companion object {
        const val FILE_NAME = "stops.tsv"
    }
}
