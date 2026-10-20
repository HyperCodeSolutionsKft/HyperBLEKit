import Foundation

/// One client's debug log as collected by the host (`ClientLogCollection.logs`).
///
/// The lines are the client's own log lines, in the order they appear on its screen, reassembled
/// from the batches it uploaded. `batchesReceived` vs `batchesExpected` shows how much has arrived;
/// `complete` is true once every batch the client announced has been received.
public struct CollectedClientLog: Equatable {
    /// The client's player ID ("p2" … "p10").
    public let playerId: String
    /// The player's name, from the host's roster.
    public let name: String
    /// The player's platform ("android" / "ios"), from the host's roster.
    public let platform: String
    /// The log lines gathered so far, oldest first.
    public let lines: [String]
    /// How many upload batches have arrived.
    public let batchesReceived: Int
    /// How many batches the client said it would send (0 until the first arrives).
    public let batchesExpected: Int

    public init(playerId: String, name: String, platform: String, lines: [String],
                batchesReceived: Int, batchesExpected: Int) {
        self.playerId = playerId
        self.name = name
        self.platform = platform
        self.lines = lines
        self.batchesReceived = batchesReceived
        self.batchesExpected = batchesExpected
    }

    /// Every announced batch has arrived.
    public var complete: Bool { batchesExpected > 0 && batchesReceived >= batchesExpected }
}

/// What the host has collected from its clients after `SessionDiagnostics.requestClientLogs()`:
/// one `CollectedClientLog` per client that has uploaded anything, in seat order. The collection
/// fills live as batches arrive.
///
/// `expectedClients` is how many clients were asked (the linked clients at the moment of the
/// request); `pending` is true while not every expected client has sent a `complete` log. nil in
/// `SessionDiagnostics.clientLogs` means no collection has been requested on this device (it is
/// also nil on a client — collection is host-only).
public struct ClientLogCollection: Equatable {
    public let logs: [CollectedClientLog]
    public let expectedClients: Int
    public let pending: Bool

    public init(logs: [CollectedClientLog], expectedClients: Int, pending: Bool) {
        self.logs = logs
        self.expectedClients = expectedClients
        self.pending = pending
    }
}
