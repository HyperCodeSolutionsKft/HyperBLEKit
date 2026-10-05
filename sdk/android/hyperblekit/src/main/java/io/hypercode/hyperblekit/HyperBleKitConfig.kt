package io.hypercode.hyperblekit

import io.hypercode.hyperblekit.game.GameModule
import java.util.UUID

/**
 * What an app tells [HyperBleKit] once, when it creates the kit. A config is checked when it is
 * created and never changes afterwards.
 *
 * Every phone in a game must use the same [serviceUuid], [localNamePrefix], [manufacturerId] and
 * [maxPlayers] — in practice: the same values in the Android and the iOS build of an app. They
 * decide which phones can find and join each other. With the defaults an app finds the games of
 * every other app that uses the defaults, so an app that ships should set its own [serviceUuid].
 *
 * What cannot be configured: the timings (a host pings every 3 seconds, a silent device counts as
 * gone after 10 seconds, a client looks for a lost host for 45 seconds, and so on) and the
 * message format. They must be the same on every phone, so they are fixed by the SDK.
 *
 * @param games the games this app can play, in the order the app wants to offer them. Game IDs
 *   ([GameModule.id]) should be unique; with duplicates the first one wins. A client needs the
 *   modules too: to route a real-time game's frames, and to continue the game if it becomes the
 *   host. The list is copied.
 * @param serviceUuid the Bluetooth service a game is advertised under. Two apps with different
 *   service UUIDs never see each other's games. The three characteristics of the service get the
 *   UUIDs that follow it: the service UUID with 1, 2 and 3 added to its first 32-bit group (for
 *   the default `6E4A0001-…`: `6E4A0002-…`, `6E4A0003-…`, `6E4A0004-…`), so leave those free.
 * @param localNamePrefix what an advertised game's Bluetooth name starts with; the name is this
 *   prefix followed by the four-character game code, e.g. "CG-7K2F". 1 to 4 ASCII characters,
 *   because the name has to fit into an advertising packet next to the service UUID.
 * @param manufacturerId the Bluetooth company identifier under which an Android host advertises
 *   the game code, 0 to 0xFFFF. The default 0xFFFF is reserved for testing; a company that has
 *   its own identifier should use it.
 * @param maxPlayers how many players a game can have, the host included: 2 to 10. Lower it if the
 *   app's games need fewer; a phone also has its own limit on simultaneous Bluetooth connections.
 * @param logCapacity how many lines [io.hypercode.hyperblekit.log.HyperBleLog.entries] keeps, at
 *   least 1. The log is shared by the whole process, so the kit created last decides.
 * @throws IllegalArgumentException if a value is outside its range.
 */
public class HyperBleKitConfig(
    games: List<GameModule>,
    /** The Bluetooth service UUID games are advertised under; see the constructor parameter. */
    public val serviceUuid: UUID = DEFAULT_SERVICE_UUID,
    /** What an advertised game's Bluetooth name starts with, 1 to 4 ASCII characters; see the constructor parameter. */
    public val localNamePrefix: String = GameConstants.LOCAL_NAME_PREFIX,
    /** The Bluetooth company identifier an Android host advertises the game code under, 0 to 0xFFFF. */
    public val manufacturerId: Int = DEFAULT_MANUFACTURER_ID,
    /** How many players a game can have, the host included: 2 to 10. */
    public val maxPlayers: Int = GameConstants.MAX_PLAYERS,
    /** How many lines the log's buffer keeps, at least 1. */
    public val logCapacity: Int = GameConstants.DEBUG_LOG_CAPACITY,
) {
    /** The games this app can play, in the order given to the constructor. */
    public val games: List<GameModule> = games.toList()

    init {
        require(maxPlayers in MIN_PLAYERS..GameConstants.MAX_PLAYERS) {
            "maxPlayers must be $MIN_PLAYERS to ${GameConstants.MAX_PLAYERS}, was $maxPlayers"
        }
        require(logCapacity >= 1) { "logCapacity must be at least 1, was $logCapacity" }
        require(localNamePrefix.length in 1..MAX_PREFIX_LENGTH && localNamePrefix.all { it.code <= MAX_ASCII }) {
            "localNamePrefix must be 1 to $MAX_PREFIX_LENGTH ASCII characters, was \"$localNamePrefix\""
        }
        require(manufacturerId in 0..MAX_COMPANY_ID) { "manufacturerId must be 0 to 0xFFFF, was $manufacturerId" }
    }

    /** The `GameInfo` characteristic (read). */
    internal val gameInfoUuid: UUID get() = characteristicUuid(GAME_INFO_OFFSET)

    /** The client → host characteristic (write with response). */
    internal val clientToHostUuid: UUID get() = characteristicUuid(CLIENT_TO_HOST_OFFSET)

    /** The host → client characteristic (notify). */
    internal val hostToClientUuid: UUID get() = characteristicUuid(HOST_TO_CLIENT_OFFSET)

    /** The module with this wire `game` value, or null if this app does not have it. */
    internal fun findGame(id: String): GameModule? = games.firstOrNull { it.id == id }

    /** [serviceUuid] with [offset] added to its first 32-bit field (wrapping around). */
    private fun characteristicUuid(offset: Int): UUID {
        val high = serviceUuid.mostSignificantBits
        val firstField = ((high ushr FIRST_FIELD_SHIFT) + offset) and FIELD_MASK
        return UUID((firstField shl FIRST_FIELD_SHIFT) or (high and FIELD_MASK), serviceUuid.leastSignificantBits)
    }

    /** The default values of the Bluetooth identifiers. */
    public companion object {
        /** The default service UUID, `6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E`. */
        @JvmField
        public val DEFAULT_SERVICE_UUID: UUID = UUID.fromString("6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E")

        /** The default company identifier, 0xFFFF, which the Bluetooth SIG reserves for testing. */
        public const val DEFAULT_MANUFACTURER_ID: Int = 0xFFFF

        private const val MIN_PLAYERS = 2
        private const val MAX_PREFIX_LENGTH = 4
        private const val MAX_ASCII = 0x7F
        private const val MAX_COMPANY_ID = 0xFFFF
        private const val GAME_INFO_OFFSET = 1
        private const val CLIENT_TO_HOST_OFFSET = 2
        private const val HOST_TO_CLIENT_OFFSET = 3
        private const val FIRST_FIELD_SHIFT = 32
        private const val FIELD_MASK = 0xFFFFFFFFL
    }
}
