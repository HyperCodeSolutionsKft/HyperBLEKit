import CoreBluetooth

/// The 4-char code that identifies a game in advertisements. See the wire specification "Advertising".
enum GameCode {
    static let length = 4
    /// No 0/O and 1/I, so codes are easy to read aloud.
    static let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")

    static func random() -> String {
        var generator = SystemRandomNumberGenerator()
        return random(using: &generator)
    }

    static func random<G: RandomNumberGenerator>(using generator: inout G) -> String {
        String((0..<length).map { _ in alphabet.randomElement(using: &generator)! })
    }

    static func isValid(_ code: String) -> Bool {
        code.count == length && code.allSatisfy(alphabet.contains)
    }

    // MARK: - Advertising (iOS host)

    /// Local name an iOS host advertises, e.g. "CG-7K2F".
    static func localName(for code: String, layout: BLELayout) -> String {
        layout.localNamePrefix + code
    }

    // MARK: - Scanning

    /// Game code from a scan result: manufacturer data (Android host) first, then local name (iOS host).
    static func from(advertisementData: [String: Any], layout: BLELayout) -> String? {
        from(manufacturerData: advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data, layout: layout)
            ?? from(localName: advertisementData[CBAdvertisementDataLocalNameKey] as? String, layout: layout)
    }

    static func from(localName: String?, layout: BLELayout) -> String? {
        guard let localName, localName.hasPrefix(layout.localNamePrefix) else { return nil }
        let code = String(localName.dropFirst(layout.localNamePrefix.count))
        return isValid(code) ? code : nil
    }

    /// Android host: company ID (little-endian) followed by the 4 ASCII code bytes.
    static func from(manufacturerData: Data?, layout: BLELayout) -> String? {
        guard let manufacturerData, manufacturerData.count == 2 + length else { return nil }
        let companyId = UInt16(manufacturerData[manufacturerData.startIndex])
            | UInt16(manufacturerData[manufacturerData.startIndex + 1]) << 8
        guard companyId == layout.manufacturerCompanyId,
              let code = String(data: manufacturerData.dropFirst(2), encoding: .ascii)
        else { return nil }
        return isValid(code) ? code : nil
    }
}
