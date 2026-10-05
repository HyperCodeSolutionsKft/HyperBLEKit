import XCTest
@testable import HyperBleKit

final class BLEConstantsTests: XCTestCase {
    func testCharacteristicsShareServiceBase() {
        let base = String(BLELayout.default.serviceUUID.uuidString.dropFirst(8))
        for uuid in [BLELayout.default.gameInfoUUID, BLELayout.default.clientToHostUUID, BLELayout.default.hostToClientUUID] {
            XCTAssertTrue(uuid.uuidString.hasSuffix(base))
        }
    }

    func testDefaultLayoutIsTheWireSpecLayout() {
        let layout = HyperBleKitConfig(games: []).layout
        XCTAssertEqual(layout, .default)
        XCTAssertEqual(layout.serviceUUID.uuidString, "6E4A0001-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(layout.gameInfoUUID.uuidString, "6E4A0002-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(layout.clientToHostUUID.uuidString, "6E4A0003-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(layout.hostToClientUUID.uuidString, "6E4A0004-B5A3-F393-E0A9-E50E24DCCA9E")
        XCTAssertEqual(layout.localNamePrefix, "CG-")
        XCTAssertEqual(layout.manufacturerCompanyId, 0xFFFF)
    }

    func testCharacteristicsAreDerivedFromTheServiceUUID() {
        let service = UUID(uuidString: "FFFFFFFE-0000-1000-8000-00805F9B34FB")!
        let layout = HyperBleKitConfig(games: [], serviceUUID: service, localNamePrefix: "XY-",
                                       manufacturerId: 0x1234).layout
        XCTAssertEqual(layout.gameInfoUUID.uuidString, "FFFFFFFF-0000-1000-8000-00805F9B34FB")
        // The first field wraps.
        XCTAssertEqual(layout.clientToHostUUID.uuidString, "00000000-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(layout.hostToClientUUID.uuidString, "00000001-0000-1000-8000-00805F9B34FB")
        XCTAssertEqual(GameCode.localName(for: "7K2F", layout: layout), "XY-7K2F")
        XCTAssertEqual(GameCode.from(localName: "XY-7K2F", layout: layout), "7K2F")
        XCTAssertNil(GameCode.from(localName: "CG-7K2F", layout: layout))
        XCTAssertEqual(GameCode.from(manufacturerData: Data([0x34, 0x12]) + Data("7K2F".utf8), layout: layout), "7K2F")
        XCTAssertNil(GameCode.from(manufacturerData: Data([0xFF, 0xFF]) + Data("7K2F".utf8), layout: layout))
    }
}
