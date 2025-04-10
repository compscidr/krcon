package com.jasonernst.krcon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.util.Properties
import java.util.Scanner

/**
 * Run with `./gradlew run --args="-h localhost -p 28017 -P password"` to run from command line
 */
class KRCon : CliktCommand() {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val localProperties = Properties()

    init {
        val currentWorkingDirectory = System.getProperty("user.dir")
        println("Current working directory: $currentWorkingDirectory")
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

    private val host by option(
        "-h",
        "--host",
        help = "Host to connect to (default is ${localProperties["host"]?.toString() ?: "localhost"})",
    ).default(localProperties["host"]?.toString() ?: "localhost")
    private val port by option("-p", "--port", help = "Port to connect to (default is ${localProperties["port"]?.toString() ?: "28017"})")
        .default(localProperties["port"]?.toString() ?: "28017")
    private val password by option(
        "-P",
        "--password",
        help = "Password to connect with (default is '${localProperties["password"]?.toString() ?: ""}')",
    ).default(localProperties["password"]?.toString() ?: "")

    private fun recvMessage(
        webRConPacket: WebRConPacket,
        connection: RConConnection,
    ) {
        println("Got: " + webRConPacket + " from " + connection.host + ":" + connection.port)
    }

    override fun run() {
        val censoredPassword =
            if (password.isNotEmpty()) {
                password.replace(Regex("(?<=.{0})."), "*")
            } else {
                ""
            }
        println("Connecting to $host:$port with password '$censoredPassword'")
        val connection = RConConnection(host, port.toInt(), password)
        connection.start(::recvMessage)
        if (connection.waitUntilConnected()) {
            println("Connected to $host:$port")
        } else {
            println("Failed to connect to $host:$port")
            return
        }

        println("Enter an rcon command:")
        val scanner = Scanner(System.`in`)
        while (scanner.hasNextLine() && connection.isDisconnected().not()) {
            val input = scanner.nextLine()
            if (input.isEmpty()) {
                println("Empty input, exiting...")
                break
            }
            connection.send(input)
        }
    }
}

fun main(args: Array<String>) {
    KRCon().main(args)
}
