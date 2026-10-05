import Foundation

/// Real-time frames: one unchunked packet `[frameSeq][0x00][0x00][payload…]` next to the chunked
/// reliable messages. A chunk header with `chunkCount = 0` marks it, so an older build drops it
/// as malformed instead of breaking. See the wire specification, "Real-time frames".
enum RealtimeFrame {
    /// The packet for one frame. `frameSeq` is set by the transport, never by the caller.
    static func packet(seq: UInt8, payload: Data) -> Data {
        var packet = Data([seq, 0, 0])
        packet.append(payload)
        return packet
    }

    /// The largest payload that fits one packet of `maxChunkLength` bytes, header included.
    static func maxPayloadLength(maxChunkLength: Int) -> Int {
        maxChunkLength - BLEConstants.chunkHeaderSize
    }
}

/// Takes or drops incoming frames by their `frameSeq`, so a late frame can never move the world
/// backwards. Use one per link and direction, new when the link comes up.
struct FrameSeqFilter {
    /// The last taken `frameSeq`, nil until the first frame after the link came up.
    private(set) var last: UInt8?

    /// The first frame is taken. After that, frame `seq` is taken only if it is 1…127 ahead of
    /// the last taken one, wrapping at 256; anything else is stale or a duplicate.
    mutating func take(_ seq: UInt8) -> Bool {
        if let last {
            let ahead = seq &- last
            guard ahead >= 1, ahead <= BLEConstants.frameSeqWindow else { return false }
        }
        last = seq
        return true
    }
}
