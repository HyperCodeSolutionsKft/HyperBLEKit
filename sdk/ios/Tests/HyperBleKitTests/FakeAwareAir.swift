import Combine
import Foundation
@testable import HyperBleKit

/// Wi-Fi Aware between the phones of one test: host paths publish under an instance ID,
/// client paths find them by the offer's, and both ends exchange messages and frames through
/// the main queue, in order. `breakAll` breaks every data path; `isAvailable` keeps new ones
/// from coming up.
///
/// Main thread only.
final class FakeAwareAir {
    var isAvailable = true
    /// How long a data path takes to come up.
    var setupDelay: TimeInterval = 0.05
    private(set) var messagesCarried = 0
    private(set) var framesCarried = 0
    private var hosts: [FakeAwareHostPath] = []
    private var instances: UInt8 = 0

    let scheduler: MainScheduler = { delay, block in DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: block) }

    func hostPath(maxDataPaths: Int = 4) -> FakeAwareHostPath {
        let path = FakeAwareHostPath(air: self, maxDataPaths: maxDataPaths)
        hosts.append(path)
        return path
    }

    func clientPath() -> FakeAwareClientPath { FakeAwareClientPath(air: self) }

    fileprivate func newInstanceId() -> Data {
        instances &+= 1
        return Data(repeating: 0, count: 7) + Data([instances])
    }

    fileprivate func findHost(_ instanceId: Data) -> FakeAwareHostPath? {
        hosts.first { $0.isReady && $0.instanceId == instanceId }
    }

    fileprivate func carriedMessage() { messagesCarried += 1 }
    fileprivate func carriedFrame() { framesCarried += 1 }

    /// Every data path breaks at once, as when Wi-Fi is switched off.
    func breakAll(reason: String = "Wi-Fi Aware unavailable") {
        hosts.forEach { $0.breakAll(reason: reason) }
    }

    func reset() {
        hosts.removeAll()
    }
}

final class FakeAwareHostPath: AwareHostPath {
    private weak var air: FakeAwareAir?
    let maxDataPaths: Int
    private let events = PassthroughSubject<AwareHostEvent, Never>()
    private var isStarted = false
    fileprivate private(set) var instanceId = Data()
    private var paths: [LinkID: (token: Data, client: FakeAwareClientPath?)] = [:]
    private(set) var offers = 0
    var isSupported = true

    fileprivate init(air: FakeAwareAir, maxDataPaths: Int) {
        self.air = air
        self.maxDataPaths = maxDataPaths
    }

    var eventsPublisher: AnyPublisher<AwareHostEvent, Never> { events.eraseToAnyPublisher() }
    var isReady: Bool { isStarted && (air?.isAvailable ?? false) }

    func start() {
        isStarted = true
        instanceId = air?.newInstanceId() ?? Data()
    }

    func stop() {
        isStarted = false
        paths.values.forEach { $0.client?.hostClosed(reason: "host stopped") }
        paths.removeAll()
    }

    func stopAfterFlush() {
        DispatchQueue.main.async { self.stop() }
    }

    func offer(for link: LinkID) -> AwareOffer? {
        guard isReady else { return nil }
        offers += 1
        let token = Data(link.uuidString.utf8.prefix(8))
        paths[link] = (token, nil)
        return AwareOffer(instanceId: instanceId, token: token, passphrase: "passphrase")
    }

    fileprivate func accept(_ client: FakeAwareClientPath, token: Data) -> Bool {
        guard let link = paths.first(where: { $0.value.token == token && $0.value.client == nil })?.key else { return false }
        paths[link]?.client = client
        events.send(.pathUp(link))
        return true
    }

    func send(_ message: Data, to link: LinkID) -> Bool {
        guard let client = paths[link]?.client else { return false }
        air?.carriedMessage()
        DispatchQueue.main.async { client.deliver(.message(message)) }
        return true
    }

    func sendFrame(_ payload: Data, to link: LinkID) -> FrameSendResult {
        guard let client = paths[link]?.client else { return .failed }
        air?.carriedFrame()
        DispatchQueue.main.async { client.deliver(.frame(payload)) }
        return .queued
    }

    func close(_ link: LinkID) {
        paths.removeValue(forKey: link)?.client?.hostClosed(reason: "closed by the host")
    }

    fileprivate func link(of client: FakeAwareClientPath) -> LinkID? {
        paths.first { $0.value.client === client }?.key
    }

    fileprivate func deliver(_ event: AwareHostEvent) {
        events.send(event)
    }

    fileprivate func clientClosed(_ client: FakeAwareClientPath, reason: String) {
        guard let link = link(of: client) else { return }
        paths[link] = nil
        DispatchQueue.main.async { self.events.send(.pathDown(link, reason: reason)) }
    }

    fileprivate func breakAll(reason: String) {
        let broken = paths
        paths.removeAll()
        for (link, path) in broken {
            path.client?.hostClosed(reason: reason)
            DispatchQueue.main.async { self.events.send(.pathDown(link, reason: reason)) }
        }
    }
}

final class FakeAwareClientPath: AwareClientPath {
    private weak var air: FakeAwareAir?
    private let events = PassthroughSubject<AwareClientEvent, Never>()
    private var host: FakeAwareHostPath?
    private var attempt = 0
    var isSupported = true

    fileprivate init(air: FakeAwareAir) {
        self.air = air
    }

    var eventsPublisher: AnyPublisher<AwareClientEvent, Never> { events.eraseToAnyPublisher() }

    func connect(_ offer: AwareOffer) {
        close()
        attempt += 1
        let current = attempt
        DispatchQueue.main.asyncAfter(deadline: .now() + (air?.setupDelay ?? 0)) { [weak self] in
            guard let self, current == self.attempt else { return }
            guard let air = self.air, air.isAvailable, let target = air.findHost(offer.instanceId),
                  target.accept(self, token: offer.token) else {
                self.events.send(.down(reason: "no data path"))
                return
            }
            self.host = target
            self.events.send(.up)
        }
    }

    func send(_ message: Data) -> Bool {
        guard let target = host else { return false }
        air?.carriedMessage()
        DispatchQueue.main.async { [weak self] in
            guard let self, self.host === target, let link = target.link(of: self) else { return }
            target.deliver(.message(link, message))
        }
        return true
    }

    func sendFrame(_ payload: Data) -> FrameSendResult {
        guard let target = host else { return .failed }
        air?.carriedFrame()
        DispatchQueue.main.async { [weak self] in
            guard let self, self.host === target, let link = target.link(of: self) else { return }
            target.deliver(.frame(link, payload))
        }
        return .queued
    }

    func close() {
        attempt += 1
        guard let target = host else { return }
        host = nil
        target.clientClosed(self, reason: "closed by the client")
    }

    func closeAfterFlush(then completion: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.close()
            completion()
        }
    }

    fileprivate func deliver(_ event: AwareClientEvent) {
        if host != nil { events.send(event) }
    }

    fileprivate func hostClosed(reason: String) {
        guard host != nil else { return }
        host = nil
        attempt += 1
        DispatchQueue.main.async { self.events.send(.down(reason: reason)) }
    }
}
