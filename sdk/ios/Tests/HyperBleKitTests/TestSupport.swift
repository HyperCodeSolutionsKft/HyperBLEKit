import Foundation
@testable import HyperBleKit

extension BLELayout {
    /// The layout of a default `HyperBleKitConfig`: the wire spec's UUIDs, "CG-" and 0xFFFF.
    static let `default` = HyperBleKitConfig(games: []).layout
}
