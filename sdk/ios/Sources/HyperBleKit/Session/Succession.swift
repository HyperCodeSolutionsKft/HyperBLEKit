import Foundation

/// Who takes over when the host is gone. The host picks its successors: connected players
/// that can host, by seat number, without the host itself.
enum Succession {
    static func order(players: [Player], hostId: String) -> [String] {
        players
            .filter { $0.connected && $0.canHost && $0.id != hostId }
            .map(\.id)
            .sorted { Player.seatNumber(of: $0) < Player.seatNumber(of: $1) }
    }

    /// A snapshot holder's rank when its snapshot does not name it: its 1-based place among the
    /// players that can host, by seat number, without the current host. `connected` is ignored
    /// on purpose: a host that stalls reports everyone as disconnected before it goes quiet.
    static func fallbackRank(of playerId: String, players: [Player], hostId: String) -> Int? {
        players
            .filter { $0.canHost && $0.id != hostId }
            .map(\.id)
            .sorted { Player.seatNumber(of: $0) < Player.seatNumber(of: $1) }
            .firstIndex(of: playerId)
            .map { $0 + 1 }
    }

    /// Two hosts of the same game: the one with the higher epoch stays; at the same epoch the
    /// lower seat number wins. True when `other` beats `own`.
    static func outranks(otherHostId: String, otherEpoch: Int, ownHostId: String, ownEpoch: Int) -> Bool {
        if otherEpoch != ownEpoch { return otherEpoch > ownEpoch }
        return Player.seatNumber(of: otherHostId) < Player.seatNumber(of: ownHostId)
    }
}
