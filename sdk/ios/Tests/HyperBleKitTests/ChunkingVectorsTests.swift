import XCTest
@testable import HyperBleKit

/// Validates the iOS chunker/reassembler against the shared `/protocol/test-vectors/chunking.json`,
/// so any drift from the Android framing turns this test red.
final class ChunkingVectorsTests: XCTestCase {

    func testSplitCasesProduceTheExactChunks() throws {
        let cases = try TestVectors.load("chunking.json")["split"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let message = TestVectors.bytes(c["message"]!)
            let maxChunkLength = c["maxChunkLength"] as! Int
            let expected = (c["chunks"] as! [Any]).map { TestVectors.bytes($0) }

            var chunker = Chunker()
            let actual = try chunker.split(message, maxChunkLength: maxChunkLength)
            XCTAssertEqual(actual, expected, name)
        }
    }

    func testRoundTripCasesReassembleToTheOriginal() throws {
        let cases = try TestVectors.load("chunking.json")["roundTrip"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let message = TestVectors.bytes(c["message"]!)
            let maxChunkLength = c["maxChunkLength"] as! Int

            var chunker = Chunker()
            var reassembler = Reassembler()
            let chunks = try chunker.split(message, maxChunkLength: maxChunkLength)
            for (index, chunk) in chunks.enumerated() {
                let result = try reassembler.receive(chunk)
                if index < chunks.count - 1 {
                    XCTAssertNil(result, "\(name): chunk \(index) should not complete the message")
                } else {
                    XCTAssertEqual(result, message, "\(name): final chunk")
                }
            }
        }
    }
}
