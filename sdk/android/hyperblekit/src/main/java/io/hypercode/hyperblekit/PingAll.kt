package io.hypercode.hyperblekit

/**
 * One client's answer to a ping-all ([SessionDiagnostics.pingAll]).
 *
 * @property playerId the client's player ID ("p1" … "p10").
 * @property rttMs its round-trip time in milliseconds, or null if it did not answer in time.
 */
public data class PingReply(val playerId: String, val rttMs: Long?)

/**
 * The outcome of one ping-all ([SessionDiagnostics.pingAllResult]): the host pinged every connected
 * client at once and collected each one's round trip. There is one [PingReply] per client that was
 * pinged, in seat order.
 *
 * While [pending] is true the host is still waiting for answers (replies show the clients pinged,
 * all with a null time); when it turns false the round is over and any client that did not answer
 * has a null [PingReply.rttMs]. Null in [SessionDiagnostics.pingAllResult] means no ping-all has run
 * on this device yet.
 */
public data class PingAllResult(val replies: List<PingReply>, val pending: Boolean)
