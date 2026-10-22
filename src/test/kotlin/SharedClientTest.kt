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
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Issue #142: connections share one client, so stopping one must not touch the others. */
class SharedClientTest {
    private var server: EmbeddedServer<*, *>? = null
    private val connections = mutableListOf<RConConnection>()

    @After fun teardown() {
        connections.forEach { it.stop() }
        server?.stop(0, 0)
    }

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

    private fun connect(port: Int): RConConnection =
        RConConnection("localhost", port, "pw").also {
            connections += it
            it.start()
            assertTrue("connection should come up", it.waitUntilConnected(10_000))
        }

    private fun waitFor(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMillis) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }

    /** Threads currently blocked in a Ktor selector loop — one per CIO client (and server). */
    private fun selectorThreads(): Int =
        Thread.getAllStackTraces().values.count { stack ->
            stack.any { it.className.startsWith("io.ktor.network.selector.") && it.methodName == "select" }
        }

    @Test
    fun stoppingOneConnectionLeavesTheOthersWorking() {
        val received = ConcurrentLinkedQueue<String>()
        val port =
            startServer {
                for (frame in incoming) if (frame is Frame.Text) received += frame.readText()
            }
        val first = connect(port)
        val second = connect(port)

        first.stop()

        assertTrue("the other connection should still send", second.send("still here") != null)
        assertTrue(waitFor { received.any { "still here" in it } })
        // and the shared client still dials new connections after a stop()
        val third = connect(port)
        assertTrue(third.send("new") != null)
        assertTrue(waitFor { received.any { "\"new\"" in it } })
    }

    @Test
    fun stopStillClosesTheConnectionsSocket() {
        val closed = AtomicInteger(0)
        val port =
            startServer {
                try {
                    for (frame in incoming) Unit
                } finally {
                    closed.incrementAndGet()
                }
            }
        val conn = connect(port)

        conn.stop()

        assertTrue("the server should see the session end", waitFor { closed.get() == 1 })
    }

    @Test
    fun moreConnectionsDoNotPinMoreSelectorThreads() {
        val port = startServer { for (frame in incoming) Unit }
        connect(port)
        val withOne = selectorThreads()

        repeat(5) { connect(port) }

        val withSix = selectorThreads()
        assertTrue("selector threads grew from $withOne to $withSix for 5 more connections", withSix <= withOne)
    }
}
