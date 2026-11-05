// The Wi-Fi Aware data path on iOS 26 (wire specification, "Wi-Fi Aware data path", "iOS").
//
// Everything here needs the iOS 26 SDK (Xcode 26) and is compiled only where the `WiFiAware`
// framework exists; elsewhere the SDK uses `UnsupportedAwareHostPath` / `UnsupportedAwareClientPath`.
// The record stream itself is `AwareStreamConnection`, which is platform-neutral and tested on
// its own; this file only connects it to the Network framework.
//
// iOS differs from Android in how the two ends find each other: the framework has no
// service-specific info and no discovery messages, and only paired devices can connect. So the
// host listens for every paired device, and the client tries every paired device that publishes
// the service, sends the offer's token in its HELLO, and keeps the connection whose host echoes
// it. The offer's instance ID and passphrase are not used on iOS (pairing secures the link).

#if canImport(WiFiAware) && os(iOS)
import Combine
import Foundation
import Network
import WiFiAware

@available(iOS 26.0, *)
enum WiFiAwareSupport {
    /// The phone supports Wi-Fi Aware and the app declares `serviceName` as publishable and
    /// subscribable in its Info.plist (`WiFiAwareServices`).
    static func isSupported(serviceName: String) -> Bool {
        WACapabilities.supportedFeatures.contains(.wifiAware)
            && WAPublishableService.allServices[serviceName] != nil
            && WASubscribableService.allServices[serviceName] != nil
    }

    /// How long a client tries to reach its host, from `connect`.
    static let setupTimeout: TimeInterval = 20
    /// How long a client waits for one paired device to echo its HELLO.
    static let helloTimeout: TimeInterval = 3
    /// How long a closing data path gets to send what is queued.
    static let flushTimeout: TimeInterval = 1
    /// How long an offer stays valid on the host.
    static let offerTimeout: TimeInterval = 20

    static func randomBytes(_ count: Int) -> Data {
        Data((0..<count).map { _ in UInt8.random(in: 0...255) })
    }

    static func randomPassphrase() -> String {
        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789")
        return String((0..<AwareWire.passphraseLength).map { _ in alphabet.randomElement()! })
    }
}

/// A TCP `NetworkConnection` of a Wi-Fi Aware data path as an `AwareByteChannel`.
@available(iOS 26.0, *)
final class NetworkConnectionChannel: AwareByteChannel {
    private let connection: NetworkConnection<TCP>
    private let onCancel: () -> Void

    /// - Parameter onCancel: ends whatever keeps the connection alive (the listener's handler,
    ///   or the client's reference to it).
    init(_ connection: NetworkConnection<TCP>, onCancel: @escaping () -> Void) {
        self.connection = connection
        self.onCancel = onCancel
    }

    func send(_ data: Data) async throws {
        try await connection.send(data)
    }

    func receive(exactly count: Int) async throws -> Data {
        try await connection.receive(exactly: count).content
    }

    func cancel() {
        onCancel()
    }
}

/// Host side: listens on the service for every paired device; a connection belongs to the link
/// whose offer's token its HELLO carries.
@available(iOS 26.0, *)
final class WiFiAwareHostPath: AwareHostPath {
    private struct Offer {
        let token: Data
        var connection: AwareStreamConnection?
    }

    private let serviceName: String
    private let events = PassthroughSubject<AwareHostEvent, Never>()
    private var listenerTask: Task<Void, Never>?
    /// Counts starts and stops, so callbacks of an earlier listener do nothing.
    private var generation = 0
    private var offers: [LinkID: Offer] = [:]
    /// Connections that have not sent their HELLO yet.
    private var unbound: [ObjectIdentifier: AwareStreamConnection] = [:]
    private(set) var isReady = false

    init(serviceName: String) {
        self.serviceName = serviceName
    }

    var eventsPublisher: AnyPublisher<AwareHostEvent, Never> { events.eraseToAnyPublisher() }
    var isSupported: Bool { WiFiAwareSupport.isSupported(serviceName: serviceName) }
    var maxDataPaths: Int { WACapabilities.maximumConnectableDevices }

    func start() {
        stop()
        guard let service = WAPublishableService.allServices[serviceName] else {
            log("Wi-Fi Aware: \(serviceName) is not a publishable service in Info.plist")
            return
        }
        let armed = generation
        listenerTask = Task { @MainActor [weak self] in
            do {
                let listener = try NetworkListener(
                    for: .wifiAware(.connecting(to: service, from: .allPairedDevices)),
                    using: { TCP() })
                    .onStateUpdate { [weak self] _, state in
                        Task { @MainActor in self?.listenerStateChanged(state, armed: armed) }
                    }
                try await listener.run { [weak self] connection in
                    // Suspends for as long as the connection lives.
                    await self?.accepted(connection, armed: armed)
                }
            } catch {
                guard let self, armed == self.generation else { return }
                self.isReady = false
                self.log("Wi-Fi Aware: listener ended (\(error))")
            }
        }
    }

    func stop() {
        generation += 1
        isReady = false
        listenerTask?.cancel()
        listenerTask = nil
        unbound.values.forEach { $0.close() }
        unbound.removeAll()
        offers.values.forEach { $0.connection?.close() }
        offers.removeAll()
    }

    func stopAfterFlush() {
        let flushing = offers.values.compactMap(\.connection)
        offers.removeAll()
        flushing.forEach { $0.closeAfterFlush(timeout: WiFiAwareSupport.flushTimeout) {} }
        DispatchQueue.main.asyncAfter(deadline: .now() + WiFiAwareSupport.flushTimeout) { [weak self] in self?.stop() }
    }

    func offer(for link: LinkID) -> AwareOffer? {
        guard isReady else { return nil }
        close(link)
        let token = WiFiAwareSupport.randomBytes(AwareWire.idLength)
        offers[link] = Offer(token: token, connection: nil)
        let armed = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + WiFiAwareSupport.offerTimeout) { [weak self] in
            guard let self, armed == self.generation, let offer = self.offers[link], offer.token == token,
                  offer.connection == nil else { return }
            self.offers[link] = nil
            self.events.send(.pathDown(link, reason: "no data path within \(Int(WiFiAwareSupport.offerTimeout)) s"))
        }
        // Instance ID and passphrase are part of the wire format but unused between iPhones.
        return AwareOffer(instanceId: WiFiAwareSupport.randomBytes(AwareWire.idLength), token: token,
                          passphrase: WiFiAwareSupport.randomPassphrase())
    }

    func send(_ message: Data, to link: LinkID) -> Bool {
        offers[link]?.connection?.sendMessage(message) ?? false
    }

    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult {
        offers[link]?.connection?.sendFrame(payload) ?? .failed
    }

    func close(_ link: LinkID) {
        offers.removeValue(forKey: link)?.connection?.close()
    }

    private func log(_ text: String) {
        events.send(.log(text))
    }

    private func listenerStateChanged(_ state: NetworkListener<TCP>.State, armed: Int) {
        guard armed == generation else { return }
        switch state {
        case .ready:
            isReady = true
            log("Wi-Fi Aware: listening on \(serviceName)")
        case .failed(let error):
            isReady = false
            log("Wi-Fi Aware: listener failed (\(error))")
        case .cancelled:
            isReady = false
        default:
            break
        }
    }

    /// A paired device connected. Returns once the connection is over.
    @MainActor
    private func accepted(_ connection: NetworkConnection<TCP>, armed: Int) async {
        guard armed == generation else { return }
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            var isFinished = false
            let finish = {
                guard !isFinished else { return }
                isFinished = true
                done.resume()
            }
            var link: LinkID?
            var stream: AwareStreamConnection?
            stream = AwareStreamConnection(
                channel: NetworkConnectionChannel(connection, onCancel: finish),
                onRecord: { [weak self] record in
                    guard let self, let current = stream else { return }
                    if let bound = link {
                        switch record.kind {
                        case AwareStream.message: self.events.send(.message(bound, record.body))
                        case AwareStream.frame: self.events.send(.frame(bound, record.body))
                        default: break
                        }
                    } else if record.kind == AwareStream.hello {
                        link = self.bind(current, token: record.body)
                    }
                },
                onClosed: { [weak self] reason in
                    defer { finish() }
                    guard let self, let current = stream else { return }
                    self.unbound[ObjectIdentifier(current)] = nil
                    if let bound = link, self.offers[bound]?.connection === current {
                        self.offers[bound] = nil
                        self.events.send(.pathDown(bound, reason: reason))
                    }
                })
            if let stream {
                unbound[ObjectIdentifier(stream)] = stream
                stream.start()
            }
        }
    }

    private func bind(_ connection: AwareStreamConnection, token: Data) -> LinkID? {
        unbound[ObjectIdentifier(connection)] = nil
        guard let link = offers.first(where: { $0.value.token == token && $0.value.connection == nil })?.key else {
            // Most often a client trying every paired device until it finds its host.
            connection.close()
            return nil
        }
        offers[link]?.connection = connection
        connection.sendRecord(kind: AwareStream.hello, body: token)
        events.send(.pathUp(link))
        return link
    }
}

/// Client side: tries every paired device that publishes the service until one echoes the
/// offer's token.
@available(iOS 26.0, *)
final class WiFiAwareClientPath: AwareClientPath {
    private let serviceName: String
    private let events = PassthroughSubject<AwareClientEvent, Never>()
    private var task: Task<Void, Never>?
    /// Counts attempts, so callbacks of an abandoned one do nothing.
    private var generation = 0
    private var connection: AwareStreamConnection?
    /// Keeps the Network framework connection alive while `connection` uses it.
    private var networkConnection: NetworkConnection<TCP>?
    private var isUp = false
    /// Paired devices already tried in this attempt (main thread only).
    private var triedDevices = Set<WAPairedDevice.ID>()

    init(serviceName: String) {
        self.serviceName = serviceName
    }

    var eventsPublisher: AnyPublisher<AwareClientEvent, Never> { events.eraseToAnyPublisher() }
    var isSupported: Bool { WiFiAwareSupport.isSupported(serviceName: serviceName) }

    func connect(_ offer: AwareOffer) {
        close()
        let armed = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + WiFiAwareSupport.setupTimeout) { [weak self] in
            guard let self, armed == self.generation, !self.isUp else { return }
            self.fail("no paired host answered within \(Int(WiFiAwareSupport.setupTimeout)) s")
        }
        guard let service = WASubscribableService.allServices[serviceName] else {
            DispatchQueue.main.async { [weak self] in
                guard let self, armed == self.generation else { return }
                self.fail("\(self.serviceName) is not a subscribable service in Info.plist")
            }
            return
        }
        triedDevices.removeAll()
        task = Task { @MainActor [weak self] in
            do {
                let browser = NetworkBrowser(for: .wifiAware(.connecting(to: .allPairedDevices, from: service)))
                try await browser.run { [weak self] endpoints in
                    await self?.tryEndpoints(endpoints, token: offer.token, armed: armed) == false ? .continue : .finish(())
                }
            } catch {
                guard let self, armed == self.generation, !self.isUp else { return }
                self.fail("browsing failed (\(error))")
            }
        }
    }

    func send(_ message: Data) -> Bool {
        isUp ? connection?.sendMessage(message) ?? false : false
    }

    func sendFrame(_ payload: Data) -> FrameSendResult {
        isUp ? connection?.sendFrame(payload) ?? .failed : .failed
    }

    func close() {
        generation += 1
        isUp = false
        task?.cancel()
        task = nil
        connection?.close()
        connection = nil
        networkConnection = nil
    }

    func closeAfterFlush(then completion: @escaping () -> Void) {
        guard let flushing = connection else {
            close()
            completion()
            return
        }
        // The Network framework connection must outlive the flush.
        let network = networkConnection
        connection = nil
        close()
        flushing.closeAfterFlush(timeout: WiFiAwareSupport.flushTimeout) {
            withExtendedLifetime(network) {}
            completion()
        }
    }

    private func fail(_ reason: String) {
        close()
        events.send(.down(reason: reason))
    }

    /// Tries the devices not tried yet. False: keep browsing; true: done (up, or abandoned).
    @MainActor
    private func tryEndpoints(_ endpoints: [WASubscriberBrowser.Endpoint], token: Data, armed: Int) async -> Bool {
        for endpoint in endpoints where !triedDevices.contains(endpoint.device.id) {
            guard armed == generation else { return true }
            triedDevices.insert(endpoint.device.id)
            if await tryHost(endpoint, token: token, armed: armed) { return true }
        }
        return armed != generation
    }

    /// Connects to one paired device and sends the token; true once it echoes it.
    @MainActor
    private func tryHost(_ endpoint: WASubscriberBrowser.Endpoint, token: Data, armed: Int) async -> Bool {
        let network = NetworkConnection(to: endpoint, using: { TCP() })
        return await withCheckedContinuation { (result: CheckedContinuation<Bool, Never>) in
            var isSettled = false
            var stream: AwareStreamConnection?
            let settle = { [weak self] (accepted: Bool) in
                guard !isSettled else { return }
                isSettled = true
                if accepted, let self, armed == self.generation, let stream {
                    self.connection = stream
                    self.networkConnection = network
                    self.isUp = true
                    self.events.send(.up)
                } else {
                    stream?.close()
                }
                result.resume(returning: accepted)
            }
            stream = AwareStreamConnection(
                channel: NetworkConnectionChannel(network, onCancel: {}),
                onRecord: { [weak self] record in
                    guard isSettled else {
                        if record.kind == AwareStream.hello { settle(record.body == token) }
                        return
                    }
                    guard let self, armed == self.generation, self.isUp else { return }
                    switch record.kind {
                    case AwareStream.message: self.events.send(.message(record.body))
                    case AwareStream.frame: self.events.send(.frame(record.body))
                    default: break
                    }
                },
                onClosed: { [weak self] reason in
                    guard isSettled else {
                        settle(false)
                        return
                    }
                    guard let self, armed == self.generation, self.isUp else { return }
                    self.fail(reason)
                })
            stream?.start()
            stream?.sendRecord(kind: AwareStream.hello, body: token)
            DispatchQueue.main.asyncAfter(deadline: .now() + WiFiAwareSupport.helloTimeout) { settle(false) }
        }
    }
}
#endif
