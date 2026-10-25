import Foundation
import LlamaKit
import Testing

/// The shared C++ engine through the LlamaKit bridge, on the Mac's Metal GPU.
/// Ports of the :model-harness probes; each test skips when its model is not
/// in the models directory.
@Suite(.serialized)
@InferenceActor
struct EngineTests {

    init() {
        TestSupport.initializeEngine()
    }

    @Test func probeReadsGgufHeader() throws {
        try #require(TestSupport.has(TestSupport.textModel))
        let meta = try #require(Engine.probe(TestSupport.model(TestSupport.textModel)))
        #expect(meta.hasChatTemplate)
        #expect(!meta.name.isEmpty)
        #expect(Engine.probe(TestSupport.fixtures.appending(path: "test_cat.jpg")) == nil)
        #expect(!meta.isProjector)
        #expect(meta.contextLength > 0)
        #expect(meta.kvBytesPerToken > 0)
        // The tensor table, which is most of the file.
        let fileSize = try FileManager.default.attributesOfItem(
            atPath: TestSupport.model(TestSupport.textModel).path)[.size] as? UInt64 ?? 0
        #expect(meta.gpuWeightBytes > fileSize * 9 / 10 && meta.gpuWeightBytes <= fileSize)
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.visionProjector)))
    func probeRecognizesProjectors() throws {
        let meta = try #require(Engine.probe(TestSupport.model(TestSupport.visionProjector)))
        #expect(meta.isProjector)
    }

    @Test func probeReportsEveryLocalModel() {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: TestSupport.modelsDir.path)) ?? []
        for file in files.sorted() where file.hasSuffix(".gguf") {
            guard let m = Engine.probe(TestSupport.model(file)) else { continue }
            print("probe \(file): arch=\(m.architecture) ctx=\(m.contextLength) kv/token=\(m.kvBytesPerToken) gpu=\(m.gpuWeightBytes >> 20)MB projector=\(m.isProjector) template=\(m.hasChatTemplate)")
        }
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func generatesAnAnswer() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        #expect(model.contextTrainSize > 0)
        #expect(model.supportsThinking)

        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        var text = ""
        let result = try session.respond(to: "What is the capital of France? Answer with one word.",
                                         thinking: false, maxTokens: 64) { text = $0 }
        #expect(result == .finished)
        #expect(text.localizedCaseInsensitiveContains("paris"))
        #expect(!session.report.isEmpty)
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func remembersEarlierTurns() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }

        _ = try session.respond(to: "My name is Olena. Just say hello.", thinking: false, maxTokens: 48) { _ in }
        var text = ""
        _ = try session.respond(to: "What is my name? Answer with the name only.", thinking: false, maxTokens: 32) { text = $0 }
        #expect(text.contains("Olena"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func replayRestoresHistory() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }

        session.replay([(user: "My favourite colour is teal.", assistant: "Noted: teal.")])
        var text = ""
        _ = try session.respond(to: "What is my favourite colour? One word.", thinking: false, maxTokens: 32) { text = $0 }
        #expect(text.localizedCaseInsensitiveContains("teal"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func thinkingIsWrappedInThinkTags() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        var params = TestSupport.greedy
        params.thinkingBudget = 128
        let session = try model.makeSession(params)
        defer { session.destroy() }

        var text = ""
        _ = try session.respond(to: "Is 17 a prime number?", thinking: true, maxTokens: 400) { text = $0 }
        #expect(text.contains("<think>"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func abortStopsGeneration() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }

        var callbacks = 0
        let result = try session.respond(to: "Count from 1 to 500, one number per line.", thinking: false) { _ in
            callbacks += 1
            if callbacks == 5 { session.abort() }
        }
        #expect(result == .finished)
        #expect(callbacks < 20)

        // The session stays usable after an abort.
        var text = ""
        _ = try session.respond(to: "Say OK.", thinking: false, maxTokens: 16) { text = $0 }
        #expect(!text.isEmpty)
    }

    /// A reply stopped mid-way is committed to the history with its turn
    /// closed, and the cache holds each turn exactly once: the incremental
    /// session ends up with the same prompt context as a clean replay of the
    /// same conversation.
    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func stoppedReplyKeepsTheCacheConsistent() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let question = "Reply with only the word OK."

        let live = try model.makeSession(TestSupport.greedy)
        defer { live.destroy() }
        var hi = ""
        _ = try live.respond(to: "Say hi.", thinking: false, maxTokens: 16) { hi = $0 }
        var story = ""
        _ = try live.respond(to: "Write a long story about a lighthouse keeper.", thinking: false,
                             maxTokens: 40) { story = $0 }
        _ = try live.respond(to: question, thinking: false, maxTokens: 8) { _ in }
        let liveContext = Self.contextUsed(live.report)

        let replayed = try model.makeSession(TestSupport.greedy)
        defer { replayed.destroy() }
        replayed.replay([(user: "Say hi.", assistant: hi),
                         (user: "Write a long story about a lighthouse keeper.", assistant: story)])
        _ = try replayed.respond(to: question, thinking: false, maxTokens: 8) { _ in }
        let replayedContext = Self.contextUsed(replayed.report)

        #expect(liveContext > 0 && replayedContext > 0)
        // Same conversation, same greedy answer: the same cache, give or take
        // tokenization at the turn boundaries.
        #expect(abs(liveContext - replayedContext) <= 4,
                "incremental \(liveContext) vs replayed \(replayedContext) tokens")
    }

    /// Continue picks up a stopped reply where it left off: greedy decoding
    /// gives the same text as an uninterrupted run, and only the token
    /// sampled before the stop is fed (no re-prefill).
    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func continueResumesAStoppedReply() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let prompt = "Count from 1 to 30, numbers separated by spaces, nothing else."

        let whole = try model.makeSession(TestSupport.greedy)
        defer { whole.destroy() }
        var uninterrupted = ""
        _ = try whole.respond(to: prompt, thinking: false, maxTokens: 50) { uninterrupted = $0 }

        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        var partial = ""
        _ = try session.respond(to: prompt, thinking: false, maxTokens: 12) { partial = $0 }
        let promptTokensBefore = Self.promptTokens(session.report)
        var continued = ""
        _ = try session.continueResponse(thinking: false, maxTokens: 38) { continued = $0 }

        #expect(!partial.isEmpty && continued.hasPrefix(partial))
        #expect(continued == uninterrupted, "continued \(continued) vs uninterrupted \(uninterrupted)")
        let fed = Self.promptTokens(session.report) - promptTokensBefore
        #expect(fed <= 2, "continue fed \(fed) prompt tokens")

        // The next turn follows the completed reply as usual.
        var next = ""
        _ = try session.respond(to: "Reply with only the word OK.", thinking: false, maxTokens: 8) { next = $0 }
        #expect(next.localizedCaseInsensitiveContains("ok"))
    }

    /// After a relaunch the partial reply comes back through the replayed
    /// history; continuing re-feeds it and keeps going.
    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func continueAfterReplay() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let prompt = "Count from 1 to 30, numbers separated by spaces, nothing else."
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        session.replay([(user: prompt, assistant: "1 2 3 4 5 6 7 8")])
        var continued = ""
        _ = try session.continueResponse(thinking: false, maxTokens: 60) { continued = $0 }
        #expect(continued.hasPrefix("1 2 3 4 5 6 7 8"))
        #expect(continued.contains("9 10 11"), "\(continued)")
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func continueNeedsAReply() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        #expect(throws: LlamaError.cannotContinue) {
            try session.continueResponse(thinking: false) { _ in }
        }
    }

    /// Stop, Continue, stop again, then a new turn: the cache must hold the
    /// same conversation as a clean replay of it, so the new turn gets the
    /// same answer. Whether that answer is "OK" or more of the story is up to
    /// the model; most small ones carry on with a cut-off story.
    @Test(arguments: [TestSupport.textModel, "Qwen3-0.6B-Q4_K_M.gguf", "LFM2.5-2.6B-Q4_K_M.gguf",
                     "Phi-4-mini-instruct-Q4_K_M.gguf"].filter(TestSupport.has))
    func continuedReplyKeepsTheCacheConsistent(_ file: String) throws {
        let model = try Engine.loadModel(at: TestSupport.model(file))
        defer { model.unload() }
        let story = "Write a long, detailed story about a lighthouse keeper, at least 800 words."
        let question = "Say OK."

        let live = try model.makeSession(TestSupport.greedy)
        defer { live.destroy() }
        var reply = ""
        _ = try live.respond(to: story, thinking: false, maxTokens: 40) { reply = $0 }
        _ = try live.continueResponse(thinking: false, maxTokens: 40) { reply = $0 }
        var liveAnswer = ""
        _ = try live.respond(to: question, thinking: false, maxTokens: 12) { liveAnswer = $0 }
        let liveContext = Self.contextUsed(live.report)

        let replayed = try model.makeSession(TestSupport.greedy)
        defer { replayed.destroy() }
        replayed.replay([(user: story, assistant: reply)])
        var replayedAnswer = ""
        _ = try replayed.respond(to: question, thinking: false, maxTokens: 12) { replayedAnswer = $0 }
        let replayedContext = Self.contextUsed(replayed.report)

        #expect(abs(liveContext - replayedContext) <= 4,
                "incremental \(liveContext) vs replayed \(replayedContext) tokens")
        #expect(liveAnswer == replayedAnswer, "live \(liveAnswer) vs replayed \(replayedAnswer)")
    }

    private static func promptTokens(_ report: String) -> Int {
        report.firstMatch(of: /Prompt tokens: (\d+)/).flatMap { Int($0.1) } ?? 0
    }

    /// Tokens in the cache, from the session report.
    private static func contextUsed(_ report: String) -> Int {
        report.firstMatch(of: /Context: (\d+)/).flatMap { Int($0.1) } ?? 0
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.textModel)))
    func toolRoundTrip() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.textModel))
        defer { model.unload() }
        #expect(model.supportsToolCalling)
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }

        session.setTools("""
        [{"type":"function","function":{"name":"calculator","description":"Evaluate an arithmetic expression and return the numeric result.","parameters":{"type":"object","properties":{"expression":{"type":"string","description":"Arithmetic expression, e.g. 17+25"}},"required":["expression"]}}}]
        """)
        let first = try session.respond(to: "Use the calculator tool to compute 1234*5678.",
                                        thinking: false, maxTokens: 256) { _ in }
        guard case .toolCalls(let json) = first else {
            Issue.record("expected a tool call, got \(first)")
            return
        }
        let calls = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [[String: Any]]
        let call = try #require(calls?.first)
        #expect(call["name"] as? String == "calculator")
        let id = call["id"] as? String ?? "0"

        var text = ""
        let second = try session.continueWithToolResults(
            #"[{"id":"\#(id)","name":"calculator","content":"{\"result\":7006652}"}]"#,
            thinking: false, maxTokens: 128) { text = $0 }
        #expect(second == .finished)
        #expect(text.contains("7006652") || text.contains("7,006,652"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.visionModel) && TestSupport.has(TestSupport.visionProjector)))
    func describesAnImage() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.visionModel))
        defer { model.unload() }
        try model.attachProjector(at: TestSupport.model(TestSupport.visionProjector))
        #expect(model.supportsVision)

        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        let image = try Data(contentsOf: TestSupport.fixtures.appending(path: "test_cat.jpg"))
        var text = ""
        _ = try session.respond(to: "What animal is in this image? One word.", image: image,
                                thinking: false, maxTokens: 32) { text = $0 }
        #expect(text.localizedCaseInsensitiveContains("cat"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.visionModel) && TestSupport.has(TestSupport.visionProjector)))
    func unreadableImageIsReportedAsSuch() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.visionModel))
        defer { model.unload() }
        try model.attachProjector(at: TestSupport.model(TestSupport.visionProjector))
        let session = try model.makeSession(TestSupport.greedy)
        defer { session.destroy() }
        #expect(throws: LlamaError.imageRejected) {
            try session.respond(to: "What is this?", image: Data("not an image".utf8),
                                thinking: false, maxTokens: 8) { _ in }
        }
        // The failed turn was rolled back: the session still answers.
        var text = ""
        _ = try session.respond(to: "Say OK.", thinking: false, maxTokens: 8) { text = $0 }
        #expect(!text.isEmpty)
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.embeddingModel)))
    func embeddingsAreNormalizedAndMeaningful() throws {
        let model = try Engine.loadModel(at: TestSupport.model(TestSupport.embeddingModel))
        defer { model.unload() }
        let session = try model.makeEmbeddingSession(contextSize: 2048)
        defer { session.destroy() }

        let cat = try session.embed("A cat is sleeping on the sofa.")
        let kitten = try session.embed("A kitten naps on the couch.")
        let tax = try session.embed("Quarterly corporate tax filing deadlines.")
        #expect(cat.count == session.dimension)
        let norm = sqrt(cat.reduce(0) { $0 + $1 * $1 })
        #expect(abs(norm - 1) < 0.01)
        func dot(_ a: [Float], _ b: [Float]) -> Float { zip(a, b).reduce(0) { $0 + $1.0 * $1.1 } }
        #expect(dot(cat, kitten) > dot(cat, tax))
    }
}

/// Parakeet speech recognition through the bridge.
@Suite(.serialized)
@AsrActor
struct AsrTests {

    @Test(.enabled(if: TestSupport.has(TestSupport.asrModel)))
    func transcribesSpeech() throws {
        let model = try AsrModel(url: TestSupport.model(TestSupport.asrModel))
        defer { model.unload() }
        let samples = try AudioFile.samples(TestSupport.fixtures.appending(path: "audio/jfk.wav"))
        let text = try model.transcribe(samples)
        #expect(text.localizedCaseInsensitiveContains("ask not what your country can do for you"))
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.asrModel)))
    func unloadReleasesMemory() throws {
        let before = TestSupport.footprintMB
        do {
            let model = try AsrModel(url: TestSupport.model(TestSupport.asrModel))
            let samples = try AudioFile.samples(TestSupport.fixtures.appending(path: "audio/jfk.wav"))
            _ = try model.transcribe(samples)
            #expect(TestSupport.footprintMB > before + 200)
            model.unload()
        }
        // Parakeet's process-wide compute backend must go with the last model.
        #expect(TestSupport.footprintMB < before + 100)
    }

    @Test(.enabled(if: TestSupport.has(TestSupport.asrModel)))
    func streamsSpeech() throws {
        let model = try AsrModel(url: TestSupport.model(TestSupport.asrModel))
        defer { model.unload() }
        let samples = try AudioFile.samples(TestSupport.fixtures.appending(path: "audio/jfk.wav"))
        let stream = try model.makeStream()
        defer { stream.close() }
        var text = ""
        let chunk = 16_000 / 2
        var offset = 0
        while offset < samples.count {
            let end = min(offset + chunk, samples.count)
            text += stream.feed(Array(samples[offset..<end]))
            offset = end
        }
        text += stream.finish()
        #expect(!text.contains("<"))
        #expect(text.localizedCaseInsensitiveContains("your country"))
    }
}
