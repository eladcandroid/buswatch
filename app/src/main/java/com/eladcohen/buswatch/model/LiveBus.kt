package com.eladcohen.buswatch.model

/**
 * A live bus position en route to a stop, from curlbus SIRI per-visit
 * `location`. [ref] is the MoT vehicle_ref — a stable id per physical bus,
 * used to keep markers identified across polls.
 */
data class LiveBus(
    val line: String,
    val lat: Double,
    val lon: Double,
    val etaMinutes: Int,
    val ref: String,
    /** Travel bearing in degrees clockwise from north, when known (moving). */
    val heading: Double? = null,
)
