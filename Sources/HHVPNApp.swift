import SwiftUI
import UIKit

@main
struct HHVPNApp: App {
    var body: some Scene {
        WindowGroup {
            Dashboard()
                .preferredColorScheme(.dark)
        }
    }
}

private enum Palette {
    static let background = Color(rgb: 0x151122)
    static let panel = Color(rgb: 0x221b34)
    static let input = Color(rgb: 0x181329)
    static let text = Color(rgb: 0xf8f3ff)
    static let muted = Color(rgb: 0xbdb1d0)
    static let border = Color(rgb: 0x493b60)
    static let button = Color(rgb: 0x34274d)
    static let pink = Color(rgb: 0xff95bc)
    static let peach = Color(rgb: 0xffb49c)
    static let gold = Color(rgb: 0xffe195)
    static let green = Color(rgb: 0x9aefc5)
    static let blue = Color(rgb: 0x99dfff)
    static let accent = Color(rgb: 0xc9b1ff)
    static let rainbow = [pink, peach, gold, green, blue, accent]
}

private extension Color {
    init(rgb: UInt32) {
        self.init(
            red: Double((rgb >> 16) & 255) / 255,
            green: Double((rgb >> 8) & 255) / 255,
            blue: Double(rgb & 255) / 255
        )
    }
}

private struct Dashboard: View {
    @State private var link = ""
    @State private var showLink = false
    @State private var profile: LinkSummary?
    @State private var linkError: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                Label("Disconnected", systemImage: "circle.fill")
                    .font(.headline)
                    .foregroundStyle(Palette.muted)
                serverCard
                vpnCard
            }
            .frame(maxWidth: 520)
            .padding(20)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .foregroundStyle(Palette.text)
        .background(Palette.background.ignoresSafeArea())
        .tint(Palette.accent)
        .onChange(of: link) { _ in
            profile = nil
            linkError = nil
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text("hh vpn")
                    .font(.largeTitle.weight(.bold))
                    .foregroundStyle(Palette.pink)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Text("VLESS / iOS")
                    .font(.subheadline)
                    .foregroundStyle(Palette.blue)
            }
            HStack(spacing: 0) {
                ForEach(Palette.rainbow.indices, id: \.self) { index in
                    Rectangle().fill(Palette.rainbow[index])
                }
            }
            .frame(height: 5)
            .clipShape(Capsule())
            .accessibilityHidden(true)
        }
    }

    private var serverCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("SERVER LINK")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Palette.peach)
                .accessibilityAddTraits(.isHeader)
            Group {
                if showLink {
                    TextField("vless:// link", text: $link)
                } else {
                    SecureField("vless:// link", text: $link)
                }
            }
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .keyboardType(.URL)
            .submitLabel(.go)
            .onSubmit(inspectLink)
            .padding(14)
            .background(Palette.input, in: RoundedRectangle(cornerRadius: 16))
            .overlay {
                RoundedRectangle(cornerRadius: 16).strokeBorder(Palette.border)
            }
            Toggle("Show link", isOn: $showLink)
            HStack(spacing: 10) {
                PasteButton(payloadType: String.self) { strings in
                    if let first = strings.first { link = first }
                }
                .labelStyle(.titleOnly)
                .buttonBorderShape(.capsule)
                .controlSize(.large)
                Button("Parse link", action: inspectLink)
                    .buttonStyle(RoundedButton())
                    .disabled(link.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            if let linkError {
                Text(linkError).foregroundStyle(Palette.peach)
            } else if let profile {
                Text(profile.name).font(.headline)
                Text(verbatim: "\(profile.endpoint) · \(profile.transport) · \(profile.security)")
                    .foregroundStyle(Palette.muted)
            } else {
                Text("No server selected")
                    .foregroundStyle(Palette.muted)
            }
        }
        .card()
    }

    private var vpnCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("VPN CONNECTION")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Palette.gold)
                .accessibilityAddTraits(.isHeader)
            Text("This build can inspect VLESS links. Connecting requires an iOS tunnel engine and Apple VPN provisioning.")
                .font(.subheadline)
                .foregroundStyle(Palette.muted)
        }
        .card()
    }

    private func inspectLink() {
        profile = LinkSummary(link: link)
        linkError = profile == nil ? "Use a VLESS link with a UUID, server, and port." : nil
    }
}

private extension View {
    func card() -> some View {
        padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Palette.panel, in: RoundedRectangle(cornerRadius: 24))
            .overlay {
                RoundedRectangle(cornerRadius: 24).strokeBorder(Palette.border)
            }
    }
}

private struct RoundedButton: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .padding(.horizontal, 14)
            .frame(maxWidth: .infinity, minHeight: 46)
            .foregroundStyle(isEnabled ? Palette.background : Palette.muted)
            .background(isEnabled ? Palette.accent : Palette.button, in: Capsule())
            .opacity(configuration.isPressed ? 0.78 : 1)
    }
}