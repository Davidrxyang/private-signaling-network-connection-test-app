package com.example.private_signaling_network_connection_test_app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.math.max

private const val TAG = "TCPClient"

/**
 * TCP client with configurable send period and optional periodic connection resets.
 *
 * @param periodMs Message interval in milliseconds (>=100ms)
 * @param resetEverySec If >0, close socket and reconnect every X seconds
 * @param resetDowntimeSec Seconds to sleep between closing and reconnecting
 */
suspend fun runTcpClient(
    host: String,
    port: Int = 8000,
    periodMs: Long = 1000L,
    connectTimeoutMs: Int = 3000,
    readTimeoutMs: Int = 1500,
    resetEverySec: Long = 0L,
    resetDowntimeSec: Long = 0L
) {
    val minPeriod = max(100L, periodMs)
    var attempt = 0
    while (true) {
        var didReset = false
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = readTimeoutMs   // bounded blocking read

                val out = sock.getOutputStream().bufferedWriter()
                val inp = sock.getInputStream().bufferedReader()

                Log.i(TAG, "Connected to $host:$port (period=${minPeriod}ms resetEvery=${resetEverySec}s downtime=${resetDowntimeSec}s)")

                supervisorScope {
                    // Writer: trigger one server reply per heartbeat
                    val writer = launch {
                        var seq = 1L
                        while (isActive) {
                            val msg = "HELLO seq=$seq from-android\r\n" // send a line
                            out.write(msg); out.flush()
                            Log.i(TAG, "TX: ${msg.trim()}")
                            seq++
                            delay(minPeriod)
                        }
                    }

                    // Reader: one JSON object per line
                    val reader = launch {
                        while (isActive) {
                            try {
                                val line = inp.readLine() ?: throw EndOfStream("server closed")
                                val t = line.trim()
                                if (t.startsWith("{") && t.endsWith("}")) {
                                    try {
                                        val json = JSONObject(t)
                                        val hello = json.optString("hello", null)
                                        if (hello != null) {
                                            Log.i(TAG, "RX JSON: hello=$hello")
                                        } else {
                                            Log.i(TAG, "RX JSON: $json")
                                        }
                                    } catch (je: Exception) {
                                        Log.w(TAG, "RX (not valid JSON?): $t")
                                    }
                                } else {
                                    Log.d(TAG, "RX: $t")
                                }
                            } catch (e: SocketTimeoutException) {
                                // no data this cycle; keep looping
                            }
                        }
                    }

                    // Watchdog for timed reset
                    val watchdog = launch {
                        if (resetEverySec > 0) {
                            val millis = resetEverySec * 1000L
                            var waited = 0L
                            while (isActive && waited < millis) {
                                delay(200L)
                                waited += 200L
                            }
                            if (isActive) {
                                didReset = true
                                Log.i(TAG, "Timed reset after ${resetEverySec}s")
                            }
                        }
                    }

                    try {
                        // Loop until reset triggers or coroutine cancelled
                        while (isActive) {
                            if (didReset) break
                            delay(200L)
                        }
                    } finally {
                        writer.cancel(); reader.cancel(); watchdog.cancel()
                    }
                }
            }
            attempt = 0
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            if (!didReset) {
                attempt++
                val backoffMs = (500L * attempt).coerceAtMost(5000L)
                Log.e(TAG, "TCP error: ${e.message} (attempt $attempt). Reconnecting in ${backoffMs}ms", e)
                delay(backoffMs)
            }
        }

        if (didReset && resetDowntimeSec > 0) {
            Log.i(TAG, "Sleeping for ${resetDowntimeSec}s before reconnect (TCP)")
            repeat(resetDowntimeSec.toInt()) {
                delay(1000L)
            }
        }
    }
}

private class EndOfStream(msg: String) : Exception(msg)
