import Combine
import Foundation

/// The SDK's debug log: one line for every message sent or received and for every connection
/// event. There is one log for the whole process, `HyperBleLog.shared`.
///
/// `entries` is a bounded buffer for a debug screen; `sink` gets every line for the app's own
/// logging. Lines have the same format on iOS and Android, so logs from different phones can
/// be compared: `HH:mm:ss.SSS TX|RX TYPE seq=N sv=N bytes=N chunks=N [extra]` for a message and
/// `HH:mm:ss.SSS -- text` for an event. A line can contain player names and device IDs.
///
/// Use it on the main thread only; `entries` changes on the main thread.
public final class HyperBleLog: ObservableObject {
    /// The log that every `HyperBleKit` in this process writes to.
    public static let shared = HyperBleLog()

    enum Direction: String {
        case sent = "TX"
        case received = "RX"
    }

    /// One line of the log.
    public struct Entry: Identifiable, Equatable {
        /// A number that counts up with every line and is not reused after `clear()`; it gives
        /// a list a stable identity.
        public let id: Int
        /// The line, starting with its local time of day, e.g.
        /// `12:00:01.250 -- client 3F2A subscribed, chunk size 182`.
        public let text: String
    }

    /// The newest lines, oldest first. At most `HyperBleKitConfig.logCapacity` lines of the kit
    /// created last (500 before any kit exists); older lines are dropped.
    @Published public private(set) var entries: [Entry] = []
    /// Called on the main thread with every new line, also with lines that `entries` has
    /// already dropped. nil by default. It runs inside the SDK's message handling: keep it
    /// fast, and do not call the SDK from it.
    public var sink: ((String) -> Void)?
    /// `entries` keeps this many lines and drops the oldest.
    var capacity = ProtocolConstants.debugLogCapacity {
        didSet { trim() }
    }
    private var nextId = 0

    init() {}

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter
    }()

    /// All of `entries` as one text, one line each, e.g. to copy the log to the pasteboard.
    public var text: String { entries.map(\.text).joined(separator: "\n") }

    /// Empties `entries`. The session and `sink` are not affected.
    public func clear() {
        entries.removeAll()
    }

    /// One line per sent or received message. `sv=` is left out when the message has none.
    func message(_ direction: Direction, _ message: Message, bytes: Int, chunks: Int,
                 extra: String? = nil, at now: Date = Date()) {
        var parts = [direction.rawValue, message.type, "seq=\(message.seq)"]
        if let stateVersion = message.stateVersion { parts.append("sv=\(stateVersion)") }
        parts.append("bytes=\(bytes)")
        parts.append("chunks=\(chunks)")
        if let detail = message.logDetail { parts.append(detail) }
        if let extra { parts.append(extra) }
        append(parts.joined(separator: " "), at: now)
    }

    /// One line per sent or received debug test frame; it has no envelope, so no `seq` or `sv`.
    func testFrame(_ direction: Direction, bytes: Int, chunks: Int, extra: String, at now: Date = Date()) {
        append("\(direction.rawValue) \(TestFrame.logType) bytes=\(bytes) chunks=\(chunks) \(extra)", at: now)
    }

    /// Once-a-second counters of the real-time lane, e.g. `RT rx fps=15 stale=0 …`. Frames
    /// themselves are never logged one by one.
    func realtime(_ text: String, at now: Date = Date()) {
        append(text, at: now)
    }

    /// Anything that is not a message: connection events, drops, failures.
    func event(_ text: String, at now: Date = Date()) {
        append("-- \(text)", at: now)
    }

    private func append(_ text: String, at now: Date) {
        let line = "\(Self.timeFormatter.string(from: now)) \(text)"
        entries.append(Entry(id: nextId, text: line))
        nextId += 1
        trim()
        sink?(line)
    }

    private func trim() {
        if entries.count > capacity {
            entries.removeFirst(entries.count - capacity)
        }
    }
}
