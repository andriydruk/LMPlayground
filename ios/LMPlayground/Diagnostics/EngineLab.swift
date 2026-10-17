import Foundation
import LlamaKit
import UIKit

/// Measurements for one model file, from the Engine Lab benchmark.
struct LabResult: Codable, Sendable, Identifiable {
    var id: String { file }
    var file: String
    var kind: String
    var fileMB: Int
    var availableBeforeMB: Int
    var footprintBeforeMB: Int
    var loadSeconds: Double?
    var footprintAfterLoadMB: Int?
    var availableAfterLoadMB: Int?
    var ttftSeconds: Double?
    var tokens: Int?
    var tokensPerSecond: Double?
    var peakFootprintMB: Int?
    var footprintAfterUnloadMB: Int?
    var visionSeconds: Double?
    var output: String?
    var report: String?
    var error: String?
}

struct LabRun: Codable, Sendable {
    var device: String
    /// When this binary was built, to tell runs of different builds apart.
    var binaryDate: Date?
    var systemVersion: String
    var startedAt: Date
    var systemInfo: String
    var results: [LabResult]
}

/// Engine Lab: loads every GGUF in Documents/Models and measures load time,
/// memory and throughput on this device. Fixtures (test_cat.jpg, jfk.wav) are
/// read from Documents/Fixtures. Launch with `-labAutorun YES` to run on
/// start; results land in Documents/lab-results.json.
enum EngineLab {

    static let prompt = "Explain in three short paragraphs how a lighthouse works."
    static let visionPrompt = "What is in this image? Answer in one sentence."
    static let maxTokens = 256

    static var fixtures: URL { AppDirectories.documents.appending(path: "Fixtures", directoryHint: .isDirectory) }
    static var resultsURL: URL { AppDirectories.documents.appending(path: "lab-results.json") }

    enum Kind: String { case llm, embedding, asr }

    static func kind(of filename: String) -> Kind {
        let lower = filename.lowercased()
        if lower.contains("embedding") { return .embedding }
        if lower.contains("asr") || lower.contains("parakeet") { return .asr }
        return .llm
    }

    /// Model files to benchmark (projectors are attached to their model).
    static func modelFiles() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: AppDirectories.models,
                                                                  includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files
            .filter { $0.pathExtension.lowercased() == "gguf" && !MmprojPairing.isMmproj($0.lastPathComponent) }
            .sorted { fileSize($0) < fileSize($1) }
    }

    static func fileSize(_ url: URL) -> UInt64 {
        UInt64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }

    @MainActor
    static func deviceDescription() -> String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return "\(UIDevice.current.model) \(machine)"
    }

    static func runAll(progress: @escaping @Sendable (LabResult) -> Void) async -> LabRun {
        var run = LabRun(device: await deviceDescription(),
                         binaryDate: (try? Bundle.main.executableURL?.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate,
                         systemVersion: await MainActor.run { UIDevice.current.systemVersion },
                         startedAt: Date(),
                         systemInfo: Engine.systemInfo,
                         results: [])
        let allFiles = (try? FileManager.default.contentsOfDirectory(atPath: AppDirectories.models.path)) ?? []
        for url in modelFiles() {
            let result: LabResult
            switch kind(of: url.lastPathComponent) {
            case .llm:
                let projector = MmprojPairing.findMmproj(for: url.lastPathComponent, in: allFiles)
                    .map { AppDirectories.models.appending(path: $0) }
                result = await benchmarkLLM(url, projector: projector)
            case .embedding:
                result = await benchmarkEmbedding(url)
            case .asr:
                result = await benchmarkASR(url)
            }
            var measured = result
            measured.footprintAfterUnloadMB = ProcessMemory.footprint.megabytes
            run.results.append(measured)
            progress(measured)
        }
        if let data = try? JSONEncoder.lab.encode(run) {
            try? data.write(to: resultsURL)
        }
        return run
    }

    private static func baseline(_ url: URL, kind: Kind) -> LabResult {
        LabResult(file: url.lastPathComponent,
                  kind: kind.rawValue,
                  fileMB: fileSize(url).megabytes,
                  availableBeforeMB: ProcessMemory.available.megabytes,
                  footprintBeforeMB: ProcessMemory.footprint.megabytes)
    }

    @InferenceActor
    static func benchmarkLLM(_ url: URL, projector: URL?) -> LabResult {
        var r = baseline(url, kind: .llm)
        let clock = ContinuousClock()
        do {
            let loadStart = clock.now
            let model = try Engine.loadModel(at: url)
            r.loadSeconds = (clock.now - loadStart).seconds
            r.footprintAfterLoadMB = ProcessMemory.footprint.megabytes
            r.availableAfterLoadMB = ProcessMemory.available.megabytes
            defer { model.unload() }

            var params = SamplerParams()
            params.seed = 42
            let session = try model.makeSession(params)
            defer { session.destroy() }

            var peak = ProcessMemory.footprint
            var first: ContinuousClock.Instant?
            var tokens = 0
            var text = ""
            let start = clock.now
            _ = try session.respond(to: prompt, thinking: false, maxTokens: maxTokens) { response in
                if first == nil { first = clock.now }
                tokens += 1
                text = response
                if tokens % 16 == 0 { peak = max(peak, ProcessMemory.footprint) }
            }
            let end = clock.now
            peak = max(peak, ProcessMemory.footprint)
            if let first {
                r.ttftSeconds = (first - start).seconds
                if tokens > 1 { r.tokensPerSecond = Double(tokens - 1) / (end - first).seconds }
            }
            r.tokens = tokens
            r.output = String(text.prefix(300))
            r.report = session.report

            if let projector {
                let image = fixtures.appending(path: "test_cat.jpg")
                if let data = try? Data(contentsOf: image) {
                    try model.attachProjector(at: projector)
                    let visionSession = try model.makeSession(params)
                    defer { visionSession.destroy() }
                    let visionStart = clock.now
                    var answer = ""
                    _ = try visionSession.respond(to: visionPrompt, image: data, thinking: false, maxTokens: 64) {
                        answer = $0
                    }
                    r.visionSeconds = (clock.now - visionStart).seconds
                    r.output = (r.output ?? "") + "\n[vision] " + String(answer.prefix(200))
                    peak = max(peak, ProcessMemory.footprint)
                }
            }
            r.peakFootprintMB = peak.megabytes
        } catch {
            r.error = "\(error)"
        }
        return r
    }

    @InferenceActor
    static func benchmarkEmbedding(_ url: URL) -> LabResult {
        var r = baseline(url, kind: .embedding)
        let clock = ContinuousClock()
        do {
            let loadStart = clock.now
            let model = try Engine.loadModel(at: url)
            r.loadSeconds = (clock.now - loadStart).seconds
            r.footprintAfterLoadMB = ProcessMemory.footprint.megabytes
            defer { model.unload() }
            let session = try model.makeEmbeddingSession(contextSize: 2048)
            defer { session.destroy() }
            let texts = (0..<8).map { "Passage \($0): \(prompt) The keeper trims the wick every evening." }
            let start = clock.now
            var dimension = 0
            for text in texts {
                dimension = try session.embed(text).count
            }
            r.tokensPerSecond = Double(texts.count) / (clock.now - start).seconds
            r.output = "dimension \(dimension), \(texts.count) passages (tokensPerSecond = passages/s)"
            r.peakFootprintMB = ProcessMemory.footprint.megabytes
        } catch {
            r.error = "\(error)"
        }
        return r
    }

    @AsrActor
    static func benchmarkASR(_ url: URL) -> LabResult {
        var r = baseline(url, kind: .asr)
        let clock = ContinuousClock()
        do {
            let loadStart = clock.now
            let model = try AsrModel(url: url)
            r.loadSeconds = (clock.now - loadStart).seconds
            r.footprintAfterLoadMB = ProcessMemory.footprint.megabytes
            defer { model.unload() }
            let samples = try AudioDecoding.samples(contentsOf: fixtures.appending(path: "jfk.wav"))
            let start = clock.now
            let text = try model.transcribe(samples)
            let elapsed = (clock.now - start).seconds
            let audioSeconds = Double(samples.count) / AudioDecoding.sampleRate
            // Realtime factor: seconds of compute per second of audio.
            r.tokensPerSecond = audioSeconds / elapsed
            r.output = "RTF \(String(format: "%.3f", elapsed / audioSeconds)) over \(String(format: "%.1f", audioSeconds)) s: \(text)"
            r.peakFootprintMB = ProcessMemory.footprint.megabytes
        } catch {
            r.error = "\(error)"
        }
        return r
    }
}

extension Duration {
    var seconds: Double {
        Double(components.seconds) + Double(components.attoseconds) / 1e18
    }
}

extension JSONEncoder {
    static var lab: JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }
}
