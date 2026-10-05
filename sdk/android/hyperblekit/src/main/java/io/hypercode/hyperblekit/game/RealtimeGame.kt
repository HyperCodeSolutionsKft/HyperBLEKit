package io.hypercode.hyperblekit.game

import org.json.JSONObject

/**
 * The numbers a real-time game gives the kit. They are the same for every state of a game, so a
 * client can read them from the module without having a game instance.
 *
 * @property tickMillis the length of one tick of the world in milliseconds, e.g. 33 for about 30
 *   ticks a second. The host advances the world once per tick on a fixed schedule.
 * @property framePeriodTicks a world frame is sent to the clients every this many ticks, e.g. 2
 *   to send 15 frames a second at 30 ticks a second. At least 1. Bluetooth carries roughly 15 to
 *   30 small frames a second per client.
 * @property worldKind the first byte of every world frame (host to client), 0 to 255. Do not use
 *   0x01 or 0x02, which the kit's speed test uses.
 * @property inputKind the first byte of every input frame (client to host), 0 to 255, different
 *   from [worldKind] and also not 0x01 or 0x02.
 * @property maxWorldFrameBytes the size in bytes of the largest world frame the game builds, kind
 *   byte included. A frame must fit one Bluetooth packet, so the kit refuses to start the game
 *   while a player's connection carries less than this. Up to 17 bytes always fit; around 240
 *   bytes fit on most links between current phones.
 */
public data class RealtimeSpec(
    public val tickMillis: Long,
    public val framePeriodTicks: Int,
    public val worldKind: Int,
    public val inputKind: Int,
    public val maxWorldFrameBytes: Int,
)

/**
 * A game whose world moves by itself, many times a second, instead of waiting for moves.
 *
 * Such a game has two parts. Its coarse state — scores, the round, who won — changes rarely and
 * travels like any game's: reliably, as [GameInstance.view] and [GameInstance.state]. Its world —
 * positions, speeds — changes on every tick and travels as small frames that are never repeated:
 * a frame that is lost or late is simply replaced by the next one. Players steer with input
 * frames in the other direction. The screen reaches both through
 * [io.hypercode.hyperblekit.RealtimeLink].
 *
 * The module itself adds only what the kit needs on a client, where no game instance exists.
 */
public interface RealtimeGameModule : GameModule {
    /** The game's timing and frame kinds. Must be the same on every phone. */
    public val realtime: RealtimeSpec

    /**
     * Reads the sequence number out of an input frame of this game. The kit uses it only for its
     * round-trip statistics in the log; a game without such a number returns null.
     *
     * @param input an input frame payload, kind byte first, as a screen sent it.
     * @return the number the frame carries, or null if it has none or is not an input frame.
     */
    public fun inputSeq(input: ByteArray): Int?

    /**
     * Reads, out of a world frame, the sequence number of the newest input the host had used for
     * one player when it built the frame. Together with [inputSeq] it lets the kit measure how
     * long an input takes to come back; a game that does not echo inputs returns null.
     *
     * @param worldFrame a world frame payload, kind byte first.
     * @param view the current view of the player in question, to tell which player it is.
     * @return the echoed number; null for a spectator, a game without echo, or a payload that is
     *   not a world frame.
     */
    public fun echoedInputSeq(worldFrame: ByteArray, view: JSONObject): Int?
}

/**
 * One state of a real-time game. [state] and [view] are the coarse state only; the moving world
 * is in [worldFrame]. Like every [GameInstance] it never changes: [input] and [tick] return the
 * next instance.
 *
 * Two things differ from a turn-based game. [GameModule.restore] cannot bring the moving world
 * back, since it is not part of [state]: restore to a sensible restart, for example a new round
 * with the scores kept. And the game is paused by the kit while one of its [players] is offline;
 * it moves again 3 seconds after the last of them is back.
 */
public interface RealtimeGameInstance : GameInstance {
    /** The same value as the module's [RealtimeGameModule.realtime]. */
    public val realtime: RealtimeSpec

    /**
     * The player IDs of those playing this game. Only their inputs are passed to [input], and
     * only they pause the game by going offline.
     */
    public val players: List<String>

    /**
     * Takes a player's newest input. It replaces an input of the same player that arrived since
     * the last [tick], and takes effect at the next one.
     *
     * @param playerId one of [players].
     * @param payload the input frame, kind byte included, as the player's screen sent it. It
     *   comes from another phone: check its length and content.
     * @return the game with that input noted; this same instance if nothing changes.
     */
    public fun input(playerId: String, payload: ByteArray): RealtimeGameInstance

    /**
     * Advances the world by one tick, using the inputs given since the last one. Called on the
     * main thread every [RealtimeSpec.tickMillis], so it must be fast. After the app was stalled
     * for up to a second the kit calls it several times in a row to catch up; after a longer
     * stall the missed ticks are skipped.
     */
    public fun tick(): RealtimeTick

    /**
     * The world as one frame for every player: the first byte is [RealtimeSpec.worldKind], the
     * rest is in the game's own format, at most [RealtimeSpec.maxWorldFrameBytes] bytes in all.
     * The same frame goes to every player, so it must not contain anything secret.
     */
    public fun worldFrame(): ByteArray
}

/**
 * The result of one [RealtimeGameInstance.tick].
 *
 * @property next the game after the tick.
 * @property coarseChanged true if the coarse state ([GameInstance.state], [GameInstance.view] or
 *   [GameInstance.finished]) differs from before the tick — a point was scored, the game ended.
 *   The kit then sends every player their new view reliably. Keep it false on ordinary ticks:
 *   each change costs a round of messages.
 */
public class RealtimeTick(
    public val next: RealtimeGameInstance,
    public val coarseChanged: Boolean,
)
