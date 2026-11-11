import XCTest
@testable import HyperBleKit

/// The Wi-Fi Aware framing against `protocol/test-vectors/wifi-aware.json`, shared with Android.
final class WifiAwareVectorsTests: XCTestCase {
    private var vectors: [String: Any] = [:]

    override func setUpWithError() throws {
        vectors = try TestVectors.load("wifi-aware.json")
    }

    private func cases(_ key: String) -> [[String: Any]] {
        vectors[key] as? [[String: Any]] ?? []
    }

    private func control(_ json: [String: Any]) -> AwareControl {
        let int = { (key: String) in (json[key] as! NSNumber).uint8Value }
        switch json["type"] as! String {
        case "hello": return .hello(version: int("version"))
        case "offer":
            return .offer(AwareOffer(instanceId: TestVectors.bytes(json["instanceId"]!),
                                     token: TestVectors.bytes(json["token"]!),
                                     passphrase: json["passphrase"] as! String))
        case "decline": return .decline(DeclineReason(rawValue: int("reason"))!)
        case "pathSwitch": return .pathSwitch(DataPath(rawValue: int("path"))!)
        case "failed": return .failed
        case let other: fatalError("unknown control type \(other)")
        }
    }

    func testControlMessagesEncodeAndDecode() {
        XCTAssertFalse(cases("control").isEmpty)
        for vector in cases("control") {
            let name = vector["name"] as! String
            let expected = control(vector["control"] as! [String: Any])
            let bytes = TestVectors.bytes(vector["bytes"]!)
            XCTAssertEqual(expected.encoded(), bytes, name)
            XCTAssertTrue(AwareWire.isControl(bytes), name)
            XCTAssertEqual(AwareControl.decode(bytes), expected, name)
        }
    }

    func testMalformedControlMessagesDecodeToNil() {
        for vector in cases("invalidControl") {
            XCTAssertNil(AwareControl.decode(TestVectors.bytes(vector["bytes"]!)), vector["name"] as! String)
        }
    }

    func testStreamRecordsEncode() {
        for vector in cases("records") {
            let kind = (vector["kind"] as! NSNumber).uint8Value
            XCTAssertEqual(AwareStream.encode(kind: kind, body: TestVectors.bytes(vector["body"]!)),
                           TestVectors.bytes(vector["bytes"]!), vector["name"] as! String)
        }
    }

    func testAStreamFedInPiecesYieldsItsRecordsInOrder() throws {
        for vector in cases("streamSplit") {
            let name = vector["name"] as! String
            let stream = TestVectors.bytes(vector["stream"]!)
            let pieceSize = (vector["pieceSize"] as! NSNumber).intValue
            var decoder = AwareStreamDecoder()
            var records: [AwareRecord] = []
            var offset = 0
            while offset < stream.count {
                let end = min(offset + pieceSize, stream.count)
                records += try decoder.feed(stream.subdata(in: offset..<end))
                offset = end
            }
            let expected = (vector["records"] as! [[String: Any]]).map {
                AwareRecord(kind: ($0["kind"] as! NSNumber).uint8Value, body: TestVectors.bytes($0["body"]!))
            }
            XCTAssertEqual(records, expected, name)
        }
    }

    func testAStreamWithABadRecordLengthFails() {
        for vector in cases("invalidStream") {
            var decoder = AwareStreamDecoder()
            XCTAssertThrowsError(try decoder.feed(TestVectors.bytes(vector["stream"]!)), vector["name"] as! String)
        }
    }

    func testServiceInfoAndDiscoveryMessage() {
        let info = vectors["serviceInfo"] as! [String: Any]
        let instanceId = TestVectors.bytes(info["instanceId"]!)
        XCTAssertEqual(AwareWire.serviceInfo(instanceId: instanceId), TestVectors.bytes(info["bytes"]!))
        XCTAssertEqual(AwareWire.parseServiceInfo(TestVectors.bytes(info["bytes"]!)), instanceId)
        XCTAssertNil(AwareWire.parseServiceInfo(Data([2]) + instanceId))

        let hello = vectors["discoveryHello"] as! [String: Any]
        let token = TestVectors.bytes(hello["token"]!)
        XCTAssertEqual(AwareWire.discoveryHello(token: token), TestVectors.bytes(hello["bytes"]!))
        XCTAssertEqual(AwareWire.parseDiscoveryHello(TestVectors.bytes(hello["bytes"]!)), token)
        XCTAssertNil(AwareWire.parseDiscoveryHello(token))
    }

    func testTheGameInfoFlag() {
        for vector in cases("gameInfoFlag") {
            let out = AwareWire.withAwareFlag(Data((vector["in"] as! String).utf8), paired: vector["paired"] as? Bool ?? false)
            XCTAssertEqual(String(data: out, encoding: .utf8), vector["out"] as? String, vector["name"] as! String)
        }
        for vector in cases("hasGameInfoFlag") {
            XCTAssertEqual(AwareWire.hasAwareFlag(Data((vector["json"] as! String).utf8)),
                           vector["expected"] as! Bool, vector["name"] as! String)
        }
        XCTAssertFalse(cases("awareScheme").isEmpty)
        for vector in cases("awareScheme") {
            let scheme = AwareWire.scheme(for: Data((vector["json"] as! String).utf8), platform: vector["platform"] as! String,
                                          crossPlatform: vector["crossPlatform"] as! Bool)
            let name: String? = scheme.map { $0 == .nan ? "nan" : "paired" }
            XCTAssertEqual(name, vector["expected"] as? String, vector["name"] as! String)
        }
    }

    func testServiceNamesFollowTheSafeDNSSDSubset() {
        XCTAssertFalse(cases("serviceNames").isEmpty)
        for vector in cases("serviceNames") {
            let name = vector["name"] as! String
            XCTAssertEqual(HyperBleKitConfig.isValidServiceName(name), vector["valid"] as! Bool, name)
        }
    }
}
