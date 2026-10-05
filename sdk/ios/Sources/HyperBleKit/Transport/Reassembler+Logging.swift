import Foundation

extension Reassembler {
    /// Feeds one packet and reports dropped or malformed chunks through `log`.
    /// Returns a frame at once, and a full message once its last chunk arrives.
    mutating func receive(_ packet: Data, log: (String) -> Void) -> Packet? {
        let droppedBefore = droppedMessageCount
        defer {
            if droppedMessageCount != droppedBefore {
                log("dropped partial message (dropped total=\(droppedMessageCount))")
            }
        }
        do {
            return try receivePacket(packet)
        } catch {
            log("ignored malformed chunk bytes=\(packet.count)")
            return nil
        }
    }
}
