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

private const val UDP_TAG = "UDPClient"

/**
 * UDP client with three directions:
 *  - CLIENT_TO_SERVER: send periodic heartbeats only
 *  - SERVER_TO_CLIENT: send a ONE-TIME "prime" datagram, then receive-only
 *  - BOTH: send heartbeats and also receive
 */
suspend fun runUdpClient(
    host: String,
    port: Int = 8000,
    periodMs: Long = 1000L,
    readTimeoutMs: Int = 1500,
    direction: UdpDirection = UdpDirection.BOTH
) {
    val addr = withContext(Dispatchers.IO) { InetAddress.getByName(host) }

    DatagramSocket().use { sock ->
        sock.soTimeout = readTimeoutMs
        sock.connect(addr, port) // restricts receive() to server and sets default send target
        Log.i(
            UDP_TAG,
            "UDP connected (pseudo) to $host:$port from ${sock.localAddress?.hostAddress}:${sock.localPort} dir=$direction"
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

                delay(periodMs)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(UDP_TAG, "Error: ${t.message}", t)
            // exit; service can restart if desired
        }
    }
}
