import Foundation

/// Deletes model files a catalog change has retired (Android:
/// SupersededFileCleanup). Every removal waits for its replacement to be on
/// disk: someone who never downloads the replacement keeps a working, if
/// slower, setup.
enum SupersededFileCleanup {

    /// retired filename → the file that must exist before it's deleted.
    /// Same list as Android: Gemma 4's BF16 projectors (much slower than the
    /// Q8_0 builds on ARM CPUs) and Qwen 3.5 at Q3_K_M (IQ4_XS is faster and
    /// closer to Q8_0).
    static let superseded: [String: String] = [
        "mmproj-gemma-4-E2B-it-BF16.gguf": "mmproj-gemma-4-E2B-it-Q8_0.gguf",
        "mmproj-gemma-4-E4B-it-BF16.gguf": "mmproj-gemma-4-E4B-it-Q8_0.gguf",
        "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf": "Qwen_Qwen3.5-0.8B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-2B-Q3_K_M.gguf": "Qwen_Qwen3.5-2B-IQ4_XS.gguf",
        "Qwen_Qwen3.5-4B-Q3_K_M.gguf": "Qwen_Qwen3.5-4B-IQ4_XS.gguf",
    ]

    /// The retired files that are safe to delete given what's `present`.
    static func deletable(present: Set<String>) -> [String] {
        superseded.filter { present.contains($0.key) && present.contains($0.value) }.keys.sorted()
    }

    /// Delete them from `directory`; returns the bytes reclaimed.
    @discardableResult
    static func run(in directory: URL) -> UInt64 {
        let names = Set((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? [])
        var reclaimed: UInt64 = 0
        for name in deletable(present: names) {
            let url = directory.appending(path: name)
            let size = UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            if (try? FileManager.default.removeItem(at: url)) != nil { reclaimed += size }
        }
        return reclaimed
    }
}
