package com.eladcohen.buswatch.phone

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Watches Nearby Bus, parses each on-screen update into a [StopBoard], and
 * pushes it to connected watches via [BoardServer].
 */
class BusAccessibilityService : AccessibilityService() {

    private val server = BoardServer(PORT)

    override fun onServiceConnected() {
        super.onServiceConnected()
        server.start()
        Log.i(TAG, "connected; serving board on :$PORT")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        if (root.packageName != TARGET_PKG) return
        val board = runCatching { BusParser.parse(root) }.getOrNull() ?: return
        val json = board.toJson()
        server.broadcast(json)
        Log.d(TAG, "board: ${board.stopName} (${board.arrivals.size} lines)")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    companion object {
        const val TAG = "BusA11y"
        const val PORT = 8731
        const val TARGET_PKG = "com.mosko.bus"
    }
}
