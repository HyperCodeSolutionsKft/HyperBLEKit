# HyperBleKit developer guide

HyperBleKit gives a mobile app local multiplayer for phones that are near each other. It works
between Android phones and iPhones in the same game, for up to 10 players, over Bluetooth Low
Energy (BLE) only: no Wi-Fi, no internet, no server, no pairing dialog.

You write two things: the rules of your game as a **game module** (a state machine without any
I/O) and your own screens. The SDK does the rest: one phone **hosts** a game and the others find
and join it, every player's screen is kept in step with the host, a player who drops out is let
back in, and when the host leaves or disappears another phone takes the game over.

The shape of the API, in Kotlin (Swift uses the same names):

```kotlin
val kit = HyperBleKit(context, HyperBleKitConfig(games = listOf(CounterGame)))
val session = kit.host()                 // or kit.startDiscovery(), then kit.join(game)
session.startGame("counter")             // host only
session.act(JSONObject().put("add", 1))  // every player; the host decides
println(session.state.value.view)        // this player's view of the game, as JSON
kit.leave()
```

**It fits** a turn-based game or a small action game for 2 to 10 people in one room, each on
their own phone, with the app open.

**It does not fit** if you need any of these:

- Players who are not near each other, or more than 10 players.
- Play while the app is in the background or the phone is locked.
- Encryption, accounts, or protection against a modified app. Everything is sent unencrypted
  to any phone that connects, and the hosting phone knows the whole game, hidden cards included.
- Saved games. A game lives in the memory of the phones that are in it.

**Status:** version 0.1.0, licensed under Apache-2.0. The library is not yet published to a
Maven repository and has no version tag; you consume it from source (see
[Installation](#installation)). It is unit-tested on both platforms, including complete games
over simulated links. The library has not yet been through a test round on real phones in its
packaged form. Expect API changes before 1.0.

## Contents

- [Limits](#limits)
- [Installation](#installation)
- [Platform setup](#platform-setup)
- [The model](#the-model)
- [A minimal app](#a-minimal-app)
- [Writing a game module](#writing-a-game-module)
- [Real-time games](#real-time-games)
- [Connection lifecycle](#connection-lifecycle)
- [Configuration](#configuration)
- [Threading](#threading)
- [Logging and diagnostics](#logging-and-diagnostics)
- [Troubleshooting](#troubleshooting)
- [Platform differences at a glance](#platform-differences-at-a-glance)
- [Where to read more](#where-to-read-more)

## Limits

| Limit | Value |
|-------|-------|
| Players | 2 to 10, the host included (`maxPlayers`). The wire specification notes that more than 4 to 5 clients on one host is untested; how many connections a phone holds depends on its Bluetooth chip. |
| Topology | A star: every client is connected to the host and to nobody else. Every phone has to stay within Bluetooth range of the host. The SDK does not relay between clients. |
| Operating systems | Android 8.0 (API 26) and later; iOS 15 and later. |
| Hosting on Android | Needs a Bluetooth chip that can advertise. Some Android phones cannot; they can still join. `HyperBleKit.canHost` tells you. |
| Foreground | The app has to be in front on every phone. See [Background and foreground](#background-and-foreground). |
| Security | No encryption, no authentication, no accounts. A player is recognised by a random ID that the phone generates for itself. |
| Protocol version | Every phone in a game must run the same protocol version (currently 1, fixed by the SDK). A client with another version is refused with `version_mismatch`. In practice: ship your Android and iOS builds from the same SDK version. |
| Message size | A view or state travels in up to 255 Bluetooth packets. Keep the JSON of a game small; it is sent after every move. |
| Numbers in JSON | Integers only, if iPhones are to play: the iOS JSON type has no floating-point case. |

## Installation

Neither library is in a public registry yet. Both are built from a clone of this repository.

### Android

The library is the Gradle module `sdk/android/hyperblekit`, with the coordinates
`io.hypercode:hyperblekit:0.1.0`. `sdk/android` is a Gradle build of its own, so an app includes
it as a composite build.

In the app's `settings.gradle.kts`:

```kotlin
// "io.hypercode:hyperblekit" now resolves to the sources in the clone.
includeBuild("<path to your clone>/sdk/android")
```

In the app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.hypercode:hyperblekit")
}
```

What the library brings and expects:

- `minSdk` 26, Java 11 bytecode.
- `kotlinx-coroutines-android` 1.9.0 as an API dependency, because `StateFlow` and `SharedFlow`
  are part of the public API.
- No ProGuard or R8 rules are needed; the library uses no reflection.
- The SDK build uses Android Gradle Plugin 9.1.1 (`sdk/android/gradle/libs.versions.toml`). An
  app on the same version is the combination the library is built and tested with.

The module also applies `maven-publish`, with the publication `io.hypercode:hyperblekit` and no
repository configured. Where a published artifact will live is not decided, so this guide gives
no repository URL.

### iOS

The Swift package manifest is `Package.swift` at the root of the repository; the sources are in
`sdk/ios/Sources/HyperBleKit`. The package has one library product, `HyperBleKit`, supports
iOS 15 and macOS 12 (macOS only so that the tests run on a Mac), and is built in Swift 5 language
mode.

In Xcode, either:

- **Local path**: *File > Add Package Dependencies… >
  Add Local…* and choose the root folder of your clone.
- **Git URL**: *File > Add Package Dependencies…*, enter `https://github.com/HyperCodeSolutionsKft/HyperBLEKit`, and choose the rule
  *Branch* or *Commit*. There is no version tag yet, so the version rules do not work.

Then add the product `HyperBleKit` to your app target.

In another package's `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/HyperCodeSolutionsKft/HyperBLEKit", branch: "main"),
    // or, from a clone on disk:
    // .package(path: "<path to your clone>"),
],
targets: [
    .target(
        name: "MyGame",
        // The package argument is the last path component of the URL or of the folder.
        dependencies: [.product(name: "HyperBleKit", package: "<repository name>")]
    ),
]
```

## Platform setup

### Android permissions

The library's manifest declares no permissions. The app declares them:

```xml
<!-- Android 12 and later -->
<uses-permission
    android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation"
    tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" tools:targetApi="s" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />

<!-- Android 8 to 11 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />

<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

The `tools:` attributes need `xmlns:tools="http://schemas.android.com/tools"` on the `<manifest>`
element.

Why location is not needed on Android 12 and later: the SDK scans only for its own service UUID
and never works out where the phone is, so the app can declare `BLUETOOTH_SCAN` with
`neverForLocation`. On Android 8 to 11 the system does not allow a Bluetooth scan without the
location permission, so `ACCESS_FINE_LOCATION` is declared up to API 30 and has to be granted
there.

`HyperBleKit.requiredPermissions` is the list to request at run time on the Android version the
app is running on:

| Android version | `requiredPermissions` |
|-----------------|-----------------------|
| 12 and later (API 31+) | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE` |
| 8 to 11 | `ACCESS_FINE_LOCATION` |

Request them with `ActivityResultContracts.RequestMultiplePermissions` before the first use of
Bluetooth; the [minimal app](#android-app) shows the flow. Creating the kit is allowed before
that. Nothing works without them, and nothing crashes: `startDiscovery()` ends in
`ScanningState.Failed` with a sentence that says the permission is missing, hosting ends in
`AdvertisingState.Failed`, and a join ends in `Ended(Disconnected)`. Ask for the permissions
first, then call the kit again.

### iOS Info.plist

| Key | Value | Needed |
|-----|-------|--------|
| `NSBluetoothAlwaysUsageDescription` | A sentence that tells the user why the app uses Bluetooth, for example "Bluetooth is used to find nearby games and exchange game data with other players." | Yes. Without it iOS stops the app when it first uses Bluetooth. |
| `UIRequiredDeviceCapabilities` | An array with `bluetooth-le`. | Optional. With it the app is offered only to devices with BLE. |

No background mode is needed, and the SDK does not use one.

iOS shows its Bluetooth permission prompt the first time the app really uses Bluetooth: at the
first `startDiscovery()`, `host()` or `join(_:)`. Creating the kit shows nothing. If the user
refuses, `scanningState` becomes `.unavailable(.unauthorized)` and a hosted session's
`state.advertising` likewise; `BluetoothAvailability.userMessage` is an English sentence for
that case.

## The model

**The kit.** `HyperBleKit` is the entry point. An app creates one for its lifetime, with a
`HyperBleKitConfig` that lists its game modules. Create only one per process: a phone has one
Bluetooth radio.

**Discovery.** A hosted game is *advertised*: the host broadcasts small Bluetooth packets that
carry the app's service UUID and a four-character **game code** such as `7K2F`.
`startDiscovery()` scans for them and keeps `discoveredGames` up to date. Players pick the game
whose code the host's screen shows.

**One session whose role changes.** `host()` and `join()` both return a `GameSession`. The
session is the same object for the whole game, while the role of the phone can change without
the user doing anything: a client that takes the game over becomes the host, and a host that
steps down becomes a client. This is **host migration**. A screen does not track it; it draws
what the session's `state` says.

**`SessionState`.** `GameSession.state` publishes one immutable value with everything a screen
shows: the role, the connection, the players, the phase (`lobby`, `playing` or `finished`), and
this player's **view** of the game. A new value arrives whenever any part changes.

**The host is authoritative.** Only the host runs the game module. A player's move is an
**action**: a JSON object in the game's own format. `session.act(action)` is the same call on
every phone. On a client it is sent to the host; on the host it goes straight to the module.
Nothing changes locally until the host has decided: an accepted action comes back to every
player as a new `state.view`, a refused one comes back to its sender as a text in
`state.gameNotice`. The SDK never reads the JSON of an action, a view or a game state.

**Seats.** Players have the IDs `p1` to `p10`. The first host is `p1`; everyone else gets the
lowest free number. A player keeps their ID for the whole game, across reconnects and host
changes, because the host recognises a returning phone by its device ID
(`kit.identity.deviceId`), not by its Bluetooth address.

## A minimal app

Both apps below host or join a game of Counter, the example game from
[Writing a game module](#writing-a-game-module): the players take turns adding 1 to a shared
number. Each app is complete apart from that module and the function `connectionText`, which is
in [Connection lifecycle](#connection-lifecycle).

### Android app

The example uses Jetpack Compose with `lifecycle-runtime-compose`, `activity-compose` and
Material 3, which are the app's own dependencies. The kit lives in a `ViewModel`, so it survives
a rotation and is closed when the activity is finished for good.

```kotlin
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.hypercode.hyperblekit.DiscoveredGame
import io.hypercode.hyperblekit.GamePhase
import io.hypercode.hyperblekit.GameSession
import io.hypercode.hyperblekit.HyperBleKit
import io.hypercode.hyperblekit.HyperBleKitConfig
import io.hypercode.hyperblekit.ScanningState
import io.hypercode.hyperblekit.SessionRole
import org.json.JSONObject

class CounterViewModel(app: Application) : AndroidViewModel(app) {
    // One kit for the whole process. Creating it does not switch Bluetooth on yet.
    val kit = HyperBleKit(app, HyperBleKitConfig(games = listOf(CounterGame)))

    // A second tap must not leave the session the first one opened.
    fun host() {
        if (kit.session.value == null) kit.host()
    }

    fun join(game: DiscoveredGame) {
        if (kit.session.value == null) kit.join(game)
    }

    override fun onCleared() = kit.close()
}

class MainActivity : ComponentActivity() {
    private val viewModel: CounterViewModel by viewModels()

    // Nothing on the kit may be called before every permission is granted.
    private var permitted by mutableStateOf(false)
    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permitted = hasPermissions() }

    private fun hasPermissions() = HyperBleKit.requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permitted = hasPermissions()
        setContent {
            val session by viewModel.kit.session.collectAsStateWithLifecycle()
            val current = session
            when {
                !permitted -> Button(onClick = {
                    askPermissions.launch(HyperBleKit.requiredPermissions.toTypedArray())
                }) { Text("Allow Bluetooth") }
                current != null -> SessionScreen(current, onLeave = viewModel.kit::leave)
                else -> GameList(viewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.kit.onForeground()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.kit.onBackground()
    }
}

/** Shown while the phone is in no game: host one, or join one that is nearby. */
@Composable
fun GameList(viewModel: CounterViewModel) {
    val kit = viewModel.kit
    val games by kit.discoveredGames.collectAsStateWithLifecycle()
    val scanning by kit.scanningState.collectAsStateWithLifecycle()
    // Scanning costs battery: it runs only while this list is on screen.
    DisposableEffect(kit) {
        kit.startDiscovery()
        onDispose { kit.stopDiscovery() }
    }
    Column {
        Button(onClick = viewModel::host) { Text("Host a game") }
        (scanning as? ScanningState.Failed)?.let { failed ->
            // For example Bluetooth is off. Android does not restart the scan by itself.
            Text(failed.reason)
            Button(onClick = kit::startDiscovery) { Text("Try again") }
        }
        for (game in games) {
            Button(onClick = { viewModel.join(game) }) { Text("Join ${game.gameCode}") }
        }
    }
}

@Composable
fun SessionScreen(session: GameSession, onLeave: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    Column {
        Text("Game ${state.gameCode}: ${state.players.size} player(s)")
        Text(connectionText(state.connection))
        if (state.phase == GamePhase.LOBBY) {
            if (state.role == SessionRole.HOST) {
                val problem = session.startGameProblem(CounterGame.id)
                Button(onClick = { session.startGame(CounterGame.id) }, enabled = problem == null) { Text("Start") }
                problem?.let { Text(it) }
            }
        } else {
            val view = remember(state.view) { state.view?.toJson() }
            Text("Count: ${view?.optInt("count")}, turn: ${view?.optString("turn")}")
            Button(onClick = { session.act(JSONObject().put("add", 1)) }) { Text("Add 1") }
        }
        state.gameNotice?.let { Text(it) }
        Button(onClick = onLeave) { Text("Leave") }
    }
}
```

### iOS app

Add `NSBluetoothAlwaysUsageDescription` to the target first (see
[iOS Info.plist](#ios-infoplist)).

```swift
import HyperBleKit
import SwiftUI

@main
struct CounterApp: App {
    // One kit for the whole app. Creating it does not touch Bluetooth yet.
    @StateObject private var kit = HyperBleKit(config: HyperBleKitConfig(games: [CounterModule()]))

    var body: some Scene {
        WindowGroup { RootView(kit: kit) }
    }
}

struct RootView: View {
    @ObservedObject var kit: HyperBleKit
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if let session = kit.session {
                SessionView(kit: kit, session: session)
            } else {
                GameListView(kit: kit)
            }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background: kit.onBackground()
            case .active: kit.onForeground()
            default: break
            }
        }
    }
}

/// Shown while the device is in no game: host one, or join one that is nearby.
struct GameListView: View {
    @ObservedObject var kit: HyperBleKit

    var body: some View {
        List {
            Button("Host a game") { kit.host() }
            if case .unavailable(let bluetooth) = kit.scanningState, let message = bluetooth.userMessage {
                Text(message)
            }
            ForEach(kit.discoveredGames) { game in
                Button("Join \(game.gameCode)") {
                    kit.stopDiscovery()
                    kit.join(game)
                }
            }
        }
        // The first scan shows the system's Bluetooth permission prompt.
        .onAppear { kit.startDiscovery() }
        .onDisappear { kit.stopDiscovery() }
    }
}

struct SessionView: View {
    let kit: HyperBleKit
    @ObservedObject var session: GameSession

    var body: some View {
        let state = session.state
        VStack(spacing: 12) {
            Text("Game \(state.gameCode): \(state.players.count) player(s)")
            Text(connectionText(state.connection))
            if state.phase == GamePhase.lobby {
                if state.role == .host {
                    let problem = session.startGameProblem("counter")
                    Button("Start") { session.startGame("counter") }.disabled(problem != nil)
                    if let problem { Text(problem) }
                }
            } else if case .int(let count)? = state.view?["count"] {
                Text("Count: \(count), turn: \(state.view?["turn"]?.stringValue ?? "?")")
                Button("Add 1") { session.act(.object(["add": .int(1)])) }
            }
            if let notice = state.gameNotice { Text(notice) }
            Button("Leave") { kit.leave() }
        }
    }
}
```

### What the two apps do differently

- **Creating the kit.** Android: `HyperBleKit(context, config)`; only the application context is
  kept. iOS: `HyperBleKit(config:)`.
- **Observing.** Android publishes `StateFlow`s (`kit.session`, `kit.discoveredGames`,
  `session.state`). On iOS `HyperBleKit` and `GameSession` are `ObservableObject`s with
  `@Published` properties.
- **Joining and discovery.** On Android `join()` stops discovery itself, because scanning and
  connecting compete for the radio. On iOS it does not; the example calls `stopDiscovery()`
  first. `host()` stops discovery on neither platform; in both examples
  the list leaves the screen and stops it.
- **Bluetooth is off.** On Android discovery fails with `ScanningState.Failed(reason)` and stays
  stopped until `startDiscovery()` is called again. On iOS it is
  `ScanningState.unavailable(BluetoothAvailability)` and the scan starts by itself when
  Bluetooth comes back.
- **The view.** Android: `state.view` is a `JsonPayload`; `toJson()` parses it into a new
  `org.json.JSONObject` on every call, so parse once per view. iOS: `state.view` is a
  `JSONValue`, an enum you read with the subscript and pattern matching.
- **After `close()`.** `host()` and `join()` on a closed kit throw `IllegalStateException` on
  Android and stop the program (`precondition`) on iOS.

Three things a real app does and the examples leave out:

- Keep the screen on while a session is open (`View.keepScreenOn` on Android,
  `UIApplication.shared.isIdleTimerDisabled` on iOS). A locked phone can neither host nor answer.
- Do not offer *Host* or *Join* while `kit.session` is not null.
- Set the player's name before hosting or joining: `kit.identity.storedPlayerName = "Anna"`.
  A name is at most 12 characters, and a session keeps the name it started with. Without one the
  player is called `And-3F9A` or `iOS-3F9A` (the first four characters of the device ID).

## Writing a game module

A game module is the rules of one game as pure logic: no Bluetooth, no screen, no clock, no
threads. Only the host runs it. The SDK calls it on the main thread, so every call has to return
quickly.

A module comes in two parts:

| Part | Android | iOS | Its job |
|------|---------|-----|---------|
| The module | `GameModule` | `GameModule` | Describes the game and creates instances. It holds no game state. |
| One state of a game | `GameInstance` | `GameInstance` | Immutable. A move does not change it; it returns the next instance. |

What the module supplies:

| Member | Meaning |
|--------|---------|
| `id` | The identifier that is sent between phones, e.g. `"counter"`. The same in the Android and the iOS build, unique among the app's games, never changed. |
| `title` | The name for the screen. The SDK uses it in the sentences of `startGameProblem`. |
| `playerCountLabel` | Android only: the supported player counts as text, e.g. `"2–4"`. iOS works the range out by asking `supports`. |
| `supports(playerCount)` | Whether that many players can play, the host included. |
| `start(playerIds, random)` | A new game for these players, in ascending seat order. |
| `restore(state)` | A game continued from the JSON another host exported with `state()`. Return null if the JSON cannot be read; the session then goes back to the lobby. |

What an instance supplies:

| Member | Meaning |
|--------|---------|
| `finished` | True once the game is over. The phase becomes `finished`, no more actions are passed on, and the last views stay on screen until the host starts another game or ends this one. |
| `apply(playerId, action, random)` | Judges one move: accepted with the next instance, or rejected with a short code and an optional sentence. |
| `view(playerId)` | What this one player may see, as JSON. |
| `state()` | The whole game as JSON, hidden information included. |
| `removePlayer(playerId)` | The game without a player who is gone for good, or null if it cannot go on; the session then goes back to the lobby. |

### The rules a module has to obey

- **Pure.** No I/O, no shared mutable state. An instance never changes after it is created.
- **Deterministic given the random source.** Use only the `random` argument for shuffling and
  dice, never a random generator of your own and never the clock. Then a test with a seeded
  generator can replay a game. Android passes a `kotlin.random.Random`; iOS passes a generic
  `inout` `RandomNumberGenerator`.
- **`view` hides what a player must not see.** The view for `p2` leaves out the other players'
  cards. A view is sent to that player's phone unencrypted and is all that phone ever gets.
  `view` is also called for people who are in the session but not in the game (they joined the
  lobby while disconnected at the start, for example): return a spectator's view for them.
- **Distrust `action`.** It comes from another phone. `apply` is called for any player in the
  session, also out of turn and also for someone who is not in the game. Reject what is not
  allowed; never crash on it.
- **`state()` and `restore()` are a pair, across platforms.** The host sends `state()` to the
  phones that may take the game over, after every change, and one of them calls `restore()` when
  the host is gone. An iPhone may restore what an Android phone exported and the other way
  round, so both builds must use the same JSON for the state, the view and the action. Keep the
  state small.
- **`removePlayer` for someone who is not in the game returns the same instance.** A removed
  spectator changes nothing.
- **Integers only in JSON.** `JSONValue` on iOS has no floating-point case, and JSON with a
  fraction cannot be decoded there.

### Counter on Android

```kotlin
import io.hypercode.hyperblekit.game.GameInstance
import io.hypercode.hyperblekit.game.GameModule
import io.hypercode.hyperblekit.game.GameOutcome
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** The players take turns adding 1 to a shared count. The game is over at 10. */
object CounterGame : GameModule {
    override val id = "counter"
    override val title = "Counter"
    override val playerCountLabel = "2–4"

    override fun supports(playerCount: Int): Boolean = playerCount in 2..4

    override fun start(playerIds: List<String>, random: Random): GameInstance =
        // The kit's random source decides who begins.
        Counter(playerIds, turn = random.nextInt(playerIds.size), count = 0)

    override fun restore(state: JSONObject): GameInstance? {
        // The state may come from an iPhone host: check it instead of trusting it.
        val array = state.optJSONArray("players") ?: return null
        val players = List(array.length()) { array.optString(it) }
        val turn = state.optInt("turn", -1)
        if (turn !in players.indices) return null
        return Counter(players, turn, state.optInt("count"))
    }
}

/** One state of the game. Its state JSON: {"players":["p1","p2"],"turn":0,"count":3}. */
private class Counter(val players: List<String>, val turn: Int, val count: Int) : GameInstance {
    override val finished: Boolean get() = count >= 10

    override fun apply(playerId: String, action: JSONObject, random: Random): GameOutcome = when {
        action.optInt("add") != 1 -> GameOutcome.Rejected("bad_action")
        players[turn] != playerId -> GameOutcome.Rejected("not_your_turn", "it is ${players[turn]}'s turn")
        else -> GameOutcome.Accepted(Counter(players, (turn + 1) % players.size, count + 1))
    }

    // Counter has no secrets, so every player gets the same facts.
    override fun view(playerId: String): JSONObject =
        JSONObject().put("count", count).put("turn", players[turn]).put("you", playerId)

    override fun state(): JSONObject =
        JSONObject().put("players", JSONArray(players)).put("turn", turn).put("count", count)

    override fun removePlayer(playerId: String): GameInstance? {
        val index = players.indexOf(playerId)
        if (index < 0) return this
        // One player alone cannot take turns: the session goes back to the lobby.
        if (players.size <= 2) return null
        val left = players - playerId
        // If it was the removed player's turn, the player after them is next.
        val nextTurn = left.indexOf(players[turn]).takeIf { it >= 0 } ?: (index % left.size)
        return Counter(left, nextTurn, count)
    }
}
```

### Counter on iOS

```swift
import HyperBleKit

/// The players take turns adding 1 to a shared count. The game is over at 10.
struct CounterModule: GameModule {
    let id = "counter"
    let title = "Counter"

    func supports(playerCount: Int) -> Bool {
        (2...4).contains(playerCount)
    }

    func start<R: RandomNumberGenerator>(playerIds: [String], using random: inout R) -> GameInstance {
        // The kit's random source decides who begins.
        CounterGame(players: playerIds, turn: Int.random(in: 0..<playerIds.count, using: &random), count: 0)
    }

    func restore(state: JSONValue) -> GameInstance? {
        // The state may come from an Android host: check it instead of trusting it.
        guard let game = try? state.decoded(as: CounterGame.self),
              game.players.indices.contains(game.turn) else { return nil }
        return game
    }
}

/// One state of the game. Codable gives the state JSON: {"players":["p1","p2"],"turn":0,"count":3}.
struct CounterGame: GameInstance, Codable {
    var players: [String]
    var turn: Int
    var count: Int

    var finished: Bool { count >= 10 }

    func apply<R: RandomNumberGenerator>(playerId: String, action: JSONValue, using random: inout R) -> GameOutcome {
        guard action["add"] == .int(1) else {
            return .rejected(GameRejection(code: "bad_action"))
        }
        guard players[turn] == playerId else {
            return .rejected(GameRejection(code: "not_your_turn", reason: "it is \(players[turn])'s turn"))
        }
        var next = self
        next.count += 1
        next.turn = (turn + 1) % players.count
        return .accepted(next)
    }

    func view(for playerId: String) -> JSONValue {
        // Counter has no secrets, so every player gets the same facts.
        .object(["count": .int(count), "turn": .string(players[turn]), "you": .string(playerId)])
    }

    func state() -> JSONValue {
        // Encoding strings and integers cannot fail.
        (try? JSONValue(encoding: self)) ?? .null
    }

    func removePlayer(_ playerId: String) -> GameInstance? {
        guard let index = players.firstIndex(of: playerId) else { return self }
        // One player alone cannot take turns: the session goes back to the lobby.
        guard players.count > 2 else { return nil }
        let current = players[turn]
        var next = self
        next.players.remove(at: index)
        // If it was the removed player's turn, the player after them is next.
        next.turn = next.players.firstIndex(of: current) ?? index % next.players.count
        return next
    }
}
```

The two files produce the same three JSON formats, which is what lets mixed phones play and take
the game over from each other:

| JSON | Example |
|------|---------|
| action | `{"add":1}` |
| view | `{"count":3,"turn":"p2","you":"p1"}` |
| state | `{"players":["p1","p2"],"turn":1,"count":3}` |

Where the platforms differ in this interface:

- A rejection is `GameOutcome.Rejected(code, reason)` on Android and
  `.rejected(GameRejection(code:reason:))` on iOS.
- JSON is `org.json.JSONObject` on Android and `JSONValue` on iOS. `JSONValue(encoding:)` and
  `decoded(as:)` convert to and from `Codable` types.
- Android's `GameModule` has `playerCountLabel`; iOS's does not.

Counter hides nothing. A card game does: its `view` for a player contains that player's own
hand and only the sizes of the other hands, while `state` contains every hand.

### Starting and ending a game

All of these are host only; on a client they do nothing.

| Call | Effect |
|------|--------|
| `session.startGame(gameId)` | Starts the module with this ID for everyone who is connected at that moment. Allowed in the lobby and after a finished game. A refused start changes nothing but `state.gameNotice`. |
| `session.startGameProblem(gameId)` | Why a start would be refused right now, as an English sentence, or null. Use it to disable the start button. It is not observable: ask again when `state` changes. |
| `session.endGame()` | Ends the game, running or finished; everyone is back in the lobby. |
| `session.removePlayer(playerId)` | Removes a disconnected player at once instead of waiting for the countdown in `state.offlineSecondsLeft`. Refused for a connected player and for the host. |

`startGameProblem` is not identical on the two platforms. iOS also answers with the
weak-connection reason of a real-time game (see below); Android reports that one only in
`state.gameNotice`, after `startGame` was called. The wording of the sentences differs too.

A client needs the module of the game the host started, in its own `HyperBleKitConfig.games`, to
take that game over and to receive a real-time game's frames.

## Real-time games

A real-time game is one whose world moves by itself many times a second, such as an action game.
It is a game module like any other, with two layers:

- The **coarse state**: scores, the round, who won. It changes rarely and travels like any
  game's, reliably, through `state()` and `view()`.
- The **world**: positions and speeds. The host advances it in fixed steps called **ticks** and
  sends it as **frames**: single Bluetooth packets that are never acknowledged and never
  repeated. A frame that is lost or late is replaced by the next one. Players steer with **input
  frames** in the other direction. This is the frame lane.

### The module side

Implement `RealtimeGameModule` and `RealtimeGameInstance` instead of the plain interfaces.

The module adds a `RealtimeSpec`, the fixed numbers of the game. For example, a world that
advances 30 times a second and is sent 15 times a second:

```kotlin
val spec = RealtimeSpec(tickMillis = 33, framePeriodTicks = 2, worldKind = 0x10, inputKind = 0x11, maxWorldFrameBytes = 87)
```

```swift
let spec = RealtimeSpec(tickMillis: 33, framePeriodTicks: 2, worldKind: 0x10, inputKind: 0x11,
                        maxWorldFrameBytes: 87)
```

| Field | Android type | iOS type | Meaning |
|-------|--------------|----------|---------|
| `tickMillis` | `Long` | `Int` | The length of one tick. 33 gives about 30 ticks a second. |
| `framePeriodTicks` | `Int` | `Int` | A world frame is sent to the clients every this many ticks; at least 1. 2 at 30 ticks a second gives 15 frames a second. Bluetooth carries roughly 15 to 30 small frames a second per client. |
| `worldKind` | `Int` (0 to 255) | `UInt8` | The first byte of every world frame. Not `0x01` or `0x02`, which the speed test uses. |
| `inputKind` | `Int` (0 to 255) | `UInt8` | The first byte of every input frame. Different from `worldKind`, and also not `0x01` or `0x02`. |
| `maxWorldFrameBytes` | `Int` | `Int` | The size of the largest world frame the game builds, kind byte included. |

The spec must be the same on every phone. The module also has two functions that the SDK uses
only for the round-trip figures in its log; a game without sequence numbers returns null from
both:

| Android | iOS | Returns |
|---------|-----|---------|
| `inputSeq(input: ByteArray): Int?` | `inputSeq(inInput: Data) -> UInt8?` | The sequence number inside an input frame. |
| `echoedInputSeq(worldFrame: ByteArray, view: JSONObject): Int?` | `echoedInputSeq(inWorld: Data, view: JSONValue) -> UInt8?` | The newest input sequence number of the viewing player that the host had used when it built this world frame. |

The instance adds:

| Android | iOS | Meaning |
|---------|-----|---------|
| `players: List<String>` | `playerIds: [String]` | Who plays. Only their inputs are passed on, and only they pause the game by going offline. |
| `realtime: RealtimeSpec` | (none) | Android only: the same value as the module's. |
| `input(playerId, payload)` | `input(playerId:payload:)` | Takes a player's newest input frame. It counts at the next tick. The payload comes from another phone: check its length and content. |
| `tick(): RealtimeTick` | `tick() -> (instance:coarseChanged:)` | Advances the world by one tick. `coarseChanged` is true when `state()`, `view()` or `finished` differ from before; the SDK then sends every player their new view reliably. Keep it false on ordinary ticks. |
| `worldFrame()` | `worldFrame()` | The world as one frame, the same for every player, so nothing secret goes in it. First byte `worldKind`, at most `maxWorldFrameBytes` bytes. |

How the host runs it:

- **Ticks.** While the game is being played the host calls `tick()` every `tickMillis`, on the
  main thread. After a stall of up to 1 second it calls `tick()` several times in a row to catch
  up; after a longer one the missed ticks are dropped.
- **The host's own input** is applied one tick late, so the host has no advantage.
- **Pause and resume.** While one of the game's players is disconnected the host does not tick
  and sends no frames. The world moves again 3 seconds after the last of them is back. There is
  no event for this: a screen shows "paused" while a player of the game has `connected == false`
  in `state.players`. A pause that a player asks for is a matter for the game itself, as an
  ordinary action through `act`, for example `pause` and `resume` actions.
- **Host migration.** `restore()` gets only the coarse state, because the world is not part of
  `state()`. Restore to a sensible restart, for example a new round with the scores kept.

### Size limits

A frame has to fit one Bluetooth packet; it is never split.

- 17 bytes always fit, on every link.
- More fits when the two phones have negotiated larger packets, which most do. An Android client
  asks for a packet size that leaves 241 bytes for a frame. iOS negotiates by itself; the wire
  specification gives about 185 bytes per packet as typical there.
- The host refuses to start a real-time game while a connected player's link carries less than
  `maxWorldFrameBytes`. The reason, e.g. "Dogfight needs a better connection to p2", appears in
  `state.gameNotice`.
- Keep input frames to a few bytes: the kind, a sequence number and the buttons pressed fit
  in 3.

### The screen side

While a real-time game is on the table (phases `playing` and `finished`),
`GameSession.realtime` holds a `RealtimeLink`. It is the same on the host and on a client, so
the screen does not need to know its role. Every game gets a new link object, and so does a game
that continues after a role change: keep per-game drawing state together with the link.

| What | Android | iOS |
|------|---------|-----|
| Frames | `worldFrames: SharedFlow<RealtimeFrame>`; a `RealtimeFrame` has `payload: ByteArray` and `atMillis` (arrival time on `SystemClock.elapsedRealtime()`) | `worldFrames: AnyPublisher<Data, Never>` |
| Newest frame, for a render loop that polls | `worldFrames.replayCache.lastOrNull()` | `latestWorldFrame` |
| Timing | `spec` (the whole `RealtimeSpec`) | `tickMillis` |
| Send input | `sendInput(payload: ByteArray)`, from any thread | `sendInput(_ payload: Data)`, main thread |
| Keep-alive interval | `RealtimeLink.INPUT_KEEPALIVE_MILLIS` (250) | no constant; use 250 ms |

Following the link and its frames:

```kotlin
import io.hypercode.hyperblekit.GameSession
import io.hypercode.hyperblekit.RealtimeLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class WorldRenderer(private val scope: CoroutineScope) {
    /** Follows the session's link: every game, and every role change, brings a new one. */
    fun follow(session: GameSession) {
        scope.launch {
            session.realtime.collectLatest { link ->
                link?.worldFrames?.collect { frame -> draw(frame.payload, frame.atMillis) }
            }
        }
    }

    /** Call when a control changes, and at least every RealtimeLink.INPUT_KEEPALIVE_MILLIS. */
    fun send(buttons: Int, seq: Int, link: RealtimeLink) {
        link.sendInput(byteArrayOf(link.spec.inputKind.toByte(), seq.toByte(), buttons.toByte()))
    }

    private fun draw(payload: ByteArray, atMillis: Long) {
        // payload[0] is the world kind; the rest is the game's own format.
    }
}
```

```swift
import Combine
import Foundation
import HyperBleKit

@MainActor
final class WorldRenderer {
    private var frames: AnyCancellable?

    /// Follows the session's link: every game, and every role change, brings a new one.
    func follow(_ session: GameSession) {
        frames = session.$realtime
            .map { link in link?.worldFrames ?? Empty().eraseToAnyPublisher() }
            .switchToLatest()
            .sink { [weak self] payload in self?.draw(payload) }
    }

    /// Call when a control changes, and at least every 250 ms.
    func send(buttons: UInt8, seq: UInt8, on session: GameSession) {
        session.realtime?.sendInput(Data([0x11, seq, buttons]))
    }

    private func draw(_ payload: Data) {
        // payload[0] is the world kind; the rest is the game's own format.
    }
}
```

What a screen has to know:

- **A host sees more frames than a client.** On the host, `worldFrames` carries every tick of
  its own simulation. A client gets one frame every `framePeriodTicks` ticks, minus the ones
  that were lost.
- **Draw a little behind and interpolate.** The sample screens draw about 100 ms behind the
  newest frame and interpolate between two frames. Put a tick number into your world frame so
  the screen can time frames by it; on Android `atMillis` is available as well. A tick number
  lower than the previous frame's means a new round started.
- **Inputs are not repeated by the link**, and one can get lost. Send one when a control changes
  and at least every 250 ms. If the previous input has not left the phone yet, the new one
  replaces it.
- **Dropped silently:** an input sent while the game is not running, while a client is not
  connected, or on a link that is no longer the current one. An input whose first byte is not
  `inputKind` is never applied.
- **Nothing arrives** while the game is paused or finished.
- On Android a collector that falls more than 16 frames behind loses the oldest ones.

## Connection lifecycle

### Connection states

`state.connection` says where the phone stands. A host is `Joined` from the start until the
session is left. (Kotlin names are shown; Swift uses the same names in lower camel case, e.g.
`.reconnecting(secondsLeft:)`.)

| `ConnectionState` | Meaning | What to show |
|-------------------|---------|--------------|
| `Connecting` | A client is setting up the Bluetooth link to the host. | A progress indicator. Joining takes a few seconds. |
| `Joining` | The link is up and the join request is sent; the host has not answered. | The same. `state.notice` may already say why the host is going to refuse. |
| `Joined` | In the game. Moves are possible. | The lobby or the game. |
| `Reconnecting(secondsLeft)` | A client lost the host and is looking for it, or for the phone that took the game over. Moves are not possible. | The last view, dimmed, with the countdown. Do not close the screen. |
| `Ended(reason)` | The session is over for this phone and will not come back. | The reason, and a button that calls `kit.leave()`. |

An ended session stays in `kit.session`, so its last state can be shown, until `leave()` is
called.

### End reasons

| `EndReason` | When |
|-------------|------|
| `Rejected(code)` | The host refused the join request or removed this player. `code` is one of the `JoinRejectCode` values below. |
| `HostLeft` | The host ended the game by leaving with nobody to hand it to, or no host was found within the reconnect window. |
| `Disconnected(detail)` | The first connection to the host could not be set up, so there was never a game to return to. `detail` is an English sentence, or null. |
| `Left` | This phone left: `leave()`, `close()`, or a new `host()` or `join()`. The session object is finished for good. |

| `JoinRejectCode` | Value on the wire | Meaning |
|------------------|-------------------|---------|
| `LOBBY_FULL` / `lobbyFull` | `lobby_full` | Every seat is taken. |
| `GAME_STARTED` / `gameStarted` | `game_started` | A game is running or finished and this phone was not in it. Only players who were in it can come back. |
| `VERSION_MISMATCH` / `versionMismatch` | `version_mismatch` | The two apps run different protocol versions. |
| `REMOVED` / `removed` | `removed` | The host removed this player. |

A function that turns the connection into a sentence, used by the minimal apps above:

```kotlin
import io.hypercode.hyperblekit.ConnectionState
import io.hypercode.hyperblekit.EndReason
import io.hypercode.hyperblekit.JoinRejectCode

fun connectionText(connection: ConnectionState): String = when (connection) {
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Joining -> "Joining…"
    ConnectionState.Joined -> "Connected"
    is ConnectionState.Reconnecting -> "Connection lost. Reconnecting, ${connection.secondsLeft} s left"
    is ConnectionState.Ended -> when (val reason = connection.reason) {
        is EndReason.Rejected -> when (reason.code) {
            JoinRejectCode.LOBBY_FULL -> "The game is full"
            JoinRejectCode.GAME_STARTED -> "The game has already started"
            JoinRejectCode.VERSION_MISMATCH -> "The host runs a different version of the app"
            JoinRejectCode.REMOVED -> "The host removed you from the game"
            else -> "The host refused: ${reason.code}"
        }
        EndReason.HostLeft -> "The host left. The game is over"
        is EndReason.Disconnected -> "Could not connect" + (reason.detail?.let { ": $it" } ?: "")
        EndReason.Left -> "You left the game"
    }
}
```

```swift
import HyperBleKit

func connectionText(_ connection: ConnectionState) -> String {
    switch connection {
    case .connecting: return "Connecting…"
    case .joining: return "Joining…"
    case .joined: return "Connected"
    case .reconnecting(let secondsLeft): return "Connection lost. Reconnecting, \(secondsLeft) s left"
    case .ended(let reason):
        switch reason {
        case .rejected(let code):
            switch code {
            case JoinRejectCode.lobbyFull: return "The game is full"
            case JoinRejectCode.gameStarted: return "The game has already started"
            case JoinRejectCode.versionMismatch: return "The host runs a different version of the app"
            case JoinRejectCode.removed: return "The host removed you from the game"
            default: return "The host refused: \(code)"
            }
        case .hostLeft: return "The host left. The game is over"
        case .disconnected(let detail): return "Could not connect" + (detail.map { ": \($0)" } ?? "")
        case .left: return "You left the game"
        }
    }
}
```

### The rest of `SessionState`

Fields that only one role has are null or empty in the other role.

| Field | Role | Meaning |
|-------|------|---------|
| `role` | both | `HOST` or `CLIENT`, right now. |
| `gameCode` | both | The four-character code. It never changes, whichever phone hosts. |
| `playerId` | both | This phone's player ID. Null on a client until the host assigned it. |
| `players` | both | Everyone in the session in seat order, as `Player` values: `id`, `name`, `platform` (`"android"` or `"ios"`), `connected`, `canHost`, `handSize` (never set by the SDK). |
| `hostId`, `hostEpoch` | both | The player ID of the current host, and how many hosts the game has had (1 for the first, one more after each takeover). Before a client has learnt them: `hostId` is null on Android and `"p1"` on iOS; `hostEpoch` is 0. |
| `phase` | both | One of the `GamePhase` constants: `lobby`, `playing`, `finished`. |
| `gameId` | both | The `id` of the module that is being played or has finished; null in the lobby. |
| `view` | both | This player's view; null in the lobby. |
| `notice` | client | Something the user should know about joining, e.g. "The lobby is closed: only returning players can join." English text. |
| `gameNotice` | both | Why the last move or host request was refused; cleared when the game moves on. Text for the screen only: its wording differs between the platforms, so do not parse it. |
| `advertising` | host | Whether the game can be found. See [Troubleshooting](#troubleshooting). |
| `pingStats` | host | Round-trip times to the clients: `count`, `totalMs`, `worstMs`, `averageMs`. |
| `successors` | host | The player IDs that would take the game over, first in line first. |
| `offlineSecondsLeft` | host | For every disconnected player, the seconds left until the host removes them. |
| `migrationInfo`, `lastReconnect` | client | One English line each for a debug screen. |

### Reconnecting

The host pings every client every 3 seconds. A side that hears nothing for 10 seconds treats the
other as gone.

- **A client that loses the host** goes to `Reconnecting` and keeps looking for the game, by its
  game code, for 45 seconds. If it gets back in, it is `Joined` again with its old seat and the
  current view. If not, the session ends with `HostLeft`.
- **On the host**, a client that went silent stays in `players` with `connected == false` and an
  entry in `offlineSecondsLeft`. After 30 seconds the host removes the seat and tells the game
  module (`removePlayer`). `session.removePlayer(playerId)` does the same at once. A player who
  comes back after being removed is a new player: welcome in the lobby, refused with
  `game_started` during a game.

### Host migration

The host regularly sends a full copy of the game to its first two **successors**: the connected
players whose phones can host, in seat order (`HyperBleKit.snapshotSuccessors` is 2). What
happens next depends on how the host goes:

- **The host leaves on purpose** (`leave()`). It hands the game to its first successor, which
  takes over at once. With no successor the game ends for everyone with `HostLeft`.
- **The host disappears** (crash, out of range, battery). The clients notice after up to
  10 seconds of silence and start reconnecting. The first successor takes over 5 seconds after
  it noticed; the next one 8 seconds later if there is still no host. The wire specification
  puts the pause at roughly 10 to 20 seconds.
- **The old host comes back** while another phone is hosting its game. It steps down and rejoins
  as a client. It does not get the host role back.

From the app's point of view:

- The `GameSession` object stays the same. On the phone that takes over, `state.role` flips to
  `HOST` and `connection` is `Joined`; on a host that steps down, `role` flips to `CLIENT`.
  `hostId` changes and `hostEpoch` goes up by one for everyone.
- Offer the host-only controls (start, end, remove) from `state.role`, not from who created the
  game.
- `session.realtime` gets a new link object, and `session.diagnostics` describes the new role.
- A game continues on the new host only if that phone's app has the module; otherwise the
  session goes back to the lobby.
- A phone that cannot host is never a successor.
- The game continues from the last copy the new host received. If the host died before its
  newest copy arrived, that is one change earlier.

### Background and foreground

Hosting needs the app in the foreground, on both platforms: iOS suspends an app in the
background and Android may freeze one, and a stopped host stalls the game for everyone. The SDK
cannot change that, but it can move the game away in time. Tell it about both transitions:

| | Android | iOS |
|-|---------|-----|
| `kit.onBackground()` | In the activity's `onStop`, except for a configuration change (`isChangingConfigurations`). | When the scene phase becomes `.background`. |
| `kit.onForeground()` | In the activity's `onStart`. | When the scene phase becomes `.active`. |

What they do:

- **A host that goes to the background** hands the game to its first successor, exactly as if it
  left on purpose, and stops hosting. On iOS the SDK asks the system for the few seconds of
  background time this takes. When the app returns, `onForeground()` rejoins the same game as a
  client, in the same `GameSession`.
- **A host with no successor** keeps the host role. Its clients go to `Reconnecting` and find it
  again if the app returns within their 45 seconds.
- **A client** does nothing in `onBackground()`. In `onForeground()` it checks at once what it
  could not check while stopped: whether the host went silent, and whether the reconnect time
  ran out.
- `onBackground()` may be called more than once before `onForeground()`.

### `leave()` and `close()`

| Call | Use it when | Effect |
|------|-------------|--------|
| `kit.leave()` | The user leaves the game, or dismisses an ended session. | A host hands the game over or ends it; a client tells the host and disconnects. `kit.session` becomes null. The session object's last state is `Ended(Left)`, and every later call on it does nothing. |
| `kit.close()` | The owner of the kit goes away (`ViewModel.onCleared`, the end of multiplayer in the app). | Leaves as above, stops discovery and everything the kit started. A closed kit is not used again; create a new one. |

The last messages of a leaving phone are sent in the background for up to 2 seconds after
`leave()` returns. Hosting again in that moment can cut them off, so do not offer *Host* while
`kit.session` is not null, and expect `host()` and `join()` to leave an open session first.

## Configuration

`HyperBleKitConfig` is created once and does not change. Only `games` is required.

| Field | Default | Limits | Meaning |
|-------|---------|--------|---------|
| `games` | (required) | IDs should be unique | The game modules this app can play, in the order the app wants to offer them. |
| `serviceUuid` (Android), `serviceUUID` (iOS) | `6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E` | any UUID | The Bluetooth service a game is advertised and found under. |
| `localNamePrefix` | `"CG-"` | 1 to 4 ASCII characters | An iOS host advertises this prefix followed by the game code as its Bluetooth name, e.g. `CG-7K2F`. |
| `manufacturerId` | `0xFFFF` | 0 to `0xFFFF` (Android `Int`, iOS `UInt16`) | The Bluetooth company identifier under which an Android host advertises the game code. `0xFFFF` is reserved for testing; a company that has its own identifier should use it. |
| `maxPlayers` | 10 | 2 to 10 | Seats in a game, the host included. |
| `logCapacity` | 500 | at least 1 | Lines kept in the log's buffer. The log is shared by the process, so the kit created last decides. |

A value outside its limits is a programming error: Android throws `IllegalArgumentException`
from the constructor, iOS stops the program (`precondition`).

```kotlin
val config = HyperBleKitConfig(
    games = listOf(CounterGame),
    serviceUuid = UUID.fromString("7B1D0001-5C3E-4F7A-9B2D-3E6A1C8F4D20"),
    localNamePrefix = "CN-",
    maxPlayers = 4,
)
```

```swift
let config = HyperBleKitConfig(
    games: [CounterModule()],
    serviceUUID: UUID(uuidString: "7B1D0001-5C3E-4F7A-9B2D-3E6A1C8F4D20")!,
    localNamePrefix: "CN-",
    maxPlayers: 4
)
```

**Why the service UUID matters.** Discovery scans for exactly this UUID, so two apps with
different service UUIDs never see each other's games. With the default, your app finds the games
of every other app that uses the default, and they find yours. Generate a UUID of your own
(`uuidgen` on a Mac) before you ship; the one in the example above is only an example. The
service's three characteristics get the UUIDs that follow it, with 1, 2 and 3 added to the first
32-bit group (for the default `6E4A0001-…`: `6E4A0002-…`, `6E4A0003-…`, `6E4A0004-…`), so leave
those three free.

**What has to match.** `serviceUuid`, `localNamePrefix`, `manufacturerId` and `maxPlayers` must
be the same on every phone in a game: use the same values in your Android and your iOS build.
An Android host puts the game code into manufacturer data and an iOS host into its name, and a
phone of either kind has to read both.

**What cannot be configured.** The timings and the message format are fixed by the SDK, because
a game only works when every phone in it uses the same ones:

| Timing | Value |
|--------|-------|
| Host pings every client | every 3 s |
| Silence until the other side counts as gone | 10 s |
| A client looks for a lost host | 45 s |
| An offline seat is removed | after 30 s (45 s right after a takeover) |
| First successor takes over | 5 s after it noticed the loss; each further successor 8 s later |
| Successors that hold a copy of the game | 2 |
| A paused real-time game resumes | 3 s after the last player is back |
| A leaving phone's last messages | up to 2 s |

## Threading

**Android**

- Create the kit and call every function of `HyperBleKit`, `GameSession`, `SessionDiagnostics`
  and `LocalIdentity` on the main thread.
- Every `StateFlow` of the kit and the session changes on the main thread, and world frames are
  emitted there.
- Game modules are called on the main thread.
- Bluetooth callbacks are handled inside the kit and never reach the app directly.
- `GameSession.state` changes one main-loop turn after the call or the message that caused it,
  never inside it. Code that reads `state.value` on the line after `startGame()` or `act()` still
  sees the old value: collect the flow instead. This is what makes it safe to call `kit.leave()`
  from inside a collector.
- Three exceptions: `RealtimeLink.sendInput` may be called from any thread (a render thread, for
  instance); `HyperBleLog.entries` may change from any thread and `HyperBleLog.clear()` may be
  called from any; `HyperBleLog.sink` is called on whichever thread logged the line, which is
  sometimes a Bluetooth callback thread.

**iOS**

- `HyperBleKit`, `GameSession` and `SessionDiagnostics` are `@MainActor`.
- `RealtimeLink`, `HyperBleLog` and `LocalIdentity` are not annotated but are for the main
  thread only; there is no exception for `sendInput`.
- Everything published changes on the main thread. Game modules are called on the main thread,
  and `HyperBleLog.sink` too.
- `GameSession.state` and `realtime` change one main-queue turn after the call or the message
  that caused it, never inside it; several changes in one turn arrive as one value. Reading
  `session.state` on the line after `startGame(_:)` or `act(_:)` still gives the old value:
  observe it instead. Leaving is the exception: `.ended(reason: .left)` is in place when
  `kit.leave()` returns. An observer may call anything on the kit, `leave()` included.
- The package is built in Swift 5 language mode: the main-thread rule is a convention there, not
  enforced by annotations on every type.

## Logging and diagnostics

### The log

Bluetooth cannot be tested without real phones, and what happened on one phone only makes sense
next to what happened on the other. `HyperBleLog` records every message sent and received and
every connection event, in the same line format on both platforms, so logs from different
phones can be compared line by line:

```text
HH:mm:ss.SSS TX|RX TYPE seq=N sv=N bytes=N chunks=N [extra]    a message
HH:mm:ss.SSS -- text                                           anything else
```

There is one log per process. It can be read in two ways: `entries`, a bounded buffer of the
newest lines for a debug screen, and `sink`, a callback that gets every line. A line can contain
player names and device IDs.

```kotlin
HyperBleLog.sink = { entry -> Log.d("Multiplayer", entry.line) }
val lastLines = HyperBleLog.entries.value.takeLast(20).map { it.line }
HyperBleLog.clear()
```

```swift
HyperBleLog.shared.sink = { line in print(line) }
let lastLines = HyperBleLog.shared.entries.suffix(20).map(\.text)
HyperBleLog.shared.clear()
```

| | Android (`io.hypercode.hyperblekit.log`) | iOS |
|-|---------|-----|
| Access | the object `HyperBleLog` | `HyperBleLog.shared` |
| `entries` | `StateFlow<List<LogEntry>>` | `@Published [HyperBleLog.Entry]` |
| An entry | `LogEntry`: `timestampMillis`, `level` (`INFO`, `WARN`, `ERROR`), `tag`, `message`, and the computed `formattedTime` and `line` | `Entry`: `id`, `text` (the whole line) |
| `sink` | `((LogEntry) -> Unit)?`; must be quick and thread-safe | `((String) -> Void)?`; called on the main thread; do not call the SDK from it |
| Whole log as text | join the `line`s | `text` |
| System log | every line also goes to Logcat under the tag `HyperBleKit` | not written anywhere else |

### Diagnostics

`session.diagnostics` holds two pieces of test equipment for checking a Bluetooth link on real
phones. A game does not need them, and they never touch the game's state.

**The JSON test frame.** `sendTestJson()` sends one fixed JSON document of a few hundred bytes:
a host sends it to every joined client, a client to the host. It is larger than one Bluetooth
packet, so it shows that a long message is split, carried and put together again unchanged. The
outcome appears as one line in `testResult` on both ends, and the received document in
`testJsonReceived`.

**The speed test.** It measures the frame lane before you design a real-time game around it. The
host moves a dot and sends its position many times a second; every client shows the dot, sends
inputs back, and both sides count what arrived, once a second.

| Member | Meaning |
|--------|---------|
| `canRunSpeedTest` | Whether a test would start now: this phone is the host, the session is in the lobby, and at least one client is connected. A `StateFlow<Boolean>` on Android; a plain property on iOS, to be read again when `state` changes. |
| `startSpeedTest(frameRate, payloadSize)` | Host only. `frameRate` is one of `SpeedTestOptions.FRAME_RATES` (15, 20, 30 frames a second), `payloadSize` one of `PAYLOAD_SIZES` (32, 64, 128 bytes). The defaults are 15 and 64. The test stops by itself when a game starts. |
| `stopSpeedTest()` | Host only. On Android it takes an optional `screenClosed: Boolean` that only changes the reason written to the log. |
| `tapSpeedTest()` | Client only: counts a tap and sends it to the host at once, to show how quickly an input arrives. |
| `hostSpeedTest` | What a host's panel shows; null on a client. |
| `clientSpeedTest` | What a client's view shows. Set while the host's test frames arrive and null again 2 seconds after the last one, so a screen can show the view whenever it is not null. |

The constants of `SpeedTestOptions` are in upper case on Android (`FRAME_RATES`, `FIELD_WIDTH`)
and lower camel case on iOS (`frameRates`, `fieldWidth`). The dot moves in a field of 320 × 180
units that the screen scales to fit.

The two state types have different shapes on purpose; each follows its platform's UI toolkit:

| | Android (`io.hypercode.hyperblekit.diagnostics`) | iOS |
|-|---------|-----|
| Kind | immutable data classes, a new value per change | `ObservableObject`s with `@Published` properties, so that only the speed-test view redraws |
| `HostSpeedTestState` | `linkedClients`, `isRunning`, `frameRate`, `payloadSize`, `dotX`, `dotY`, `lines` | `isRunning`, `frameRate`, `payloadSize`, `dot: CGPoint?`, `lines` |
| `ClientSpeedTestState` | `dotX`, `dotY`, `taps`, `lastSecond: String?`, `sinceStart` | `dot: CGPoint?`, `taps`, `lastSecondText`, `totalText` |

A statistics line reads like `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=48/71 size=64`:
frames received in the last second, frames dropped because a newer one had already arrived, the
average and the longest gap between frames in milliseconds, how many gaps were longer than
150 ms, the average and the longest time for an input to come back from the host, and the frame
size in bytes.

## Troubleshooting

**Discovery fails at once on Android, or finds nothing.**
A Bluetooth permission is missing; `scanningState` is `Failed` and says so. Declare the
permissions in the manifest and request `HyperBleKit.requiredPermissions` before the first call
on the kit; see
[Android permissions](#android-permissions). On Android 8 to 11, the system also finds nothing
while Location is switched off in the phone's settings.

**The app stops on iOS the first time it scans or hosts.**
`NSBluetoothAlwaysUsageDescription` is missing from the Info.plist.

**The user refused Bluetooth on iOS.**
`scanningState` is `.unavailable(.unauthorized)`. `BluetoothAvailability.userMessage` gives the
sentence "Bluetooth access is denied. Allow it in Settings > Privacy > Bluetooth." iOS does not
ask a second time; the user has to change it in Settings.

**Bluetooth is switched off.**

| | Android | iOS |
|-|---------|-----|
| Discovery | `ScanningState.Failed("Bluetooth is off — turn it on to find games.")`. Call `startDiscovery()` again once it is on. | `ScanningState.unavailable(.poweredOff)`. The scan starts by itself when Bluetooth is back. |
| Hosting | `state.advertising` is `AdvertisingState.Failed(reason)`. The kit starts hosting again by itself when Bluetooth is back. | `state.advertising` is `.unavailable(.poweredOff)`. Advertising resumes by itself. |
| `canHost` | false while Bluetooth is off | always true |

**Running on an emulator or simulator.**

- *iOS Simulator.* It has no Bluetooth, so the SDK replaces Bluetooth with stand-ins there:
  discovery lists two made-up games (`7K2F` and `Q9MX`); hosting reports that it is advertising
  but no client ever connects; joining ends after a moment with
  `.ended(reason: .disconnected(detail: "Connecting needs a real device: the simulator has no Bluetooth."))`.
  That is enough to build and preview screens. It cannot run a game with two players.
- *Android emulator.* The Android library has no stand-in. Without a usable Bluetooth adapter,
  discovery reports `ScanningState.Failed` and a hosted session reports `AdvertisingState.Failed`
  or `Unsupported`.
- To exercise game rules without phones, test the module directly: it is pure logic. For
  screens, both platforms let you construct `SessionState`, `Player`, `DiscoveredGame` and the
  other value types yourself, for previews and tests.

**This phone cannot host (Android).**
Its Bluetooth chip cannot advertise. `kit.canHost` is false, and a session from `host()` has
`state.advertising == AdvertisingState.Unsupported(reason)`. Such a phone can still join and
play; it never takes a game over. Check `canHost` before offering *Host*.

**Two phones do not see each other.** Check, in this order:

1. Both apps are in the foreground, and Bluetooth is on on both.
2. The host's `state.advertising` is `Advertising` and the other phone's `scanningState` is
   `Scanning`.
3. Both builds use the same `serviceUuid`, `localNamePrefix` and `manufacturerId`. A different
   service UUID makes the phones invisible to each other without any error.
4. The game has not dropped out of the list for a moment: a game that has not been heard for a
   while is removed (after 10 seconds on Android, 5 on iOS) and reappears with its next
   advertisement.
5. The log on both phones (see [The log](#the-log)).

**The game is in the list, but joining fails.**
Look at the end reason. `Rejected("game_started")`: a game is running and only its players can
come back; `state.notice` says so before the host answers. `Rejected("lobby_full")`: every seat
up to `maxPlayers` is taken. `Rejected("version_mismatch")`: the two apps were built from
different protocol versions. `Disconnected(detail)`: the link could not be set up; the entry in
the list may be stale, so scan again.

**`startGame` does nothing.**
Ask `startGameProblem(gameId)`, and show `state.gameNotice`. The usual reasons: the number of
connected players is one the module does not support, a real-time game's frames do not fit a
player's link, or the ID is not in `HyperBleKitConfig.games`. An unknown ID is not reported in
`gameNotice`.

**A move does nothing.**
Refused moves show up in `state.gameNotice`. On a client that is not `Joined`, a move is not
sent: Android says so in `gameNotice`, iOS drops it silently. Disable the controls unless
`connection` is `Joined`.

**An iPhone joins but never shows the game, or cannot take it over.**
Check that the iOS build has the module in `HyperBleKitConfig.games` with the same `id`, and
that the Android module's JSON contains only integers and the same keys as the iOS module
expects.

**A player drops out when a SwiftUI screen changes.**
Do not call `kit.leave()` from `onDisappear`. SwiftUI also sends it when it rebuilds a screen.
Leave only from an explicit *Leave* button.

**Hosting again right after leaving a hosted game misbehaves.**
The old game's last messages get up to 2 seconds to go out. Offer *Host* only while
`kit.session` is null, and not in the same moment as *Leave*.

**Where the device ID and the player's name are stored.**
`LocalIdentity` on iOS keeps its two values in `UserDefaults.standard` under the keys
`hyperblekit.deviceId` and `hyperblekit.playerName`. On Android they are in a private
`SharedPreferences` file named `local_identity`. Deleting them gives the device a new identity:
a host no longer recognises it as a returning player.

## Platform differences at a glance

The two libraries share their model and most names. These are the places where code does not
carry over one to one; each is explained where it comes up above.

| Topic | Android | iOS |
|-------|---------|-----|
| Create the kit | `HyperBleKit(context, config)` | `HyperBleKit(config:)` |
| Observation | `StateFlow` / `SharedFlow` | `ObservableObject` with `@Published`; Combine publishers |
| Permissions | `HyperBleKit.requiredPermissions`, requested by the app | Info.plist key; the system prompts at first use |
| `canHost` | read from the system each time: true when the chip can advertise and Bluetooth is on | always true |
| `discoveredGames` order | strongest signal first | order of first sighting |
| A game leaves the list after | 10 s without an advertisement | 5 s |
| `stopDiscovery()` | the list keeps its last content | the list is emptied |
| `join()` stops discovery | yes | no |
| Scan after Bluetooth returns | call `startDiscovery()` again | restarts by itself |
| `ScanningState` cases | `Idle`, `Scanning`, `Failed(reason)` | `idle`, `scanning`, `unavailable(BluetoothAvailability)` |
| `AdvertisingState` cases | `Idle`, `Starting`, `Advertising(gameCode)`, `Unsupported(reason)`, `Failed(reason)` | `idle`, `starting(gameCode:)`, `advertising(gameCode:)`, `unavailable(BluetoothAvailability)`, `failed(String)` |
| `BluetoothAvailability` | (none) | `unknown`, `ready`, `poweredOff`, `unauthorized`, `unsupported`, with `userMessage` |
| `DiscoveredGame` | `id: String`, `gameCode`, `platform` (currently always `UNKNOWN`), `rssi: Int?`, `firstSeenMillis`, `lastSeenMillis` | `id: UUID`, `gameCode`, `rssi: Int`, `discoveredAt`, `lastSeen` |
| `timeToFirstResult` | `StateFlow<Long?>`, milliseconds | `Int?`, milliseconds; also `discoveryTime(of:)` per game, in seconds |
| JSON type | `org.json.JSONObject` in; `JsonPayload` out (`state.view`) | `JSONValue` both ways |
| `act` | `act(action: JSONObject)` | `act(_ action: JSONValue)` |
| Config field | `serviceUuid` | `serviceUUID` |
| Invalid config | `IllegalArgumentException` | `precondition` failure |
| `host()` / `join()` after `close()` | `IllegalStateException` | `precondition` failure |
| Seconds in `Reconnecting`, `offlineSecondsLeft` | `Long` | `Int` |
| `SessionState.hostId` | `String?` | `String` (`"p1"` until known) |
| `LocalIdentity.maxNameLength` | instance property | static property |
| Default player name | `And-` + 4 characters | `iOS-` + 4 characters |
| Game module | has `playerCountLabel` | has none |
| Rejection | `GameOutcome.Rejected(code, reason)` | `.rejected(GameRejection(code:reason:))` |
| Real-time instance | `players`, `realtime`, `tick(): RealtimeTick` | `playerIds`, `tick()` returns a tuple |
| `RealtimeLink` | `spec`, `worldFrames: SharedFlow<RealtimeFrame>`, `INPUT_KEEPALIVE_MILLIS`; `sendInput` from any thread | `tickMillis`, `worldFrames` publisher of `Data`, `latestWorldFrame`; main thread only |
| `canRunSpeedTest` | `StateFlow<Boolean>` | plain property |
| Log | `HyperBleLog` object, `LogEntry`, also Logcat | `HyperBleLog.shared`, `Entry`, `text` |
| No-Bluetooth stand-in | none | on the iOS Simulator |

## Where to read more

- **The source.** Every public declaration has a documentation comment, and the source is the
  reference: `sdk/android/hyperblekit/src/main/java/io/hypercode/hyperblekit` (the root package
  and `game`, `diagnostics`, `log`; everything else is internal) and
  `sdk/ios/Sources/HyperBleKit`.
- **[`docs/wire-protocol.md`](wire-protocol.md)**, the wire protocol specification: what is
  sent, when, and in which format. Both platforms implement it byte for byte.
