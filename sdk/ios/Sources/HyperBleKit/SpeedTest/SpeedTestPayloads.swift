import Foundation

/// `0x01` world frame payload, host → client. All numbers big-endian, unsigned.
struct SpeedTestWorld: Equatable {
    /// Host tick counter, 30 ticks a second.
    var tick: UInt32
    /// The host's monotonic clock, ms, wrapping.
    var hostMillis: UInt32
    var x: UInt16
    var y: UInt16
    /// `inputSeq` of the last input taken from this client, 0 before any.
    var echoInputSeq: UInt8
    /// `clientMillis` of that input.
    var echoClientMillis: UInt32
    /// ms between taking that input and building this frame, capped at 65535.
    var holdMillis: UInt16

    /// The payload, padded with zeros to `size` bytes (never shorter than its fields).
    func encoded(size: Int) -> Data {
        var data = Data([SpeedTestConstants.worldKind])
        data.appendBigEndian(tick)
        data.appendBigEndian(hostMillis)
        data.appendBigEndian(x)
        data.appendBigEndian(y)
        data.append(echoInputSeq)
        data.appendBigEndian(echoClientMillis)
        data.appendBigEndian(holdMillis)
        if size > data.count { data.append(Data(count: size - data.count)) }
        return data
    }

    /// nil when the payload is not a world frame or is too short. Padding is ignored.
    init?(payload: Data) {
        let bytes = [UInt8](payload)
        guard bytes.count >= SpeedTestConstants.worldFieldsLength,
              bytes[0] == SpeedTestConstants.worldKind else { return nil }
        tick = bytes.bigEndian(at: 1)
        hostMillis = bytes.bigEndian(at: 5)
        x = bytes.bigEndian(at: 9)
        y = bytes.bigEndian(at: 11)
        echoInputSeq = bytes[13]
        echoClientMillis = bytes.bigEndian(at: 14)
        holdMillis = bytes.bigEndian(at: 18)
    }

    init(tick: UInt32, hostMillis: UInt32, x: UInt16, y: UInt16, echoInputSeq: UInt8,
         echoClientMillis: UInt32, holdMillis: UInt16) {
        self.tick = tick
        self.hostMillis = hostMillis
        self.x = x
        self.y = y
        self.echoInputSeq = echoInputSeq
        self.echoClientMillis = echoClientMillis
        self.holdMillis = holdMillis
    }
}

/// `0x02` input frame payload, client → host. All numbers big-endian, unsigned.
struct SpeedTestInput: Equatable {
    /// +1 per input sent, wrapping.
    var inputSeq: UInt8
    /// The client's monotonic clock, ms, wrapping.
    var clientMillis: UInt32
    /// Taps on the client's field so far, wrapping.
    var taps: UInt8

    func encoded() -> Data {
        var data = Data([SpeedTestConstants.inputKind, inputSeq])
        data.appendBigEndian(clientMillis)
        data.append(taps)
        return data
    }

    /// nil when the payload is not an input frame or is too short.
    init?(payload: Data) {
        let bytes = [UInt8](payload)
        guard bytes.count >= SpeedTestConstants.inputLength,
              bytes[0] == SpeedTestConstants.inputKind else { return nil }
        inputSeq = bytes[1]
        clientMillis = bytes.bigEndian(at: 2)
        taps = bytes[6]
    }

    init(inputSeq: UInt8, clientMillis: UInt32, taps: UInt8) {
        self.inputSeq = inputSeq
        self.clientMillis = clientMillis
        self.taps = taps
    }
}

private extension Data {
    mutating func appendBigEndian<T: FixedWidthInteger>(_ value: T) {
        Swift.withUnsafeBytes(of: value.bigEndian) { append(contentsOf: $0) }
    }
}

private extension Array where Element == UInt8 {
    func bigEndian<T: FixedWidthInteger & UnsignedInteger>(at offset: Int) -> T {
        self[offset..<offset + MemoryLayout<T>.size].reduce(T(0)) { $0 << 8 | T($1) }
    }
}
