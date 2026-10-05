import Foundation

/// The client side of the speed test: counts world frames, the gaps between them and the input
/// round trip, for the last second and since the start. Pure: the caller gives it the clock.
struct SpeedTestClient {
    /// Counters over some time span.
    struct Stats: Equatable {
        var frames = 0
        var stale = 0
        var gapCount = 0
        var gapSum: UInt64 = 0
        var gapMax: UInt32 = 0
        /// Gaps over `slowGapMillis`.
        var over150 = 0
        var rttCount = 0
        var rttSum: UInt64 = 0
        var rttMax: UInt32 = 0

        var gapAverage: UInt64 { gapCount == 0 ? 0 : gapSum / UInt64(gapCount) }
        var rttAverage: UInt64 { rttCount == 0 ? 0 : rttSum / UInt64(rttCount) }

        mutating func addGap(_ gap: UInt32) {
            gapCount += 1
            gapSum += UInt64(gap)
            gapMax = max(gapMax, gap)
            if gap > SpeedTestConstants.slowGapMillis { over150 += 1 }
        }

        mutating func addRoundTrip(_ rtt: UInt32) {
            rttCount += 1
            rttSum += UInt64(rtt)
            rttMax = max(rttMax, rtt)
        }

        /// e.g. `fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80`, `rtt=-/-` without a
        /// sample, as on Android; `fps` is the number of frames, so this reads as a rate only for
        /// one second.
        var text: String {
            let rtt = rttCount == 0 ? "-/-" : "\(rttAverage)/\(rttMax)"
            return "fps=\(frames) stale=\(stale) gapAvg=\(gapAverage) gapMax=\(gapMax) over150=\(over150) "
                + "rtt=\(rtt)"
        }
    }

    private(set) var lastSecond = Stats()
    private(set) var total = Stats()
    private(set) var seconds = 0
    /// The newest world frame, for drawing.
    private(set) var world: SpeedTestWorld?
    /// Payload size of the newest world frame.
    private(set) var payloadSize = 0
    /// Taps on the field so far, sent with every input.
    private(set) var taps: UInt8 = 0
    private var current = Stats()
    private var lastFrameAt: UInt32?
    private var lastInputSeq: UInt8 = 0
    /// The transport's stale counter when the current second started.
    private var staleCountAtSecondStart: Int

    /// - Parameter staleFrameCount: the transport's stale counter right now.
    init(staleFrameCount: Int) {
        staleCountAtSecondStart = staleFrameCount
    }

    /// A world frame was taken.
    mutating func worldReceived(_ newWorld: SpeedTestWorld, payloadSize size: Int, nowMillis: UInt32) {
        current.frames += 1
        if let lastFrameAt { current.addGap(nowMillis &- lastFrameAt) }
        lastFrameAt = nowMillis
        // The round trip counts once per input, when the echo first changes. The first frame
        // only sets where the echo stands. An echo of 0 at clientMillis 0 means the host has
        // taken no input yet (it restarted the test), not a round trip.
        let hasEcho = newWorld.echoInputSeq != 0 || newWorld.echoClientMillis != 0
        if let previous = world, newWorld.echoInputSeq != previous.echoInputSeq, hasEcho {
            current.addRoundTrip(Self.roundTrip(nowMillis: nowMillis, world: newWorld))
        }
        world = newWorld
        payloadSize = size
    }

    /// `now − echoClientMillis − holdMillis`, mod 2³². A result that went below 0 through
    /// millisecond rounding on both clocks counts as 0.
    static func roundTrip(nowMillis: UInt32, world: SpeedTestWorld) -> UInt32 {
        let rtt = nowMillis &- world.echoClientMillis &- UInt32(world.holdMillis)
        return rtt > UInt32(Int32.max) ? 0 : rtt
    }

    /// Since the start, e.g. `60 s: fps=15 stale=0 gapAvg=66 gapMax=120 over150=0 rtt=46/95`,
    /// with `fps` averaged over the seconds.
    var totalText: String {
        var average = total
        average.frames = seconds == 0 ? 0 : total.frames / seconds
        return "\(seconds) s: \(average.text)"
    }

    /// The next input to send; a tap counts first.
    mutating func nextInput(nowMillis: UInt32, tapped: Bool) -> SpeedTestInput {
        if tapped { taps &+= 1 }
        lastInputSeq &+= 1
        return SpeedTestInput(inputSeq: lastInputSeq, clientMillis: nowMillis, taps: taps)
    }

    /// Once a second: closes the second and adds it to the total. Returns the log line, e.g.
    /// `RT rx fps=15 stale=0 gapAvg=66 gapMax=90 over150=0 rtt=45/80 size=64`.
    /// - Parameter staleFrameCount: the transport's stale counter right now.
    mutating func endSecond(staleFrameCount: Int) -> String {
        current.stale = staleFrameCount - staleCountAtSecondStart
        staleCountAtSecondStart = staleFrameCount
        lastSecond = current
        seconds += 1
        total.frames += current.frames
        total.stale += current.stale
        total.gapCount += current.gapCount
        total.gapSum += current.gapSum
        total.gapMax = max(total.gapMax, current.gapMax)
        total.over150 += current.over150
        total.rttCount += current.rttCount
        total.rttSum += current.rttSum
        total.rttMax = max(total.rttMax, current.rttMax)
        current = Stats()
        return "RT rx \(lastSecond.text) size=\(payloadSize)"
    }
}
