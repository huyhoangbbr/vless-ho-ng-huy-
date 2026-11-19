import Foundation

struct LinkSummary {
    let name: String
    let endpoint: String
    let transport: String
    let security: String

    init?(link: String) {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.utf8.count <= 32_768,
              !text.contains(where: \.isWhitespace),
              let parts = URLComponents(string: text),
              parts.scheme?.lowercased() == "vless",
              let user = parts.user, UUID(uuidString: user) != nil,
              parts.password == nil, parts.path.isEmpty || parts.path == "/",
              let host = parts.host, !host.isEmpty,
              let port = parts.port, (1...65_535).contains(port)
        else { return nil }

        let items = parts.queryItems ?? []
        guard items.count <= 100, Set(items.map(\.name)).count == items.count else { return nil }
        let label = parts.fragment?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        name = label.isEmpty ? "VLESS server" : Self.displayText(label)
        let address = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
        endpoint = "\(Self.displayText(address)):\(port)"
        transport = Self.displayText(items.first { $0.name == "type" }?.value ?? "tcp").uppercased()
        security = Self.displayText(items.first { $0.name == "security" }?.value ?? "none").uppercased()
    }

    private static func displayText(_ value: String) -> String {
        String(value.components(separatedBy: .controlCharacters).joined().prefix(160))
    }
}