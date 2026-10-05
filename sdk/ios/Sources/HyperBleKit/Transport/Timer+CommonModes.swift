import Foundation

extension Timer {
    /// Like `scheduledTimer`, but in the common run-loop modes, so the timer keeps firing while
    /// the user scrolls (touch tracking pauses timers scheduled in the default mode).
    static func scheduledInCommonModes(withTimeInterval interval: TimeInterval, repeats: Bool,
                                       block: @escaping (Timer) -> Void) -> Timer {
        let timer = Timer(timeInterval: interval, repeats: repeats, block: block)
        RunLoop.main.add(timer, forMode: .common)
        return timer
    }
}
