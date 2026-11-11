import Foundation

/// What an app tells the SDK once, when it creates its `HyperBleKit`: the games it can play and
/// how its games are told apart from other apps' on the air.
///
/// Every phone in a game must use the same `serviceUUID`, `localNamePrefix`, `manufacturerId`
/// and `maxPlayers`, on iOS and on Android. A configuration does not change after it is
/// created. The timings of the protocol (ping interval, silence timeout, reconnect window,
/// takeover delays) are fixed by the SDK and cannot be configured, because they too must be
/// the same on every phone.
public struct HyperBleKitConfig {
    /// The default `serviceUUID`: `6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E`. Use your own UUID in
    /// your own app, so that it does not find the games of other apps built on this SDK.
    public static let defaultServiceUUID = UUID(uuidString: "6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E")!
    /// The default `localNamePrefix`: "CG-".
    public static let defaultLocalNamePrefix = "CG-"
    /// The default `manufacturerId`: 0xFFFF, the Bluetooth company ID reserved for testing.
    public static let defaultManufacturerId: UInt16 = 0xFFFF
    /// The default and largest `maxPlayers`: 10.
    public static let defaultMaxPlayers = ProtocolConstants.maxPlayers
    /// The default `wifiAwareServiceName`: `_hyperblekit._tcp`.
    public static let defaultWiFiAwareServiceName = "_hyperblekit._tcp"
    /// The default `logCapacity`: 500 lines.
    public static let defaultLogCapacity = ProtocolConstants.debugLogCapacity

    /// The game modules this app can play. A host can start any of them. A client needs the
    /// module of the game the host started in order to take that game over and to receive a
    /// real-time game's frames; without it the client can still show the game's view.
    public let games: [GameModule]
    /// The Bluetooth GATT service the games of this app are advertised and found under. Two
    /// apps with different service UUIDs never see each other's games. The service's three
    /// characteristic UUIDs are derived from it by adding 1, 2 and 3 to its first 32-bit field.
    public let serviceUUID: UUID
    /// An iOS host advertises this prefix followed by the 4-character game code as its
    /// Bluetooth local name, e.g. "CG-7K2F". 1 to 4 ASCII characters.
    public let localNamePrefix: String
    /// An Android host advertises the game code as manufacturer data under this Bluetooth
    /// company ID.
    public let manufacturerId: UInt16
    /// The number of seats in a game, the host's included. 2 to 10.
    public let maxPlayers: Int
    /// The number of lines `HyperBleLog.entries` keeps before it drops the oldest. At least 1.
    public let logCapacity: Int
    /// Whether connected phones may move their messages to Wi-Fi Aware; see `DataPaths`.
    /// Phones with different values still play together, over Bluetooth LE.
    public let dataPaths: DataPaths
    /// The Wi-Fi Aware service a host publishes, in DNS-SD form: an underscore, 1 to 15
    /// lowercase letters, digits or hyphens (at least one letter, no hyphen first or last), then
    /// `._tcp`. Every phone in a game must use the
    /// same one, and the app must list it under `WiFiAwareServices` in its Info.plist. Only
    /// used with `.preferWiFiAware`.
    public let wifiAwareServiceName: String
    /// **Experimental.** Also try Wi-Fi Aware with Android phones (that set the same option),
    /// after pairing them with `WiFiAwarePairingButton`. Nobody has reported Wi-Fi Aware between
    /// an iPhone and an Android phone working yet, so expect it to fall back to Bluetooth LE.
    /// Only used with `.preferWiFiAware`.
    public let experimentalCrossPlatformWiFiAware: Bool

    /// Creates a configuration. Only `games` is required; the other values have defaults.
    ///
    /// The values are checked here, and a value out of range stops the program
    /// (`precondition`), because it is a programming error that would otherwise show up as
    /// phones that cannot find or join each other.
    ///
    /// - Parameters:
    ///   - games: the game modules this app can play; may be empty for an app that only joins
    ///     and shows games it cannot host.
    ///   - serviceUUID: the GATT service UUID of this app's games.
    ///   - localNamePrefix: 1 to 4 ASCII characters. The advertised name is the prefix plus the
    ///     4-character code, and an advertisement has room for about 8 bytes of name next to
    ///     the service UUID.
    ///   - manufacturerId: the Bluetooth company ID an Android host advertises the code under.
    ///   - maxPlayers: 2 to 10, the host included.
    ///   - logCapacity: at least 1 line.
    ///   - dataPaths: `.bleOnly` (the default) or `.preferWiFiAware`.
    ///   - wifiAwareServiceName: the Wi-Fi Aware service name, like `_hyperblekit._tcp`.
    ///   - experimentalCrossPlatformWiFiAware: experimental; also try Wi-Fi Aware with Android phones.
    public init(games: [GameModule],
                serviceUUID: UUID = HyperBleKitConfig.defaultServiceUUID,
                localNamePrefix: String = HyperBleKitConfig.defaultLocalNamePrefix,
                manufacturerId: UInt16 = HyperBleKitConfig.defaultManufacturerId,
                maxPlayers: Int = HyperBleKitConfig.defaultMaxPlayers,
                logCapacity: Int = HyperBleKitConfig.defaultLogCapacity,
                dataPaths: DataPaths = .bleOnly,
                wifiAwareServiceName: String = HyperBleKitConfig.defaultWiFiAwareServiceName,
                experimentalCrossPlatformWiFiAware: Bool = false) {
        precondition((2...ProtocolConstants.maxPlayers).contains(maxPlayers),
                     "HyperBleKitConfig.maxPlayers must be 2 to \(ProtocolConstants.maxPlayers), got \(maxPlayers)")
        precondition(logCapacity >= 1, "HyperBleKitConfig.logCapacity must be at least 1, got \(logCapacity)")
        precondition((1...4).contains(localNamePrefix.unicodeScalars.count)
                        && localNamePrefix.unicodeScalars.allSatisfy(\.isASCII),
                     "HyperBleKitConfig.localNamePrefix must be 1 to 4 ASCII characters, got \"\(localNamePrefix)\"")
        precondition(Self.isValidServiceName(wifiAwareServiceName),
                     "HyperBleKitConfig.wifiAwareServiceName must look like \"_name._tcp\" with 1 to 15 lowercase "
                        + "letters, digits or hyphens as the name, got \"\(wifiAwareServiceName)\"")
        self.games = games
        self.serviceUUID = serviceUUID
        self.localNamePrefix = localNamePrefix
        self.manufacturerId = manufacturerId
        self.maxPlayers = maxPlayers
        self.logCapacity = logCapacity
        self.dataPaths = dataPaths
        self.wifiAwareServiceName = wifiAwareServiceName
        self.experimentalCrossPlatformWiFiAware = experimentalCrossPlatformWiFiAware
    }

    /// The BLE identifiers this configuration stands for.
    /// `_name._tcp`, the name 1 to 15 of `a-z`, `0-9` and `-`, at least one letter, no hyphen
    /// first or last; the same rule as on Android. Apple's rule is wider, but an invalid name in
    /// Info.plist crashes the app, so the SDK keeps to a safe subset.
    static func isValidServiceName(_ name: String) -> Bool {
        guard name.hasPrefix("_"), name.hasSuffix("._tcp") else { return false }
        let inner = name.dropFirst().dropLast(5)
        return (1...15).contains(inner.count)
            && inner.unicodeScalars.allSatisfy { ("a"..."z").contains($0) || ("0"..."9").contains($0) || $0 == "-" }
            && inner.unicodeScalars.contains { ("a"..."z").contains($0) }
            && inner.first != "-" && inner.last != "-"
    }

    var layout: BLELayout {
        BLELayout(serviceUUID: serviceUUID, localNamePrefix: localNamePrefix, manufacturerId: manufacturerId)
    }
}
