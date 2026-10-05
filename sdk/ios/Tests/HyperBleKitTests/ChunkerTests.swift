import XCTest
@testable import HyperBleKit

final class ChunkerTests: XCTestCase {
    func testSmallMessageIsOneChunkWithHeader() throws {
        var chunker = Chunker()
        let chunks = try chunker.split(Data("hello".utf8), maxChunkLength: 20)
        XCTAssertEqual(chunks, [Data([0, 0, 1]) + Data("hello".utf8)])
    }

    func testEmptyMessageIsOneHeaderOnlyChunk() throws {
        var chunker = Chunker()
        XCTAssertEqual(try chunker.split(Data(), maxChunkLength: 20), [Data([0, 0, 1])])
    }

    func testSplitsAtPayloadBoundary() throws {
        var chunker = Chunker()
        // 5 bytes payload per chunk.
        let chunks = try chunker.split(Data(0..<12), maxChunkLength: 8)
        XCTAssertEqual(chunks, [
            Data([0, 0, 3, 0, 1, 2, 3, 4]),
            Data([0, 1, 3, 5, 6, 7, 8, 9]),
            Data([0, 2, 3, 10, 11]),
        ])
    }

    func testExactMultipleHasNoEmptyTrailingChunk() throws {
        var chunker = Chunker()
        let chunks = try chunker.split(Data(0..<10), maxChunkLength: 8)
        XCTAssertEqual(chunks.count, 2)
        XCTAssertEqual(chunks.last, Data([0, 1, 2, 5, 6, 7, 8, 9]))
    }

    func testWorksOnDataSlices() throws {
        var chunker = Chunker()
        let slice = Data(0..<20).dropFirst(10)
        let chunks = try chunker.split(slice, maxChunkLength: 8)
        XCTAssertEqual(chunks, [Data([0, 0, 2, 10, 11, 12, 13, 14]), Data([0, 1, 2, 15, 16, 17, 18, 19])])
    }

    func testMessageIdIncrementsAndWraps() throws {
        var chunker = Chunker()
        for expected in 0...255 {
            XCTAssertEqual(try chunker.split(Data([1]), maxChunkLength: 20)[0][0], UInt8(expected))
        }
        XCTAssertEqual(try chunker.split(Data([1]), maxChunkLength: 20)[0][0], 0)
    }

    func testRejectsChunkLengthWithoutPayloadRoom() {
        var chunker = Chunker()
        XCTAssertThrowsError(try chunker.split(Data([1]), maxChunkLength: 3)) {
            XCTAssertEqual($0 as? ChunkError, .chunkLengthTooSmall(3))
        }
    }

    func testRejectsMoreThan255Chunks() throws {
        var chunker = Chunker()
        XCTAssertEqual(try chunker.split(Data(count: 255), maxChunkLength: 4).count, 255)
        XCTAssertThrowsError(try chunker.split(Data(count: 256), maxChunkLength: 4)) {
            XCTAssertEqual($0 as? ChunkError, .messageTooLarge(byteCount: 256, chunkCount: 256))
        }
    }

    func testFailedSplitDoesNotConsumeMessageId() throws {
        var chunker = Chunker()
        _ = try? chunker.split(Data(count: 256), maxChunkLength: 4)
        XCTAssertEqual(chunker.nextMessageId, 0)
    }
}
