package io.hypercode.hyperblekit.fake

import io.hypercode.hyperblekit.game.GameInstance
import io.hypercode.hyperblekit.game.GameModule
import io.hypercode.hyperblekit.game.GameOutcome
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * The smallest game that exercises the engine: the players take turns adding 1 to a shared count,
 * and the game is finished when the count reaches [CounterGame.TARGET]. An action is
 * `{"add": 1}`; out of turn it is rejected with `not_your_turn`, anything else with `bad_action`.
 * The view is `{"count": n, "turn": "p2", "you": "p3"}`.
 */
internal object CounterGame : GameModule {
    const val TARGET = 5
    override val id = "counter"
    override val title = "Counter"
    override val playerCountLabel = "2–3"

    override fun supports(playerCount: Int): Boolean = playerCount in 2..3

    override fun start(playerIds: List<String>, random: Random): GameInstance = Counter(playerIds, 0, 0)

    override fun restore(state: JSONObject): GameInstance? {
        val players = state.optJSONArray("players") ?: return null
        return Counter(List(players.length(), players::getString), state.getInt("turn"), state.getInt("count"))
    }

    fun add(): JSONObject = JSONObject().put("add", 1)

    private class Counter(val players: List<String>, val turn: Int, val count: Int) : GameInstance {
        override val finished: Boolean get() = count >= TARGET || players.size < 2

        override fun apply(playerId: String, action: JSONObject, random: Random): GameOutcome = when {
            action.optInt("add") != 1 -> GameOutcome.Rejected("bad_action")
            finished -> GameOutcome.Rejected("game_over")
            players[turn] != playerId -> GameOutcome.Rejected("not_your_turn", "it is ${players[turn]}'s turn")
            else -> GameOutcome.Accepted(Counter(players, (turn + 1) % players.size, count + 1))
        }

        override fun view(playerId: String): JSONObject =
            JSONObject().put("count", count).put("turn", players[turn]).put("you", playerId)

        override fun state(): JSONObject =
            JSONObject().put("players", JSONArray(players)).put("turn", turn).put("count", count)

        override fun removePlayer(playerId: String): GameInstance? {
            if (playerId !in players) return this
            val left = players - playerId
            if (left.isEmpty()) return null
            return Counter(left, turn % left.size, count)
        }
    }
}
