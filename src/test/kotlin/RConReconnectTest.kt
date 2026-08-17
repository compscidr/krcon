import com.jasonernst.krcon.RConConnection
import com.jasonernst.krcon.WebRConPacket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for issue #112 (auto-reconnect) and #113 (send() blocking forever),
 * using local in-process servers so no real RCON server is needed.
 */
class RConReconnectTest {
    private var server: EmbeddedServer<*, *>? = null
    private var connection: RConConnection? = null

    @After fun teardown() {
        connection?.stop()
        server?.stop(0, 0)
    }

    /** Starts a local websocket server on an ephemeral port; returns the port. */
    private fun startServer(handler: suspend DefaultWebSocketSession.() -> Unit): Int {
        val s =
            embeddedServer(CIO, port = 0) {
                install(WebSockets)
                routing {
                    webSocket("/pw") { handler() }
                }
            }.start(wait = false)
        server = s
        return runBlocking {
            s.engine
                .resolvedConnectors()
                .first()
                .port
        }
    }

    private fun waitFor(
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Issue #113: send() must return instead of spinning when the websocket never connects. */
    @Test
    fun sendReturnsWhenNeverConnected() {
        // Accepts TCP but never completes the websocket upgrade, so state stays CONNECTING
        val rawSocket = ServerSocket(0)
        try {
            val conn = RConConnection("localhost", rawSocket.localPort, "pw")
            connection = conn
            conn.start()
            val sender = Thread { conn.send("status") }
            sender.isDaemon = true
            sender.start()
            sender.join(3_000)
            assertFalse("send() should return promptly when not connected", sender.isAlive)
        } finally {
            rawSocket.close()
        }
    }

    /** Issue #112: a dropped established connection is re-dialed, and the callback survives. */
    @Test
    fun reconnectsAfterServerClosesConnection() {
        val serverConnections = AtomicInteger(0)
        val port =
            startServer {
                if (serverConnections.incrementAndGet() == 1) {
                    close(CloseReason(CloseReason.Codes.NORMAL, "server restart"))
                } else {
                    send(Frame.Text("""{"Identifier": 1, "Message": "hello", "Type": "Generic"}"""))
                    delay(30_000)
                }
            }
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        val received = mutableListOf<WebRConPacket>()
        conn.start { packet, _ -> synchronized(received) { received.add(packet) } }
        assertTrue("reconnect after drop", waitFor { serverConnections.get() >= 2 && conn.isConnected() })
        assertTrue("callback survives reconnect", waitFor { synchronized(received) { received.isNotEmpty() } })
        assertEquals("hello", synchronized(received) { received.first().message })
    }

    /** Issue #112: a failed initial connect is retried until the server comes up. */
    @Test
    fun retriesFailedInitialConnect() {
        val freePort = ServerSocket(0).use { it.localPort }
        val conn = RConConnection("localhost", freePort, "pw")
        connection = conn
        conn.start()
        assertTrue("first attempt should fail", waitFor { conn.isDisconnected() })
        server =
            embeddedServer(CIO, port = freePort) {
                install(WebSockets)
                routing {
                    webSocket("/pw") { delay(30_000) }
                }
            }.start(wait = false)
        assertTrue("should connect once the server is up", waitFor { conn.isConnected() })
    }

    /** A throwing user callback must not tear down the websocket session. */
    @Test
    fun callbackExceptionDoesNotDropConnection() {
        val serverConnections = AtomicInteger(0)
        val port =
            startServer {
                serverConnections.incrementAndGet()
                send(Frame.Text("""{"Identifier": 1, "Message": "boom", "Type": "Generic"}"""))
                send(Frame.Text("""{"Identifier": 2, "Message": "ok", "Type": "Generic"}"""))
                delay(30_000)
            }
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        val received = mutableListOf<String>()
        conn.start { packet, _ ->
            check(packet.message != "boom") { "handler bug" }
            synchronized(received) { received.add(packet.message) }
        }
        assertTrue(conn.waitUntilConnected(10_000))
        assertTrue("second message still delivered", waitFor { synchronized(received) { "ok" in received } })
        assertEquals("no reconnect happened", 1, serverConnections.get())
    }

    /** Issue #112: stop() stays terminal — no reconnect after an explicit stop. */
    @Test
    fun stopPreventsReconnect() {
        val serverConnections = AtomicInteger(0)
        val port =
            startServer {
                serverConnections.incrementAndGet()
                delay(30_000)
            }
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        conn.start()
        assertTrue(conn.waitUntilConnected())
        conn.stop()
        Thread.sleep(5_000) // longer than the initial reconnect backoff
        assertEquals("no re-dial after stop()", 1, serverConnections.get())
        assertTrue(conn.isDisconnected())
    }
}
