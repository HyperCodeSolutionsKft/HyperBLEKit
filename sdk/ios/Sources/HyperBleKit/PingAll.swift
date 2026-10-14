import Foundation

/// One client's answer to a ping-all (`SessionDiagnostics.pingAll()`).
public struct PingReply: Equatable {
    /// The client's player ID ("p1" … "p10").
    public let playerId: String
    /// Its round-trip time in milliseconds, or nil if it did not answer in time.
    public let rttMs: Int?

    public init(playerId: String, rttMs: Int?) {
        self.playerId = playerId
        self.rttMs = rttMs
    }
}

/// The outcome of one ping-all (`SessionDiagnostics.pingAllResult`): the host pinged every
/// connected client at once and collected each one's round trip. There is one `PingReply` per
/// client that was pinged, in seat order.
///
/// While `pending` is true the host is still waiting for answers (replies show the clients
/// pinged, all with a nil time); when it turns false the round is over and any client that did
/// not answer has a nil `PingReply.rttMs`. nil in `SessionDiagnostics.pingAllResult` means no
/// ping-all has run on this device yet.
public struct PingAllResult: Equatable {
    public let replies: [PingReply]
    public let pending: Bool

    public init(replies: [PingReply], pending: Bool) {
        self.replies = replies
        self.pending = pending
    }
}
