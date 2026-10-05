# Module hyperblekit

HyperBleKit: local multiplayer for nearby phones, Android and iOS mixed, over Bluetooth Low Energy
only. One phone hosts a game, the others find and join it; the app brings the game's rules and its
screens, the kit does the rest.

Start with `io.hypercode.hyperblekit.HyperBleKit`.

# Package io.hypercode.hyperblekit

The kit itself and everything a session screen shows.

- `HyperBleKit` is the entry point: discovery, hosting, joining, leaving. `HyperBleKitConfig` is
  what it is created with.
- `GameSession` is one game this phone is in. Its `state` is a `SessionState`: the role
  (`SessionRole`), the connection (`ConnectionState`, `EndReason`), the players (`Player`), the
  game phase (`GamePhase`) and this player's view of the game (`JsonPayload`).
- `DiscoveredGame`, `ScanningState` and `AdvertisingState` describe finding games and being found.
- `RealtimeLink` and `RealtimeFrame` are what the screen of a real-time game uses.
- `LocalIdentity` is this phone's device ID and player name.
- `SessionDiagnostics` holds the debug tools of a session.

Everything here is used on the main thread.

# Package io.hypercode.hyperblekit.game

The interfaces an app implements to add a game. `GameModule` and `GameInstance` describe a
turn-based game as pure rules: start, apply a move, show a player their view, export the state.
`RealtimeGameModule` and `RealtimeGameInstance` add what a game needs whose world moves by itself.
Only the host runs a game's rules; every phone needs the modules in its configuration.

# Package io.hypercode.hyperblekit.diagnostics

The values shown by the speed test, a tool for measuring a Bluetooth link on real phones:
`HostSpeedTestState` on the host, `ClientSpeedTestState` on a client, and `SpeedTestOptions` with
the rates and sizes to choose from. The tool itself is reached through
`io.hypercode.hyperblekit.SessionDiagnostics`.

# Package io.hypercode.hyperblekit.log

The kit's log. `HyperBleLog` keeps the newest lines for a debug screen and passes every line to a
callback for the app's own logging; `LogEntry` is one line and `LogLevel` its severity.
