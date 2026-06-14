package com.eladcohen.buswatch.presentation

import android.Manifest
import android.app.RemoteInput
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.input.RemoteInputIntentHelper
import com.eladcohen.buswatch.data.StopStore
import com.eladcohen.buswatch.data.StopsDb
import com.eladcohen.buswatch.model.Stop
import com.eladcohen.buswatch.presentation.theme.BusWatchTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var stopsDb: StopsDb
    private lateinit var controller: NearbyBusController
    private val searchResults = MutableStateFlow<List<Stop>>(emptyList())
    private var started = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { start() }

    private val searchInput =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val query = RemoteInput.getResultsFromIntent(res.data)
                ?.getCharSequence(KEY_QUERY)?.toString().orEmpty()
            if (query.isNotBlank()) {
                val loc = controller.lastLocation()
                lifecycleScope.launch {
                    searchResults.value = withContext(Dispatchers.Default) {
                        stopsDb.search(query, loc?.latitude, loc?.longitude)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        stopsDb = StopsDb(applicationContext)
        controller = NearbyBusController(applicationContext, stopsDb, StopStore(applicationContext))

        setContent {
            BusWatchTheme {
                var onSearchScreen by remember { mutableStateOf(false) }
                var mapTarget by remember { mutableStateOf<MapTarget?>(null) }
                var mapStops by remember { mutableStateOf<List<Stop>>(emptyList()) }
                val boards by controller.boards.collectAsState()
                val link by controller.link.collectAsState()
                val status by controller.statusText.collectAsState()
                val mode by controller.mode.collectAsState()
                val results by searchResults.collectAsState()

                BackHandler(enabled = onSearchScreen) { onSearchScreen = false }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val target = mapTarget
                    if (target != null) {
                        val loc = controller.lastLocation()
                        LineMapScreen(
                            stopCode = target.stopCode,
                            stopLat = target.lat,
                            stopLon = target.lon,
                            line = target.line,
                            userLat = loc?.latitude,
                            userLon = loc?.longitude,
                            stops = mapStops,
                            onStopTap = { stop ->
                                lifecycleScope.launch { controller.selectFixed(stop) }
                                mapTarget = null
                            },
                            onBack = { mapTarget = null },
                        )
                    } else if (onSearchScreen) {
                        StopSearchScreen(
                            results = results,
                            onNearby = {
                                lifecycleScope.launch { controller.selectNearby() }
                                searchResults.value = emptyList()
                                onSearchScreen = false
                            },
                            onSearch = { launchVoiceSearch() },
                            onPick = { stop ->
                                lifecycleScope.launch { controller.selectFixed(stop) }
                                searchResults.value = emptyList()
                                onSearchScreen = false
                            },
                        )
                    } else {
                        if (boards.isEmpty()) {
                            StatusScreen(status)
                        } else {
                            BusBoardScreen(
                                boards = boards,
                                link = link,
                                mode = mode,
                                onOpenStops = { onSearchScreen = true },
                                onOpenMap = { board, arrival -> openMap(board, arrival) { t, s ->
                                    mapStops = s
                                    mapTarget = t
                                } },
                            )
                        }
                    }
                }
            }
        }

        if (hasPermission()) start() else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** Resolve a tapped card to map coords + nearby stops, then open the map. */
    private fun openMap(
        board: com.eladcohen.buswatch.model.StopBoard,
        arrival: com.eladcohen.buswatch.model.Arrival,
        onReady: (MapTarget, List<Stop>) -> Unit,
    ) {
        val stop = controller.currentStops().firstOrNull { it.code.toString() == board.stopCode }
        lifecycleScope.launch {
            val near = withContext(Dispatchers.Default) {
                runCatching { stopsDb.load() }
                val lat = stop?.lat ?: return@withContext emptyList<Stop>()
                stopsDb.nearestN(lat, stop.lon, MAP_STOPS)
            }
            val lat = stop?.lat ?: near.firstOrNull()?.lat ?: return@launch
            val lon = stop?.lon ?: near.firstOrNull()?.lon ?: return@launch
            onReady(MapTarget(board.stopCode, lat, lon, arrival.line), near)
        }
    }

    private fun launchVoiceSearch() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val inputs = listOf(RemoteInput.Builder(KEY_QUERY).setLabel("שם או מספר תחנה").build())
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, inputs)
        searchInput.launch(intent)
    }

    private fun start() {
        if (started) return
        started = true
        // Run only while STARTED: GPS + polling stop when the app is backgrounded
        // / screen off, and resume on return. Avoids background battery drain.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.run()
            }
        }
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val KEY_QUERY = "q"
        private const val MAP_STOPS = 160
    }
}

/** A line-at-a-stop the live map is opened for. */
data class MapTarget(
    val stopCode: String,
    val lat: Double,
    val lon: Double,
    val line: String,
)
