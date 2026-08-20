# krcon
[![codecov](https://codecov.io/gh/compscidr/krcon/graph/badge.svg)](https://codecov.io/gh/compscidr/krcon)

Kotlin Remote Console library using websockets

## Features
Supports:
- Sending and receiving messages
- Reception of messages using a callback
- Automatic incremental message IDs, returned from `send()` so replies can be
  matched to their request

## Usage
Add the dependency to your project:
```kotlin
implementation("com.jasonernst.krcon:krcon:0.0.8")

```
```kotlin
fun someCallback(message: WebRConPacket, connection: RConConnection) {
    println("Got message: $message from $connection")
}

val connection = RConConnection("localhost", 28017, "somepass")
connection.start(::someCallback)
connection.waitUntilConnected()
connection.send("playerlist")
```

`send()` returns the identifier stamped on the outgoing packet (or `null` when
not connected — nothing was sent). The server echoes that identifier on the
command's reply, so replies can be matched to their request; unsolicited
console output arrives with identifier `0`:
```kotlin
val received = ConcurrentLinkedQueue<WebRConPacket>()
connection.start { packet, _ -> received.add(packet) }
connection.waitUntilConnected()
val id = connection.send("serverinfo")
// the command's reply is the received packet whose identifier == id
```

## Example
You can see an example at [src/main/kotlin/com/jasonernst/krcon/KRCon.kt](src/main/kotlin/com/jasonernst/krcon/KRCon.kt).
If you'd like run the example, copy the `local.properties.sample` file to `local.properties` and set the `host`, `port`, 
and `password` values to your server's values. Then you can either run directly from the IDE
or run directly with gradle:
```bash
./gradlew run
```

You can also just pass runtime arguments to the main function:
```bash
./gradlew run --args="--host localhost --port 28017 --password somepass"
```

## Demo
![Alt Text](demo.gif)

