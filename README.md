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
implementation("com.jasonernst.krcon:krcon:0.0.5")

```
```kotlin
fun someCallback(message: WebRConPacket) {
    println("Got message: $message")
}

val connection = RConConnection("localhost", 28017, "somepass")
connection.start(::someCallback)
connection.waitUntilConnected()
connection.send("playerlist")
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

