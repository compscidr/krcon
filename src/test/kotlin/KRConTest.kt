import com.github.ajalt.clikt.core.main
import com.jasonernst.krcon.KRCon
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory

/**
 * Tests for the KRCon CLI, using a local in-process echo server like [RConSendIdentifierTest].
 * The CLI talks through stdin/stdout, so these tests swap System.in/System.out; the connection
 * the CLI opens is never exposed, so it leaks after run() returns — harmless daemon threads.
 */
class KRConTest {
    private var server: EmbeddedServer<*, *>? = null
    private val originalOut = System.out
    private val originalIn = System.`in`
    private val originalUserDir = System.getProperty("user.dir")

    @After fun teardown() {
        System.setOut(originalOut)
        System.setIn(originalIn)
        System.setProperty("user.dir", originalUserDir)
        server?.stop(0, 0)
    }

    /** Starts a local websocket server that echoes every packet back; returns the port. */
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
                                send(Frame.Text(json.encodeToString(reply)))
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

    private fun captureStdout(): ByteArrayOutputStream {
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        return captured
    }

    @Test
    fun connectsSendsAndExitsOnEmptyLine() {
        val port = startEchoServer()
        System.setIn(ByteArrayInputStream("playerlist\n\n".toByteArray()))
        val captured = captureStdout()

        KRCon().main(arrayOf("--host", "localhost", "--port", "$port", "--password", "pw"))

        // the reply arrives async after run() returns; the callback still prints it
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !captured.toString().contains("echo: playerlist")) {
            Thread.sleep(100)
        }
        System.setOut(originalOut)
        val out = captured.toString()
        assertTrue("password must be censored, got:\n$out", out.contains("Connecting to localhost:$port with password '**'"))
        assertTrue("expected connected banner, got:\n$out", out.contains("Connected to localhost:$port"))
        assertTrue("expected the echoed reply, got:\n$out", out.contains("echo: playerlist"))
        assertTrue("empty line must exit, got:\n$out", out.contains("Empty input, exiting..."))
    }

    @Test
    fun printsFailureWhenServerRefusesDial() {
        val freePort = ServerSocket(0).use { it.localPort }
        System.setIn(ByteArrayInputStream(ByteArray(0)))
        val captured = captureStdout()

        KRCon().main(arrayOf("-h", "localhost", "-p", "$freePort", "-P", ""))

        System.setOut(originalOut)
        val out = captured.toString()
        assertTrue("empty password must not be censored, got:\n$out", out.contains("with password ''"))
        assertTrue("expected failure banner, got:\n$out", out.contains("Failed to connect to localhost:$freePort"))
    }

    @Test
    fun defaultsComeFromLocalProperties() {
        val freePort = ServerSocket(0).use { it.localPort }
        val dir = createTempDirectory("krcon-test").toFile()
        dir.resolve("local.properties").writeText("host=localhost\nport=$freePort\npassword=pw\n")
        System.setProperty("user.dir", dir.absolutePath)
        System.setIn(ByteArrayInputStream(ByteArray(0)))
        val captured = captureStdout()

        KRCon().main(emptyArray())

        System.setOut(originalOut)
        val out = captured.toString()
        assertTrue(
            "host/port/password must default from local.properties, got:\n$out",
            out.contains("Connecting to localhost:$freePort with password '**'"),
        )
        assertTrue("expected failure banner, got:\n$out", out.contains("Failed to connect to localhost:$freePort"))
    }
}
