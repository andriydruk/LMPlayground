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
