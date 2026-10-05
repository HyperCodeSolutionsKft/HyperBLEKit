import Foundation

/// Debug-only "JSON send test": the wire specification's game-state snapshot, sent as a raw message
/// without the envelope and without a `seq`. Not part of the protocol; the Android library
/// sends and expects exactly the same bytes.
enum TestFrame {
    /// Type shown in the debug log.
    static let logType = "TEST_JSON"

    /// Sent byte for byte. Never rebuild it through Codable: key order and spacing must not change.
    static let json = #"{"gameId":"7K2F","stateVersion":12,"phase":"playing","players":[{"id":"p1","name":"Host","platform":"android","connected":true,"handSize":2},{"id":"p2","name":"Anna","platform":"ios","connected":true,"handSize":2},{"id":"p3","name":"Bence","platform":"android","connected":true,"handSize":4},{"id":"p4","name":"Dora","platform":"ios","connected":true,"handSize":3}],"turn":{"number":4,"playerId":"p4"},"topCard":{"id":"c21","color":"blue","value":4},"deckCount":22}"#

    static let data = Data(json.utf8)

    /// A received message is a test frame when it is a JSON object with `gameId` and without `type`
    /// (every protocol message has `type` in its envelope).
    static func isTestFrame(_ message: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: message) as? [String: Any] else { return false }
        return object["gameId"] != nil && object["type"] == nil
    }

    /// The end of the RX log line: did the bytes arrive unchanged?
    static func matchDetail(for received: Data) -> String {
        received == data ? "match=yes" : "match=no expected=\(data.count)"
    }

    /// The received bytes as indented text for the screen. Works on the raw text, so keys stay
    /// in the order they arrived. Anything that is not JSON is returned as it is.
    static func prettyText(_ received: Data) -> String {
        let text = String(decoding: received, as: UTF8.self)
        guard (try? JSONSerialization.jsonObject(with: received)) != nil else { return text }
        let indentUnit = "  "
        let raw = Array(text)
        var result = ""
        var depth = 0
        var inString = false
        var isEscaped = false
        func newLine() { result += "\n" + String(repeating: indentUnit, count: depth) }

        for (index, character) in raw.enumerated() {
            if inString {
                result.append(character)
                if isEscaped {
                    isEscaped = false
                } else if character == "\\" {
                    isEscaped = true
                } else if character == "\"" {
                    inString = false
                }
                continue
            }
            switch character {
            case "\"":
                inString = true
                result.append(character)
            case "{", "[":
                result.append(character)
                depth += 1
                // An empty object or array stays on one line.
                let next = raw[(index + 1)...].first { !$0.isWhitespace }
                if next != "}" && next != "]" { newLine() }
            case "}", "]":
                depth = max(0, depth - 1)
                if let last = result.last, last != "{", last != "[" { newLine() }
                result.append(character)
            case ",":
                result.append(character)
                newLine()
            case ":":
                result += ": "
            case _ where character.isWhitespace:
                break
            default:
                result.append(character)
            }
        }
        return result
    }

    /// What the screen shows after a test frame arrived.
    static func receivedSummary(_ received: Data, chunks: Int, from sender: String) -> String {
        let verdict = received == data ? "match" : "MISMATCH (expected \(data.count) B)"
        return "Test JSON: received \(received.count) B in \(chunks) chunks from \(sender) — \(verdict)"
    }
}
