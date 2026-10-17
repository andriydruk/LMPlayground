import Foundation
import LlamaKit
import Testing

/// Models and fixtures shared with the Android :model-harness.
enum TestSupport {

    /// `$LMP_MODELS_DIR`, or the harness default ~/.cache/lmplayground/models.
    static var modelsDir: URL {
        if let dir = ProcessInfo.processInfo.environment["LMP_MODELS_DIR"], !dir.isEmpty {
            return URL(filePath: dir, directoryHint: .isDirectory)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appending(path: ".cache/lmplayground/models", directoryHint: .isDirectory)
    }

    static var repoRoot: URL {
        URL(filePath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    }

    static var fixtures: URL {
        repoRoot.appending(path: "android/app/src/androidTest/assets", directoryHint: .isDirectory)
    }

    static let textModel = "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf"
    static let visionModel = "LFM2.5-VL-1.6B-Q4_K_M.gguf"
    static let visionProjector = "mmproj-LFM2.5-VL-1.6b-Q8_0.gguf"
    static let embeddingModel = "embeddinggemma-300m-Q4_0.gguf"
    static let asrModel = "nemotron-3.5-asr-streaming-0.6b-q4_k.gguf"

    static func model(_ name: String) -> URL {
        modelsDir.appending(path: name)
    }

    static func has(_ name: String) -> Bool {
        FileManager.default.fileExists(atPath: model(name).path)
    }

    @InferenceActor
    static func initializeEngine() {
        let state = FileManager.default.temporaryDirectory.appending(path: "lmp-llamakit-tests")
        Engine.initialize(stateDirectory: state)
    }

    /// Deterministic sampling for assertions.
    static var greedy: SamplerParams {
        var p = SamplerParams()
        p.seed = 42
        p.temperature = 0
        p.contextSize = 4096
        return p
    }
}
