import Foundation

/// What travels for Wi-Fi Aware (wire specification, "Wi-Fi Aware data path"). No framework
/// types: the same bytes are checked against `protocol/test-vectors/wifi-aware.json` on both
/// platforms.
enum AwareWire {
    /// Version of everything here; a receiver ignores what carries another one.
    static let version: UInt8 = 1
    /// The first byte of a transport-control message. A JSON message never starts with it.
    static let controlMarker: UInt8 = 0x00
    /// Length of a host's instance ID and of a link token, in bytes.
    static let idLength = 8
    /// Length of a data-path passphrase, in characters (Wi-Fi Aware allows 8 to 63).
    static let passphraseLength = 32
    /// The first byte of the discovery message a client sends a host over Wi-Fi Aware: "H".
    static let discoveryHelloMarker: UInt8 = 0x48
    /// The GameInfo field an Aware-capable host adds: `"aware":1`.
    static let gameInfoField = "aware"

    /// The `hostPlatform` value of this build in GameInfo.
    static let thisPlatform = "ios"

    /// How a client on `platform` ("android" / "ios") reaches this host over Wi-Fi Aware, or
    /// nil if it should not ask: a host on the same platform by that platform's own scheme; a
    /// host on the other platform only by the paired scheme, only if the client allows
    /// `crossPlatform` and the host announces `"awarePaired":1`.
    static func scheme(for info: Data, platform: String, crossPlatform: Bool) -> AwareScheme? {
        guard hasAwareFlag(info),
              let object = try? JSONSerialization.jsonObject(with: info) as? [String: Any] else { return nil }
        if object["hostPlatform"] as? String == platform { return .native(for: platform) }
        if crossPlatform, (object[gameInfoPairedField] as? NSNumber)?.intValue == Int(version) { return .paired }
        return nil
    }

    /// Whether a message received over BLE is a transport-control message, not a protocol one.
    static func isControl(_ message: Data) -> Bool {
        message.first == controlMarker
    }

    /// The GameInfo field of a host that also takes clients by the paired (iPhone) scheme.
    static let gameInfoPairedField = "awarePaired"

    /// `info` (compact JSON, as the runtime encodes GameInfo) with `"aware":1` added as its
    /// last field, and `"awarePaired":1` after it if `paired`. Clients without Wi-Fi Aware
    /// ignore them, as they ignore every unknown field.
    static func withAwareFlag(_ info: Data, paired: Bool = false) -> Data {
        guard let text = String(data: info, encoding: .utf8), text.hasSuffix("}") else { return info }
        var body = String(text.dropLast())
        let separator = body.trimmingCharacters(in: .whitespaces).hasSuffix("{") ? "" : ","
        body += "\(separator)\"\(gameInfoField)\":\(version)"
        if paired { body += ",\"\(gameInfoPairedField)\":\(version)" }
        return Data((body + "}").utf8)
    }

    /// Whether a host's GameInfo says it takes clients over Wi-Fi Aware (this `version`).
    static func hasAwareFlag(_ info: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: info) as? [String: Any],
              let value = object[gameInfoField] as? NSNumber else { return false }
        return value.intValue == Int(version)
    }

    /// A host's publish service-specific info: `[version][instanceId]`.
    static func serviceInfo(instanceId: Data) -> Data {
        Data([version]) + instanceId
    }

    /// The instance ID in a publish service-specific info, or nil if it is not ours.
    static func parseServiceInfo(_ bytes: Data) -> Data? {
        let bytes = Data(bytes)
        guard bytes.count == 1 + idLength, bytes[0] == version else { return nil }
        return bytes.subdata(in: 1..<bytes.count)
    }

    /// The discovery message from a client: `["H"][version][token]`.
    static func discoveryHello(token: Data) -> Data {
        Data([discoveryHelloMarker, version]) + token
    }

    /// The token in a discovery message, or nil if it is not one.
    static func parseDiscoveryHello(_ bytes: Data) -> Data? {
        let bytes = Data(bytes)
        guard bytes.count == 2 + idLength, bytes[0] == discoveryHelloMarker, bytes[1] == version else { return nil }
        return bytes.subdata(in: 2..<bytes.count)
    }
}

/// How a client finds its host's data path (wire specification, "Wi-Fi Aware data path").
enum AwareScheme: Equatable {
    /// Android ↔ Android: instance ID, NAN discovery message, passphrase-secured data path.
    case nan
    /// With an iPhone: paired devices only, the host picked by the echo of the token.
    case paired

    /// The scheme two phones of `platform` use with each other.
    static func native(for platform: String) -> AwareScheme { platform == "ios" ? .paired : .nan }
}

/// Which path the messages of one direction of a link travel on.
enum DataPath: UInt8 {
    case ble = 0
    case wifiAware = 1
}

/// Why a host declines a client's `hello`.
enum DeclineReason: UInt8 {
    /// The host cannot use Wi-Fi Aware right now (off, not published yet, or not allowed).
    case unavailable = 1
    /// Every data path the host's chip supports is taken.
    case noFreeDataPath = 2
}

/// How a client reaches the host over Wi-Fi Aware; see `AwareControl.offer`.
struct AwareOffer: Equatable {
    /// Tells this host's publication apart from other hosts' nearby.
    let instanceId: Data
    /// Ties the Wi-Fi Aware connection to the BLE link the offer went over.
    let token: Data
    /// The data path's PSK passphrase.
    let passphrase: String
}

/// Transport-control messages: sent as ordinary (chunked) messages over BLE, told apart from
/// protocol messages by their first byte `AwareWire.controlMarker`. Layout `[0x00][type][body]`.
/// Only a peer that announced Wi-Fi Aware support ever receives one.
enum AwareControl: Equatable {
    /// Client → host: "I can use Wi-Fi Aware". `[0x00][0x01][version]`
    case hello(version: UInt8)
    /// Host → client. `[0x00][0x02][version][instanceId: 8][token: 8][passLen][passphrase]`
    case offer(AwareOffer)
    /// Host → client: no data path for this link. `[0x00][0x03][reason]`
    case decline(DeclineReason)
    /// Either way: "the messages I send after this one travel on `path`". `[0x00][0x04][path]`
    case pathSwitch(DataPath)
    /// Client → host: setting the data path up failed, release it. `[0x00][0x05]`
    case failed

    static let helloType: UInt8 = 0x01
    static let offerType: UInt8 = 0x02
    static let declineType: UInt8 = 0x03
    static let pathSwitchType: UInt8 = 0x04
    static let failedType: UInt8 = 0x05

    static var hello: AwareControl { .hello(version: AwareWire.version) }

    func encoded() -> Data {
        var out = Data([AwareWire.controlMarker])
        switch self {
        case .hello(let version):
            out.append(contentsOf: [Self.helloType, version])
        case .offer(let offer):
            precondition(offer.instanceId.count == AwareWire.idLength && offer.token.count == AwareWire.idLength)
            let passphrase = Data(offer.passphrase.utf8)
            precondition(passphrase.count <= Int(UInt8.max))
            out.append(contentsOf: [Self.offerType, AwareWire.version])
            out.append(offer.instanceId)
            out.append(offer.token)
            out.append(UInt8(passphrase.count))
            out.append(passphrase)
        case .decline(let reason):
            out.append(contentsOf: [Self.declineType, reason.rawValue])
        case .pathSwitch(let path):
            out.append(contentsOf: [Self.pathSwitchType, path.rawValue])
        case .failed:
            out.append(Self.failedType)
        }
        return out
    }

    /// The control message in `bytes`, or nil if it is malformed, of an unknown type or version.
    static func decode(_ bytes: Data) -> AwareControl? {
        let bytes = [UInt8](bytes)
        guard bytes.count >= 2, bytes[0] == AwareWire.controlMarker else { return nil }
        let body = Array(bytes[2...])
        switch bytes[1] {
        case helloType:
            return body.count == 1 ? .hello(version: body[0]) : nil
        case offerType:
            return decodeOffer(body)
        case declineType:
            guard body.count == 1 else { return nil }
            return .decline(DeclineReason(rawValue: body[0]) ?? .unavailable)
        case pathSwitchType:
            guard body.count == 1, let path = DataPath(rawValue: body[0]) else { return nil }
            return .pathSwitch(path)
        case failedType:
            return body.isEmpty ? .failed : nil
        default:
            return nil
        }
    }

    private static func decodeOffer(_ body: [UInt8]) -> AwareControl? {
        let idsEnd = 1 + 2 * AwareWire.idLength
        guard body.count >= idsEnd + 1, body[0] == AwareWire.version else { return nil }
        let passLength = Int(body[idsEnd])
        guard body.count == idsEnd + 1 + passLength,
              let passphrase = String(bytes: body[(idsEnd + 1)...], encoding: .utf8) else { return nil }
        return .offer(AwareOffer(instanceId: Data(body[1..<(1 + AwareWire.idLength)]),
                                 token: Data(body[(1 + AwareWire.idLength)..<idsEnd]),
                                 passphrase: passphrase))
    }
}

/// Records on the Wi-Fi Aware TCP stream: `[length: 4 bytes, big-endian][kind: 1][body]`, where
/// `length` counts the kind byte and the body.
enum AwareStream {
    /// Client → host, the first record: body = the link token. The host answers with the same
    /// record once it has tied the connection to its link.
    static let hello: UInt8 = 0x01
    /// A whole protocol message.
    static let message: UInt8 = 0x02
    /// A real-time frame's payload (no frameSeq: TCP neither reorders nor duplicates).
    static let frame: UInt8 = 0x03
    /// Nothing to say; keeps the other side's silence timeout from firing.
    static let keepalive: UInt8 = 0x04

    static let lengthSize = 4
    /// Longest record (kind + body) a receiver accepts; a longer one closes the connection.
    static let maxRecordLength = 256 * 1024
    /// A side that has sent nothing for this long sends a keepalive.
    static let keepaliveInterval: TimeInterval = 2
    /// A connection that received nothing for this long is dead.
    static let silenceTimeout: TimeInterval = 6

    static func encode(kind: UInt8, body: Data = Data()) -> Data {
        let length = 1 + body.count
        precondition(length <= maxRecordLength, "record of \(length) bytes is too long")
        var record = Data([UInt8(truncatingIfNeeded: length >> 24), UInt8(truncatingIfNeeded: length >> 16),
                           UInt8(truncatingIfNeeded: length >> 8), UInt8(truncatingIfNeeded: length), kind])
        record.append(body)
        return record
    }
}

/// One record read from the stream.
struct AwareRecord: Equatable {
    let kind: UInt8
    let body: Data
}

/// The stream carried something that is not a record: the connection must be closed.
struct AwareStreamError: Error, Equatable {
    let length: Int
}

/// Cuts the incoming byte stream into records; keeps a partial record for the next call.
struct AwareStreamDecoder {
    private var buffer = Data()

    /// The records completed by `bytes`, oldest first.
    mutating func feed(_ bytes: Data) throws -> [AwareRecord] {
        buffer.append(bytes)
        var records: [AwareRecord] = []
        var offset = buffer.startIndex
        while buffer.endIndex - offset >= AwareStream.lengthSize {
            let length = Int(buffer[offset]) << 24 | Int(buffer[offset + 1]) << 16
                | Int(buffer[offset + 2]) << 8 | Int(buffer[offset + 3])
            guard length >= 1, length <= AwareStream.maxRecordLength else { throw AwareStreamError(length: length) }
            guard buffer.endIndex - offset - AwareStream.lengthSize >= length else { break }
            let start = offset + AwareStream.lengthSize
            records.append(AwareRecord(kind: buffer[start], body: buffer.subdata(in: (start + 1)..<(start + length))))
            offset = start + length
        }
        buffer = buffer.subdata(in: offset..<buffer.endIndex)
        return records
    }
}
