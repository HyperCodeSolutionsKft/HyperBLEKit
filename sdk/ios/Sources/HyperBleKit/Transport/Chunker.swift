import Foundation

enum ChunkError: Error, Equatable {
    /// Max chunk length leaves no room for payload after the header.
    case chunkLengthTooSmall(Int)
    /// Message would need more than 255 chunks.
    case messageTooLarge(byteCount: Int, chunkCount: Int)
    /// Chunk is shorter than the header, or its index/count are invalid.
    case malformedHeader
}

/// Splits outgoing messages into chunks: `[msgId][chunkIndex][chunkCount][payload...]`.
/// Use one instance per connection and direction, since `msgId` is scoped to that.
struct Chunker {
    private(set) var nextMessageId: UInt8 = 0

    /// - Parameter maxChunkLength: max bytes per write/notify, header included.
    mutating func split(_ message: Data, maxChunkLength: Int) throws -> [Data] {
        let payloadLength = maxChunkLength - BLEConstants.chunkHeaderSize
        guard payloadLength > 0 else { throw ChunkError.chunkLengthTooSmall(maxChunkLength) }

        let chunkCount = max(1, (message.count + payloadLength - 1) / payloadLength)
        guard chunkCount <= Int(UInt8.max) else {
            throw ChunkError.messageTooLarge(byteCount: message.count, chunkCount: chunkCount)
        }

        let msgId = nextMessageId
        nextMessageId &+= 1

        return (0..<chunkCount).map { index in
            let start = message.startIndex + index * payloadLength
            let end = min(start + payloadLength, message.endIndex)
            var chunk = Data([msgId, UInt8(index), UInt8(chunkCount)])
            chunk.append(message[start..<end])
            return chunk
        }
    }
}
