package com.eladcohen.buswatch.phone

import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal TCP push server. The watch connects, immediately gets the latest
 * board snapshot, then one NDJSON line per update. No request protocol.
 */
class BoardServer(private val port: Int) {

    @Volatile private var latest: String? = null
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    fun start() {
        if (running) return
        running = true
        thread(name = "BoardServer", isDaemon = true) {
            try {
                ServerSocket(port).also { serverSocket = it }.use { ss ->
                    Log.i(TAG, "listening on :$port")
                    while (running) {
                        val socket = ss.accept()
                        clients.add(socket)
                        Log.i(TAG, "client ${socket.inetAddress.hostAddress} (${clients.size} total)")
                        latest?.let { send(socket, it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "server stopped: ${e.message}")
            }
        }
    }

    fun broadcast(json: String) {
        if (json == latest) return
        latest = json
        val dead = ArrayList<Socket>()
        for (s in clients) if (!send(s, json)) dead.add(s)
        for (s in dead) {
            clients.remove(s)
            runCatching { s.close() }
        }
    }

    private fun send(socket: Socket, json: String): Boolean = try {
        socket.getOutputStream().apply {
            write((json + "\n").toByteArray(Charsets.UTF_8))
            flush()
        }
        true
    } catch (e: Exception) {
        false
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        for (s in clients) runCatching { s.close() }
        clients.clear()
    }

    companion object {
        private const val TAG = "BoardServer"
    }
}
