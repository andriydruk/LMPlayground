import Foundation
import Testing
import LlamaKit
@testable import LMPlayground

/// Ports of the Android unit tests for the logic both apps share.
struct ThinkingSplitTests {

    @Test func plainTextHasNoThinking() {
        let split = ThinkingSplit("Hello there")
        #expect(split.thinking == "")
        #expect(split.response == "Hello there")
    }

    @Test func closedBlockIsSplitOut() {
        let split = ThinkingSplit("<think>\nLet me see.\n</think>\nThe answer is 4.")
        #expect(split.thinking == "Let me see.")
        #expect(split.response == "The answer is 4.")
    }

    @Test func streamingBlockIsAllThinking() {
        let split = ThinkingSplit("<think>still working")
        #expect(split.thinking == "still working")
        #expect(split.response == "")
    }

    @Test func multipleBlocksAreJoined() {
        let split = ThinkingSplit("<think>one</think><think>two</think>done")
        #expect(split.thinking == "one\ntwo")
        #expect(split.response == "done")
    }
}

struct ResponseProcessorTests {

    @Test func separatorAfterThinkingIsRemoved() {
        #expect(ResponseProcessor.process("<think>x</think>\n---\nAnswer") == "<think>x</think>\n\nAnswer")
        #expect(ResponseProcessor.process("<think>x</think>———Answer") == "<think>x</think>\n\nAnswer")
    }

    @Test func textWithoutThinkingIsUntouched() {
        #expect(ResponseProcessor.process("a --- b") == "a --- b")
    }
}

struct PairingTests {

    @Test func turnsPairUserWithFollowingAssistant() {
        let messages = [
            ChatMessage(role: .assistant, content: "orphan"),
            ChatMessage(role: .user, content: "q1"),
            ChatMessage(role: .assistant, content: "a1"),
            ChatMessage(role: .user, content: "q2"),
        ]
        let pairs = ConversationModel.pairTurns(messages)
        #expect(pairs.count == 1)
        #expect(pairs[0].user == "q1")
        #expect(pairs[0].assistant == "a1")
    }

    @Test func toolCallsPairByIdNotPosition() {
        let calls = #"[{"id":"a","name":"web_search","arguments":"{}"},{"id":"b","name":"web_fetch","arguments":"{}"}]"#
        let results = #"[{"id":"b","name":"web_fetch","content":"page"},{"id":"a","name":"web_search","content":"hits"}]"#
        let paired = ToolCallPairing.pair(callsJSON: calls, resultsJSON: results, durationMs: 100)
        #expect(paired.map(\.result) == ["hits", "page"])
        #expect(paired.allSatisfy { $0.durationMs == 50 })
    }

    @Test func projectorsPairByModelIdentity() {
        let files = ["mmproj-Qwen_Qwen3.5-0.8B-f16.gguf", "gemma-3-4b-it-mmproj-f16.gguf", "mmproj-gemma-4-E2B-it-BF16.gguf"]
        #expect(MmprojPairing.findMmproj(for: "Qwen_Qwen3.5-0.8B-Q3_K_M.gguf", in: files) == "mmproj-Qwen_Qwen3.5-0.8B-f16.gguf")
        #expect(MmprojPairing.findMmproj(for: "gemma-3-4b-it-Q4_K_M.gguf", in: files) == "gemma-3-4b-it-mmproj-f16.gguf")
        #expect(MmprojPairing.findMmproj(for: "gemma-4-E2B_q4_0-it.gguf", in: files) == "mmproj-gemma-4-E2B-it-BF16.gguf")
        #expect(MmprojPairing.findMmproj(for: "Llama-3.2-1B.gguf", in: files) == nil)
    }
}

struct CatalogTests {

    @Test func filenamesAreUnique() {
        let names = ModelCatalog.chatModels.map(\.filename)
        #expect(Set(names).count == names.count)
    }

    @Test func everyOfferedModelHasAURL() {
        for model in ModelCatalog.chatModels where !model.deprecated {
            #expect(model.remoteURL.flatMap(URL.init(string:)) != nil, "\(model.filename)")
            if model.mmprojFilename != nil {
                #expect(model.mmprojURL.flatMap(URL.init(string:)) != nil, "\(model.filename) projector")
            }
        }
    }

    @Test func helperModelsAreKnown() {
        #expect(ModelCatalog.knownFilenames.contains(ModelCatalog.embeddingModel.filename))
        #expect(ModelCatalog.knownFilenames.contains(ModelCatalog.dictationModel.filename))
    }
}

@MainActor
struct MemoryBudgetTests {

    @Test func projectorAndContextDriveTheEstimate() {
        let small = MemoryBudget.estimate(metadata: nil, contextSize: 4096, projectorBytes: 0)
        let withProjector = MemoryBudget.estimate(metadata: nil, contextSize: 4096, projectorBytes: 500 << 20)
        #expect(withProjector > small + (500 << 20))
    }

    @Test func tooLargeWhenOverAvailable() {
        let verdict = MemoryBudget.check(metadata: nil, contextSize: 0, projectorBytes: 2 << 30, available: 1 << 30)
        guard case .tooLarge = verdict else {
            Issue.record("expected tooLarge, got \(verdict)")
            return
        }
    }

    /// Weights past Metal's working set make garbage even when RAM is free.
    /// Header numbers and the working set of an iPhone 16 Pro, where Llama
    /// 3.1 8B failed with a 4K context and the Qwen-based 7B worked.
    @Test func tooLargeWhenOverTheGPUWorkingSet() {
        func model(weightsMB: UInt64, kvPerToken: UInt64) -> ModelMetadata {
            ModelMetadata(name: "m", hasChatTemplate: true, architecture: "llama", contextLength: 32768,
                          kvBytesPerToken: kvPerToken, gpuWeightBytes: weightsMB << 20, isProjector: false)
        }
        let workingSet: UInt64 = 5461 << 20
        let llama8B = model(weightsMB: 4685, kvPerToken: 128 << 10)
        let verdict = MemoryBudget.check(metadata: llama8B, contextSize: 4096, projectorBytes: 0,
                                         available: 8 << 30, gpuWorkingSet: workingSet)
        guard case .tooLarge = verdict else {
            Issue.record("expected tooLarge, got \(verdict)")
            return
        }
        // The same model works with a 2K context and fails with 3K.
        #expect(MemoryBudget.check(metadata: llama8B, contextSize: 2048, projectorBytes: 0,
                                   available: 8 << 30, gpuWorkingSet: workingSet) == .fits)
        #expect(MemoryBudget.check(metadata: llama8B, contextSize: 3072, projectorBytes: 0,
                                   available: 8 << 30, gpuWorkingSet: workingSet) != .fits)
        let qwen7B = model(weightsMB: 4460, kvPerToken: 56 << 10)
        #expect(MemoryBudget.check(metadata: qwen7B, contextSize: 4096, projectorBytes: 0,
                                   available: 8 << 30, gpuWorkingSet: workingSet) == .fits)
        // An unknown working set leaves the decision to the RAM check.
        #expect(MemoryBudget.check(metadata: llama8B, contextSize: 4096, projectorBytes: 0,
                                   available: 8 << 30, gpuWorkingSet: 0) == .fits)
    }

    @Test func noProcessLimitMeansNoRAMVerdict() {
        // os_proc_available_memory is 0 in the simulator and on a Mac.
        let verdict = MemoryBudget.check(metadata: nil, contextSize: 4096, projectorBytes: 2 << 30,
                                         available: 0, gpuWorkingSet: 0)
        #expect(verdict == .fits)
    }

    @Test func maxContextRespectsTheGPUWorkingSet() {
        let meta = ModelMetadata(name: "m", hasChatTemplate: true, architecture: "llama", contextLength: 0,
                                 kvBytesPerToken: 1 << 20, gpuWeightBytes: 1 << 30, isProjector: false)
        let limit = MemoryBudget.maxContext(metadata: meta, projectorBytes: 0, available: 64 << 30,
                                            gpuWorkingSet: (1 << 30) + MemoryBudget.gpuComputeBytes + (100 << 20))
        #expect(limit == 100)
    }
}

struct ChatStoreTests {

    @Test func deletingASessionCascadesToItsMessages() throws {
        let store = ChatStore(database: try AppDatabase.makeEmpty())
        let session = try store.createSession(title: "Hello", model: nil, params: GenerationParams(), systemPrompt: "")
        var message = ChatMessage(role: .user, content: "hi")
        message.imagePath = "cat.jpg"
        try store.insert(message.record(sessionId: session.id))
        try store.insert(ChatMessage(role: .assistant, content: "hello").record(sessionId: session.id))
        #expect(try store.messages(in: session.id).count == 2)

        let images = try store.deleteSession(session.id)
        #expect(images == ["cat.jpg"])
        #expect(try store.messages(in: session.id).isEmpty)
        #expect(try store.session(session.id) == nil)
    }

    @Test func messagesRoundTrip() throws {
        let store = ChatStore(database: try AppDatabase.makeEmpty())
        let session = try store.createSession(title: "T", model: nil, params: GenerationParams(), systemPrompt: "Be brief")
        var reply = ChatMessage(role: .assistant, content: "<think>hmm</think>ok")
        reply.thinkingTokens = 3
        reply.responseTokens = 1
        reply.interrupted = true
        reply.toolCalls = [ToolCallInfo(name: "web_search", arguments: "{}", result: "[]")]
        try store.insert(reply.record(sessionId: session.id))

        let loaded = try #require(try store.messages(in: session.id).first.map(ChatMessage.init(record:)))
        #expect(loaded.content == reply.content)
        #expect(loaded.thinkingTokens == 3)
        #expect(loaded.interrupted)
        #expect(loaded.toolCalls.first?.name == "web_search")
        #expect(try store.session(session.id)?.systemPrompt == "Be brief")
    }

    @Test func promptsAreOrderedByUseWithTheModel() throws {
        let store = ChatStore(database: try AppDatabase.makeEmpty())
        let a = try #require(try store.createPrompt(text: "Pirate"))
        let b = try #require(try store.createPrompt(text: "Poet"))
        try store.touchPromptUsage(promptId: a.id, modelFilename: "m.gguf")
        #expect(try store.prompts(recentFor: "m.gguf").first?.id == a.id)
        #expect(try store.createPrompt(text: "Poet")?.id == b.id)
    }
}

struct SupersededFileCleanupTests {

    @Test func deletesOnlyOnceTheReplacementIsPresent() {
        #expect(SupersededFileCleanup.deletable(present: ["Qwen_Qwen3.5-2B-Q3_K_M.gguf"]).isEmpty)
        #expect(SupersededFileCleanup.deletable(present: ["Qwen_Qwen3.5-2B-Q3_K_M.gguf", "Qwen_Qwen3.5-2B-IQ4_XS.gguf"])
                == ["Qwen_Qwen3.5-2B-Q3_K_M.gguf"])
        #expect(SupersededFileCleanup.deletable(present: ["Qwen_Qwen3.5-2B-IQ4_XS.gguf"]).isEmpty)
    }

    @Test func runRemovesRetiredFiles() throws {
        let folder = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for name in ["mmproj-gemma-4-E2B-it-BF16.gguf", "mmproj-gemma-4-E2B-it-Q8_0.gguf", "Qwen_Qwen3.5-4B-Q3_K_M.gguf"] {
            try Data(count: 10).write(to: folder.appending(path: name))
        }
        #expect(SupersededFileCleanup.run(in: folder) == 10)
        let left = try FileManager.default.contentsOfDirectory(atPath: folder.path).sorted()
        #expect(left == ["Qwen_Qwen3.5-4B-Q3_K_M.gguf", "mmproj-gemma-4-E2B-it-Q8_0.gguf"])
    }
}
