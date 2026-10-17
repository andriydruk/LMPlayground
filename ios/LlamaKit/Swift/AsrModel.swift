import Foundation

/// Speech recognition runs on its own thread: dictation can happen while a
/// chat model sits loaded but idle, and parakeet's state is not re-entrant.
@globalActor
public actor AsrActor {
    public static let shared = AsrActor()

    private let queue = DispatchSerialQueue(label: "com.druk.lmplayground.asr",
                                            qos: .userInitiated)

    public nonisolated var unownedExecutor: UnownedSerialExecutor {
        queue.asUnownedSerialExecutor()
    }
}

/// A Parakeet ASR model. Audio is 16 kHz mono Float32.
@AsrActor
public final class AsrModel {
    private let native: LMKAsrModel

    /// `threads` > 0 overrides the default (the performance-core count).
    public init(url: URL, threads: Int32 = 0) throws {
        native = try LMKAsrModel.load(atPath: url.path, threads: threads)
    }

    /// Transcribe a whole utterance. `language` is a hint ("en"), or nil to
    /// let the model detect it.
    public func transcribe(_ samples: [Float], language: String? = nil) throws -> String {
        try samples.withUnsafeBufferPointer { buffer in
            try native.transcribeSamples(buffer.baseAddress!, count: buffer.count, language: language)
        }
    }

    /// A streaming recognizer, or nil for models without streaming support.
    public func makeStream(language: String? = nil) throws -> AsrStream {
        AsrStream(native: try native.beginStream(withLanguage: language))
    }

    public func unload() {
        native.unload()
    }
}

@AsrActor
public final class AsrStream {
    private let native: LMKAsrStream

    init(native: LMKAsrStream) {
        self.native = native
    }

    /// Feed recorded audio; returns the text finalized since the last call.
    public func feed(_ samples: [Float]) -> String {
        guard !samples.isEmpty else { return "" }
        return samples.withUnsafeBufferPointer { native.feedSamples($0.baseAddress!, count: $0.count) }
    }

    /// Flush the remaining audio; returns the final text delta.
    public func finish() -> String {
        native.finish()
    }

    public func close() {
        native.close()
    }
}
