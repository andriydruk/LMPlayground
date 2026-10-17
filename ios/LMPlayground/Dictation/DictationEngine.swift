import Foundation
import LlamaKit

/// Owns the speech-recognition model (Android: DictationManager plus the
/// service's ASR idle unload). Independent of the chat model: dictation works
/// whatever is loaded, or with nothing loaded.
///
/// Parakeet loads its weights into RAM (~685 MB of footprint, unlike mmapped
/// chat weights), so the model is released a minute after the last stream
/// ends, on a memory warning, and when the app goes to the background.
@AsrActor
final class DictationEngine {

    nonisolated static let shared = DictationEngine()
    static let idleUnload: Duration = .seconds(60)

    nonisolated let url: URL
    private var model: AsrModel?
    private var openStreams = 0
    private var lastUse = 0

    nonisolated init(directory: URL = AppDirectories.models) {
        url = directory.appending(path: ModelCatalog.dictationModel.filename)
    }

    nonisolated func isModelOnDisk() -> Bool {
        FileManager.default.fileExists(atPath: url.path)
    }

    var isLoaded: Bool { model != nil }

    /// A new stream, loading the model first if needed.
    func beginStream() throws -> AsrStream {
        if model == nil {
            model = try AsrModel(url: url)
        }
        let stream = try model!.makeStream()
        openStreams += 1
        lastUse += 1
        return stream
    }

    /// Recognize `samples`; returns the newly finalized text.
    func feed(_ stream: AsrStream, _ samples: [Float]) -> String {
        stream.feed(samples)
    }

    /// Flush and close; returns the final delta.
    func finish(_ stream: AsrStream) -> String {
        let tail = stream.finish()
        close(stream)
        return tail
    }

    /// Close without flushing (dictation was abandoned).
    func close(_ stream: AsrStream) {
        stream.close()
        openStreams = max(0, openStreams - 1)
        scheduleIdleUnload()
    }

    /// Release the model unless a stream is still open.
    func unloadIfIdle() {
        guard openStreams == 0, let model else { return }
        model.unload()
        self.model = nil
    }

    private func scheduleIdleUnload() {
        lastUse += 1
        let mark = lastUse
        Task { @AsrActor [weak self] in
            try? await Task.sleep(for: Self.idleUnload)
            guard let self, self.lastUse == mark else { return }
            self.unloadIfIdle()
        }
    }
}
