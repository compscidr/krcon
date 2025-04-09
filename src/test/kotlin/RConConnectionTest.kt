import com.jasonernst.krcon.RConConnection
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

    @Test
    fun testWebRcon() {
        val connection =
            RConConnection(
                localProperties["host"]?.toString() ?: "localhost",
                localProperties["port"]?.toString()?.toInt() ?: 28017,
                localProperties["password"]?.toString() ?: "",
            )

        var packetReceived = false
        connection.start { rconpacket ->
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
