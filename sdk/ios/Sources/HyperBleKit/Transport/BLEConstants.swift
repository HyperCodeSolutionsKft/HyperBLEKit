import CoreBluetooth

/// Framing and timing constants of the GATT layout shared with the Android SDK. The UUIDs and the
/// advertising format are per app: see `BLELayout`. See the wire specification "BLE Layout".
enum BLEConstants {
    /// Chunk header: [msgId][chunkIndex][chunkCount].
    static let chunkHeaderSize = 3
    /// A real-time frame is taken only if its `frameSeq` is this far ahead of the last taken
    /// one at most (wrapping at 256); otherwise it is stale or a duplicate.
    static let frameSeqWindow: UInt8 = 127

    /// Connect, discover, read GameInfo and subscribe must finish within this time;
    /// CoreBluetooth itself never gives up on a connection attempt.
    static let connectTimeout: TimeInterval = 10
    /// A subscribed central that takes no notification for this long is dropped by the host,
    /// so it cannot hold up the others.
    static let blockedLinkTimeout: TimeInterval = 10
    /// After `disconnect()`, queued writes get this long before the link is closed anyway.
    /// A host that stops gives its last notifications the same time.
    static let disconnectFlushTimeout: TimeInterval = 2
}

/// What tells one app's games from another's on the air: the service, its three characteristics
/// and how the game code is advertised. Built from `HyperBleKitConfig`.
struct BLELayout: Equatable {
    let serviceUUID: CBUUID
    /// Read, host → client: game name, player count, protocol version, lobby state.
    let gameInfoUUID: CBUUID
    /// Write with response, client → host.
    let clientToHostUUID: CBUUID
    /// Notify, host → clients.
    let hostToClientUUID: CBUUID
    /// Advertised local name is this prefix + a 4-char game code, e.g. "CG-7K2F".
    let localNamePrefix: String
    /// Android hosts put the game code in manufacturer data under this company ID
    /// (0xFFFF is reserved for testing).
    let manufacturerCompanyId: UInt16

    /// The characteristic UUIDs are the service UUID with its first 32-bit field plus 1, 2 and 3.
    init(serviceUUID: UUID, localNamePrefix: String, manufacturerId: UInt16) {
        self.serviceUUID = CBUUID(nsuuid: serviceUUID)
        gameInfoUUID = CBUUID(nsuuid: Self.uuid(serviceUUID, plus: 1))
        clientToHostUUID = CBUUID(nsuuid: Self.uuid(serviceUUID, plus: 2))
        hostToClientUUID = CBUUID(nsuuid: Self.uuid(serviceUUID, plus: 3))
        self.localNamePrefix = localNamePrefix
        manufacturerCompanyId = manufacturerId
    }

    /// `uuid` with `offset` added to its first 32-bit field, wrapping.
    private static func uuid(_ uuid: UUID, plus offset: UInt32) -> UUID {
        var bytes = uuid.uuid
        let first = (UInt32(bytes.0) << 24 | UInt32(bytes.1) << 16 | UInt32(bytes.2) << 8 | UInt32(bytes.3)) &+ offset
        bytes.0 = UInt8(truncatingIfNeeded: first >> 24)
        bytes.1 = UInt8(truncatingIfNeeded: first >> 16)
        bytes.2 = UInt8(truncatingIfNeeded: first >> 8)
        bytes.3 = UInt8(truncatingIfNeeded: first)
        return UUID(uuid: bytes)
    }
}
