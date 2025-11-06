package com.example.private_signaling_network_connection_test_app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlin.math.max

private const val UDP_TAG = "UDPClient"

/**
 * UDP client with three directions and configurable send period and optional
 * periodic connection resets.
 *
 * @param host Server hostname
 * @param port UDP port (default 8000)
 * @param periodMs Message interval in milliseconds (>=100ms)
 * @param readTimeoutMs Socket receive timeout (ms)
 * @param direction Direction mode for UDP
 * @param resetEverySec If >0, close the socket and recreate it every X seconds
 * @param resetDowntimeSec Seconds to sleep between closing and recreating socket
 */
suspend fun runUdpClient(
    host: String,
    port: Int = 8000,
    periodMs: Long = 1000L,
    readTimeoutMs: Int = 1500,
    direction: UdpDirection = UdpDirection.BOTH,
    resetEverySec: Long = 0L,
    resetDowntimeSec: Long = 0L
) {
    val addr = withContext(Dispatchers.IO) { InetAddress.getByName(host) }
    val minPeriod = max(100L, periodMs)

    while (coroutineContext.isActive) {
        var didReset = false
        val startNs = System.nanoTime()

        DatagramSocket().use { sock ->
            sock.soTimeout = readTimeoutMs
            sock.connect(addr, port) // restricts receive() and sets default target
            Log.i(
                UDP_TAG,
                "UDP connected (pseudo) to $host:$port from ${sock.localAddress?.hostAddress}:${sock.localPort} dir=$direction " +
                        "(period=${minPeriod}ms resetEvery=${resetEverySec}s downtime=${resetDowntimeSec}s)"
            )

            var seq = 1L
            val recvBuf = ByteArray(4096)

            try {
                // --- ONE-TIME PRIME if we are in receive-only mode ---
                if (direction == UdpDirection.SERVER_TO_CLIENT) {
                    val prime = "PRIME from-android"
                    val primeBytes = prime.toByteArray()
                    val primePkt = DatagramPacket(primeBytes, primeBytes.size)
                    sock.send(primePkt)
                    Log.i(UDP_TAG, "TX PRIME (${primeBytes.size}B): $prime")
                    // After this, do not send periodically—receive only
                }

                while (coroutineContext.isActive) {
                    // Optional time-based reset
                    if (resetEverySec > 0) {
                        val elapsedSec = (System.nanoTime() - startNs) / 1_000_000_000L
                        if (elapsedSec >= resetEverySec) {
                            didReset = true
                            Log.i(UDP_TAG, "Timed reset after ${elapsedSec}s")
                            break
                        }
                    }

                    // ---- SEND (Client → Server) ----
                    if (direction == UdpDirection.CLIENT_TO_SERVER || direction == UdpDirection.BOTH) {
                        val payload = "HELLO seq=$seq from-android"
                        val out = payload.toByteArray()
                        val sendPkt = DatagramPacket(out, out.size)
                        sock.send(sendPkt)
                        Log.i(UDP_TAG, "TX (${out.size}B): $payload")
                        seq++
                    }

                    // ---- RECV (Server → Client) ----
                    if (direction == UdpDirection.SERVER_TO_CLIENT || direction == UdpDirection.BOTH) {
                        val pkt = DatagramPacket(recvBuf, recvBuf.size)
                        try {
                            sock.receive(pkt)  // bounded by soTimeout
                            val text = String(pkt.data, pkt.offset, pkt.length).trim()
                            if (text.startsWith("{") && text.endsWith("}")) {
                                try {
                                    val json = JSONObject(text)
                                    val hello = json.optString("hello", null)
                                    if (hello != null) {
                                        Log.i(
                                            UDP_TAG,
                                            "RX JSON from ${pkt.address.hostAddress}:${pkt.port}: hello=$hello"
                                        )
                                    } else {
                                        Log.i(
                                            UDP_TAG,
                                            "RX JSON from ${pkt.address.hostAddress}:${pkt.port}: $json"
                                        )
                                    }
                                } catch (je: Exception) {
                                    Log.w(
                                        UDP_TAG,
                                        "RX (not valid JSON?) ${pkt.address.hostAddress}:${pkt.port} -> $text"
                                    )
                                }
                            } else {
                                Log.d(
                                    UDP_TAG,
                                    "RX ${pkt.length}B from ${pkt.address.hostAddress}:${pkt.port}: $text"
                                )
                            }
                        } catch (ste: SocketTimeoutException) {
                            // no datagram this tick; fine
                        }
                    }

                    delay(minPeriod)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(UDP_TAG, "Error: ${t.message}", t)
                // exit; outer loop may recreate
            }
        }

        if (didReset && resetDowntimeSec > 0) {
            Log.i(UDP_TAG, "Sleeping for ${resetDowntimeSec}s before reconnect (UDP)")
            repeat(resetDowntimeSec.toInt()) {
                if (!coroutineContext.isActive) return
                delay(1000L)
            }
        } else if (!didReset) {
            // error or cancellation -> backoff a little to avoid spin
            delay(500L)
        }
    }
}
