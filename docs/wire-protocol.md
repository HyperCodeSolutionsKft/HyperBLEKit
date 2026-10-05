# HyperBleKit wire protocol

This document specifies what HyperBleKit devices send to each other over Bluetooth Low Energy: the advertising data, the GATT service, the chunk and frame formats, the JSON messages, and the rules for sequencing, state sync, reconnection and host migration.

The Android and iOS implementations must agree on everything here byte for byte; a phone of either platform can host the other. Shared test vectors in `protocol/test-vectors/` check the envelope and the chunking on both platforms.

**Protocol version: 1.** It is the `v` field of every message and of `GameInfo`. A host rejects a join with a different version (`JOIN_REJECT` `version_mismatch`).

This is a reference for people implementing or debugging the protocol. How to use the SDK in an app is in the developer guide.

## Contents

- [Architecture](#architecture)
- [BLE Layout](#ble-layout): [Advertising](#advertising), [GATT Service](#gatt-service), [GameInfo](#gameinfo), [MTU and chunking](#mtu-and-chunking), [Real-time frames](#real-time-frames)
- [Connection Lifecycle](#connection-lifecycle)
- [Message Protocol](#message-protocol): [JSON rules](#json-rules), [Envelope](#envelope), [`seq` rules](#seq-rules), [Data models](#data-models), [`stateVersion` rules](#stateversion-rules), [Message types](#message-types), [Example flow](#example-flow)
- [Players and Seats](#players-and-seats)
- [Game modules](#game-modules): [Real-time games](#real-time-games)
- [Reliability Rules](#reliability-rules): [Reconnection](#reconnection)
- [Host Migration](#host-migration)
- [Known BLE Constraints](#known-ble-constraints)

---

## Architecture

```text
UI                          (lobby, player list, debug log)   +  game screen (per game module)
 ↓
Game module                 (rules of one game — runs on the host only)
 ↓
Multiplayer / Game Protocol (seats, messages, sequencing, state sync, host migration)
 ↓
BLE Transport               (advertising, scanning, GATT, chunking)
```

BLE is treated purely as a transport. The protocol layer sends and receives **messages**; it does not know about GATT.

The protocol layer in turn knows nothing about the rules of any game. A game is a **game module** that plugs into it (see *Game modules*), so the game can be swapped without touching the engine.

### Network topology: star (host-relay)

```text
          Player 2 (client)
                │
Player 5 ── HOST (Player 1) ── Player 3
                │
          Player 4 (client)
```

- The **host** runs a GATT server (BLE *peripheral*) and advertises the game.
- **Clients** are BLE *centrals* that connect to the host.
- Clients never talk to each other directly. The host receives every message, validates it, and **relays/broadcasts** the result to all clients.
- The host is the **single source of truth** for game state. Clients send *intents* ("I want to make this move"); the host sends *results* ("move made, next turn").
- **The host is also a player.** The first host is `p1`; after a takeover (see *Host Migration*) the host is whichever player `hostId` names. Its own actions go through the same game engine locally, without BLE.

Why star: a full mesh of 5 phones means each device holding 4 connections in mixed roles, which is unreliable on both platforms. In a star, only the host holds multiple connections (4), and each client holds just one.

---

## BLE Layout

### Advertising

| Field          | Value                                      | Notes |
|----------------|--------------------------------------------|-------|
| Service UUID   | `6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E`     | Clients scan filtered by this UUID |
| Game code      | e.g. `7K2F`                                | Where it goes depends on the host platform, see below |

**Game code format:** 4 characters from `A–Z` and `2–9`, excluding the easily confused `O` and `I` (digits `0` and `1` are never used). 32 symbols → ~1M codes. The host generates it randomly.

**How the game code is advertised:**

- **iOS host:** local name `CG-7K2F` (`CG-` + game code). Keep it short — iOS truncates long names.
- **Android host:** manufacturer-specific data in the **scan response**, company ID `0xFFFF` (reserved for testing), payload = the 4 ASCII bytes of the game code. The main packet holds flags + the 128-bit service UUID (21 of 31 bytes).
  Do **not** use `setIncludeDeviceName(true)`: it advertises the phone's system Bluetooth name, and changing that would rename the user's device.
- **Scanner:** accepts either source — local name with `CG-` prefix, or manufacturer data `0xFFFF`.

**Advertising during the game:** the host keeps advertising for the whole session so reconnects work. `GameInfo.lobbyOpen: false` tells scanners that new players cannot join.
On some Android devices advertising stops when a central connects — the Android host must check this and restart advertising after each connection if needed.

### GATT Service

| Characteristic | UUID | Properties          | Direction        | Purpose |
|----------------|------|---------------------|------------------|---------|
| `GameInfo`     | `6E4A0002-B5A3-F393-E0A9-E50E24DCCA9E` | Read                | host → client    | Game summary for the lobby list (see below) |
| `ClientToHost` | `6E4A0003-B5A3-F393-E0A9-E50E24DCCA9E` | Write (with response) | client → host  | Client messages (join, actions, acks, pongs) |
| `HostToClient` | `6E4A0004-B5A3-F393-E0A9-E50E24DCCA9E` | Notify              | host → clients   | Host messages (state, turns, broadcasts) |

Only the **first block** differs from the service UUID (`6E4A0001` → `0002`/`0003`/`0004`), like the Nordic UART service.

### GameInfo

Plain JSON, **not chunked** (no chunk header), max ~180 bytes:

```json
{"v":1,"gameId":"7K2F","hostName":"Gabor","players":2,"maxPlayers":10,"lobbyOpen":true,"hostPlatform":"ios","hostId":"p1","hostEpoch":1}
```

- The host updates the value whenever players join/leave or the lobby closes.
- Before the MTU exchange, reading 180 bytes needs several Read Blob requests. Both stacks do this automatically, but an **iOS host that serves the value dynamically must honor `request.offset`** in `didReceiveRead`, otherwise the JSON arrives corrupted.

### MTU and chunking

#### MTU

- **Android client:** call `requestMtu(247)` immediately after connecting, and start service discovery only after `onMtuChanged`.
- **Android host:** store the MTU **per device** from `onMtuChanged`. Assume 23 until a callback arrives.
- **iOS** negotiates automatically (typically ~185).

#### Chunk header

Every message is sent with a 3-byte header, **including single-chunk messages**:

```text
[msgId: 1 byte][chunkIndex: 1 byte][chunkCount: 1 byte][payload...]
```

| Rule | Value |
|------|-------|
| `msgId` | Separate counter **per connection and per direction**. Starts at 0, wraps to 0 after 255. |
| `chunkIndex` | Starts at 0. |
| `chunkCount` | At least 1. A single-chunk message is `[id][0][1][payload]`. |
| Max message size | 255 chunks × payload. At default MTU (23) that is 255 × 17 B ≈ 4 KB; at MTU 247 ≈ 61 KB. |

#### Payload size per chunk

Computed **per recipient**; the host chunks separately for each client and does not fall back to the smallest MTU:

| Sender | Max chunk payload |
|--------|-------------------|
| iOS host → client | `central.maximumUpdateValueLength − 3` |
| iOS client → host | `peripheral.maximumWriteValueLength(for: .withResponse) − 3` |
| Android (either role) | `MTU − 3 (ATT) − 3 (header)` = **`MTU − 6`** |

#### Reassembly

- A GATT link delivers in order, so chunks arrive in sequence. If `chunkIndex` is not the expected one, the **whole message is dropped** and logged.
- If a new `msgId` arrives while the previous message is incomplete, the partial message is dropped and logged.
- A partial message is also dropped after a **5 s timeout**.
- Dropped messages are recovered by the normal retry / state-sync mechanisms; the transport itself never retries.

#### Flow control

Chunks get lost, not reordered, if you ignore backpressure:

- **iOS host:** `updateValue(...)` returns `false` when the queue is full; wait for `peripheralManagerIsReady(toUpdateSubscribers:)` and resend that chunk.
- **Android host:** wait for `onNotificationSent` before sending the next notification to the same device.
- **Clients:** send the next chunk only after the write-with-response callback.

Most messages fit in one chunk; `STATE_SYNC` usually needs 2–3.

### Real-time frames

A real-time game (see *Game modules*) needs small updates many times a second, where only the newest one matters. These travel as **frames** on the same two characteristics, next to the messages:

```text
[frameSeq: 1 byte][0x00][0x00][payload...]
```

| Rule | Value |
|------|-------|
| Marker | `chunkIndex` 0 and `chunkCount` 0. A receiver that does not know frames drops them as malformed chunks, so older builds just ignore them. `chunkCount` 0 with another `chunkIndex` is malformed. |
| `frameSeq` | Counter **per link and per direction**, 0 for the first frame after the link comes up, +1 per frame sent, wraps 255 → 0. |
| Taking a frame | The first frame after the link comes up is taken. After that, frame `s` is taken only if `(s − last) mod 256` is 1…127; anything else is stale or duplicated, dropped and counted. |
| Size | One packet, never chunked: `payload ≤` the link's max chunk payload (table above); the frame header takes the place of the chunk header. |
| Payload | The first byte is the **kind**: `0x01` / `0x02` the SDK's speed test, `0x10`… the real-time games. |

- **Only the newest frame matters.** Each sender holds one unsent frame per link; a new frame replaces it. When the link may send again, a waiting frame goes **before** the next message chunk. Frames may arrive between the chunks of a message; reassembly ignores them.
- Client → host frames are written with response, like chunks, one write at a time.
- Frames are not ACKed, never resent, carry no `seq` or `stateVersion`, and are not logged one by one. Each side logs a summary line once a second (`RT rx …` / `RT tx …`).
- A frame from a client counts as traffic for the heartbeat, like a message.

---

## Connection Lifecycle

1. Client scans (filtered by service UUID), shows games with their code.
2. Client connects. Android client: `requestMtu(247)` → discovery. Android client also calls `requestConnectionPriority(HIGH)` for latency (this is a client-side API only; an Android host cannot set it).
3. Client reads `GameInfo` (optional, for the lobby list).
4. Client **subscribes to `HostToClient` notifications** and waits for confirmation (iOS: `didUpdateNotificationStateFor`; Android: `onDescriptorWrite` on the CCCD).
5. **Only then** does the client send `JOIN_REQUEST`. Otherwise it misses `JOIN_ACCEPT`.
   The host only sends to clients that have subscribed (iOS: `didSubscribeTo`; Android: CCCD write in `onDescriptorWriteRequest`).

---

## Message Protocol

Messages are **JSON** (UTF-8) — readable in logs and easy to debug.

### JSON rules

- Compact UTF-8, no whitespace.
- **Unknown fields are ignored.**
- `ts` is an Int64 in milliseconds. All numbers are integers.
- A missing optional field is **omitted**, never sent as `null`.

The JSON examples in this document are spread over several lines for reading; on the wire they are compact.

### Envelope

Every message has the same envelope:

```json
{
  "v": 1,
  "type": "GAME_ACTION",
  "seq": 42,
  "from": "p2",
  "ts": 1759415400123,
  "data": { }
}
```

| Field  | Meaning |
|--------|---------|
| `v`    | Protocol version — reject mismatches at join |
| `type` | Message type (see below) |
| `seq`  | Sender sequence number, increments by 1. Used for duplicate detection and acks (see rules below) |
| `from` | Player ID (the host uses its own player ID; the first host is `p1`). `"?"` before the client has a player ID; identification then uses `data.deviceId` |
| `ts`   | Sender timestamp in ms (for latency measurement only, never for game logic) |
| `data` | Type-specific payload |

### `seq` rules

- **Host:** one shared counter for all outgoing messages. A targeted message consumes its own `seq`; a broadcast uses one `seq` for all recipients. Each client therefore sees **gaps** in the host's `seq` (messages addressed to others) — **`seq` is not used for gap detection; `stateVersion` is.**
- **Client:** its own counter, starts at **1 on every (re)connection**. The host clears that player's dedup state when it receives `JOIN_REQUEST`.
- **Retries reuse the same `seq`**, so duplicate detection works.

### Data models

**Player**

```json
{ "id": "p2", "name": "Anna", "platform": "ios", "connected": true, "canHost": true }
```

**Game state** (host-owned) — what the engine knows. Everything about the game itself (whose turn it is, scores, what each player may see) lives in the game module's own JSON, see *Game modules*.

```json
{
  "gameId": "7K2F",
  "stateVersion": 12,
  "phase": "playing",
  "players": [
    { "id": "p1", "name": "Host", "platform": "android", "connected": true, "canHost": true },
    { "id": "p2", "name": "Anna", "platform": "ios", "connected": true, "canHost": true },
    { "id": "p3", "name": "Bence", "platform": "android", "connected": true, "canHost": true },
    { "id": "p4", "name": "Dora", "platform": "ios", "connected": true, "canHost": true }
  ],
  "hostId": "p1",
  "hostEpoch": 1
}
```

`phase` is one of `lobby`, `playing`, `finished`.

### `stateVersion` rules

- Increases by 1 on **every state change**: player joined/left/disconnected/reconnected, game started, every accepted game action, game ended.
- **Every state-changing message carries `stateVersion`**: `PLAYER_LIST`, `GAME_STATE`, `STATE_SYNC`.
- The per-player variants of one change (the `GAME_STATE` each player gets) carry the same `stateVersion`.
- Messages that don't change state (`PONG`, `ACK`, `ERROR`) carry no `stateVersion`. `PING` carries the host's current `stateVersion` for comparison only.
- A message whose `stateVersion` is **≤ the client's own** is already reflected in its state: ACK it if required, but don't apply it again.
- If a client sees a gap (it has 10 and receives a `PLAYER_LIST` or `GAME_STATE` with 12, or a `PING` with anything above 10), it does not apply the message; it sends `SYNC_REQUEST` and the host answers with `STATE_SYNC`. Until the first `STATE_SYNC` of a connection has been applied, every `PLAYER_LIST`, `GAME_STATE` and host `PING` counts as a gap: that `STATE_SYNC` was lost.

### Message types

| Type           | Direction       | Purpose | Needs ACK |
|----------------|-----------------|---------|-----------|
| `JOIN_REQUEST` | client → host   | Ask to join with a name; `canHost` says whether the device could take over as host | — |
| `JOIN_ACCEPT`  | host → client   | Assigned player ID + current `stateVersion`. **Always followed by `STATE_SYNC`** | yes |
| `JOIN_REJECT`  | host → client   | `lobby_full` / `game_started` / `version_mismatch` / `removed` | — |
| `PLAYER_LIST`  | host → all      | Player joined, left, disconnected, reconnected, or removed | — (gap detection) |
| `GAME_STATE`   | host → each     | The game changed: the phase and the recipient's own view of the game | yes |
| `GAME_ACTION`  | client → host   | A player's move, in the game module's own format | — |
| `STATE_SYNC`   | host → client   | Full engine state **+ the recipient's view of the game** (after every `JOIN_ACCEPT`, and on gap) | yes |
| `SYNC_REQUEST` | client → host   | Client detected a `stateVersion` gap | — |
| `ERROR`        | host → client   | A `GAME_ACTION` was rejected | — |
| `GAME_OVER`    | host → all      | The session ends because the host left with no successor | yes |
| `HOST_SNAPSHOT`| host → successors | Full authoritative state for a possible takeover (see *Host Migration*) | yes |
| `HOST_LEAVING` | host → all      | Host leaves on purpose and names the next host | — |
| `ACK`          | client → host   | Confirms receipt of a host `seq` | — |
| `LEAVE`        | client → host   | Player leaves intentionally | — |
| `PING` / `PONG`| both            | Heartbeat + latency measurement | — |

**ACK direction:** only clients send `ACK`. Client → host messages use write-with-response, so delivery is already confirmed at the BLE level and the host never sends `ACK`.

### Example flow

The examples below follow one game in order (4 players, playing `counter`). Host `seq` values are from the host's shared counter.

`counter` is a minimal game used here so that the game-specific objects have something in them: the players take turns in seat order, each adds 1, 2 or 3 to a shared total, and whoever brings it to 21 wins. Its `action` is `{"add":n}`, its `view` is `{"total","turn","yourTurn"}` and its full state is `{"total","turn","order"}`. None of that is part of the protocol. The engine carries `action`, `view` and `gameState` unread; only the game module on each end gives them a meaning.

**1. Client asks to join** (after subscribing to notifications)

```json
{ "v": 1, "type": "JOIN_REQUEST", "seq": 1, "from": "?", "ts": 1759415400000,
  "data": { "name": "Anna", "platform": "ios", "deviceId": "a1b2c3", "canHost": true } }
```

`deviceId` is a random ID stored on the device so a reconnecting player gets the same seat back.

**2. Host accepts**

```json
{ "v": 1, "type": "JOIN_ACCEPT", "seq": 1, "from": "p1", "ts": 1759415400050,
  "data": { "playerId": "p2", "stateVersion": 2 } }
```

**3. Host sends the joining client the full state** — after **every** `JOIN_ACCEPT` (first join and reconnect alike), so a new client never has to build its state from later messages. In the lobby there is no game, so `game` and `view` are omitted.

```json
{ "v": 1, "type": "STATE_SYNC", "seq": 2, "from": "p1", "ts": 1759415400055,
  "data": {
    "state": { "gameId": "7K2F", "stateVersion": 2, "phase": "lobby", "players": [ "..." ], "hostId": "p1", "hostEpoch": 1 }
  } }
```

**4. Host broadcasts updated player list** — the new client already has `stateVersion` 2 from `STATE_SYNC`, so for it this is a no-op; the others apply it.

```json
{ "v": 1, "type": "PLAYER_LIST", "seq": 3, "from": "p1", "ts": 1759415400060,
  "data": {
    "event": "joined", "playerId": "p2", "stateVersion": 2,
    "players": [
      { "id": "p1", "name": "Host", "platform": "android", "connected": true },
      { "id": "p2", "name": "Anna", "platform": "ios", "connected": true }
    ]
  } }
```

*(p3 and p4 join the same way → host `seq` 4–9, `stateVersion` 3 and 4.)*

**Join rejected** (e.g. a new device after the game started)

```json
{ "v": 1, "type": "JOIN_REJECT", "seq": 30, "from": "p1", "ts": 1759415500000,
  "data": { "code": "game_started" } }
```

**5. Game starts** — the host starts the game from its screen. Each player gets its own `GAME_STATE`, since a view holds only what that player is allowed to see (one `seq` per recipient, the same `stateVersion`). `game` names the game module; `view` is that module's JSON (here `counter`). Example for p2:

```json
{ "v": 1, "type": "GAME_STATE", "seq": 10, "from": "p1", "ts": 1759415460000,
  "data": {
    "stateVersion": 5,
    "phase": "playing",
    "game": "counter",
    "view": { "total": 0, "turn": "p1", "yourTurn": false }
  } }
```

*(p1 is the host: it adds 2 through the same game module locally → every client gets a `GAME_STATE` with v6, and it is p2's turn.)*

**6. Client makes a move (intent)**

```json
{ "v": 1, "type": "GAME_ACTION", "seq": 6, "from": "p2", "ts": 1759415470000,
  "data": { "game": "counter", "action": { "add": 1 } } }
```

`action` is the game module's own format; the engine passes it on unread.

**7. Host confirms (result)** — there is no separate message for the move itself. The host applies the action and sends every player its new view. Example for p2:

```json
{ "v": 1, "type": "GAME_STATE", "seq": 16, "from": "p1", "ts": 1759415470040,
  "data": {
    "stateVersion": 7,
    "phase": "playing",
    "game": "counter",
    "view": { "total": 3, "turn": "p3", "yourTurn": false }
  } }
```

This is **targeted** messaging: one change, a different message for every player. (In this example p3 gets the same view with `"yourTurn": true`.)

**8. Acknowledgement** (p2 acks the `GAME_STATE` from example 7)

```json
{ "v": 1, "type": "ACK", "seq": 7, "from": "p2", "ts": 1759415470100,
  "data": { "ackSeq": 16 } }
```

**9. Rejected move**

```json
{ "v": 1, "type": "ERROR", "seq": 23, "from": "p1", "ts": 1759415495000,
  "data": { "refSeq": 8, "code": "not_your_turn", "reason": "it is p3's turn" } }
```

`refSeq` is the client `seq` that was rejected. `reason` is optional text for the log. Nothing changes and the turn stays where it was. The engine's own codes are `no_game` (no game is running) and `wrong_game` (`data.game` is not the running game); all other codes come from the game module (for example `not_your_turn`, `invalid_move`).

**10. Gap detected → resync**

```json
{ "v": 1, "type": "SYNC_REQUEST", "seq": 9, "from": "p2", "ts": 1759415496000,
  "data": { "haveVersion": 10 } }
```

```json
{ "v": 1, "type": "STATE_SYNC", "seq": 24, "from": "p1", "ts": 1759415496040,
  "data": {
    "state": { "gameId": "7K2F", "stateVersion": 12, "phase": "playing", "...": "..." },
    "game": "counter",
    "view": { "total": 9, "turn": "p3", "yourTurn": false }
  } }
```

`STATE_SYNC` is always **targeted** — `view` is the recipient's own view.

**11. Game ends** — the last accepted action finishes the game. It is an ordinary `GAME_STATE` with `phase: "finished"`; the result is in the view.

```json
{ "v": 1, "type": "GAME_STATE", "seq": 90, "from": "p1", "ts": 1759415590000,
  "data": { "stateVersion": 39, "phase": "finished", "game": "counter",
            "view": { "total": 21, "winner": "p4", "yourTurn": false } } }
```

From `finished` the host can start another game (a new `GAME_STATE` with `phase: "playing"`) or go back to the lobby. A `GAME_STATE` without a game has no `game` and no `view`:

```json
{ "v": 1, "type": "GAME_STATE", "seq": 95, "from": "p1", "ts": 1759415595000,
  "data": { "stateVersion": 40, "phase": "lobby" } }
```

The host can also end a running game this way.

**12. Player disconnects / reconnects**

```json
{ "v": 1, "type": "PLAYER_LIST", "seq": 25, "from": "p1", "ts": 1759415500000,
  "data": { "event": "disconnected", "playerId": "p3", "stateVersion": 14, "players": [ "..." ] } }
```

On reconnect, the client sends `JOIN_REQUEST` (with `seq` 1 again) and the same `deviceId`; the host restores seat `p3`, replies with `JOIN_ACCEPT` + `STATE_SYNC`, and broadcasts `PLAYER_LIST` with `"event": "reconnected"`.

**13. Host left, nobody can take over**

```json
{ "v": 1, "type": "GAME_OVER", "seq": 40, "from": "p1", "ts": 1759415600000,
  "data": { "reason": "host_left" } }
```

`GAME_OVER` ends the **session**, and `host_left` is its only reason. A game that is played to the end finishes with `GAME_STATE` (example 11).

**14. Heartbeat**

```json
{ "v": 1, "type": "PING", "seq": 50, "from": "p1", "ts": 1759415610000,
  "data": { "pingTs": 1759415610000, "stateVersion": 12 } }
```

```json
{ "v": 1, "type": "PONG", "seq": 12, "from": "p2", "ts": 1759415610045,
  "data": { "pingTs": 1759415610000 } }
```

The host's `PING` carries its current `stateVersion`; a client that is behind asks for a `STATE_SYNC`. A `PING` sent by a client has no `stateVersion`.

---

## Players and Seats

- **Max 10 players including the host.** Names are max **12 characters** (the host truncates longer names). How many links a host phone really holds depends on its Bluetooth chip; more than 4–5 clients is untested.
- Player IDs: the first host is `p1`; clients get the **lowest free seat** (`p2`…`p10`). Player IDs never change, also not when the host changes.
- A `JOIN_REQUEST` with a known `deviceId` always gets its old seat back.
- `LEAVE` in the lobby frees the seat. `LEAVE` during the game keeps the seat for now: the player is marked `connected: false`, like a player who went silent.
- **Offline players are removed.** A seat that stays `connected: false` for **30 s** is removed, in the lobby and during a game alike; the host can also remove an offline player at once from its screen. After a takeover the new host gives every seat the full reconnect window (45 s) before this applies. The host is never removed.
- Removing a seat: `stateVersion` +1 and `PLAYER_LIST` with `"event": "removed"`; if that player still has a link, the host sends it `JOIN_REJECT` `removed` first. During a game the game module is told (see *Game modules*); that is a second change, `stateVersion` +1 again, with a `GAME_STATE` to everyone.
- A removed player who comes back is a new player: it gets a free seat in the lobby, or `game_started` during a game.
- Joining during the game is only possible as a **reconnect** with a known `deviceId`. Anyone else gets `JOIN_REJECT` with `game_started`.
- `JOIN_REJECT` codes: `lobby_full`, `game_started`, `version_mismatch`, and `removed` (sent to a player the host removed, on a link it still has).

---

## Game modules

The engine (everything above) runs a session: seats, messages, state sync, host migration. It does not know any game. A **game module** supplies the game, and the rest of the app reaches it through one registry, so adding or swapping a game touches no engine code.

A module has two parts:

- **Rules** — pure logic, no I/O, used by the host only. It can:
  - say which player counts it supports,
  - **start** a game for a list of player IDs (the engine passes the only source of randomness),
  - **apply** a player's `action` → a new state, or a rejection `code` (the engine passes the randomness here too: a game may need it again mid-session),
  - produce the **view** of the state for one player (what that player is allowed to see),
  - export the full **state** as JSON and **restore** it, so a successor can continue the game after a host takeover,
  - **remove a player** the engine dropped from the session → a new state, or *cannot continue*.
- **Screen** — draws a view and turns taps into actions. It never works out the rules; everything it needs is in the view.

What crosses between the engine and a module is plain JSON objects — `action`, `view`, `gameState` — which the engine passes on without reading them:

| JSON | Travels in | Seen by |
|------|-----------|---------|
| `action` | `GAME_ACTION` | host |
| `view` | `GAME_STATE`, `STATE_SYNC` | one player |
| `gameState` | `HOST_SNAPSHOT` | successors (never shown) |

Engine rules around a game:

- Only the host starts a game, from `lobby` or `finished`, and picks which one: the host screen offers every game in the registry. The players are everyone who is `connected` at that moment, in ascending seat order; the module must support that many. The phase becomes `playing`.
- Every accepted action is one state change: `stateVersion` +1, then a `GAME_STATE` to **every** linked client with its own view (and a `HOST_SNAPSHOT` to the successors). When the module reports the game finished, the phase is `finished`. A game played as a series of rounds never reports that; it runs until the host ends it.
- A rejected action changes nothing; the sender gets `ERROR`.
- The host is a player too: its own actions go through the same module locally, without BLE.
- A client that is not one of the game's players (it was disconnected at the start) still gets a `GAME_STATE`; the module gives it a spectator view.
- The host can end the game at any time: phase `lobby`, a `GAME_STATE` without `game` and `view`.
- When a player of a running game is removed, the module either continues without them (the phase becomes `finished` if that ends the game) or says it cannot continue; then the game ends as if the host had ended it. A removed spectator changes nothing in the game.
- A client that receives a `game` it has no module for shows "unknown game" and keeps the session.

### Real-time games

A real-time game is a module like the others, with a world that moves by itself. Its rules can also:

- give the **tick** length, the **frame rate** (a world frame every *n*th tick), its world and input frame **kinds**, and the largest world frame it builds,
- take a player's newest **input** (a frame payload),
- advance the world **one tick** → the new world, and whether the **coarse state** changed,
- build the **world frame** (the same for every player).

The coarse state is what `state()` / `view()` return: for example scores, round, winner. The moving world is not in it. Everything above holds for it unchanged: `GAME_STATE`, `STATE_SYNC`, `HOST_SNAPSHOT`, restore, remove.

Engine rules for a real-time game:

- **Starting** additionally needs every player's link to carry the largest world frame; otherwise the host refuses with the reason (for example "… needs a better connection to p2").
- **Ticking:** while the phase is `playing` and the game is running (below), the host advances the world on a fixed schedule. It runs late ticks one after another, but drops ticks after a stall of more than 1 s instead of catching up. Every *n*th tick it sends the world frame to every linked client of the game, and also after every tick that changes the coarse state, so the tick that decides the match always reaches the clients.
- **Inputs:** a client's input frame of the game's input kind is taken only from a player of the game; the newest one counts at the next tick. The host's own input is applied `HOST_INPUT_DELAY` ticks late (1, tunable), so the host has no edge over a client.
- **Coarse changes:** a tick that changes the coarse state is one state change: `stateVersion` +1, a `GAME_STATE` to every linked client, a `HOST_SNAPSHOT` to the successors. A tick that only moves the world changes nothing but the frames.
- **Paused:** while a player of the game is `connected: false`, the host does not tick and sends no world frames. It resumes **3 s** after the last of them is connected again. There is no message for this. A screen shows "paused" while a player of the game is offline in `PLAYER_LIST`, and "get ready" while everyone is back but frames have not started.
- **After a takeover** the module restores the coarse state and starts a new round. Every other seat is `connected: false` at that moment, so the game stays paused until they are back.
- **Clients** draw the world from the frames, about 100 ms behind the newest one, interpolating between two frames. They send an input frame when a control changes, and at least every 250 ms.

World and input frames travel as described in *Real-time frames*.

---

## Reliability Rules

- **Host is authoritative.** Clients never change state locally until the host confirms.
- **Client → host writes use "write with response"**, so delivery is confirmed at the BLE level. No application-level ACK in this direction.
- **Host → client notifications are not acknowledged by BLE**, so clients send `ACK` for: `JOIN_ACCEPT`, `GAME_STATE`, `STATE_SYNC`, `HOST_SNAPSHOT`, `GAME_OVER`. The ACKs show up in the debug log, which is how lost messages are counted.
- **A lost host message heals itself; the host does not resend.** Every `GAME_STATE`, `PLAYER_LIST` and `STATE_SYNC` describes the whole of what it covers rather than a step, and every `PING` (every 3 s) carries the host's `stateVersion`. A client that missed something notices at the next message or `PING` at the latest, sends `SYNC_REQUEST`, and gets a `STATE_SYNC`.
- **A silent client that speaks again:** when traffic arrives from a client the host had marked `connected: false` (e.g. a `PONG`), the host marks it connected and sends it a `STATE_SYNC`.
  Reason: an **iOS host cannot disconnect a central** (`CBPeripheralManager` has no API for it), so the client may never actually reconnect.
- **Duplicates** are ignored using `(from, seq)`. A player's dedup state is cleared on `JOIN_REQUEST`.
- **State gaps** (a `stateVersion` that skips ahead, or a `PING` ahead of the client) → client sends `SYNC_REQUEST` → host replies with `STATE_SYNC`.
- **Heartbeat:** the host sends `PING` to every client every 3 s; the client answers with `PONG` echoing `data.pingTs`. A client may also ping the host for its own RTT measurement; the host answers with `PONG`.
  - Host: a client is marked `connected: false` after **10 s without traffic**.
  - Client: the host is considered lost after **10 s of silence**.

### Reconnection

- **Reconnection:** the client retries scanning (by **game code**, not by Bluetooth address) and connecting for **45 s**, then shows **"Host left, game over"**. During that window another player may take over as host (see *Host Migration*).
- **Host leaving intentionally** hands over with `HOST_LEAVING` if a successor exists; otherwise it sends `GAME_OVER` with `reason: "host_left"` so clients don't wait 45 s.

---

## Host Migration

If the host disappears, one of the remaining players becomes the host of the same game and the others rejoin it. The topology stays a star.

### Extra fields

- `JOIN_REQUEST.data.canHost` and `Player.canHost` (boolean; missing = `false`): the device can advertise and run a GATT server.
- Game state and `GameInfo` carry `hostId` (the current host's player ID) and `hostEpoch` (starts at 1, +1 on every takeover). Missing = `"p1"` / `1`.

### Successors and snapshots

- **Successor order:** players with `connected` and `canHost`, in ascending seat order, excluding `hostId`.
- After every `stateVersion` change, and whenever the first two successors change, the host sends `HOST_SNAPSHOT` to the **first two** successors:

```json
{ "v": 1, "type": "HOST_SNAPSHOT", "seq": 31, "from": "p1", "ts": 1759415500000,
  "data": {
    "state": { "gameId": "7K2F", "stateVersion": 12, "phase": "playing", "hostId": "p1", "hostEpoch": 1, "...": "..." },
    "seats": { "p1": { "deviceId": "f0e1d2" }, "p2": { "deviceId": "a1b2c3" } },
    "successors": ["p2", "p3"],
    "game": "counter",
    "gameState": { "total": 9, "turn": "p3", "order": ["p1", "p2", "p3", "p4"] }
  } }
```

- `successors` is the ordered list of players this snapshot round was sent to. A client's **takeover rank** is its position in the `successors` of the snapshot it holds (fallback when absent: its position among `canHost` players in seat order, excluding the host, ignoring `connected`).
- `seats` lists every seat, including the host's own.
- `game` and `gameState` are present while a game exists (phase `playing` or `finished`) and omitted in the lobby. `gameState` is the game module's full authoritative state — everything hidden from the players included.
- A successor keeps only the newest snapshot, in memory, and never shows it.

### Takeover

Times run from the moment a client detects the loss (disconnect, or 10 s of silence).

| Time | What happens |
|------|--------------|
| 0–5 s | Everyone tries to reconnect to the current host. |
| 5 s | The snapshot holder with rank 1, if it has not rejoined, starts hosting the **same game code**. |
| 13 s, 21 s, … | The holder with rank 2, 3, … does the same if it has still not rejoined a host (5 s + 8 s per rank). |
| up to 45 s | All other clients keep scanning for the game code and rejoin with their `deviceId`. |

- The new host restores the state from its snapshot with `hostId` = itself, `hostEpoch + 1`, `stateVersion + 1`, its own seat connected and every other seat `connected: false`. Its `seq` counter starts at 1. The phase and the game are restored from `game` / `gameState`, so the game continues where the snapshot left it. If the new host has no module for `game`, it falls back to the lobby.
- Rejoining clients get `JOIN_ACCEPT` + `STATE_SYNC` and the usual `PLAYER_LIST` `reconnected`.
- A client takes over by its rank even if the last `PLAYER_LIST` marked it as disconnected; a stalling host often sends exactly that before it goes quiet.
- **Stale hosts:** a client that reads a `GameInfo.hostEpoch` lower than the highest one it has seen does not join; it disconnects and keeps scanning.

### The old host

- The host looks for its own game code advertised by another device, and reads that `GameInfo`, in three cases: it has seated clients but no live link for 10 s; its Bluetooth just came back; or a player who held a snapshot has been disconnected for 10 s. It repeats every 10 s while the case holds, and keeps serving its other clients meanwhile.
- If the other host has a higher `hostEpoch` (or the same epoch and a lower player ID), it stops hosting and rejoins as a client with its `deviceId`. It does not get the host role back. Clients still linked to it lose it, reconnect by game code and find the winning host.
- A new player joining a migrated game gets the lowest free seat of `p2`…`p10`, never `p1`.

### Leaving on purpose

```json
{ "v": 1, "type": "HOST_LEAVING", "seq": 44, "from": "p1", "ts": 1759415700000,
  "data": { "nextHostId": "p2" } }
```

- The host sends a final `HOST_SNAPSHOT` to the first successor, then `HOST_LEAVING` to everyone. The named player takes over at once; the others start reconnecting at once.
- With no successor, the host sends `GAME_OVER` `host_left` and the game ends.

### Background and stalls

- A host whose app goes to the background (for example the phone is locked) hands over at once, as if leaving on purpose, when a successor is linked. When it returns to the foreground it rejoins as a client.
- A host that notices its own heartbeat was stalled (more than two ping intervals since the last tick) does not judge clients on that tick.
- Both platforms keep the screen on during a session, and measure all timeouts by elapsed real time.

### Limits

- An unplanned takeover pauses the game for roughly 10–20 s.
- A phone that cannot advertise never becomes host.
- If the host dies before the latest snapshot arrives, the game continues from the previous one.

---

## Known BLE Constraints

- **iOS background**: advertising and scanning are heavily restricted when the app is backgrounded. The protocol assumes the app stays in the **foreground** (see *Background and stalls* for what a host does when it does not).
- **iOS addresses**: iOS does not expose MAC addresses and rotates them. Identify players by `deviceId`, never by Bluetooth address.
- **iOS host cannot disconnect a central**: handled by the "silent client that speaks again" rule in *Reliability Rules*.
- **iOS long reads**: an iOS host must handle `request.offset` for `GameInfo`.
- **Android permissions**: Android 12+ needs `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`; older versions need location permission for scanning.
- **Android peripheral support**: some Android devices cannot advertise. Check `isMultipleAdvertisementSupported()` before offering to host a game.
- **Android advertising name**: the advertised device name is the system Bluetooth name — use manufacturer data for the game code instead.
- **Android advertising after connect**: may stop on some devices; restart it.
- **Connection priority**: only an Android **client** can call `requestConnectionPriority`; the GATT server has no such API.
- **Notification backpressure**: respect `peripheralManagerIsReady` (iOS) and `onNotificationSent` (Android), or chunks get dropped.
- **Connection limits**: varies by device and chipset. A host holding more than 4–5 clients is untested.
