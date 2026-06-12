package com.eladcohen.buswatch.net

import com.eladcohen.buswatch.model.Arrival
import com.eladcohen.buswatch.model.StopBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/**
 * Standalone live source: pulls SIRI real-time arrivals straight from the
 * community MoT wrapper (curlbus.app) by stop code. No phone required.
 */
object CurlbusClient {

    private val etaFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX")

    suspend fun fetch(stopCode: String, stopName: String): StopBoard = withContext(Dispatchers.IO) {
        val conn = (URL("https://curlbus.app/$stopCode").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WatchMirror/0.1")
            connectTimeout = 6000
            readTimeout = 6000
        }
        val body = try {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
        parse(stopCode, stopName, body)
    }

    private data class Visit(val line: String, val dest: String, val minutes: Int, val realtime: Boolean)

    private fun parse(stopCode: String, stopName: String, body: String): StopBoard {
        val visitsObj = JSONObject(body).getJSONObject("visits")
        val arr = visitsObj.optJSONArray(stopCode)
        val now = OffsetDateTime.now()

        val visits = ArrayList<Visit>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val line = o.optString("line_name")
                if (line.isBlank()) continue
                val etaStr = o.optString("eta")
                if (etaStr.isBlank()) continue
                val minutes = runCatching {
                    val eta = OffsetDateTime.parse(etaStr, etaFmt)
                    max(0L, ChronoUnit.SECONDS.between(now, eta)).toInt() / 60
                }.getOrNull() ?: continue
                val dest = o.optJSONObject("static_info")
                    ?.optJSONObject("route")
                    ?.optJSONObject("destination")
                    ?.optJSONObject("name")
                    ?.optString("HE")
                    .orEmpty()
                visits.add(Visit(line, dest, minutes, o.optString("producer") == "SIRI"))
            }
        }

        // One card per line: soonest = eta, the one after = "ובעוד".
        val arrivals = visits.groupBy { it.line }
            .map { (line, vs) ->
                val sorted = vs.sortedBy { it.minutes }
                Arrival(
                    line = line,
                    destination = sorted.first().dest,
                    area = "",
                    etaMinutes = sorted.first().minutes,
                    realtime = sorted.first().realtime,
                    nextMinutes = sorted.getOrNull(1)?.minutes,
                )
            }
            .sortedBy { it.etaMinutes }

        return StopBoard(
            stopName = stopName.ifBlank { "תחנה" },
            stopCode = stopCode,
            arrivals = arrivals,
        )
    }
}
