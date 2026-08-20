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
import java.util.concurrent.atomic.AtomicInteger

class RConConnection(
    val host: String,
    val port: Int,
    val password: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Starts at 1, never 0: the server broadcasts unsolicited console output with Identifier 0,
    // so a command stamped 0 would have a reply indistinguishable from broadcast traffic.
    // Atomic because send() may be called from any thread.
    private val identifier = AtomicInteger(1)
    private val client =
        HttpClient(CIO) {
            install(WebSockets) {
                // pingIntervalMillis = 20_000
            }
        }
    private var outgoingChannel: SendChannel<Frame>? = null
    private var job: Job? = null
    private val state = MutableStateFlow(RconConnectionState.CONNECTING)
    private val json = Json { decodeEnumsCaseInsensitive = true }

    /**
     * Sends [message] and returns the identifier stamped on the outgoing packet. The server
     * echoes that identifier on the command's reply, so this is what lets a caller match a reply
     * to its own request instead of adopting whatever arrives next. Returns null when the
     * connection is not connected — nothing was sent. A non-null return means "queued", not
     * "delivered": the connection can still drop before the frame goes out.
     */
    fun send(message: String): Int? {
        val channel = outgoingChannel
        if (state.value != RconConnectionState.CONNECTED || channel == null) {
            logger.error("Cannot send message, connection is not connected")
            return null
        }
        val id = identifier.getAndIncrement()
        val rconPacket = WebRConPacket(id, message, "krcon")
        val payload = Json.encodeToString(rconPacket)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                channel.send(Frame.Text(payload))
            } catch (e: Exception) {
                // the connection can drop between the state check and the send
                logger.error("Failed to send message: ${e.message}")
            }
        }
        return id
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
                                        val rconPacket =
                                            try {
                                                json.decodeFromString<WebRConPacket>(text)
                                            } catch (e: Exception) {
                                                logger.error("Received non-JSON message: $text EX: (${e.message})")
                                                null
                                            }
                                        if (rconPacket != null) {
                                            logger.debug("Received: {}", rconPacket)
                                            try {
                                                callback(rconPacket, myConnection)
                                            } catch (e: Exception) {
                                                logger.error("Message callback threw for $rconPacket", e)
                                            }
                                        }
                                    } else {
                                        logger.error("Received non-text frame: $frame")
                                    }
                                }
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            if (isRefusedDial(it)) {
                                // A down or unreachable server is a NORMAL state for a long-lived
                                // connection to sit in — the loop below retries with backoff by
                                // design (#112). WARN without a stack: the message says everything
                                // a refused dial has to say, and an ERROR per retry buries real
                                // errors in consumers' logs.
                                logger.warn("Connection to $host:$port failed: ${it.message}")
                            } else {
                                logger.error("Connection to $host:$port failed: ${it.message}", it)
                            }
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
        outgoingChannel = null
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

        /** Whether [t] is a dial the peer refused, wherever ktor buried the ConnectException
         *  in the cause chain — CIO throws it raw, but engines and plugins wrap. */
        private fun isRefusedDial(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { it is java.net.ConnectException }
    }
}

enum class RconConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
}
