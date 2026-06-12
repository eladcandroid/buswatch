package com.eladcohen.buswatch.model

/** One real-time arrival for a line at a stop. */
data class Arrival(
    val line: String,
    val destination: String,
    val area: String,
    val etaMinutes: Int,
    val realtime: Boolean,
    val nextMinutes: Int?,
)

/** A stop's live board: header + ordered arrivals. */
data class StopBoard(
    val stopName: String,
    val stopCode: String,
    val arrivals: List<Arrival>,
)

/**
 * Sample board captured from Nearby Bus (com.mosko.bus) via the accessibility
 * tree on 2026-06-12. Stands in for live Data Layer input until Milestone 2.
 */
object SampleData {
    val board = StopBoard(
        stopName = "אדמונית החורש/הרב חיים פינטו",
        stopCode = "14158",
        arrivals = listOf(
            Arrival(line = "1", destination = "מסוף המעפילים", area = "שדרות", etaMinutes = 12, realtime = true, nextMinutes = 33),
            Arrival(line = "5", destination = "מסוף המעפילים", area = "שדרות", etaMinutes = 12, realtime = true, nextMinutes = 35),
            Arrival(line = "4", destination = "מסוף המעפילים", area = "שדרות", etaMinutes = 16, realtime = true, nextMinutes = 48),
            Arrival(line = "2", destination = "מסוף המעפילים", area = "שדרות", etaMinutes = 28, realtime = false, nextMinutes = 57),
        ),
    )
}
