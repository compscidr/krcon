import com.jasonernst.krcon.RConConnection
import com.jasonernst.krcon.WebRConPacket
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Rust's webrcon protocol echoes the request's Identifier on the reply — that is the only way a
 * client can tell a command's own reply apart from concurrent traffic (broadcast console output
 * arrives as identifier 0). These tests pin that send() hands the caller the identifier it
 * stamped, so correlation is possible at all, using a local in-process server like
 * [RConReconnectTest].
 */
class RConSendIdentifierTest {
    private var server: EmbeddedServer<*, *>? = null
    private var connection: RConConnection? = null

    @After fun teardown() {
        connection?.stop()
        server?.stop(0, 0)
    }

    /** Starts a local websocket server that echoes every packet back with its identifier; returns the port. */
    private fun startEchoServer(): Int {
        val json = Json { ignoreUnknownKeys = true }
        val s =
            embeddedServer(CIO, port = 0) {
                install(WebSockets)
                routing {
                    webSocket("/pw") {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val request = json.decodeFromString<WebRConPacket>(frame.readText())
                                val reply = WebRConPacket(request.identifier, "echo: ${request.message}", "test")
                                send(Frame.Text(Json.encodeToString(reply)))
                            }
                        }
                    }
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

    @Test
    fun sendReturnsTheIdentifierTheReplyEchoes() {
        val port = startEchoServer()
        val conn = RConConnection("localhost", port, "pw")
        connection = conn
        val received = ConcurrentLinkedQueue<WebRConPacket>()
        conn.start { packet, _ -> received.add(packet) }
        assertTrue(conn.waitUntilConnected(15_000))

        val first = conn.send("status")
        val second = conn.send("playerlist")

        assertTrue("expected both replies", waitFor { received.size >= 2 })
        // Identifier 0 is the server's broadcast identifier — a command must never be stamped
        // with it, or its reply is indistinguishable from unsolicited console output.
        assertTrue("identifiers must not collide with broadcast id 0", first != null && first > 0)
        assertEquals("identifiers must be distinct per send", first!! + 1, second)
        assertEquals(setOf(first, second), received.map { it.identifier }.toSet())
        assertEquals("echo: status", received.first { it.identifier == first }.message)
    }

    @Test
    fun sendReturnsNullWhenNotConnected() {
        val conn = RConConnection("localhost", 1, "pw")
        connection = conn
        // start() never called, so the connection cannot be CONNECTED
        assertNull(conn.send("status"))
    }
}
