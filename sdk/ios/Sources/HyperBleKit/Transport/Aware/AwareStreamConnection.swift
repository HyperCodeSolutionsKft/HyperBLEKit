import Foundation

/// A reliable, ordered byte pipe: one TCP connection of a Wi-Fi Aware data path. The real one
/// wraps a Network framework connection (iOS 26); tests use an in-memory pair.
protocol AwareByteChannel: AnyObject {
    func send(_ data: Data) async throws
    /// Exactly `count` bytes; throws once the pipe is closed or broken.
    func receive(exactly count: Int) async throws -> Data
    /// Closes the pipe; pending and later calls throw.
    func cancel()
}

/// One Wi-Fi Aware connection carrying `AwareStream` records over an `AwareByteChannel`; the
/// counterpart of the Android `AwareConnection`.
///
/// Like a BLE link, it holds a queue of messages and a single slot for the newest frame, and a
/// waiting frame goes before the next message. Nothing sent for `keepaliveInterval` sends a
/// keepalive; nothing received for `silenceTimeout` closes the connection.
///
/// Main thread only: every callback arrives on the main thread.
final class AwareStreamConnection {
    private let channel: AwareByteChannel
    private let silenceTimeout: TimeInterval
    private let keepaliveInterval: TimeInterval
    private let onRecord: (AwareRecord) -> Void
    private let onClosed: (String) -> Void

    private var queue: [Data] = []
    private var frame: Data?
    private var isWriting = false
    private var isClosed = false
    /// Set by `close()` and `closeAfterFlush`: no `onClosed` any more.
    private var isSilenced = false
    private var flushCompletion: (() -> Void)?
    private var lastReceived = Date()
    private var lastSent = Date()
    private var readTask: Task<Void, Never>?
    private var timer: Timer?

    /// - Parameters:
    ///   - onRecord: a received `hello`, `message` or `frame` record (keepalives are not handed on).
    ///   - onClosed: the connection broke, with the reason; called once, never after `close()`.
    init(channel: AwareByteChannel,
         silenceTimeout: TimeInterval = AwareStream.silenceTimeout,
         keepaliveInterval: TimeInterval = AwareStream.keepaliveInterval,
         onRecord: @escaping (AwareRecord) -> Void,
         onClosed: @escaping (String) -> Void) {
        self.channel = channel
        self.silenceTimeout = silenceTimeout
        self.keepaliveInterval = keepaliveInterval
        self.onRecord = onRecord
        self.onClosed = onClosed
    }

    func start() {
        lastReceived = Date()
        lastSent = Date()
        let tick = min(silenceTimeout, keepaliveInterval) / 4
        timer = Timer.scheduledInCommonModes(withTimeInterval: tick, repeats: true) { [weak self] _ in self?.checkTimers() }
        readTask = Task { @MainActor [weak self, channel] in
            do {
                while true {
                    let header = try await channel.receive(exactly: AwareStream.lengthSize)
                    let length = header.reduce(0) { $0 << 8 | Int($1) }
                    guard length >= 1, length <= AwareStream.maxRecordLength else { throw AwareStreamError(length: length) }
                    let record = try await channel.receive(exactly: length)
                    guard let self else { return }
                    self.received(AwareRecord(kind: record[record.startIndex], body: Data(record.dropFirst())))
                }
            } catch let error as AwareStreamError {
                self?.fail("bad record length \(error.length)")
            } catch {
                self?.fail(self?.isClosed == false ? "read failed: \(error)" : "closed")
            }
        }
    }

    /// Queues a record of any kind; false once the connection is closed or closing.
    @discardableResult
    func sendRecord(kind: UInt8, body: Data = Data()) -> Bool {
        guard !isClosed, flushCompletion == nil else { return false }
        queue.append(AwareStream.encode(kind: kind, body: body))
        pump()
        return true
    }

    func sendMessage(_ message: Data) -> Bool {
        sendRecord(kind: AwareStream.message, body: message)
    }

    func sendFrame(_ payload: Data) -> FrameSendResult {
        guard !isClosed, flushCompletion == nil else { return .failed }
        let replaced = frame != nil
        frame = AwareStream.encode(kind: AwareStream.frame, body: payload)
        pump()
        return replaced ? .replaced : .queued
    }

    /// Closes at once; no callback follows.
    func close() {
        isSilenced = true
        shutDown()
    }

    /// Sends what is queued (a waiting frame is dropped), then closes and calls `completion`;
    /// after `timeout` it closes anyway. No `onClosed` follows.
    func closeAfterFlush(timeout: TimeInterval, then completion: @escaping () -> Void) {
        isSilenced = true
        guard !isClosed else {
            DispatchQueue.main.async(execute: completion)
            return
        }
        frame = nil
        flushCompletion = completion
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in self?.finishFlush() }
        pump()
    }

    // MARK: - Internals

    private func received(_ record: AwareRecord) {
        guard !isClosed else { return }
        lastReceived = Date()
        if record.kind == AwareStream.keepalive { return }
        if !isSilenced { onRecord(record) }
    }

    /// Sends the waiting frame, else the oldest message, unless a send is in flight.
    private func pump() {
        guard !isWriting, !isClosed else { return }
        let next: Data
        if let waiting = frame {
            frame = nil
            next = waiting
        } else if !queue.isEmpty {
            next = queue.removeFirst()
        } else {
            if flushCompletion != nil { finishFlush() }
            return
        }
        isWriting = true
        Task { @MainActor [weak self, channel] in
            do {
                try await channel.send(next)
            } catch {
                self?.fail("write failed: \(error)")
                return
            }
            guard let self else { return }
            self.isWriting = false
            self.lastSent = Date()
            self.pump()
        }
    }

    private func checkTimers() {
        guard !isClosed else { return }
        let now = Date()
        if now.timeIntervalSince(lastReceived) >= silenceTimeout {
            fail("silent for \(Int(silenceTimeout)) s")
        } else if now.timeIntervalSince(lastSent) >= keepaliveInterval, !isWriting, frame == nil, queue.isEmpty,
                  flushCompletion == nil {
            queue.append(AwareStream.encode(kind: AwareStream.keepalive))
            pump()
        }
    }

    private func finishFlush() {
        guard let completion = flushCompletion else { return }
        flushCompletion = nil
        shutDown()
        completion()
    }

    private func fail(_ reason: String) {
        guard !isClosed else { return }
        shutDown()
        // A flush in progress ends here too: what is left is lost, the caller still goes on.
        if let completion = flushCompletion {
            flushCompletion = nil
            completion()
        }
        if !isSilenced { onClosed(reason) }
    }

    private func shutDown() {
        guard !isClosed else { return }
        isClosed = true
        queue.removeAll()
        frame = nil
        timer?.invalidate()
        timer = nil
        readTask?.cancel()
        channel.cancel()
    }
}
