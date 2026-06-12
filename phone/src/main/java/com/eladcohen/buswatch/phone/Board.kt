package com.eladcohen.buswatch.phone

import org.json.JSONArray
import org.json.JSONObject

data class Arrival(
    val line: String,
    val destination: String,
    val area: String,
    val etaMinutes: Int,
    val realtime: Boolean,
    val nextMinutes: Int?,
)

data class StopBoard(
    val stopName: String,
    val stopCode: String,
    val arrivals: List<Arrival>,
)

/** Serialize to a single-line JSON object (NDJSON wire format to the watch). */
fun StopBoard.toJson(): String {
    val arr = JSONArray()
    arrivals.forEach { a ->
        arr.put(
            JSONObject().apply {
                put("line", a.line)
                put("destination", a.destination)
                put("area", a.area)
                put("etaMinutes", a.etaMinutes)
                put("realtime", a.realtime)
                put("nextMinutes", a.nextMinutes ?: JSONObject.NULL)
            },
        )
    }
    return JSONObject().apply {
        put("stopName", stopName)
        put("stopCode", stopCode)
        put("arrivals", arr)
    }.toString()
}
