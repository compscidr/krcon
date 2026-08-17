package com.jasonernst.krcon

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.http.HttpMethod
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
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
        val channel = outgoingChannel
        if (state.value != RconConnectionState.CONNECTED || channel == null) {
            logger.error("Cannot send message, connection is not connected")
            return
        }
        val rconPacket = WebRConPacket(identifier++, message, "krcon")
        val json = Json.encodeToString(rconPacket)
        CoroutineScope(Dispatchers.IO).launch {
            channel.send(Frame.Text(json))
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun start(callback: (message: WebRConPacket, connection: RConConnection) -> Unit = { _, _ -> }) {
        val myConnection = this
        job =
            CoroutineScope(Dispatchers.IO).launch {
                var backoffMillis = INITIAL_BACKOFF_MILLIS
                while (isActive) {
                    state.value = RconConnectionState.CONNECTING
                    client
                        .runCatching {
                            webSocket(method = HttpMethod.Get, host = host, port = port, path = "/$password") {
                                backoffMillis = INITIAL_BACKOFF_MILLIS
                                outgoingChannel = outgoing
                                state.value = RconConnectionState.CONNECTED
                                logger.info("Connected to $host:$port")
                                incoming.consumeEach { frame ->
                                    if (frame is Frame.Text) {
                                        val text = frame.readText()
                                        try {
                                            val json = Json { decodeEnumsCaseInsensitive = true }
                                            val rconPacket = json.decodeFromString<WebRConPacket>(text)
                                            logger.debug("Received: {}", rconPacket)
                                            callback(rconPacket, myConnection)
                                        } catch (e: Exception) {
                                            logger.error("Received non-JSON message: $text EX: (${e.message})")
                                        }
                                    } else {
                                        logger.error("Received non-text frame: $frame")
                                    }
                                }
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            logger.error("Failed to connect to $host:$port: ${it.message}")
                        }.onSuccess {
                            logger.info("Connection closed")
                        }
                    outgoingChannel = null
                    state.value = RconConnectionState.DISCONNECTED
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                }
            }
    }

    fun waitUntilConnected(timeoutMillis: Long = Long.MAX_VALUE): Boolean {
        val start = System.currentTimeMillis()
        while (state.value == RconConnectionState.CONNECTING && System.currentTimeMillis() - start < timeoutMillis) {
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
        state.value = RconConnectionState.DISCONNECTED
    }

    fun waitForClose(timeoutMillis: Long = Long.MAX_VALUE) {
        val start = System.currentTimeMillis()
        while (job?.isActive == true && System.currentTimeMillis() - start < timeoutMillis) {
            Thread.sleep(1000) // Sleep for 1 second
        }
    }

    companion object {
        private const val INITIAL_BACKOFF_MILLIS = 2_000L
        private const val MAX_BACKOFF_MILLIS = 30_000L
    }
}

enum class RconConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}
