import AVFoundation

/// A slice of recorded audio: 16 kHz mono Float32 and its RMS amplitude
/// (full scale 1.0).
struct AudioChunk: Sendable {
    let samples: [Float]
    let amplitude: Float
}

/// Where dictation audio comes from: the microphone, or a file in the
/// self-test.
protocol AudioSource: AnyObject, Sendable {
    /// Chunks until `stop()`, the duration cap, or an error. The stream
    /// finishes only once the source has let go of the microphone.
    func start() throws -> AsyncThrowingStream<AudioChunk, Error>
    func stop()
}

/// Records the microphone in Parakeet's format (Android: AudioRecorder) with
/// AVAudioEngine, converting each buffer to 16 kHz mono as it arrives.
final class MicrophoneCapture: AudioSource, @unchecked Sendable {

    /// Dictation is a sentence or two, not a memo.
    static let maxDuration: Double = 60

    private let lock = NSLock()
    private var engine: AVAudioEngine?
    private var continuation: AsyncThrowingStream<AudioChunk, Error>.Continuation?

    func start() throws -> AsyncThrowingStream<AudioChunk, Error> {
        let session = AVAudioSession.sharedInstance()
        // Measurement mode skips the voice processing ASR models dislike
        // (Android records VOICE_RECOGNITION for the same reason);
        // play-and-record so the earcons can sound.
        try session.setCategory(.playAndRecord, mode: .measurement,
                                options: [.defaultToSpeaker, .allowBluetoothHFP])
        try session.setActive(true)

        let engine = AVAudioEngine()
        let input = engine.inputNode
        let inputFormat = input.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let converter = AVAudioConverter(from: inputFormat, to: AudioDecoding.targetFormat) else {
            throw NSError(domain: "MicrophoneCapture", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "No microphone input"])
        }

        let (stream, continuation) = AsyncThrowingStream<AudioChunk, Error>.makeStream()
        let maxSamples = Int(Self.maxDuration * AudioDecoding.sampleRate)
        nonisolated(unsafe) var total = 0
        input.installTap(onBus: 0, bufferSize: 1600, format: inputFormat) { [weak self] buffer, _ in
            guard let samples = Self.convert(buffer, with: converter), !samples.isEmpty else { return }
            total += samples.count
            var sumSquares: Float = 0
            for s in samples { sumSquares += s * s }
            continuation.yield(AudioChunk(samples: samples, amplitude: (sumSquares / Float(samples.count)).squareRoot()))
            if total >= maxSamples {
                DispatchQueue.main.async { self?.stop() }
            }
        }
        engine.prepare()
        try engine.start()

        lock.withLock {
            self.engine = engine
            self.continuation = continuation
        }
        return stream
    }

    func stop() {
        let (engine, continuation) = lock.withLock {
            defer {
                self.engine = nil
                self.continuation = nil
            }
            return (self.engine, self.continuation)
        }
        guard let engine else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        continuation?.finish()
    }

    /// One tap buffer in 16 kHz mono. The converter keeps its resampler
    /// state between buffers, so each call hands it exactly one buffer.
    private static func convert(_ buffer: AVAudioPCMBuffer, with converter: AVAudioConverter) -> [Float]? {
        let ratio = AudioDecoding.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let output = AVAudioPCMBuffer(pcmFormat: AudioDecoding.targetFormat, frameCapacity: capacity) else { return nil }
        nonisolated(unsafe) var supplied = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let channel = output.floatChannelData else { return nil }
        return Array(UnsafeBufferPointer(start: channel[0], count: Int(output.frameLength)))
    }
}

/// Feeds an audio file in 100 ms slices at real-time pace, like the
/// microphone does (self-test and harness parity).
final class FileAudioSource: AudioSource, @unchecked Sendable {
    private let url: URL
    private let lock = NSLock()
    private var task: Task<Void, Never>?

    init(url: URL) {
        self.url = url
    }

    func start() throws -> AsyncThrowingStream<AudioChunk, Error> {
        let samples = try AudioDecoding.samples(contentsOf: url)
        let (stream, continuation) = AsyncThrowingStream<AudioChunk, Error>.makeStream()
        let slice = Int(AudioDecoding.sampleRate / 10)
        let task = Task.detached {
            var start = 0
            while start < samples.count, !Task.isCancelled {
                let chunk = Array(samples[start ..< min(start + slice, samples.count)])
                let rms = (chunk.reduce(0) { $0 + $1 * $1 } / Float(chunk.count)).squareRoot()
                continuation.yield(AudioChunk(samples: chunk, amplitude: rms))
                start += slice
                try? await Task.sleep(for: .milliseconds(100))
            }
            continuation.finish()
        }
        lock.withLock { self.task = task }
        return stream
    }

    func stop() {
        lock.withLock { task }?.cancel()
    }
}
