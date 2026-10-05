# Shared test vectors

Language-neutral vectors that **both** the Android and the iOS library validate against. They are
how a mismatch between the two implementations is caught without real hardware: if the framing or
the message encoding on one platform drifts, its vector test fails.

Any change to the protocol or the framing updates these vectors **and both platforms** in the same
change. The protocol is specified in [`docs/wire-protocol.md`](../../docs/wire-protocol.md).

## Files

- `chunking.json` — chunk framing: `[msgId:1][chunkIndex:1][chunkCount:1][payload...]`.
  - `split`: feed `message` through a **fresh** `Chunker` (so `msgId` starts at 0) with the given
    `maxChunkLength` (max bytes per write/notify, header included); the output must equal `chunks`.
  - `roundTrip`: split `message`, feed the chunks back through a fresh `Reassembler`; it must return
    `message` on the final chunk and `null` before it.
  - All byte values are integers in `0-255`.

- `envelope.json` — the message envelope `{v,type,seq,from,ts,data}`.
  - `decode`: parse `json` and expect `v`/`type`/`seq`/`from`/`ts`, and a `data` object equal to the
    parsed `dataJson`. JSON key order is **not** significant on the wire, so compare `data`
    structurally, not byte for byte.
  - `roundTrip`: build an envelope from the fields (with `data` = parsed `dataJson`), encode, decode
    again, and expect the same fields back.
  - `invalidDecode`: `json` strings that must be rejected with a decode error (malformed JSON, a
    non-object, or a **known** type missing a required field).
  - An **unknown `type`** is *not* an error: both platforms decode it to a tolerated "unknown"
    message that keeps the `type` and carries an empty `data` object, then log and ignore it
    (`unknown_type_is_tolerated_not_rejected`). This keeps a newer peer's message types from
    breaking an older build.
  - `ts` is an Int64 in milliseconds.

The `view`, `action` and `gameState` objects in the vectors belong to a made-up counter game. The
protocol carries them without reading them.

## Where they are used

- Android: `sdk/android/hyperblekit/src/test/java/io/hypercode/hyperblekit/protocol/`
  (`ChunkingVectorsTest`, `EnvelopeVectorsTest`).
- iOS: `sdk/ios/Tests/HyperBleKitTests/` (`ChunkingVectorsTests`, `EnvelopeVectorsTests`).

Both find this folder by walking up from where the test runs, so it must stay at
`protocol/test-vectors` in the repository root.
