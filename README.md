# krcon
Kotlin Remote Console library using websockets

## Features
Supports:
- Sending and receiving messages
- Reception of messages using a callback
- Automatic incremental message IDs

## Usage
Add the dependency to your project:
```kotlin
implementation("com.jasonernst.krcon:krcon:0.0.1")
```
## Example
```kotlin
fun someCallback(message: WebRConPacket) {
    println("Got message: $message")
}

val connection = RConConnection("localhost", 28017, "somepass")
connection.start(::someCallback)
connection.waitUntilConnected()
connection.send("playerlist")
```

