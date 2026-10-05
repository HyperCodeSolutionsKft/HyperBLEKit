import CoreBluetooth
import XCTest
@testable import HyperBleKit

final class GameCodeTests: XCTestCase {
    func testRandomCodesAreValid() {
        for _ in 0..<200 {
            XCTAssertTrue(GameCode.isValid(GameCode.random()))
        }
    }

    func testValidation() {
        XCTAssertTrue(GameCode.isValid("7K2F"))
        XCTAssertFalse(GameCode.isValid("7K2"))
        XCTAssertFalse(GameCode.isValid("7K2FF"))
        XCTAssertFalse(GameCode.isValid("7k2f"))
        XCTAssertFalse(GameCode.isValid("O1I0"), "ambiguous characters are not in the alphabet")
    }

    func testLocalNameRoundTrip() {
        XCTAssertEqual(GameCode.localName(for: "7K2F", layout: .default), "CG-7K2F")
        XCTAssertEqual(GameCode.from(localName: "CG-7K2F", layout: .default), "7K2F")
        XCTAssertNil(GameCode.from(localName: "Galaxy S23", layout: .default))
        XCTAssertNil(GameCode.from(localName: "CG-7K2", layout: .default))
        XCTAssertNil(GameCode.from(localName: nil, layout: .default))
    }

    func testManufacturerData() {
        XCTAssertEqual(GameCode.from(manufacturerData: Data([0xFF, 0xFF]) + Data("7K2F".utf8), layout: .default), "7K2F")
        XCTAssertNil(GameCode.from(manufacturerData: Data([0x4C, 0x00]) + Data("7K2F".utf8), layout: .default), "other company ID")
        XCTAssertNil(GameCode.from(manufacturerData: Data([0xFF, 0xFF]) + Data("7K".utf8), layout: .default))
        XCTAssertNil(GameCode.from(manufacturerData: Data([0xFF, 0xFF]) + Data("7K2FX".utf8), layout: .default))
        XCTAssertNil(GameCode.from(manufacturerData: Data([0xFF, 0xFF, 0xFF, 0xFE, 0x00, 0x01]), layout: .default))
        XCTAssertNil(GameCode.from(manufacturerData: nil, layout: .default))
    }

    func testAdvertisementFromIOSHost() {
        let ad: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "CG-7K2F",
            CBAdvertisementDataServiceUUIDsKey: [BLELayout.default.serviceUUID],
        ]
        XCTAssertEqual(GameCode.from(advertisementData: ad, layout: .default), "7K2F")
    }

    func testAdvertisementFromAndroidHostIgnoresDeviceName() {
        let ad: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "Galaxy S23",
            CBAdvertisementDataManufacturerDataKey: Data([0xFF, 0xFF]) + Data("Q9MX".utf8),
            CBAdvertisementDataServiceUUIDsKey: [BLELayout.default.serviceUUID],
        ]
        XCTAssertEqual(GameCode.from(advertisementData: ad, layout: .default), "Q9MX")
    }

    func testAdvertisementWithoutCodeIsIgnored() {
        let ad: [String: Any] = [
            CBAdvertisementDataLocalNameKey: "Galaxy S23",
            CBAdvertisementDataServiceUUIDsKey: [BLELayout.default.serviceUUID],
        ]
        XCTAssertNil(GameCode.from(advertisementData: ad, layout: .default))
    }
}
