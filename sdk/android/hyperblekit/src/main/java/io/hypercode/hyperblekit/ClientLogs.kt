package io.hypercode.hyperblekit

/**
 * One client's debug log as collected by the host ([ClientLogCollection.logs]).
 *
 * The lines are the client's own log lines, in the order they appear on its screen, reassembled
 * from the batches it uploaded. [linesReceived] vs [batchesExpected] shows how much has arrived;
 * [complete] is true once every batch the client announced has been received.
 *
 * @property playerId the client's player ID ("p2" … "p10").
 * @property name the player's name, from the host's roster.
 * @property platform the player's platform ("android" / "ios"), from the host's roster.
 * @property lines the log lines gathered so far, oldest first.
 * @property batchesReceived how many upload batches have arrived.
 * @property batchesExpected how many batches the client said it would send (0 until the first arrives).
 */
public data class CollectedClientLog(
    val playerId: String,
    val name: String,
    val platform: String,
    val lines: List<String>,
    val batchesReceived: Int,
    val batchesExpected: Int,
) {
    /** Every announced batch has arrived. */
    public val complete: Boolean get() = batchesExpected > 0 && batchesReceived >= batchesExpected
}

/**
 * What the host has collected from its clients after [SessionDiagnostics.requestClientLogs]: one
 * [CollectedClientLog] per client that has uploaded anything, in seat order. The collection fills
 * live as batches arrive.
 *
 * [expectedClients] is how many clients were asked (the linked clients at the moment of the
 * request); [pending] is true while not every expected client has sent a [CollectedClientLog.complete]
 * log. Null in [SessionDiagnostics.clientLogs] means no collection has been requested on this device
 * (it is also null on a client — collection is host-only).
 */
public data class ClientLogCollection(
    val logs: List<CollectedClientLog>,
    val expectedClients: Int,
    val pending: Boolean,
)
