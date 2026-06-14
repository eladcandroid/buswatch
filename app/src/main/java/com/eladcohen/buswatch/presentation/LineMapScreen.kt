package com.eladcohen.buswatch.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.eladcohen.buswatch.location.HeadingProvider
import com.eladcohen.buswatch.model.Arrival
import com.eladcohen.buswatch.model.LiveBus
import com.eladcohen.buswatch.model.Stop
import com.eladcohen.buswatch.net.CurlbusClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen live map for one line at a stop: OSM tiles, the user's position,
 * every nearby stop, and live bus markers refreshed every [POLL_MS]. Tapping a
 * stop marker selects it (same as searching it). Swipe-back or the ✕ chip exits.
 */
@Composable
fun LineMapScreen(
    stopCode: String,
    stopLat: Double,
    stopLon: Double,
    line: String,
    userLat: Double?,
    userLon: Double?,
    stops: List<Stop>,
    onStopTap: (Stop) -> Unit,
    onBack: () -> Unit,
) {
    var recenter by remember { mutableStateOf(0) }

    // Compass heading for the user marker (low-passed to tame magnetometer jitter).
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        HeadingProvider(context).updates().collect { raw ->
            val prev = heading
            heading = if (prev == null) raw else {
                var delta = (raw - prev + 540f) % 360f - 180f
                (prev + delta * 0.25f + 360f) % 360f
            }
        }
    }

    // Latest fix from the feed (the glide target) + a per-vehicle snapshot of
    // where each marker currently sits, so a new poll glides from there.
    var target by remember(stopCode) { mutableStateOf<List<LiveBus>>(emptyList()) }
    // Full board (all arrivals, incl. schedule-only) for the remaining-time pill.
    var arrivals by remember(stopCode) { mutableStateOf<List<Arrival>>(emptyList()) }
    val anim = remember(stopCode) { Animatable(1f) }
    val fromByRef = remember(stopCode) { mutableMapOf<String, Pair<Double, Double>>() }
    // Last travel bearing per bus, kept across static polls so the arrow holds
    // its direction instead of blinking off whenever a fix repeats.
    val headingByRef = remember(stopCode) { mutableMapOf<String, Double>() }

    // Poll the stop every POLL_MS (board + positions in one request). Snapshot
    // where each marker sits now as the glide start, swap in the fresh fix, then
    // glide old→new across the interval so buses are seen travelling.
    LaunchedEffect(stopCode) {
        while (true) {
            val res = runCatching { CurlbusClient.fetchStop(stopCode) }.getOrNull()
            res?.let { arrivals = it.first.arrivals }
            val fresh = res?.second ?: emptyList()
            val shownNow = interpolatedBuses(fromByRef, target, anim.value, headingByRef)
            fromByRef.clear()
            shownNow.forEach { fromByRef[it.ref] = it.lat to it.lon }
            // Refresh travel bearing for buses that actually moved since last shown.
            fresh.forEach { b ->
                val p = fromByRef[b.ref]
                if (p != null && abs(b.lat - p.first) + abs(b.lon - p.second) > 1e-5) {
                    headingByRef[b.ref] = bearing(p.first, p.second, b.lat, b.lon)
                }
            }
            target = fresh
            anim.snapTo(0f)
            launch { anim.animateTo(1f, tween(durationMillis = ANIM_MS, easing = LinearEasing)) }
            delay(POLL_MS)
        }
    }

    val shown = interpolatedBuses(fromByRef, target, anim.value, headingByRef)
    // Next arrival for this line from the board (works even with no live GPS bus).
    val lineArr = arrivals.firstOrNull { it.line == line }
    val soonest = lineArr?.etaMinutes
    val pillText = buildAnnotatedString {
        append("קו $line")
        if (soonest != null) {
            append(" · ")
            if (soonest <= 0) {
                withStyle(SpanStyle(color = NowColor, fontWeight = FontWeight.Bold)) { append("עכשיו") }
            } else {
                append("עוד $soonest דק׳")
            }
            // GPS status: ● green = tracked on map, ○ gray = schedule-only (no marker).
            append("  ")
            if (lineArr.hasGps) withStyle(SpanStyle(color = GpsLiveColor)) { append("●") }
            else withStyle(SpanStyle(color = NoGpsColor)) { append("○") }
        }
    }

    BackHandler(enabled = true) { onBack() }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            initialLat = stopLat,
            initialLon = stopLon,
            userLat = userLat,
            userLon = userLon,
            userHeading = heading,
            buses = shown,
            highlightLine = line,
            stops = stops,
            focusStopCode = stopCode.toIntOrNull() ?: -1,
            recenterSignal = recenter,
            onStopTap = onStopTap,
            modifier = Modifier.fillMaxSize(),
        )

        // Top pill: line + soonest live arrival + how many buses are en route.
        Text(
            text = pillText,
            style = MaterialTheme.typography.caption1,
            color = Color.White,
            textAlign = TextAlign.Center,
            // Capped width + centred: long content (3-digit line, 2-digit ETA)
            // wraps to a second line inside the round screen instead of clipping.
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 26.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xCC000000))
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .widthIn(max = 132.dp),
        )

        // Back to the board — compact chevron at the mid-edge (drag-to-pan can
        // swallow the swipe-dismiss gesture, so give a small explicit control).
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 3.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0xAA000000))
                .clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Text("‹", color = Color.White, style = MaterialTheme.typography.title2)
        }

        // Recenter on the stop (after panning/zooming away).
        CompactChip(
            onClick = { recenter++ },
            label = { Text("⌖", textAlign = TextAlign.Center) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        )
    }
}

/**
 * Re-projects [target] bus positions part-way (fraction [t]) from where each
 * vehicle was last shown ([fromByRef], keyed by vehicle_ref) toward its newest
 * fix — so markers glide along the road instead of jumping. Buses with no prior
 * position (just appeared) render straight at the target.
 */
private fun interpolatedBuses(
    fromByRef: Map<String, Pair<Double, Double>>,
    target: List<LiveBus>,
    t: Float,
    headingByRef: Map<String, Double>,
): List<LiveBus> = target.map { b ->
    val from = fromByRef[b.ref]
    val h = headingByRef[b.ref]
    if (from == null) b.copy(heading = h)
    else b.copy(
        lat = from.first + (b.lat - from.first) * t,
        lon = from.second + (b.lon - from.second) * t,
        heading = h,
    )
}

/** Initial great-circle bearing, degrees clockwise from north. */
private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

private const val POLL_MS = 12_000L
private const val ANIM_MS = 11_000
