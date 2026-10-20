package io.hypercode.hyperblekit.protocol

import io.hypercode.hyperblekit.JsonPayload
import io.hypercode.hyperblekit.Player
import io.hypercode.hyperblekit.GameConstants
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Compact UTF-8 JSON, the format of every message and of GameInfo (wire specification "JSON rules").
 *
 * Unknown fields are ignored, optional fields are omitted (never `null`), and every number is
 * written from an `Int` / `Long`, so nothing is ever emitted as a float. `org.json` escapes `/`
 * as `\/`, which is valid JSON and what the iOS encoder does too.
 */
internal object WireJson {

    fun encode(message: Message): ByteArray =
        JSONObject()
            .put("v", message.v)
            .put("type", message.type)
            .put("seq", message.seq)
            .put("from", message.from)
            .put("ts", message.ts)
            .put("data", data(message.body))
            .toBytes()

    /** Returns null when [bytes] are not a message envelope or a known type lacks a required field. */
    fun decodeMessage(bytes: ByteArray): Message? = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val type = json.getString("type")
        Message(
            v = json.getInt("v"),
            seq = json.getInt("seq"),
            from = json.getString("from"),
            ts = json.getLong("ts"),
            body = body(type, json.optJSONObject("data") ?: JSONObject()),
        )
    } catch (_: JSONException) {
        null
    }

    fun encode(info: GameInfo): ByteArray =
        JSONObject()
            .put("v", info.v)
            .put("gameId", info.gameId)
            .put("hostName", info.hostName)
            .put("players", info.players)
            .put("maxPlayers", info.maxPlayers)
            .put("lobbyOpen", info.lobbyOpen)
            .put("hostPlatform", info.hostPlatform)
            .put("hostId", info.hostId)
            .put("hostEpoch", info.hostEpoch)
            .toBytes()

    fun decodeGameInfo(bytes: ByteArray): GameInfo? = try {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        GameInfo(
            v = json.getInt("v"),
            gameId = json.getString("gameId"),
            hostName = json.getString("hostName"),
            players = json.getInt("players"),
            maxPlayers = json.getInt("maxPlayers"),
            lobbyOpen = json.getBoolean("lobbyOpen"),
            hostPlatform = json.getString("hostPlatform"),
            hostId = json.optString("hostId", GameConstants.HOST_PLAYER_ID),
            hostEpoch = json.optInt("hostEpoch", GameConstants.FIRST_HOST_EPOCH),
        )
    } catch (_: JSONException) {
        null
    }

    private fun JSONObject.toBytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)

    /** An optional field is omitted when it has no value, never written as `null`. */
    private fun JSONObject.putOptional(key: String, value: Any?): JSONObject =
        if (value == null) this else put(key, value)

    private fun JSONObject.isPresent(key: String): Boolean = has(key) && !isNull(key)

    private fun JSONObject.optionalString(key: String): String? = if (isPresent(key)) getString(key) else null

    private fun JSONObject.optionalPayload(key: String): JsonPayload? =
        if (isPresent(key)) JsonPayload(getJSONObject(key)) else null

    private fun data(body: Message.Body): JSONObject = when (body) {
        is Message.Body.JoinRequest -> JSONObject()
            .put("name", body.name)
            .put("platform", body.platform)
            .put("deviceId", body.deviceId)
            .put("canHost", body.canHost)
        is Message.Body.JoinAccept -> JSONObject()
            .put("playerId", body.playerId)
            .put("stateVersion", body.stateVersion)
        is Message.Body.JoinReject -> JSONObject().put("code", body.code)
        is Message.Body.StateSync -> JSONObject()
            .put("state", state(body.state))
            .putOptional("game", body.game)
            .putOptional("view", body.view?.toJson())
        is Message.Body.PlayerList -> JSONObject()
            .put("event", body.event)
            .put("playerId", body.playerId)
            .put("stateVersion", body.stateVersion)
            .put("players", players(body.players))
        is Message.Body.Ping -> JSONObject()
            .put("pingTs", body.pingTs)
            .putOptional("stateVersion", body.stateVersion)
        is Message.Body.Pong -> JSONObject().put("pingTs", body.pingTs)
        is Message.Body.Ack -> JSONObject().put("ackSeq", body.ackSeq)
        is Message.Body.HostSnapshot -> JSONObject()
            .put("state", state(body.snapshot.state))
            .put("seats", seats(body.snapshot.seats))
            .put("successors", JSONArray(body.snapshot.successors))
            .putOptional("game", body.snapshot.game)
            .putOptional("gameState", body.snapshot.gameState?.toJson())
        is Message.Body.HostLeaving -> JSONObject().put("nextHostId", body.nextHostId)
        is Message.Body.GameOver -> JSONObject().put("reason", body.reason)
        is Message.Body.GameUpdate -> JSONObject()
            .put("stateVersion", body.stateVersion)
            .put("phase", body.phase)
            .putOptional("game", body.game)
            .putOptional("view", body.view?.toJson())
        is Message.Body.GameAction -> JSONObject()
            .put("game", body.game)
            .put("action", body.action.toJson())
        is Message.Body.Error -> JSONObject()
            .put("refSeq", body.refSeq)
            .put("code", body.code)
            .putOptional("reason", body.reason)
        is Message.Body.SyncRequest -> JSONObject().put("haveVersion", body.haveVersion)
        is Message.Body.LogUpload -> JSONObject()
            .put("part", body.part)
            .put("parts", body.parts)
            .put("lines", JSONArray(body.lines))
        Message.Body.Leave, Message.Body.LogRequest, is Message.Body.Unknown -> JSONObject()
    }

    private fun body(type: String, data: JSONObject): Message.Body = when (type) {
        MessageType.JOIN_REQUEST -> Message.Body.JoinRequest(
            name = data.getString("name"),
            platform = data.getString("platform"),
            deviceId = data.getString("deviceId"),
            canHost = data.optBoolean("canHost", false),
        )
        MessageType.JOIN_ACCEPT -> Message.Body.JoinAccept(
            playerId = data.getString("playerId"),
            stateVersion = data.getInt("stateVersion"),
        )
        MessageType.JOIN_REJECT -> Message.Body.JoinReject(data.getString("code"))
        MessageType.STATE_SYNC -> Message.Body.StateSync(
            state = state(data.getJSONObject("state")),
            game = data.optionalString("game"),
            view = data.optionalPayload("view"),
        )
        MessageType.PLAYER_LIST -> Message.Body.PlayerList(
            event = data.getString("event"),
            playerId = data.getString("playerId"),
            stateVersion = data.getInt("stateVersion"),
            players = players(data.getJSONArray("players")),
        )
        MessageType.LEAVE -> Message.Body.Leave
        MessageType.PING -> Message.Body.Ping(
            pingTs = data.getLong("pingTs"),
            stateVersion = if (data.isPresent("stateVersion")) data.getInt("stateVersion") else null,
        )
        MessageType.PONG -> Message.Body.Pong(data.getLong("pingTs"))
        MessageType.ACK -> Message.Body.Ack(data.getInt("ackSeq"))
        MessageType.HOST_SNAPSHOT -> Message.Body.HostSnapshot(
            HostSnapshot(
                state = state(data.getJSONObject("state")),
                seats = seats(data.getJSONObject("seats")),
                successors = strings(data.optJSONArray("successors")),
                game = data.optionalString("game"),
                gameState = data.optionalPayload("gameState"),
            )
        )
        MessageType.HOST_LEAVING -> Message.Body.HostLeaving(data.getString("nextHostId"))
        MessageType.GAME_OVER -> Message.Body.GameOver(data.getString("reason"))
        MessageType.GAME_STATE -> Message.Body.GameUpdate(
            stateVersion = data.getInt("stateVersion"),
            phase = data.getString("phase"),
            game = data.optionalString("game"),
            view = data.optionalPayload("view"),
        )
        MessageType.GAME_ACTION -> Message.Body.GameAction(
            game = data.getString("game"),
            action = JsonPayload(data.getJSONObject("action")),
        )
        MessageType.ERROR -> Message.Body.Error(
            refSeq = data.getInt("refSeq"),
            code = data.getString("code"),
            reason = data.optionalString("reason"),
        )
        MessageType.SYNC_REQUEST -> Message.Body.SyncRequest(data.getInt("haveVersion"))
        MessageType.LOG_REQUEST -> Message.Body.LogRequest
        MessageType.LOG_UPLOAD -> Message.Body.LogUpload(
            part = data.getInt("part"),
            parts = data.getInt("parts"),
            lines = strings(data.optJSONArray("lines")),
        )
        else -> Message.Body.Unknown(type)
    }

    private fun state(state: GameState): JSONObject =
        JSONObject()
            .put("gameId", state.gameId)
            .put("stateVersion", state.stateVersion)
            .put("phase", state.phase)
            .put("players", players(state.players))
            .put("hostId", state.hostId)
            .put("hostEpoch", state.hostEpoch)

    /** A missing array is an empty list. */
    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else List(array.length()) { array.getString(it) }

    private fun seats(seats: Map<String, String>): JSONObject {
        val json = JSONObject()
        for ((playerId, deviceId) in seats) json.put(playerId, JSONObject().put("deviceId", deviceId))
        return json
    }

    private fun seats(json: JSONObject): Map<String, String> {
        val seats = LinkedHashMap<String, String>()
        for (playerId in json.keys()) seats[playerId] = json.getJSONObject(playerId).getString("deviceId")
        return seats
    }

    private fun state(json: JSONObject): GameState =
        GameState(
            gameId = json.getString("gameId"),
            stateVersion = json.getInt("stateVersion"),
            phase = json.getString("phase"),
            players = players(json.getJSONArray("players")),
            hostId = json.optString("hostId", GameConstants.HOST_PLAYER_ID),
            hostEpoch = json.optInt("hostEpoch", GameConstants.FIRST_HOST_EPOCH),
        )

    private fun players(players: List<Player>): JSONArray {
        val array = JSONArray()
        for (player in players) {
            val json = JSONObject()
                .put("id", player.id)
                .put("name", player.name)
                .put("platform", player.platform)
                .put("connected", player.connected)
            if (player.handSize != null) json.put("handSize", player.handSize)
            json.put("canHost", player.canHost)
            array.put(json)
        }
        return array
    }

    private fun players(array: JSONArray): List<Player> =
        List(array.length()) { index ->
            val json = array.getJSONObject(index)
            Player(
                id = json.getString("id"),
                name = json.getString("name"),
                platform = json.getString("platform"),
                connected = json.getBoolean("connected"),
                handSize = if (json.isPresent("handSize")) json.getInt("handSize") else null,
                canHost = json.optBoolean("canHost", false),
            )
        }
}
