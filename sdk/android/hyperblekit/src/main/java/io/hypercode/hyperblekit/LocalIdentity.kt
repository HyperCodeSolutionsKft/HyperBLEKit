package io.hypercode.hyperblekit

import android.content.Context
import io.hypercode.hyperblekit.protocol.truncatedPlayerName
import java.util.UUID

/** Where [LocalIdentity] keeps its two values: SharedPreferences on a phone, a map in tests. */
internal interface IdentityStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Who this device is in a game ([HyperBleKit.identity]): a permanent random device ID and the
 * player's name. Both are stored on the phone (in the app's private SharedPreferences file
 * "local_identity") and survive app restarts, but not reinstalling the app or clearing its data.
 *
 * The device ID is how a host recognises a returning player and gives them their seat back;
 * Bluetooth addresses are never used for that, because phones change them.
 *
 * Use it on the main thread.
 */
public class LocalIdentity internal constructor(private val store: IdentityStore) {

    internal constructor(context: Context) : this(
        object : IdentityStore {
            private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            override fun get(key: String): String? = prefs.getString(key, null)
            override fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
        }
    )

    /**
     * This installation's ID: 32 lowercase hexadecimal characters, generated randomly the first
     * time it is read and the same ever after. It is sent to the host of every game joined.
     */
    public val deviceId: String by lazy {
        store.get(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().replace("-", "").also {
            store.put(KEY_DEVICE_ID, it)
        }
    }

    /**
     * The name used while the user has not typed one: "And-" plus the first four characters of
     * [deviceId] in upper case, e.g. "And-3F9A". It tells phones apart before anyone has typed a
     * name; show it as the name field's placeholder.
     */
    public val defaultPlayerName: String get() = DEFAULT_NAME_PREFIX + deviceId.take(DEFAULT_NAME_ID_LENGTH).uppercase()

    /**
     * The name exactly as the user typed it, for the name field; empty if they typed none. Setting
     * it stores it at once. A name longer than [maxNameLength] characters is cut to that length,
     * so read the property back after setting it if the field should show what was stored.
     */
    public var storedPlayerName: String
        get() = store.get(KEY_PLAYER_NAME).orEmpty()
        set(value) = store.put(KEY_PLAYER_NAME, value.truncatedPlayerName())

    /**
     * The name the next session will use: [storedPlayerName] without surrounding spaces, or
     * [defaultPlayerName] if that is empty. It is read when a session starts ([HyperBleKit.host],
     * [HyperBleKit.join]) and again each time a session that was hosting becomes a client (after
     * stepping down or coming back from the background), so a name changed during a game can
     * show up then; otherwise a change only applies to the next session.
     */
    public val playerName: String get() = storedPlayerName.trim().ifEmpty { defaultPlayerName }

    /** The longest player name, in characters (Unicode code points): 12. Longer names are cut. */
    public val maxNameLength: Int get() = GameConstants.MAX_NAME_LENGTH

    private companion object {
        const val PREFS_NAME = "local_identity"
        const val KEY_DEVICE_ID = "deviceId"
        const val KEY_PLAYER_NAME = "playerName"
        const val DEFAULT_NAME_PREFIX = "And-"
        const val DEFAULT_NAME_ID_LENGTH = 4
    }
}
