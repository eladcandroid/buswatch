package com.eladcohen.buswatch.presentation

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.eladcohen.buswatch.model.LiveBus
import com.eladcohen.buswatch.model.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

private const val TILE = 256.0

/** Zoom levels gained per rotary scroll pixel — small = smooth, gradual zoom. */
private const val ZOOM_SENS = 0.0035f
private const val MIN_ZOOM = 13f
private const val MAX_ZOOM = 18f
/** Opens wide enough that approaching buses (often 1–2 km out) are in view. */
private const val DEFAULT_ZOOM = 15f

/** Integer tile level under a fractional zoom, and the scale to stretch it by. */
private fun baseZoom(zoom: Float) = floor(zoom).toInt()
private fun zoomScale(zoom: Float) = Math.pow(2.0, (zoom - floor(zoom)).toDouble()).toFloat()

/** OpenStreetMap raster tiles — free, no API key. Web-Mercator (EPSG:3857). */
private object TileStore {
    private val cache = LruCache<String, ImageBitmap>(80)

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun fetch(z: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$z/$x/$y"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val conn = (URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "BusWatch/1.0 (eladc.android@gmail.com)")
                connectTimeout = 6000
                readTimeout = 6000
            }
            val bmp = try {
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            } finally {
                conn.disconnect()
            }
            bmp?.asImageBitmap()?.also { cache.put(key, it) }
        }.getOrNull()
    }
}

private fun lonToWorldX(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z) * TILE
private fun latToWorldY(lat: Double, z: Int): Double {
    val r = Math.toRadians(lat)
    return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z) * TILE
}
private fun worldXToLon(x: Double, z: Int) = x / (TILE * (1 shl z)) * 360.0 - 180.0
private fun worldYToLat(y: Double, z: Int): Double {
    val n = PI - 2.0 * PI * y / (TILE * (1 shl z))
    return Math.toDegrees(atan(sinh(n)))
}

/**
 * Slippy-map view rendered on a Canvas: OSM tiles + overlay markers (nearby
 * stops, the user, and live buses). Drag to pan, rotate the crown to zoom,
 * tap a stop marker to select it.
 */
@Composable
fun OsmMap(
    initialLat: Double,
    initialLon: Double,
    userLat: Double?,
    userLon: Double?,
    userHeading: Float?,
    buses: List<LiveBus>,
    highlightLine: String,
    stops: List<Stop>,
    focusStopCode: Int,
    recenterSignal: Int,
    onStopTap: (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(DEFAULT_ZOOM) }
    var centerLat by remember { mutableStateOf(initialLat) }
    var centerLon by remember { mutableStateOf(initialLon) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // Re-center on the target stop when the map opens or the user taps recenter.
    LaunchedEffect(initialLat, initialLon, recenterSignal) {
        centerLat = initialLat
        centerLon = initialLon
        zoom = DEFAULT_ZOOM
    }

    val baseZ = baseZoom(zoom)

    // Load every tile the current integer level needs (skips cached ones).
    LaunchedEffect(centerLat, centerLon, baseZ, canvasSize) {
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        val z = baseZ
        val cwx = lonToWorldX(centerLon, z)
        val cwy = latToWorldY(centerLat, z)
        val halfW = canvasSize.width / 2.0
        val halfH = canvasSize.height / 2.0
        val maxTile = (1 shl z) - 1
        val minTx = floor((cwx - halfW) / TILE).toInt()
        val maxTx = floor((cwx + halfW) / TILE).toInt()
        val minTy = floor((cwy - halfH) / TILE).toInt()
        val maxTy = floor((cwy + halfH) / TILE).toInt()
        for (tx in minTx..maxTx) for (ty in minTy..maxTy) {
            if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue
            val key = "$z/$tx/$ty"
            if (tiles[key] == null) {
                TileStore.cached(key)?.let { tiles[key] = it }
                    ?: scope.launch { TileStore.fetch(z, tx, ty)?.let { tiles[key] = it } }
            }
        }
    }

    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
    }
    // White ETA label with a dark halo so it stays readable over any tile.
    val etaPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(5f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    Box(modifier.onSizeChanged { canvasSize = it }) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { _, drag ->
                        val z = baseZoom(zoom)
                        val sc = zoomScale(zoom)
                        // Screen drag → world delta is smaller the more we're zoomed in.
                        centerLon = worldXToLon(lonToWorldX(centerLon, z) - drag.x / sc, z)
                        centerLat = worldYToLat(latToWorldY(centerLat, z) - drag.y / sc, z)
                    }
                }
                .pointerInput(stops, centerLat, centerLon, canvasSize) {
                    detectTapGestures { tap ->
                        val z = baseZoom(zoom)
                        val sc = zoomScale(zoom)
                        val cwx = lonToWorldX(centerLon, z)
                        val cwy = latToWorldY(centerLat, z)
                        val cx = size.width / 2.0
                        val cy = size.height / 2.0
                        var best: Stop? = null
                        var bestD = Double.MAX_VALUE
                        for (s in stops) {
                            val ux = lonToWorldX(s.lon, z) - cwx + cx
                            val uy = latToWorldY(s.lat, z) - cwy + cy
                            val ssx = cx + (ux - cx) * sc - tap.x
                            val ssy = cy + (uy - cy) * sc - tap.y
                            val d = ssx * ssx + ssy * ssy
                            if (d < bestD) {
                                bestD = d
                                best = s
                            }
                        }
                        val hit = best
                        if (hit != null && bestD <= 28.0 * 28.0) onStopTap(hit)
                    }
                }
                .onRotaryScrollEvent { e ->
                    // Continuous fractional zoom — the map scales smoothly, no level jumps.
                    zoom = (zoom + e.verticalScrollPixels * ZOOM_SENS).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            val z = baseZoom(zoom)
            val sc = zoomScale(zoom)
            val cwx = lonToWorldX(centerLon, z)
            val cwy = latToWorldY(centerLat, z)
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Unscaled (base-level) screen position, and the same stretched by the
            // zoom fraction about the centre — markers use the scaled one but keep
            // a fixed pixel size so they don't pulse as the tiles scale.
            fun ux(lon: Double) = (lonToWorldX(lon, z) - cwx).toFloat() + cx
            fun uy(lat: Double) = (latToWorldY(lat, z) - cwy).toFloat() + cy
            fun ssx(lon: Double) = cx + (ux(lon) - cx) * sc
            fun ssy(lat: Double) = cy + (uy(lat) - cy) * sc

            drawRect(Color(0xFF2B2B2B))

            // Tiles, scaled about the centre for smooth fractional zoom.
            scale(sc, pivot = Offset(cx, cy)) {
                val maxTile = (1 shl z) - 1
                val halfW = size.width / 2.0
                val halfH = size.height / 2.0
                val minTx = floor((cwx - halfW) / TILE).toInt()
                val maxTx = floor((cwx + halfW) / TILE).toInt()
                val minTy = floor((cwy - halfH) / TILE).toInt()
                val maxTy = floor((cwy + halfH) / TILE).toInt()
                for (tx in minTx..maxTx) for (ty in minTy..maxTy) {
                    if (tx < 0 || ty < 0 || tx > maxTile || ty > maxTile) continue
                    val img = tiles["$z/$tx/$ty"] ?: continue
                    drawImage(
                        img,
                        topLeft = Offset((tx * TILE - cwx).toFloat() + cx, (ty * TILE - cwy).toFloat() + cy),
                    )
                }
            }

            // Nearby stops — gray dots; the focus stop gets a larger amber ring.
            stops.forEach { s ->
                val x = ssx(s.lon)
                val y = ssy(s.lat)
                if (s.code == focusStopCode) {
                    drawCircle(Color.White, 7.dp.toPx(), Offset(x, y))
                    drawCircle(Color(0xFFEF6C00), 5.5.dp.toPx(), Offset(x, y))
                } else {
                    drawCircle(Color.White, 4.dp.toPx(), Offset(x, y))
                    drawCircle(Color(0xFF455A64), 3.dp.toPx(), Offset(x, y))
                }
            }

            // User location — blue dot + heading arrow (north-up map).
            if (userLat != null && userLon != null) {
                val x = ssx(userLon)
                val y = ssy(userLat)
                userHeading?.let { h ->
                    rotate(h, pivot = Offset(x, y)) {
                        val tip = Path().apply {
                            moveTo(x, y - 15.dp.toPx())
                            lineTo(x - 5.5.dp.toPx(), y - 5.dp.toPx())
                            lineTo(x + 5.5.dp.toPx(), y - 5.dp.toPx())
                            close()
                        }
                        drawPath(tip, Color(0xFF1E88E5))
                    }
                }
                drawCircle(Color.White, 7.dp.toPx(), Offset(x, y))
                drawCircle(Color(0xFF1E88E5), 5.dp.toPx(), Offset(x, y))
            }

            // Live buses — circle + line number + travel-direction arrow.
            textPaint.textSize = 11.dp.toPx()
            buses.forEach { b ->
                val x = ssx(b.lon)
                val y = ssy(b.lat)
                val col = if (b.line == highlightLine) Color(0xFFFF6D00) else Color(0xFF00897B)
                // Travel-direction arrow, drawn first so the circle hides its base
                // and only a coloured, white-outlined tip protrudes (legible on any tile).
                b.heading?.let { h ->
                    rotate(h.toFloat(), pivot = Offset(x, y)) {
                        val tip = Path().apply {
                            moveTo(x, y - 19.dp.toPx())
                            lineTo(x - 6.5.dp.toPx(), y - 5.dp.toPx())
                            lineTo(x + 6.5.dp.toPx(), y - 5.dp.toPx())
                            close()
                        }
                        drawPath(tip, col)
                        drawPath(tip, Color.White, style = Stroke(width = 1.5.dp.toPx()))
                    }
                }
                drawCircle(Color.White, 11.dp.toPx(), Offset(x, y))
                drawCircle(col, 9.5.dp.toPx(), Offset(x, y))
                drawIntoCanvas { it.nativeCanvas.drawText(b.line, x, y + 4.dp.toPx(), textPaint) }
                // Arrival time under the marker — "עכשיו" (yellow) when due now.
                etaPaint.textSize = 10.dp.toPx()
                if (b.etaMinutes <= 0) {
                    etaPaint.color = android.graphics.Color.parseColor("#FFEB3B")
                    drawIntoCanvas { it.nativeCanvas.drawText("עכשיו", x, y + 21.dp.toPx(), etaPaint) }
                    etaPaint.color = android.graphics.Color.WHITE
                } else {
                    drawIntoCanvas { it.nativeCanvas.drawText("${b.etaMinutes}׳", x, y + 21.dp.toPx(), etaPaint) }
                }
            }
        }
    }
}
