import com.jasonernst.krcon.RConConnection
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge cases in RConConnection's frame handling and state accessors, using local in-process
 * servers like [RConReconnectTest].
 */
class RConConnectionEdgeCasesTest {
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

    /** A binary frame or non-JSON text from the server must be skipped, not tear the session down. */
    @Test
    fun malformedFramesDoNotKillTheConnection() {
        val port =
            startServer {
                send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                send(Frame.Text("not json"))
                send(Frame.Text("""{"Identifier": 7, "Message": "ok", "Type": "Generic"}"""))
                delay(30_000)
            }
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        val received = mutableListOf<String>()
        conn.start { packet, _ -> synchronized(received) { received.add(packet.message) } }
        assertTrue(conn.waitUntilConnected(15_000))
        assertTrue("valid packet after garbage still delivered", waitFor { synchronized(received) { "ok" in received } })
        assertEquals("garbage must not reach the callback", listOf("ok"), synchronized(received) { received.toList() })
        assertTrue("connection must survive garbage frames", conn.isConnected())
    }

    @Test
    fun waitUntilConnectedTimesOutWhileStillConnecting() {
        // never started, so the state stays CONNECTING and only the timeout can end the wait
        val conn = RConConnection("localhost", 1, "pw")
        assertTrue(conn.isConnecting())
        assertFalse(conn.waitUntilConnected(300))
    }

    @Test
    fun waitForCloseReturnsAfterStop() {
        // no job yet: must return immediately, not sit out the timeout
        var start = System.currentTimeMillis()
        RConConnection("localhost", 1, "pw").waitForClose(1_000)
        assertTrue("waitForClose without start() must return immediately", System.currentTimeMillis() - start < 900)

        val port = startServer { delay(30_000) }
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        conn.start()
        assertTrue(conn.waitUntilConnected(15_000))
        conn.stop()
        start = System.currentTimeMillis()
        conn.waitForClose(10_000)
        assertTrue("waitForClose after stop() must not run out the timeout", System.currentTimeMillis() - start < 5_000)
        assertTrue(conn.isDisconnected())
    }
}
