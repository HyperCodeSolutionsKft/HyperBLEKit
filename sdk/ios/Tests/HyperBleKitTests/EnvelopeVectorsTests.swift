import XCTest
@testable import HyperBleKit

/// Validates the iOS envelope encode/decode against the shared `/protocol/test-vectors/envelope.json`.
/// JSON key order is not significant on the wire, so `data` is compared structurally via
/// `NSDictionary`/`NSArray` equality.
final class EnvelopeVectorsTests: XCTestCase {

    /// The re-encoded `data` object of a decoded message, as a JSON-serialized value.
    private func dataPayload(ofEncoded message: Message) throws -> Any {
        let object = try JSONSerialization.jsonObject(with: try message.encoded()) as! [String: Any]
        return object["data"]!
    }

    private func assertEnvelopeFields(_ c: [String: Any], _ message: Message, _ name: String) {
        XCTAssertEqual(message.v, c["v"] as! Int, "\(name): v")
        XCTAssertEqual(message.type, c["type"] as! String, "\(name): type")
        XCTAssertEqual(message.seq, c["seq"] as! Int, "\(name): seq")
        XCTAssertEqual(message.from, c["from"] as! String, "\(name): from")
        XCTAssertEqual(message.ts, (c["ts"] as! NSNumber).int64Value, "\(name): ts")
    }

    func testDecodeCasesParseTheFieldsAndDataPayload() throws {
        let cases = try TestVectors.load("envelope.json")["decode"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let message = try Message.decode((c["json"] as! String).data(using: .utf8)!)
            assertEnvelopeFields(c, message, name)

            let expected = try JSONSerialization.jsonObject(with: (c["dataJson"] as! String).data(using: .utf8)!)
            let actual = try dataPayload(ofEncoded: message)
            XCTAssertTrue((actual as! NSObject).isEqual(expected as! NSObject), "\(name): data payload")
        }
    }

    func testRoundTripCasesSurviveEncodeThenDecode() throws {
        let cases = try TestVectors.load("envelope.json")["roundTrip"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            let dataObject = try JSONSerialization.jsonObject(with: (c["dataJson"] as! String).data(using: .utf8)!)
            let envelope: [String: Any] = [
                "v": c["v"]!, "type": c["type"]!, "seq": c["seq"]!,
                "from": c["from"]!, "ts": c["ts"]!, "data": dataObject,
            ]
            let wire = try JSONSerialization.data(withJSONObject: envelope)

            let first = try Message.decode(wire)
            assertEnvelopeFields(c, first, name)
            let second = try Message.decode(try first.encoded())
            XCTAssertEqual(first, second, "\(name): round trip")
        }
    }

    func testInvalidDecodeCasesAreRejected() throws {
        let cases = try TestVectors.load("envelope.json")["invalidDecode"] as! [[String: Any]]
        XCTAssertFalse(cases.isEmpty)
        for c in cases {
            let name = c["name"] as! String
            XCTAssertThrowsError(try Message.decode((c["json"] as! String).data(using: .utf8)!), name)
        }
    }
}
