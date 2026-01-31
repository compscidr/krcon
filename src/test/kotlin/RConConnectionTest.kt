import com.jasonernst.krcon.RConConnection
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.Properties

class RConConnectionTest {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val localProperties = Properties()

    @Before fun setup() {
        val configStream: InputStream? =
            try {
                File(System.getProperty("user.dir") + "/local.properties").inputStream()
            } catch (e: Exception) {
                logger.debug("No local.properties file found in current working directory")
                null
            }
        if (configStream != null) {
            localProperties.load(configStream)
        }
    }

    /**
     * Integration test that requires a real RCON server.
     * Skipped automatically when no server is configured via local.properties.
     * To run: create local.properties with host, port, and password set to a real server.
     */
    @Test
    fun testWebRcon() {
        // Skip this test if no real RCON server is configured
        // This prevents CI failures when secrets aren't set
        val host = localProperties["host"]?.toString()
        val port = localProperties["port"]?.toString()
        val password = localProperties["password"]?.toString()

        assumeTrue(
            "Skipping: no RCON server configured (set host/port/password in local.properties)",
            !host.isNullOrBlank() && !port.isNullOrBlank() && !password.isNullOrBlank()
        )

        val connection =
            RConConnection(
                host!!,
                port!!.toInt(),
                password!!,
            )

        var packetReceived = false
        connection.start { rconpacket, _ ->
            println("Received packet: $rconpacket")
            packetReceived = true
        }
        connection.waitUntilConnected()
        connection.send("playerlist")
        Thread.sleep(1000)
        assertTrue(packetReceived)
        connection.stop()
    }
}
