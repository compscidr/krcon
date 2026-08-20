import com.jasonernst.krcon.RConConnection
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.ServerSocket

/**
 * A monitor pointed at a down server is a NORMAL state for a long-lived connection to sit in —
 * the reconnect loop retries with backoff by design (issue #112). Logging every refused dial at
 * ERROR with a full stack trace makes each retry look like a fault and buries real errors in
 * consumers' logs (rustd.xyz PR #513 discussion), so a refused dial must log at WARN, message
 * only. Genuinely unexpected failures keep the ERROR + stack.
 *
 * Asserted through captured stderr because the library ships slf4j-simple, which writes there;
 * its output is the observable behavior consumers actually see.
 */
class RefusedDialLoggingTest {
    private var connection: RConConnection? = null
    private val originalErr = System.err

    @After fun teardown() {
        connection?.stop()
        System.setErr(originalErr)
    }

    @Test
    fun refusedDialLogsWarnWithoutStackTrace() {
        // Bind and release an ephemeral port so it is known-free: the dial must be REFUSED.
        val port = ServerSocket(0).use { it.localPort }
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true))
        val conn = RConConnection("127.0.0.1", port, "pw")
        connection = conn
        conn.start()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            !captured.toString().contains("Connection to 127.0.0.1:$port failed")
        ) {
            Thread.sleep(100)
        }
        conn.stop()
        System.setErr(originalErr)
        val log = captured.toString()
        assertTrue("expected the refused dial to be logged, got:\n$log", log.contains("Connection to 127.0.0.1:$port failed"))
        assertTrue("expected the refusal at WARN, got:\n$log", log.contains("WARN"))
        assertFalse("a refused dial must not log at ERROR, got:\n$log", log.contains("ERROR"))
        assertFalse("a refused dial must not carry a stack trace, got:\n$log", log.contains("\tat "))
    }
}
