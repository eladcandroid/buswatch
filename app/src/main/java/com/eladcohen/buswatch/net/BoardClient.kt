package com.eladcohen.buswatch.net

import android.util.Log
import com.eladcohen.buswatch.model.Arrival
import com.eladcohen.buswatch.model.StopBoard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.coroutineContext

enum class LinkState { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Connects to the phone's [BoardServer] over Wi-Fi and exposes the latest
 * [StopBoard] as a flow. Reconnects forever with a short backoff.
 */
class BoardClient(private val host: String, private val port: Int) {

    val board = MutableStateFlow<StopBoard?>(null)
    val link = MutableStateFlow(LinkState.CONNECTING)

    suspend fun run() = withContext(Dispatchers.IO) {
        while (coroutineContext.isActive) {
            link.value = LinkState.CONNECTING
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    link.value = LinkState.CONNECTED
                    Log.i(TAG, "connected to $host:$port")
                    val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                    while (coroutineContext.isActive) {
                        val line = reader.readLine() ?: break
                        parse(line)?.let { board.value = it }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "link error: ${e.message}")
            }
            link.value = LinkState.DISCONNECTED
            delay(RETRY_MS)
        }
    }

    private fun parse(line: String): StopBoard? = runCatching {
        val o = JSONObject(line)
        val arr = o.getJSONArray("arrivals")
        val list = (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            Arrival(
                line = a.getString("line"),
                destination = a.optString("destination"),
                area = a.optString("area"),
                etaMinutes = a.getInt("etaMinutes"),
                realtime = a.optBoolean("realtime"),
                nextMinutes = if (a.isNull("nextMinutes")) null else a.getInt("nextMinutes"),
            )
        }
        StopBoard(
            stopName = o.optString("stopName"),
            stopCode = o.optString("stopCode"),
            arrivals = list,
        )
    }.getOrNull()

    companion object {
        private const val TAG = "BoardClient"
        private const val CONNECT_TIMEOUT_MS = 4000
        private const val RETRY_MS = 2000L
    }
}
