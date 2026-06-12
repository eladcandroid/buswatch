package com.eladcohen.buswatch.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale

/**
 * Refreshes the stops snapshot into filesDir in the background. Runs at most
 * once per [MAX_AGE_MS], only on unmetered networks, and writes atomically so a
 * failed download never corrupts the active file. Off the UI path entirely.
 */
object StopsUpdater {

    suspend fun maybeRefresh(context: Context) = withContext(Dispatchers.IO) {
        // Yield CPU to the UI/render thread — this download+parse is heavy.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            val dest = File(context.filesDir, StopsDb.FILE_NAME)
            val ageMs = System.currentTimeMillis() - dest.lastModified()
            if (dest.exists() && ageMs < MAX_AGE_MS) return@withContext

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (cm.isActiveNetworkMetered) {
                Log.i(TAG, "skip refresh: metered network")
                return@withContext
            }
            download(context, dest)
        } catch (e: Exception) {
            Log.w(TAG, "refresh failed: ${e.message}")
        }
    }

    private fun download(context: Context, dest: File) {
        val date = LocalDate.now().toString()
        val seen = HashSet<Int>(32000)
        val sb = StringBuilder(2_000_000)
        var offset = 0
        while (true) {
            val body = httpGet("$BASE/gtfs_stops/list?date_from=$date&date_to=$date&limit=$LIMIT&offset=$offset")
                ?: break
            val arr = JSONArray(body)
            val count = arr.length()
            if (count == 0) break
            for (i in 0 until count) {
                val o = arr.getJSONObject(i)
                val code = o.optInt("code", -1)
                if (code < 0 || !seen.add(code)) continue
                val lat = o.optDouble("lat", Double.NaN)
                val lon = o.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) continue
                val name = o.optString("name").replace('\t', ' ').replace('\n', ' ')
                sb.append(code).append('\t')
                    .append(fmt(lat)).append('\t')
                    .append(fmt(lon)).append('\t')
                    .append(name).append('\n')
            }
            offset += count
            if (count < LIMIT) break
        }
        if (sb.length < 1000) return // sanity: don't replace with garbage

        val tmp = File(dest.parentFile, "stops.tmp")
        tmp.writeText(sb.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        Log.i(TAG, "stops refreshed: ${seen.size} stops, ${dest.length()} bytes")
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.6f", v)

    private fun httpGet(url: String): String? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 25000
            setRequestProperty("Accept", "application/json")
        }
        return try {
            if (c.responseCode == 200) c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }

    private const val TAG = "StopsUpdater"
    private const val BASE = "https://open-bus-stride-api.hasadna.org.il"
    private const val LIMIT = 10000
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000 // 7 days
}
