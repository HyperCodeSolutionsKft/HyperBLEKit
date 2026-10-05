import XCTest
@testable import HyperBleKit

final class ReassemblerTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 0)

    func testSingleChunkCompletesImmediately() throws {
        var reassembler = Reassembler()
        XCTAssertEqual(try reassembler.receive(Data([7, 0, 1, 42, 43])), Data([42, 43]))
    }

    func testMultiChunkCompletesOnLastChunk() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([3, 0, 3, 1, 2])))
        XCTAssertNil(try reassembler.receive(Data([3, 1, 3, 3, 4])))
        XCTAssertEqual(try reassembler.receive(Data([3, 2, 3, 5])), Data([1, 2, 3, 4, 5]))
        XCTAssertEqual(reassembler.droppedMessageCount, 0)
    }

    func testRoundTripWithChunker() throws {
        let message = Data((0..<1000).map { UInt8($0 % 251) })
        var chunker = Chunker()
        var reassembler = Reassembler()
        for maxChunkLength in [20, 23, 185, 244, 512] {
            let chunks = try chunker.split(message, maxChunkLength: maxChunkLength)
            let results = try chunks.map { try reassembler.receive($0) }
            XCTAssertEqual(results.last, message, "maxChunkLength \(maxChunkLength)")
            XCTAssertTrue(results.dropLast().allSatisfy { $0 == nil })
        }
    }

    func testNewMessageDropsUnfinishedOne() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([1, 0, 2, 9])))
        XCTAssertEqual(try reassembler.receive(Data([2, 0, 1, 8])), Data([8]))
        XCTAssertEqual(reassembler.droppedMessageCount, 1)
    }

    func testUnexpectedIndexDropsMessage() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([1, 0, 3, 9])))
        XCTAssertNil(try reassembler.receive(Data([1, 2, 3, 9])))
        XCTAssertEqual(reassembler.droppedMessageCount, 1)
        // The real chunk 1 arriving late must not resurrect the message.
        XCTAssertNil(try reassembler.receive(Data([1, 1, 3, 9])))
    }

    func testMismatchedMessageIdOrCountDropsMessage() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([1, 0, 2, 9])))
        XCTAssertNil(try reassembler.receive(Data([2, 1, 2, 9])))
        XCTAssertEqual(reassembler.droppedMessageCount, 1)

        XCTAssertNil(try reassembler.receive(Data([3, 0, 3, 9])))
        XCTAssertNil(try reassembler.receive(Data([3, 1, 2, 9])))
        XCTAssertEqual(reassembler.droppedMessageCount, 2)
    }

    func testContinuationWithoutStartIsIgnored() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([1, 1, 2, 9])))
        XCTAssertEqual(reassembler.droppedMessageCount, 0)
    }

    func testTimeoutDropsUnfinishedMessage() throws {
        var reassembler = Reassembler(timeout: 5)
        XCTAssertNil(try reassembler.receive(Data([1, 0, 2, 1]), at: t0))
        XCTAssertNil(try reassembler.receive(Data([1, 1, 2, 2]), at: t0.addingTimeInterval(5.1)))
        XCTAssertEqual(reassembler.droppedMessageCount, 1)
    }

    func testChunkWithinTimeoutCompletes() throws {
        var reassembler = Reassembler(timeout: 5)
        XCTAssertNil(try reassembler.receive(Data([1, 0, 3, 1]), at: t0))
        XCTAssertNil(try reassembler.receive(Data([1, 1, 3, 2]), at: t0.addingTimeInterval(4)))
        // Timeout counts from the last chunk, not the first.
        XCTAssertEqual(try reassembler.receive(Data([1, 2, 3, 3]), at: t0.addingTimeInterval(8)), Data([1, 2, 3]))
    }

    func testMalformedChunksThrowAndKeepPendingMessage() throws {
        var reassembler = Reassembler()
        XCTAssertNil(try reassembler.receive(Data([1, 0, 2, 1])))
        for bad in [Data(), Data([1, 0]), Data([1, 0, 0]), Data([1, 2, 2])] {
            XCTAssertThrowsError(try reassembler.receive(bad)) {
                XCTAssertEqual($0 as? ChunkError, .malformedHeader)
            }
        }
        XCTAssertEqual(try reassembler.receive(Data([1, 1, 2, 2])), Data([1, 2]))
        XCTAssertEqual(reassembler.droppedMessageCount, 0)
    }

    func testWorksOnDataSlices() throws {
        var reassembler = Reassembler()
        let chunk = Data([0xFF, 4, 0, 1, 42]).dropFirst()
        XCTAssertEqual(try reassembler.receive(chunk), Data([42]))
    }
}
