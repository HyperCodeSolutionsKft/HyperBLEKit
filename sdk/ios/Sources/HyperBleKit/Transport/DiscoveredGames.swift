import Foundation

/// The scanner's bookkeeping, kept free of CoreBluetooth so it can be unit tested.
struct DiscoveredGames {
    /// CoreBluetooth reports 127 when it has no RSSI for the packet.
    static let unknownRSSI = 127

    private(set) var byId: [UUID: DiscoveredGame] = [:]

    /// Games in the order they were first found.
    var list: [DiscoveredGame] {
        byId.values.sorted { $0.discoveredAt < $1.discoveredAt }
    }

    mutating func record(id: UUID, gameCode: String, rssi: Int, at now: Date) {
        if var game = byId[id] {
            game.gameCode = gameCode
            if rssi != Self.unknownRSSI { game.rssi = rssi }
            game.lastSeen = now
            byId[id] = game
        } else {
            byId[id] = DiscoveredGame(id: id, gameCode: gameCode, rssi: rssi,
                                      discoveredAt: now, lastSeen: now)
        }
    }

    /// Drops games not seen for `DiscoveredGame.staleAfter`. Returns true if anything was removed.
    mutating func removeStale(at now: Date) -> Bool {
        let before = byId.count
        byId = byId.filter { now.timeIntervalSince($0.value.lastSeen) <= DiscoveredGame.staleAfter }
        return byId.count != before
    }

    mutating func removeAll() {
        byId.removeAll()
    }
}
