package io.hypercode.hyperblekit.transport

import io.hypercode.hyperblekit.DiscoveredGame
import io.hypercode.hyperblekit.Platform

/**
 * Holds the current set of discovered games and keeps it fresh.
 *
 * Pure (no Android, no threads, clock injected per call) so the add / refresh / prune logic can be
 * unit-tested without a device. A transport implementation feeds it raw observations and publishes
 * the returned snapshots.
 *
 * Not thread-safe: the owning scanner must call it from a single thread.
 */
internal class DiscoveryRegistry(
    private val staleTimeoutMillis: Long,
) {
    private val games = LinkedHashMap<String, DiscoveredGame>()

    /**
     * Records that [id] was just seen, then returns the pruned, sorted snapshot.
     *
     * A re-observation refreshes `lastSeenMillis` and preserves `firstSeenMillis`. A known
     * platform is never overwritten by [Platform.UNKNOWN] (platform is learned later and shouldn't
     * be lost on a subsequent bare advertisement).
     */
    fun observe(
        id: String,
        gameCode: String,
        platform: Platform,
        rssi: Int?,
        nowMillis: Long,
    ): List<DiscoveredGame> {
        val existing = games[id]
        games[id] = DiscoveredGame(
            id = id,
            gameCode = gameCode,
            platform = if (platform == Platform.UNKNOWN && existing != null) existing.platform else platform,
            rssi = rssi,
            firstSeenMillis = existing?.firstSeenMillis ?: nowMillis,
            lastSeenMillis = nowMillis,
        )
        return prune(nowMillis)
    }

    /** Drops games not seen within the stale timeout and returns the snapshot, strongest signal first. */
    fun prune(nowMillis: Long): List<DiscoveredGame> {
        val iterator = games.entries.iterator()
        while (iterator.hasNext()) {
            val lastSeen = iterator.next().value.lastSeenMillis
            if (nowMillis - lastSeen > staleTimeoutMillis) iterator.remove()
        }
        return games.values.sortedByDescending { it.rssi ?: Int.MIN_VALUE }
    }

    fun clear() = games.clear()
}
