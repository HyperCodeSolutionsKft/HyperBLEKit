# HyperBleKit

Local multiplayer for phones that are near each other, over Bluetooth Low Energy only. Android
phones and iPhones play in the same game, up to 10 players, with no Wi-Fi, no internet, no server
and no pairing dialog.

You write the rules of your game as a **game module** (a state machine without any I/O) and your
own screens. HyperBleKit does the rest: one phone hosts a game and the others find and join it,
every screen is kept in step with the host, a player who drops out is let back in, and when the
host leaves or disappears another phone takes the game over.

```kotlin
val kit = HyperBleKit(context, HyperBleKitConfig(games = listOf(CounterGame)))
val session = kit.host()                 // or kit.startDiscovery(), then kit.join(game)
session.startGame("counter")             // host only
session.act(JSONObject().put("add", 1))  // every player; the host decides
println(session.state.value.view)        // this player's view of the game, as JSON
kit.leave()
```

The Swift API has the same shape and the same names.

## Status

Version 0.1.0. The library is unit-tested on both platforms, including complete games over
simulated links. It has **not yet been through a test round on real phones** in its packaged
form, it is not published to a package registry, and there is no version tag. Expect API changes
before 1.0.

## What is here

| Path | What |
|------|------|
| `sdk/android` | The Android library, `io.hypercode:hyperblekit`: a Gradle build of its own. Kotlin, `minSdk` 26. |
| `Package.swift`, `sdk/ios` | The Swift package `HyperBleKit`. iOS 15. |
| [`docs/sdk-guide.md`](docs/sdk-guide.md) | The developer guide: installation, permissions, a minimal app, writing a game module, real-time games, the connection lifecycle, troubleshooting. **Start here.** |
| [`docs/wire-protocol.md`](docs/wire-protocol.md) | The protocol both platforms implement byte for byte. |
| `protocol/test-vectors` | Vectors that both platforms' tests check their framing and message encoding against. |

## Running the tests

No test needs Bluetooth; they run over in-process fake transports.

```sh
# Android
cd sdk/android && ./gradlew :hyperblekit:testDebugUnitTest

# iOS (runs on macOS)
swift test
```

## Limits worth knowing first

- Players must be near each other, and the app must be open on every phone. A host cannot host
  from the background.
- Nothing is encrypted, and the hosting phone knows the whole game. Not for games where cheating
  or eavesdropping matters.
- A game lives in the memory of the phones that are in it. Nothing is saved.

The [guide](docs/sdk-guide.md#limits) has the full list.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
