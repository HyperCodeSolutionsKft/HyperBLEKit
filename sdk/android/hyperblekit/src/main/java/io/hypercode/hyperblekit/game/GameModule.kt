package io.hypercode.hyperblekit.game

import org.json.JSONObject
import kotlin.random.Random

/**
 * The rules of one game, written by the app and given to the kit in
 * [io.hypercode.hyperblekit.HyperBleKitConfig.games].
 *
 * A module is pure logic: no Bluetooth, no screen, no clock, no threads. Only the host runs it.
 * The kit starts a game through it, passes every player's moves to it and sends each player the
 * view the game gives for them. Everything that crosses between the kit and a module is a plain
 * JSON object in the module's own format — a move (`action`), what one player may see (`view`),
 * and the whole game (`state`) — which the kit passes on without reading it. The same game
 * written for Android and for iOS must use the same JSON, so mixed phones can play together.
 *
 * Modules are called on the main thread and must return quickly.
 */
public interface GameModule {
    /**
     * The game's identifier, e.g. "chess". It is sent between phones, so it must be the same in
     * every build of the app, unique among the app's games, and never change.
     */
    public val id: String

    /** The game's name for the screen, e.g. "Chess". */
    public val title: String

    /** The supported numbers of players as text for the screen, e.g. "2–4". */
    public val playerCountLabel: String

    /**
     * Whether the game can be played by this many players. The kit starts a game only with a
     * supported number of connected players.
     *
     * @param playerCount the number of players, the host included.
     */
    public fun supports(playerCount: Int): Boolean

    /**
     * Starts a new game.
     *
     * @param playerIds the players, e.g. `["p1", "p2", "p4"]`, in ascending seat order; a number
     *   of players that [supports] accepted.
     * @param random the only source of randomness the game may use (shuffling, dice), so that a
     *   test can make a game repeatable.
     * @return the game in its first state.
     */
    public fun start(playerIds: List<String>, random: Random): GameInstance

    /**
     * Continues a game from a state another phone exported with [GameInstance.state]. Called on
     * a phone that takes the game over from a host that left or disappeared.
     *
     * @param state the JSON a [GameInstance.state] call returned, possibly on the other platform.
     * @return the game in that state, or null if the state cannot be read; the session then goes
     *   back to the lobby.
     */
    public fun restore(state: JSONObject): GameInstance?
}

/**
 * One game in one state. An instance never changes: [apply] and [removePlayer] return the next
 * state as a new instance, and the kit keeps whichever is current.
 */
public interface GameInstance {
    /**
     * True once the game is over. The kit then shows the phase `finished` and passes on no more
     * moves; the final [view] stays on screen until the host starts another game or ends this one.
     */
    public val finished: Boolean

    /**
     * Applies one player's move.
     *
     * @param playerId who made the move. It may be anyone in the session, also a player who is
     *   not in this game, and it may not be their turn: the game checks.
     * @param action the move in the game's own JSON format, exactly as the player's screen gave
     *   it to [io.hypercode.hyperblekit.GameSession.act]. It comes from another phone: check it.
     * @param random the kit's randomness, for a game whose moves need it (a new deal).
     * @return [GameOutcome.Accepted] with the next state, or [GameOutcome.Rejected] if the move is
     *   not allowed; then nothing changes.
     */
    public fun apply(playerId: String, action: JSONObject, random: Random): GameOutcome

    /**
     * What one player is allowed to see of this state, in the game's own JSON format; this is
     * what arrives in [io.hypercode.hyperblekit.SessionState.view] on that player's phone. Leave
     * out what the player must not know (other players' cards).
     *
     * If the view names the player whose turn it is in a top-level string `turn`, apps can find
     * out generically whose turn it is.
     *
     * @param playerId the player the view is for. For someone who is not in the game, return a
     *   spectator's view.
     */
    public fun view(playerId: String): JSONObject

    /**
     * The whole game as JSON, hidden information included, so that [GameModule.restore] can
     * continue it on another phone. The host sends it to the phones that may take the game over.
     * Keep it small: it travels over Bluetooth after every move.
     */
    public fun state(): JSONObject

    /**
     * A player is gone for good: they stayed offline too long, or the host removed them.
     *
     * @param playerId the player who left. For a player who is not in the game, return this same
     *   instance.
     * @return the game without that player (it may now be [finished]), or null if the game cannot
     *   go on without them; the session then goes back to the lobby.
     */
    public fun removePlayer(playerId: String): GameInstance?
}

/** What became of a move: the result of [GameInstance.apply]. */
public sealed interface GameOutcome {
    /**
     * The move was made.
     *
     * @property next the game after the move. Every player gets their new view of it.
     */
    public data class Accepted(val next: GameInstance) : GameOutcome

    /**
     * The move is not allowed and nothing changed. Only the player who made it is told.
     *
     * @property code a short machine-readable reason, e.g. "not_your_turn". It is sent to the
     *   player and shown in [io.hypercode.hyperblekit.SessionState.gameNotice].
     * @property reason optional English detail, shown after the code.
     */
    public data class Rejected(val code: String, val reason: String? = null) : GameOutcome
}
