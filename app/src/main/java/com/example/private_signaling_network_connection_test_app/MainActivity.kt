package com.example.private_signaling_network_connection_test_app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Top-level enums
enum class Mode { TCP, UDP }
enum class UdpDirection { CLIENT_TO_SERVER, SERVER_TO_CLIENT, BOTH }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Default values
            var host by remember { mutableStateOf("private-signaling.cs-georgetown.net") }
            var port by remember { mutableStateOf("8000") }
            var isRunning by remember { mutableStateOf(false) }
            var mode by remember { mutableStateOf(Mode.TCP) }
            var udpDirection by remember { mutableStateOf(UdpDirection.BOTH) }

            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // --- Server Address Input ---
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text("Server Address") },
                            placeholder = { Text("e.g. private-signaling.cs-georgetown.net") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // --- Port Input ---
                        OutlinedTextField(
                            value = port,
                            onValueChange = { p -> port = p.filter { it.isDigit() } },
                            label = { Text("Port") },
                            placeholder = { Text("8000") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // --- Transport toggle (TCP / UDP) ---
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = mode == Mode.TCP,
                                onClick = { mode = Mode.TCP },
                                label = { Text("TCP") }
                            )
                            FilterChip(
                                selected = mode == Mode.UDP,
                                onClick = { mode = Mode.UDP },
                                label = { Text("UDP") }
                            )
                        }

                        // --- UDP Direction options (only visible in UDP mode) ---
                        if (mode == Mode.UDP) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp)
                                        .wrapContentHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = "UDP Direction",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.align(Alignment.CenterHorizontally)
                                    )

                                    FilterChip(
                                        selected = udpDirection == UdpDirection.CLIENT_TO_SERVER,
                                        onClick = { udpDirection = UdpDirection.CLIENT_TO_SERVER },
                                        label = { Text("Client → Server") }
                                    )

                                    FilterChip(
                                        selected = udpDirection == UdpDirection.SERVER_TO_CLIENT,
                                        onClick = { udpDirection = UdpDirection.SERVER_TO_CLIENT },
                                        label = { Text("Server → Client (prime once)") }
                                    )

                                    FilterChip(
                                        selected = udpDirection == UdpDirection.BOTH,
                                        onClick = { udpDirection = UdpDirection.BOTH },
                                        label = { Text("Both Directions") }
                                    )
                                }
                            }
                        }

                        // --- Start Button ---
                        Button(
                            onClick = {
                                val intent =
                                    Intent(this@MainActivity, NetworkTestService::class.java)
                                intent.putExtra("host", host)
                                intent.putExtra("port", port.toIntOrNull() ?: 8000)
                                intent.putExtra(
                                    "mode",
                                    if (mode == Mode.TCP) "tcp" else "udp"
                                )
                                if (mode == Mode.UDP) {
                                    val dir = when (udpDirection) {
                                        UdpDirection.CLIENT_TO_SERVER -> "cts"
                                        UdpDirection.SERVER_TO_CLIENT -> "stc"
                                        UdpDirection.BOTH -> "both"
                                    }
                                    intent.putExtra("udpDirection", dir)
                                }
                                startForegroundService(intent)
                                isRunning = true
                            },
                            enabled = !isRunning
                        ) {
                            val startText =
                                if (mode == Mode.TCP) "Start TCP Test"
                                else "Start UDP (${udpDirectionLabel(udpDirection)})"
                            Text(startText)
                        }

                        // --- Stop Button ---
                        Button(
                            onClick = {
                                stopService(
                                    Intent(
                                        this@MainActivity,
                                        NetworkTestService::class.java
                                    )
                                )
                                isRunning = false
                            },
                            enabled = isRunning
                        ) {
                            Text("Stop Test")
                        }
                    }
                }
            }
        }
    }
}

// Utility function for display label
private fun udpDirectionLabel(d: UdpDirection): String = when (d) {
    UdpDirection.CLIENT_TO_SERVER -> "C→S"
    UdpDirection.SERVER_TO_CLIENT -> "S→C (prime)"
    UdpDirection.BOTH -> "Both"
}
