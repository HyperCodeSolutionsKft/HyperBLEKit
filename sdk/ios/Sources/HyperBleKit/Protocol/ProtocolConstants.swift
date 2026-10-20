import Foundation

/// Protocol and session limits shared with the Android library. See the wire specification "Message Protocol",
/// "Players and Seats" and "Reliability Rules".
enum ProtocolConstants {
    /// Envelope and GameInfo `v`.
    static let version = 1

    static let platform = "ios"
    /// The player who creates a game. After a host migration the host can be any player.
    static let firstHostPlayerId = "p1"
    /// `hostEpoch` of a game that has never changed its host.
    static let firstHostEpoch = 1
    /// Envelope `from` before the host has assigned a player ID.
    static let unassignedPlayerId = "?"

    /// Including the host.
    static let maxPlayers = 10
    static let maxNameLength = 12

    /// Host sends `PING` to every client this often.
    static let pingInterval: TimeInterval = 3
    /// No traffic for this long means the other side is gone.
    static let silenceTimeout: TimeInterval = 10
    /// How often the client checks for host silence.
    static let silenceCheckInterval: TimeInterval = 1

    /// After losing the host, the client keeps trying to get back in for this long.
    static let reconnectWindow: TimeInterval = 45
    /// The host removes a seat that stays disconnected this long. A host that has just taken
    /// over gives every seat `reconnectWindow` instead.
    static let offlineRemovalAfter: TimeInterval = 30
    /// Pause between a failed reconnect attempt and the next one.
    static let reconnectPause: TimeInterval = 1

    /// After losing the host, the first successor waits this long before it takes over.
    static let takeoverDelay: TimeInterval = 5
    /// Each further successor waits this much longer than the one before it.
    static let takeoverStep: TimeInterval = 8
    /// The host sends its full state to this many successors.
    static let snapshotHolders = 2
    /// A host with seated players but no link to any of them looks for a newer host after this
    /// long, and again at this interval while it stays alone.
    static let hostAloneProbeAfter: TimeInterval = 10

    /// Real-time games (wire specification "Real-time games"): the host's own input is applied this many
    /// ticks late, so it has no edge over a client.
    static let hostInputDelayTicks = 1
    /// A paused real-time game resumes this long after the last of its players is back.
    static let realtimeResumeDelay: TimeInterval = 3
    /// The host runs late ticks one after another, but after a stall longer than this it drops
    /// them instead of catching up.
    static let realtimeStallLimit: TimeInterval = 1

    /// The debug log keeps this many lines and drops the oldest.
    static let debugLogCapacity = 500

    /// How many log lines go in one `LOG_UPLOAD` batch when a client uploads its log to the host
    /// (`SessionDiagnostics.requestClientLogs()`). Small enough to stay within a few chunks even
    /// with long lines; a full log is sent as several batches.
    static let logUploadBatchLines = 50

    /// An on-demand ping-all (`SessionDiagnostics.pingAll()`) waits this long for the clients'
    /// PONGs before it reports the round trips; a client that stayed silent gets no time.
    static let pingAllTimeout: TimeInterval = 2
}
