# Wi-Fi Aware: feature plan

Status: phases 1 and 2 implemented on branch `feature/wifi-aware`, not yet tested on real phones. See "Implementation status" at the end.

## Goal

Without changes to the app, the SDK works exactly as it does today. An app that opts in lets
two phones that both support Wi-Fi Aware (NAN) **move their link onto Wi-Fi Aware**, and the SDK
falls back to BLE silently if that link fails. Game modules, `GameSession`, the message protocol
and host migration stay the same.

What Wi-Fi Aware adds:

- **Throughput.** Tens of Mbit/s, where BLE GATT gives tens of kbit/s. A `STATE_SYNC` or snapshot
  that takes 2–3 chunks today takes one packet.
- **Latency and frame rate** for real-time games: no write-with-response round trip per frame.
- **Range**, which is usually better than BLE indoors.
- **Link-layer encryption** (passphrase data path). BLE stays unencrypted.

## Key decision: an upgrade path, not a second stack

BLE stays the **control plane**: it handles discovery, the game code, `GameInfo`, the first
connection and the fallback link. Wi-Fi Aware is an optional **data plane** that is added to each
link separately, the same way AirDrop and Nearby Share work.

Rejected alternative: a standalone `WifiAwareHostTransport` with its own publish/subscribe
discovery, chosen instead of BLE. It is simpler to describe, but it splits players into
"BLE games" and "Aware games". A mixed lobby, for example one phone without Aware or one iPhone,
could no longer play together. It also loses the fallback.

Why the upgrade path fits this codebase: `GameHostTransport` / `GameClientTransport` already
hide everything below "whole messages and frames per `LinkId`". A new **`HybridHostTransport` /
`HybridClientTransport`** implements the same interfaces. It wraps the existing BLE transport and
a new Wi-Fi Aware data path, and swaps the pipe under a `LinkId` that does not change. The
runtime, sessions and engine see no difference.

```text
HostRuntime / ClientRuntime          (unchanged)
        │  GameHostTransport / GameClientTransport
HybridHost/ClientTransport           (new: picks the active path per link, handles switchover)
   ├── BleHost/ClientTransport       (existing, always up)
   └── AwareDataPath                 (new: NAN publish/subscribe + NDP + sockets)
```

## Platform reality (the main risk)

| | Android | iOS |
|---|---|---|
| API | `android.net.wifi.aware`, API 26+, device must report `FEATURE_WIFI_AWARE` | `WiFiAware` framework, **iOS 26+**, newer iPhones only |
| Pairing | None; an open or passphrase data path is set up by the app | **Mandatory one-time pairing per device pair** through `DeviceDiscoveryUI` (system sheet + PIN) |
| Data path | `WifiAwareNetworkSpecifier` → `ConnectivityManager.requestNetwork` → IPv6 sockets (TCP/UDP) | Network framework (`NWListener` / `NWConnection`) on the Aware service |
| Limits | Few concurrent data paths on many chipsets (`Characteristics.getNumberOfSupportedDataPaths()`, API 31+). Off while hotspot is on, and on some devices with other Wi-Fi concurrency | Entitlement + `WiFiAwareServices` in Info.plist; foreground only |
| Cross-platform | Interop with iOS 26 is reported as **unreliable / chipset-dependent** in Apple developer forums (pairing and NDP setup fail on some Android devices) | same |

What this means:

1. **Android ↔ Android** is the solid case and the first deliverable.
2. **iPhone ↔ iPhone** works, but the pairing sheet breaks the README's promise of "no pairing
   dialog". It has to be opt-in and clearly documented, ideally offered only to players who play
   together often.
3. **Android ↔ iPhone over Aware** is research. It gets an experimental flag at most, and BLE stays
   the path between them by default.

## Design details

### 1. Configuration (default = today's behaviour)

```kotlin
HyperBleKitConfig(
    games = …,
    dataPaths = DataPaths.BLE_ONLY,          // default; current behaviour
    // DataPaths.PREFER_WIFI_AWARE           // upgrade when both sides support it
)
```

Swift gets the same value: `HyperBleKitConfig(dataPaths: .bleOnly / .preferWiFiAware)`. Read-only
capability: `HyperBleKit.isWifiAwareAvailable` (hardware support, OS version, and Wi-Fi switched
on).

### 2. Capability advertisement

`GameInfo` gets an optional field `aware`: `{ "v": 1, "svc": "<service name>" }`, added only when
the host can publish. Older clients ignore unknown fields, so this is backward compatible. Clients
add `aware: true` to their existing hello/join message (or the transport-control packet below)
only when they can subscribe.

### 3. Transport-control packets (below the message protocol)

The switchover is negotiated inside the transport, not with new protocol message types, so the
engine stays untouched. It uses a marker that older builds already drop. The spec says
"`chunkCount` 0 with another `chunkIndex` is malformed":

```text
[ctrlType: 1 byte][0xFF][0x00][payload...]
```

| ctrlType | Direction | Payload |
|---|---|---|
| `0x01 AWARE_OFFER` | host → client | service-specific info, data-path passphrase, port |
| `0x02 AWARE_ACCEPT` / `0x03 AWARE_DECLINE` | client → host | reason code |
| `0x04 PATH_SWITCH` | either | "everything after this goes on path X" |

Test vectors for these go in `protocol/test-vectors/` and are checked on both platforms, like
chunking and envelopes today.

Note: the passphrase travels over unencrypted BLE. That protects against passive Wi-Fi sniffing,
but not against an attacker who also captures the BLE traffic. The guide must say this plainly.

### 4. Framing on the Wi-Fi Aware path

- **Reliable messages over TCP**: a 4-byte length prefix + the message bytes. No 3-byte chunk
  header and no chunking. The 255-chunk size limit no longer applies, but keep a cap such as 64 KB
  so a message that works on Aware still works after a fallback to BLE.
- **Real-time frames over UDP**: `[frameSeq][0x00][0x00][payload]`, the same header and the same
  stale-frame rule as today. This avoids TCP head-of-line blocking.
- **Frame size limit stays at the BLE value of the link.** A game that works on BLE then never
  breaks on fallback, and it gets a higher rate and lower latency for free. A larger frame budget
  can come later as an explicit opt-in.

### 5. Switchover and fallback

- **Upgrade**: the link comes up on BLE as today (`LinkUp`). If both sides support Aware, the host
  sends `AWARE_OFFER`, the client subscribes, and the NDP and sockets come up. Each side then
  finishes the BLE message in flight, sends `PATH_SWITCH`, and from then on sends on Aware.
  Receivers accept from both paths during the switch. `msgId` counters are per path.
- **BLE stays connected** as the fallback and liveness channel; it is idle and cheap.
- **Fallback**: on a socket error, a lost NDP, or no traffic on Aware for N seconds while BLE is
  alive, the link switches back to BLE without a `LinkDown`. Messages lost in flight are recovered
  by the existing retry / `STATE_SYNC` rules. The transport still never retries.
- **`LinkDown`** only when BLE and Aware are both gone, so host-migration timing does not change.
- **Data-path limit on the host**: when the chipset has no free NDPs, the remaining clients stay on
  BLE. Upgrades are first come, first served; prefer the snapshot successors so a takeover is fast.
- **Host migration**: the new host's hybrid transport publishes the Aware service again. Clients
  reconnect on BLE first, as today, and upgrade after that.

### 6. Diagnostics

`SessionDiagnostics` / the player list show the active path per link (`BLE` / `WIFI_AWARE`), the
count of upgrades and fallbacks, and the throughput per path. The speed test runs on whichever
path is active. Log lines: `PATH up aware`, `PATH fallback ble (reason)`.

### 7. Permissions and setup

- **Android**: `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `NEARBY_WIFI_DEVICES` (API 33+, with
  `neverForLocation`), `ACCESS_FINE_LOCATION` on API 26–32, and
  `<uses-feature android:name="android.hardware.wifi.aware" android:required="false"/>`.
  `requiredPermissions` adds these only when `dataPaths` asks for Aware.
- **iOS**: the Wi-Fi Aware entitlement, `WiFiAwareServices` in Info.plist, and a
  `DeviceDiscoveryUI` pairing flow that the SDK exposes as an API the app calls from its own UI.
  Gate every use with `#if canImport(WiFiAware)` + `if #available(iOS 26, *)` so the package keeps
  iOS 15 as its deployment target. Building needs the Xcode 26 SDK.

## Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| 0. Spike | Android-only sample: two phones, publish/subscribe, NDP, TCP+UDP echo. Measure throughput, latency, setup time, and the NDP limit on 3–4 chipsets (Pixel, Samsung Exynos/Snapdragon, one cheap device). | Numbers that justify the work; a list of devices that misbehave |
| 1. Protocol + fakes | Control packets, TCP/UDP framing, `Hybrid*Transport` against fake BLE + fake Aware paths in `FakeRadio` / `FakeAir`. Test vectors on both platforms. | Switchover, fallback during a message, NDP limit and host migration all covered by unit tests |
| 2. Android Aware | Real `AwareDataPath` for Android, config flag, permissions, diagnostics. | Android ↔ Android game upgrades, survives Wi-Fi off/on, falls back cleanly; BLE-only devices in the same lobby are unaffected |
| 3. iOS Aware | `WiFiAware` + `DeviceDiscoveryUI` pairing, iPhone ↔ iPhone upgrade. | Same as phase 2 for iOS; pairing flow documented |
| 4. Cross-platform (experimental) | Android ↔ iPhone Aware behind a separate flag. | Works on a named list of devices, or is deferred |
| 5. Docs | `wire-protocol.md` (control packets, Aware framing), `sdk-guide.md` (config, permissions, pairing, limits, troubleshooting), README. | — |

Phases 1–2 are worth shipping alone. Phases 3–4 depend on the spike and on how iOS 26
interoperability develops.

## Files expected to change

- New: `transport/hybrid/` and `transport/aware/` on both platforms; fake Aware paths in the
  tests.
- Changed: `HyperBleKitConfig` (both), `HyperBleKit.kt` `bleWiring` → wiring that picks the
  transports, iOS `TransportFactory.swift`, `GameInfo` models, `SessionDiagnostics`, permissions
  (`BlePermissions` → a combined permission list), `AndroidManifest.xml` (no hard requirement on
  Aware hardware).
- Unchanged: `game/`, `session/`, `runtime/` (apart from wiring), the message protocol.

## Decisions

1. iOS pairing through `DeviceDiscoveryUI` is acceptable: iOS gets Wi-Fi Aware too (phase 3).
2. The real-time frame budget stays at the BLE value of the link, also on Wi-Fi Aware.
3. Implementation starts with phases 1 and 2; testing on real phones follows once everything is
   implemented.

## Open questions

1. Should the default for new apps become `PREFER_WIFI_AWARE` once it is stable, or stay
   `BLE_ONLY`?

## Implementation status

Phases 1 and 2 are done (2026-11-05). Not yet tested on real phones.

- **Phase 1, both platforms:** transport-control messages, the TCP record stream, the GameInfo
  flag (`protocol/test-vectors/wifi-aware.json`, checked on Android and iOS); `HybridHostTransport`
  / `HybridClientTransport` with the order-keeping gate; fake Wi-Fi Aware paths and end-to-end
  games over them on both platforms; `dataPaths` and `wifiAwareServiceName` in the config.
- **Phase 2, Android:** `WifiAwareHostPath` / `WifiAwareClientPath` (attach, publish/subscribe,
  NAN discovery message, `WifiAwareNetworkSpecifier` data path, TCP over it), `AwareConnection`
  (tested over loopback), `HyperBleKit.isWifiAwareAvailable`, `requiredPermissions(config)`.
- **iOS** builds the hybrid transports with an "unsupported" Wi-Fi Aware path until phase 3, so an
  iPhone behaves as with `.bleOnly`.
- The wire specification has a new section, "Wi-Fi Aware data path"; the guide has "Wi-Fi Aware".

Where the implementation differs from the design above, and why:

1. **Control messages are ordinary chunked messages** with first byte `0x00`, not packets with a
   `[ctrl][0xFF][0x00]` header. An `OFFER` is longer than one packet at MTU 23, and messages are
   already reliable and ordered. Old builds never receive one, because they never send `HELLO`
   or set the GameInfo flag.
2. **Frames share the TCP connection** with the messages instead of using UDP. A frame slot
   on the sender keeps only the newest frame, so head-of-line blocking stays short on a local link.
   A UDP lane can be added later without changing the control messages.
3. **A link lives as long as its BLE connection.** The `LinkDown` / `Disconnected` timing,
   reconnecting and host migration stay exactly as before. Keeping a link alive on Wi-Fi Aware
   alone would need a second liveness and reconnect logic.
4. **Data paths go first come, first served.** Snapshot successors get no priority: the transport
   does not know who they are.
5. **No per-link path in `SessionDiagnostics` yet.** The log lines (`PATH …`) are the diagnostics
   for the first round of testing on real phones.

Next: phase 3 (iOS `WiFiAware` + `DeviceDiscoveryUI` pairing; needs the iOS 26 SDK and Xcode 26),
then real-device testing of phases 2 and 3.
