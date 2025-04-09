package com.jasonernst.krcon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class RConConnection(
    val host: String,
    val port: Int,
    val password: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private var identifier = 0 // this should increase for every message sent
    private val client =
        HttpClient(CIO) {
            install(WebSockets) {
                // pingIntervalMillis = 20_000
            }
        }
    private var outgoingChannel: SendChannel<Frame>? = null
    private var job: Job? = null
    private val state = MutableStateFlow(RconConnectionState.CONNECTING)

    fun send(message: String) {
        if (state.value == RconConnectionState.DISCONNECTED) {
            logger.error("Cannot send message, connection is disconnected")
            return
        }
        val rconPacket = WebRConPacket(identifier++, message, "krcon")
        val json = Json.encodeToString(rconPacket)
        val frameToSend = Frame.Text(json)
        while (outgoingChannel == null) {
            Thread.sleep(100) // Wait for the outgoing channel to be initialized
        }
        CoroutineScope(Dispatchers.IO).launch {
            outgoingChannel?.send(frameToSend)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun start(callback: (message: WebRConPacket) -> Unit = {}) {
        job =
            CoroutineScope(Dispatchers.IO).launch {
                client
                    .runCatching {
                        webSocket(method = HttpMethod.Get, host = host, port = port, path = "/$password") {
                            state.value = RconConnectionState.CONNECTED
                            logger.info("Connected to $host:$port")
                            outgoingChannel = outgoing
                            incoming.consumeEach { frame ->
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    try {
                                        val json = Json { decodeEnumsCaseInsensitive = true }
                                        val rconPacket = json.decodeFromString<WebRConPacket>(text)
                                        logger.debug("Received: {}", rconPacket)
                                        callback(rconPacket)
                                    } catch (e: Exception) {
                                        logger.error("Received non-JSON message: $text EX: (${e.message})")
                                    }
                                } else {
                                    logger.error("Received non-text frame: $frame")
                                }
                            }
                        }
                    }.onFailure {
                        logger.error("Failed to connect to $host:$port: ${it.message}")
                        state.value = RconConnectionState.DISCONNECTED
                    }.onSuccess {
                        logger.info("Connection closed")
                        state.value = RconConnectionState.DISCONNECTED
                    }
            }
    }

    fun waitUntilConnected(): Boolean {
        while (state.value == RconConnectionState.CONNECTING) {
            Thread.sleep(100) // Sleep for 100 milliseconds
        }
        return state.value == RconConnectionState.CONNECTED
    }

    fun isConnecting(): Boolean = state.value == RconConnectionState.CONNECTING

    fun isConnected(): Boolean = state.value == RconConnectionState.CONNECTED

    fun isDisconnected(): Boolean = state.value == RconConnectionState.DISCONNECTED

    fun stop() {
        job?.cancel()
        client.close()
    }

    fun waitForClose() {
        while (job?.isActive == true) {
            Thread.sleep(1000) // Sleep for 1 second
        }
    }
}

enum class RconConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}
