package com.eladcohen.buswatch.model

/** A physical bus stop from the bundled MoT GTFS snapshot. */
data class Stop(
    val code: Int,
    val lat: Double,
    val lon: Double,
    val name: String,
)
