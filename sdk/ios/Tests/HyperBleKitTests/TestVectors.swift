import Foundation

/// Loads the language-neutral vectors from `/protocol/test-vectors`, shared with the Android
/// library. They are how Android/iOS wire mismatches are caught without real hardware. The
/// file lives in the repository, not the test bundle, so the directory is found by walking up
/// from this source file's path.
enum TestVectors {

    struct NotFound: Error, CustomStringConvertible {
        let fileName: String
        var description: String { "could not find protocol/test-vectors/\(fileName)" }
    }

    static func load(_ fileName: String, file: String = #filePath) throws -> [String: Any] {
        var dir = URL(fileURLWithPath: file).deletingLastPathComponent()
        for _ in 0..<8 {
            let candidate = dir.appendingPathComponent("protocol/test-vectors/\(fileName)")
            if FileManager.default.fileExists(atPath: candidate.path) {
                let data = try Data(contentsOf: candidate)
                guard let dict = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw NotFound(fileName: fileName)
                }
                return dict
            }
            dir = dir.deletingLastPathComponent()
        }
        throw NotFound(fileName: fileName)
    }

    /// A JSON array of integers 0-255 as the raw bytes it represents.
    static func bytes(_ any: Any) -> Data {
        Data((any as? [Any] ?? []).map { UInt8(truncating: $0 as! NSNumber) })
    }
}
