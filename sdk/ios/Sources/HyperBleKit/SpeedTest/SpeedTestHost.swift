import Foundation

/// A dot bouncing around the 320 × 180 field, one step per host tick.
struct SpeedTestDot: Equatable {
    private(set) var x = SpeedTestConstants.fieldWidth / 2
    private(set) var y = SpeedTestConstants.fieldHeight / 2
    private var stepX = SpeedTestConstants.dotStepX
    private var stepY = SpeedTestConstants.dotStepY

    mutating func step() {
        (x, stepX) = Self.bounce(x + stepX, step: stepX, max: SpeedTestConstants.fieldWidth - 1)
        (y, stepY) = Self.bounce(y + stepY, step: stepY, max: SpeedTestConstants.fieldHeight - 1)
    }

    /// Reflects a position that went past an edge back into 0…max and turns the step around.
    private static func bounce(_ position: Int, step: Int, max: Int) -> (Int, Int) {
        if position < 0 { return (-position, -step) }
        if position > max { return (2 * max - position, -step) }
        return (position, step)
    }
}

/// The host side of the speed test: moves the dot, builds a world frame per client and counts
/// what goes out and comes in. Pure: the caller gives it the clock and does the sending.
struct SpeedTestHost {
    /// One client, by link.
    private struct Peer {
        /// The last input taken, echoed in every world frame to this client.
        var echoInputSeq: UInt8 = 0
        var echoClientMillis: UInt32 = 0
        var inputTakenAt: UInt32?
        var taps: UInt8 = 0
        var lastInputAt: UInt32?
        // This second:
        var framesSent = 0
        var framesReplaced = 0
        var framesFailed = 0
        var inputs = 0
        var worstInputGap: UInt32 = 0
    }

    let frameRate: Int
    let payloadSize: Int
    private(set) var tick: UInt32 = 0
    private(set) var dot = SpeedTestDot()
    /// Frame credit: grows by `frameRate` per tick, a frame is due at `ticksPerSecond`. This
    /// spreads 20 frames a second evenly over 30 ticks.
    private var frameCredit = SpeedTestConstants.ticksPerSecond
    private var peers: [LinkID: Peer] = [:]
    private var lastReportAt: UInt32

    init(frameRate: Int, payloadSize: Int, nowMillis: UInt32) {
        self.frameRate = frameRate
        self.payloadSize = payloadSize
        lastReportAt = nowMillis
    }

    /// One host tick: moves the dot. Returns whether a world frame is due on this tick.
    mutating func advance() -> Bool {
        tick &+= 1
        dot.step()
        guard frameCredit >= SpeedTestConstants.ticksPerSecond else {
            frameCredit += frameRate
            return false
        }
        frameCredit += frameRate - SpeedTestConstants.ticksPerSecond
        return true
    }

    /// The world frame payload for one client, echoing the last input taken from it.
    func worldPayload(for link: LinkID, nowMillis: UInt32) -> Data {
        let peer = peers[link] ?? Peer()
        let hold = peer.inputTakenAt.map { min(nowMillis &- $0, UInt32(UInt16.max)) } ?? 0
        return SpeedTestWorld(tick: tick, hostMillis: nowMillis, x: UInt16(dot.x), y: UInt16(dot.y),
                              echoInputSeq: peer.echoInputSeq, echoClientMillis: peer.echoClientMillis,
                              holdMillis: UInt16(hold))
            .encoded(size: payloadSize)
    }

    mutating func frameSent(to link: LinkID, result: FrameSendResult) {
        var peer = peers[link] ?? Peer()
        switch result {
        case .queued: peer.framesSent += 1
        case .replaced:
            peer.framesSent += 1
            peer.framesReplaced += 1
        case .failed: peer.framesFailed += 1
        }
        peers[link] = peer
    }

    /// A frame from a client. Returns false when it is not a speed-test input.
    @discardableResult
    mutating func inputReceived(_ payload: Data, from link: LinkID, nowMillis: UInt32) -> Bool {
        guard let input = SpeedTestInput(payload: payload) else { return false }
        var peer = peers[link] ?? Peer()
        if let last = peer.lastInputAt { peer.worstInputGap = max(peer.worstInputGap, nowMillis &- last) }
        peer.lastInputAt = nowMillis
        peer.inputs += 1
        peer.echoInputSeq = input.inputSeq
        peer.echoClientMillis = input.clientMillis
        peer.inputTakenAt = nowMillis
        peer.taps = input.taps
        peers[link] = peer
        return true
    }

    /// Once a second: one stat line per client in `clients` (link and label), e.g.
    /// `p2 fps=15 replaced=0 inFps=4 inGapMax=260 taps=3`, and the counters start over.
    /// nil while the second is not over. Clients no longer in `clients` are forgotten.
    mutating func reportIfDue(nowMillis: UInt32, clients: [(link: LinkID, label: String)]) -> [String]? {
        guard nowMillis &- lastReportAt >= SpeedTestConstants.statsIntervalMillis else { return nil }
        lastReportAt = nowMillis
        let current = Set(clients.map(\.link))
        peers = peers.filter { current.contains($0.key) }
        return clients.map { client in
            var peer = peers[client.link] ?? Peer()
            // A client that went quiet shows up at once, not only when its next input arrives.
            let openGap = peer.lastInputAt.map { nowMillis &- $0 } ?? 0
            var line = "\(client.label) fps=\(peer.framesSent) replaced=\(peer.framesReplaced) "
                + "inFps=\(peer.inputs) inGapMax=\(max(peer.worstInputGap, openGap)) taps=\(peer.taps)"
            if peer.framesFailed > 0 { line += " failed=\(peer.framesFailed)" }
            peer.framesSent = 0
            peer.framesReplaced = 0
            peer.framesFailed = 0
            peer.inputs = 0
            peer.worstInputGap = 0
            peers[client.link] = peer
            return line
        }
    }
}
