import Foundation

/// Rebuilds messages from incoming chunks. Use one instance per connection and direction.
///
/// Chunks arrive in order on a single GATT link, so anything unexpected drops the
/// message in progress instead of waiting for a fix that will never come.
struct Reassembler {
    /// What one incoming packet turned out to be.
    enum Packet: Equatable {
        /// The last chunk of a reliable message arrived: the whole message.
        case message(Data, chunks: Int)
        /// A real-time frame (`chunkCount = 0`), not yet checked for staleness.
        case frame(seq: UInt8, payload: Data)
    }

    static let defaultTimeout: TimeInterval = 5

    let timeout: TimeInterval

    /// Number of unfinished messages dropped so far (for the debug screen).
    private(set) var droppedMessageCount = 0

    private var pending: Pending?

    private struct Pending {
        let msgId: UInt8
        let chunkCount: UInt8
        var nextIndex: UInt8
        var payload: Data
        var lastChunkAt: Date
    }

    init(timeout: TimeInterval = Reassembler.defaultTimeout) {
        self.timeout = timeout
    }

    /// Feeds one packet: a real-time frame comes back at once and leaves a reliable message
    /// being reassembled untouched; a chunk goes to `receive(_:at:)`.
    mutating func receivePacket(_ packet: Data, at now: Date = Date()) throws -> Packet? {
        guard packet.count >= BLEConstants.chunkHeaderSize else { throw ChunkError.malformedHeader }
        let index = packet[packet.startIndex + 1]
        let count = packet[packet.startIndex + 2]
        if count == 0 {
            guard index == 0 else { throw ChunkError.malformedHeader }
            return .frame(seq: packet[packet.startIndex], payload: Data(packet.dropFirst(BLEConstants.chunkHeaderSize)))
        }
        return try receive(packet, at: now).map { .message($0, chunks: Int(count)) }
    }

    /// Feeds one chunk of a reliable message. Returns the full message once its last chunk
    /// arrives, otherwise nil. Throws `ChunkError.malformedHeader` for chunks that can't be
    /// parsed, frames included (`receivePacket` takes those); they are ignored.
    mutating func receive(_ chunk: Data, at now: Date = Date()) throws -> Data? {
        guard chunk.count >= BLEConstants.chunkHeaderSize else { throw ChunkError.malformedHeader }
        let msgId = chunk[chunk.startIndex]
        let index = chunk[chunk.startIndex + 1]
        let count = chunk[chunk.startIndex + 2]
        guard count > 0, index < count else { throw ChunkError.malformedHeader }
        let payload = chunk.dropFirst(BLEConstants.chunkHeaderSize)

        if let current = pending, now.timeIntervalSince(current.lastChunkAt) > timeout {
            dropPending()
        }

        if index == 0 {
            dropPending()
            if count == 1 { return Data(payload) }
            pending = Pending(msgId: msgId, chunkCount: count, nextIndex: 1, payload: Data(payload), lastChunkAt: now)
            return nil
        }

        guard var current = pending,
              current.msgId == msgId,
              current.chunkCount == count,
              current.nextIndex == index
        else {
            dropPending()
            return nil
        }

        current.payload.append(payload)
        current.nextIndex += 1
        current.lastChunkAt = now

        if current.nextIndex == current.chunkCount {
            pending = nil
            return current.payload
        }
        pending = current
        return nil
    }

    private mutating func dropPending() {
        if pending != nil {
            droppedMessageCount += 1
            pending = nil
        }
    }
}
