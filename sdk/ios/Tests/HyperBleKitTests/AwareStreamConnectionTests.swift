import XCTest
@testable import HyperBleKit

/// One end of an in-memory byte pipe; `MemoryChannel.pair()` makes two connected ends. Bytes
/// arrive in order, in whatever pieces they were sent. Thread-safe: the async methods run off
/// the main thread, as the real channel's do.
final class MemoryChannel: AwareByteChannel {
    struct Closed: Error {}

    fileprivate weak var peer: MemoryChannel?
    private let lock = NSLock()
    private var buffer = Data()
    private var isClosed = false
    private var waiting: (count: Int, continuation: CheckedContinuation<Data, Error>)?
    private var _stallSends = false
    private var _sentChunks: [Data] = []

    /// While true, `send` never returns: a peer that stopped reading.
    var stallSends: Bool {
        get { lock.withLock { _stallSends } }
        set { lock.withLock { _stallSends = newValue } }
    }

    /// Every chunk sent, for counting keepalives.
    var sentChunks: [Data] { lock.withLock { _sentChunks } }

    static func pair() -> (MemoryChannel, MemoryChannel) {
        let a = MemoryChannel(), b = MemoryChannel()
        a.peer = b
        b.peer = a
        return (a, b)
    }

    func send(_ data: Data) async throws {
        let (closed, stall) = lock.withLock { (isClosed, _stallSends) }
        guard !closed, let peer, !peer.closed else { throw Closed() }
        if stall { try await Task.sleep(nanoseconds: 60_000_000_000) }
        lock.withLock { _sentChunks.append(data) }
        peer.deliver(data)
    }

    func receive(exactly count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            let result: Result<Data, Error>? = lock.withLock {
                if buffer.count >= count { return .success(take(count)) }
                if isClosed { return .failure(Closed()) }
                waiting = (count, continuation)
                return nil
            }
            if let result { continuation.resume(with: result) }
        }
    }

    func cancel() {
        guard markClosed() else { return }
        peer?.markClosed()
    }

    private var closed: Bool { lock.withLock { isClosed } }

    @discardableResult
    private func markClosed() -> Bool {
        let (wasOpen, resume) = lock.withLock { () -> (Bool, (() -> Void)?) in
            guard !isClosed else { return (false, nil) }
            isClosed = true
            return (true, settleWaiting())
        }
        resume?()
        return wasOpen
    }

    private func deliver(_ data: Data) {
        let resume = lock.withLock { () -> (() -> Void)? in
            buffer.append(data)
            return settleWaiting()
        }
        resume?()
    }

    /// Under the lock: what to resume, called once the lock is released.
    private func settleWaiting() -> (() -> Void)? {
        guard let (count, continuation) = waiting else { return nil }
        if buffer.count >= count {
            waiting = nil
            let data = take(count)
            return { continuation.resume(returning: data) }
        }
        if isClosed {
            waiting = nil
            return { continuation.resume(throwing: Closed()) }
        }
        return nil
    }

    private func take(_ count: Int) -> Data {
        let head = Data(buffer.prefix(count))
        buffer = Data(buffer.dropFirst(count))
        return head
    }
}

@MainActor
final class AwareStreamConnectionTests: XCTestCase {
    private final class End {
        var records: [AwareRecord] = []
        var closed: [String] = []
    }

    private func pair(silence: TimeInterval = 6, keepalive: TimeInterval = 2)
        -> (AwareStreamConnection, AwareStreamConnection, End, End, MemoryChannel, MemoryChannel) {
        let (channelA, channelB) = MemoryChannel.pair()
        let a = End(), b = End()
        let connectionA = AwareStreamConnection(channel: channelA, silenceTimeout: silence, keepaliveInterval: keepalive,
                                                onRecord: { a.records.append($0) }, onClosed: { a.closed.append($0) })
        let connectionB = AwareStreamConnection(channel: channelB, silenceTimeout: silence, keepaliveInterval: keepalive,
                                                onRecord: { b.records.append($0) }, onClosed: { b.closed.append($0) })
        connectionA.start()
        connectionB.start()
        return (connectionA, connectionB, a, b, channelA, channelB)
    }

    private func waitUntil(_ what: String, timeout: TimeInterval = 3, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() >= deadline { return XCTFail("timed out waiting until \(what)") }
            RunLoop.main.run(until: Date().addingTimeInterval(0.01))
        }
    }

    func testMessagesAndFramesArriveInOrderBothWays() {
        let (a, b, endA, endB, _, _) = pair()
        a.sendRecord(kind: AwareStream.hello, body: Data([1, 2]))
        XCTAssertTrue(a.sendMessage(Data("one".utf8)))
        XCTAssertTrue(a.sendMessage(Data(repeating: 7, count: 100_000)))
        XCTAssertEqual(b.sendFrame(Data([0x10, 5])), .queued)

        waitUntil("B has three records") { endB.records.count == 3 }
        XCTAssertEqual(endB.records.map(\.kind), [AwareStream.hello, AwareStream.message, AwareStream.message])
        XCTAssertEqual(endB.records[1].body, Data("one".utf8))
        XCTAssertEqual(endB.records[2].body.count, 100_000)
        waitUntil("A has the frame") { endA.records.count == 1 }
        XCTAssertEqual(endA.records[0], AwareRecord(kind: AwareStream.frame, body: Data([0x10, 5])))
    }

    func testAFrameWaitingBehindASendIsReplacedByANewerOne() {
        let (a, _, _, endB, channelA, _) = pair()
        channelA.stallSends = true
        XCTAssertTrue(a.sendMessage(Data("stuck".utf8)))
        XCTAssertEqual(a.sendFrame(Data([1])), .queued)
        XCTAssertEqual(a.sendFrame(Data([2])), .replaced)
        XCTAssertTrue(endB.records.isEmpty)
        a.close()
    }

    func testKeepalivesKeepAQuietConnectionOpenAndAreNotHandedOn() {
        let (a, b, endA, endB, channelA, _) = pair(silence: 0.6, keepalive: 0.1)
        RunLoop.main.run(until: Date().addingTimeInterval(1.2))
        XCTAssertTrue(endA.closed.isEmpty)
        XCTAssertTrue(endB.closed.isEmpty)
        XCTAssertTrue(endA.records.isEmpty && endB.records.isEmpty)
        XCTAssertGreaterThan(channelA.sentChunks.count, 3)
        a.close()
        b.close()
    }

    func testAConnectionWhoseOtherEndGoesSilentCloses() {
        let (channelA, _) = MemoryChannel.pair()
        let end = End()
        let connection = AwareStreamConnection(channel: channelA, silenceTimeout: 0.3, keepaliveInterval: 10,
                                               onRecord: { end.records.append($0) }, onClosed: { end.closed.append($0) })
        connection.start()
        waitUntil("the silence is noticed") { !end.closed.isEmpty }
        XCTAssertEqual(end.closed, ["silent for 0 s"])
    }

    func testClosingOneEndIsReportedOnTheOtherNeverOnTheClosingOne() {
        let (a, b, endA, endB, _, _) = pair()
        a.close()
        waitUntil("B hears it") { !endB.closed.isEmpty }
        RunLoop.main.run(until: Date().addingTimeInterval(0.1))
        XCTAssertTrue(endA.closed.isEmpty)
        withExtendedLifetime(b) {}
    }

    func testABadRecordLengthClosesTheConnection() {
        let (channelA, channelB) = MemoryChannel.pair()
        let end = End()
        let connection = AwareStreamConnection(channel: channelB, onRecord: { end.records.append($0) },
                                               onClosed: { end.closed.append($0) })
        connection.start()
        Task { try await channelA.send(Data([0, 0, 0, 0])) }
        waitUntil("the bad record is noticed") { !end.closed.isEmpty }
        XCTAssertEqual(end.closed, ["bad record length 0"])
    }

    func testCloseAfterFlushSendsWhatIsQueuedThenCallsBack() {
        let (a, b, endA, endB, _, _) = pair()
        for index in 0..<20 { _ = a.sendMessage(Data("m\(index)".utf8)) }
        var done = false
        a.closeAfterFlush(timeout: 2) { done = true }
        XCTAssertFalse(a.sendMessage(Data("late".utf8)))
        waitUntil("the flush is done") { done }
        waitUntil("B has all 20") { endB.records.count == 20 }
        XCTAssertEqual(endB.records.map { String(decoding: $0.body, as: UTF8.self) }, (0..<20).map { "m\($0)" })
        XCTAssertTrue(endA.closed.isEmpty)
        withExtendedLifetime(b) {}
    }
}
