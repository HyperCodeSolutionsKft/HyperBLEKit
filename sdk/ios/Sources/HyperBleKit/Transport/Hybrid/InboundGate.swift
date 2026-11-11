import Foundation

/// Keeps the messages of one direction of a link in order while the sender moves from BLE to
/// Wi-Fi Aware (wire specification, "Switching paths").
///
/// The sender sends `pathSwitch(wifiAware)` over BLE after its last BLE message, then continues
/// on Wi-Fi Aware, whose first messages can overtake the last BLE ones. They are held here until
/// the switch arrives over BLE, or for `timeout` at most if it never does. BLE messages are
/// always delivered at once.
///
/// Main thread only.
final class InboundGate {
    static let timeout: TimeInterval = 3
    static let maxHeld = 256

    private let scheduler: MainScheduler
    private let deliver: (Data) -> Void
    private let log: (String) -> Void
    private var isOpen = false
    private var held: [Data] = []
    /// Counts resets, so a timeout armed for an earlier path does nothing.
    private var generation = 0

    init(scheduler: @escaping MainScheduler, deliver: @escaping (Data) -> Void, log: @escaping (String) -> Void) {
        self.scheduler = scheduler
        self.deliver = deliver
        self.log = log
    }

    /// A message that arrived over Wi-Fi Aware.
    func fromAware(_ message: Data) {
        if isOpen {
            deliver(message)
            return
        }
        held.append(message)
        if held.count > Self.maxHeld {
            log("PATH gate: \(Self.maxHeld) messages held without PATH_SWITCH, opening")
            open()
            return
        }
        if held.count == 1 {
            let armed = generation
            scheduler(Self.timeout) { [weak self] in
                guard let self, armed == self.generation, !self.isOpen, !self.held.isEmpty else { return }
                self.log("PATH gate: no PATH_SWITCH after \(Int(Self.timeout * 1000)) ms, opening")
                self.open()
            }
        }
    }

    /// `pathSwitch(wifiAware)` arrived over BLE: every BLE message before it has been delivered.
    func open() {
        isOpen = true
        flush()
    }

    /// The Wi-Fi Aware path is gone or not set up yet: what it delivered is handed on, and the
    /// gate is closed for the next path.
    func reset() {
        generation += 1
        flush()
        isOpen = false
    }

    private func flush() {
        let messages = held
        held.removeAll()
        messages.forEach(deliver)
    }
}
