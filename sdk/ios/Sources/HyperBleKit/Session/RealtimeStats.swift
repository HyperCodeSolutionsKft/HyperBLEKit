import Foundation

/// The host's counters of a running real-time game, per client, logged once a second as
/// `RT tx p2 fps=15 replaced=0 inFps=4 inGapMax=260`. Frames are never logged one by one.
/// Pure: the caller gives it the clock (`SpeedTestClock` milliseconds).
struct RealtimeTxStats {
    /// One client, by link, for the current second.
    private struct Peer {
        var lastInputAt: UInt32?
        var framesSent = 0
        var framesReplaced = 0
        var framesFailed = 0
        var inputs = 0
        var worstInputGap: UInt32 = 0
    }

    private var peers: [LinkID: Peer] = [:]
    private var lastReportAt: UInt32

    init(nowMillis: UInt32) {
        lastReportAt = nowMillis
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

    /// An input of the game was taken from this client.
    mutating func inputReceived(from link: LinkID, nowMillis: UInt32) {
        var peer = peers[link] ?? Peer()
        if let last = peer.lastInputAt { peer.worstInputGap = max(peer.worstInputGap, nowMillis &- last) }
        peer.lastInputAt = nowMillis
        peer.inputs += 1
        peers[link] = peer
    }

    /// Once a second: one line per client in `clients`, without the `RT tx` prefix, and the
    /// counters start over. nil while the second is not over. Clients no longer in `clients`
    /// are forgotten.
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
                + "inFps=\(peer.inputs) inGapMax=\(max(peer.worstInputGap, openGap))"
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

/// A client's counters of a running real-time game, logged once a second while world frames
/// arrive, as `RT rx fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80 size=57` (the speed
/// test's format).
/// The round trip runs from sending an input to the first world frame that echoes its sequence
/// number. Pure: the caller gives it the clock (`SpeedTestClock` milliseconds).
struct RealtimeRxStats {
    private var current = SpeedTestClient.Stats()
    private var lastFrameAt: UInt32?
    /// Payload size of the newest frame.
    private var size = 0
    /// When each input sequence number was last sent.
    private var sentAt: [UInt8: UInt32] = [:]
    /// The echoed sequence number of the newest frame; nil before the first frame.
    private var lastAck: UInt8?
    /// The transport's stale counter when the current second started.
    private var staleCountAtSecondStart: Int

    /// - Parameter staleFrameCount: the transport's stale counter right now.
    init(staleFrameCount: Int) {
        staleCountAtSecondStart = staleFrameCount
    }

    mutating func inputSent(seq: UInt8?, nowMillis: UInt32) {
        if let seq { sentAt[seq] = nowMillis }
    }

    /// A world frame of the game was taken. `ackedInputSeq`: the own input it echoes, if any.
    mutating func frameReceived(size frameSize: Int, ackedInputSeq: UInt8?, nowMillis: UInt32) {
        size = frameSize
        current.frames += 1
        if let lastFrameAt { current.addGap(nowMillis &- lastFrameAt) }
        lastFrameAt = nowMillis
        guard let ack = ackedInputSeq else { return }
        // The first frame only sets where the echo stands; then each new echo is one round trip.
        if let lastAck, ack != lastAck, let sent = sentAt[ack] {
            current.addRoundTrip(nowMillis &- sent)
        }
        lastAck = ack
    }

    /// No world frame for `closeAfterMillis`: the counters are closed.
    func isIdle(nowMillis: UInt32, closeAfterMillis: UInt32) -> Bool {
        lastFrameAt.map { nowMillis &- $0 >= closeAfterMillis } ?? true
    }

    /// Once a second: closes the second and returns its log line.
    mutating func endSecond(staleFrameCount: Int) -> String {
        // The transport's counter starts over with a new link: then all of it is this second's.
        current.stale = staleFrameCount >= staleCountAtSecondStart
            ? staleFrameCount - staleCountAtSecondStart : staleFrameCount
        staleCountAtSecondStart = staleFrameCount
        defer { current = SpeedTestClient.Stats() }
        return "RT rx \(current.text) size=\(size)"
    }
}
