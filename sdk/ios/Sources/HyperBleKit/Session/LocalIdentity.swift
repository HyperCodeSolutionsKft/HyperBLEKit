import Foundation

/// Who this device is in a game: a stable random device ID and the player's name. Both are
/// kept in `UserDefaults.standard`, under the keys "hyperblekit.deviceId" and
/// "hyperblekit.playerName". Get it from `HyperBleKit.identity`.
///
/// A change of the name is used the next time this device hosts or joins a game. In a session
/// that is already open, the other players keep seeing the old name, with one exception: a
/// host that steps down, or that handed its game over in the background, rejoins the game as
/// a client under the name set at that moment. Use it on the main thread.
public final class LocalIdentity {
    // Prefixed: the values share `UserDefaults.standard` with the app's own settings.
    static let deviceIdKey = "hyperblekit.deviceId"
    static let playerNameKey = "hyperblekit.playerName"

    /// The longest player name, in characters: 12. A longer one is cut to this length.
    public static let maxNameLength = ProtocolConstants.maxNameLength

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// A random identifier of this installation of the app: 32 lowercase hexadecimal
    /// characters, generated on first use and then always the same. A host uses it to give a
    /// returning player its seat back. It is sent to the host of every game this device joins.
    public var deviceId: String {
        if let stored = defaults.string(forKey: Self.deviceIdKey) { return stored }
        let generated = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        defaults.set(generated, forKey: Self.deviceIdKey)
        return generated
    }

    /// The name used while the player has not typed one: "iOS-" and the first 4 characters of
    /// `deviceId`, e.g. "iOS-3FA9".
    public var defaultPlayerName: String { "iOS-" + deviceId.prefix(4).uppercased() }

    /// The name this device plays under: `storedPlayerName` without surrounding whitespace, or
    /// `defaultPlayerName` when that is empty.
    public var playerName: String {
        let stored = storedPlayerName.trimmingCharacters(in: .whitespacesAndNewlines)
        return stored.isEmpty ? defaultPlayerName : stored
    }

    /// The name as the player typed it, for a text field; empty when none was typed. Setting it
    /// stores the first `maxNameLength` characters.
    public var storedPlayerName: String {
        get { defaults.string(forKey: Self.playerNameKey) ?? "" }
        set { defaults.set(String(newValue.prefix(Self.maxNameLength)), forKey: Self.playerNameKey) }
    }
}
