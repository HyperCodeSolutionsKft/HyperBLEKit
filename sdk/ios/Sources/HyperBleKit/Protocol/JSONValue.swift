import Foundation

/// Any JSON value. The SDK carries a game module's moves, views and states as this, without
/// reading them, so a module is free to choose its own format. All numbers are integers:
/// there is no floating-point case, and decoding JSON that contains a fraction fails.
public enum JSONValue: Equatable {
    /// A JSON object: members by key.
    case object([String: JSONValue])
    /// A JSON array.
    case array([JSONValue])
    /// A JSON string.
    case string(String)
    /// A JSON number, always an integer.
    case int(Int)
    /// `true` or `false`.
    case bool(Bool)
    /// JSON `null`.
    case null

    /// The member `key` of an object; nil when there is no such member or this is not an object.
    public subscript(key: String) -> JSONValue? {
        if case .object(let members) = self { return members[key] }
        return nil
    }

    /// The text of a string value; nil for every other case.
    public var stringValue: String? {
        if case .string(let value) = self { return value }
        return nil
    }
}

extension JSONValue: Codable {
    /// Decodes any JSON value. Throws for a number that is not an integer.
    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    /// Encodes the value as the JSON it stands for.
    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .int(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
}

extension JSONValue {
    /// Converts an `Encodable` value to its JSON form, for a game module that keeps its state
    /// in Swift types. Throws when the value cannot be encoded or contains a number that is
    /// not an integer.
    public init<T: Encodable>(encoding value: T) throws {
        self = try WireJSON.decode(JSONValue.self, from: try WireJSON.encode(value))
    }

    /// Reads this value as a `Decodable` type; throws if it does not have that type's shape.
    public func decoded<T: Decodable>(as type: T.Type) throws -> T {
        try WireJSON.decode(type, from: try WireJSON.encode(self))
    }
}
