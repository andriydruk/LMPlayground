import Foundation
import LlamaKit

/// Runs tool calls between generation rounds. Implemented by the tool
/// registry (M5); nil disables tool calling for the turn.
protocol ToolExecutor: Sendable {
    /// OpenAI-format tool array for the enabled tools, or nil for none.
    var toolsJSON: String? { get async }
    /// Execute `callsJSON` (as returned by the engine) and return the results
    /// array to feed back.
    func execute(callsJSON: String) async -> String
    func cancelInFlight() async
}

/// What the conversation screen hears during a turn. Called on the
/// inference thread; implementations hop to the main actor.
struct TurnObserver: Sendable {
    /// Full processed reply so far, with the thinking/answer token split.
    var onDelta: @Sendable (_ text: String, _ thinkingTokens: Int, _ responseTokens: Int, _ thinkingJustStarted: Bool) -> Void
    /// A tool round finished.
    var onToolCalls: @Sendable ([ToolCallInfo]) -> Void
    /// The model is answering again after tools; `thinking` says whether a
    /// thinking block is expected first.
    var onRoundStarted: @Sendable (_ thinking: Bool) -> Void
}

/// One generation turn, including tool rounds: port of Android's
/// GenerationCoordinator streaming callback and :llamacpp's ToolCallLoop.
enum TurnRunner {

    static let maxToolRounds = 5

    struct Outcome: Sendable {
        var totalTokens: Int
        var toolRounds: Int
        var hitRoundLimit: Bool
    }

    /// Counts tokens and tracks the thinking phase like Android's callback.
    private final class StreamState: @unchecked Sendable {
        var totalTokens = 0
        var thinkingTokenCount = 0
        var thinkingComplete: Bool
        var modelIsThinking: Bool

        init(thinking: Bool) {
            thinkingComplete = !thinking
            modelIsThinking = thinking
        }

        /// Counting on from a reply that was cut short.
        init(resuming reply: Resume) {
            totalTokens = reply.thinkingTokens + reply.responseTokens
            thinkingTokenCount = reply.thinkingTokens
            thinkingComplete = !reply.inThinking
            modelIsThinking = reply.inThinking
        }

        func reset(thinking: Bool) {
            totalTokens = 0
            thinkingTokenCount = 0
            thinkingComplete = !thinking
            modelIsThinking = thinking
        }
    }

    /// Where a reply cut short left off, so its counts carry on.
    struct Resume: Sendable {
        var thinkingTokens: Int
        var responseTokens: Int
        /// Stopped inside the thinking block.
        var inThinking: Bool
    }

    /// How the turn begins: a new message, or more of the last reply.
    enum Start: Sendable {
        case message(String, image: Data?)
        case continuation(Resume)
    }

    @InferenceActor
    static func run(session: GenerationSession,
                    message: String,
                    image: Data?,
                    enableThinking: Bool,
                    supportsThinking: Bool,
                    tools: (any ToolExecutor)?,
                    observer: TurnObserver) async throws -> Outcome {
        try await run(session: session, start: .message(message, image: image), enableThinking: enableThinking,
                      supportsThinking: supportsThinking, tools: tools, observer: observer)
    }

    @InferenceActor
    static func run(session: GenerationSession,
                    start: Start,
                    enableThinking: Bool,
                    supportsThinking: Bool,
                    tools: (any ToolExecutor)?,
                    observer: TurnObserver) async throws -> Outcome {
        let toolsJSON = await tools?.toolsJSON
        session.setTools(toolsJSON ?? "[]")

        let state: StreamState = switch start {
        case .message: StreamState(thinking: enableThinking)
        case .continuation(let resume): StreamState(resuming: resume)
        }
        let onResponse: (String) -> Void = { response in
            state.totalTokens += 1
            let text = ResponseProcessor.process(response)
            // Some models think whatever the toggle says (LFM 2.5).
            var justStarted = false
            if !state.modelIsThinking, text.hasPrefix("<think>") {
                state.modelIsThinking = true
                state.thinkingComplete = false
                justStarted = true
            }
            if !state.thinkingComplete, text.contains("</think>") {
                state.thinkingComplete = true
                state.thinkingTokenCount = state.totalTokens
            }
            let thinkingTokens = state.thinkingComplete ? state.thinkingTokenCount : state.totalTokens
            observer.onDelta(text, thinkingTokens, state.totalTokens - thinkingTokens, justStarted)
        }

        var result: RoundResult = switch start {
        case .message(let message, let image):
            try session.respond(to: message, image: image, thinking: enableThinking, onResponse: onResponse)
        case .continuation:
            try session.continueResponse(thinking: enableThinking, onResponse: onResponse)
        }
        var rounds = 0
        var hitLimit = false
        var total = state.totalTokens

        while case .toolCalls(let callsJSON) = result, let tools, !Task.isCancelled {
            if rounds >= maxToolRounds { hitLimit = true; break }
            rounds += 1
            let started = ContinuousClock.now
            let results = await withTaskCancellationHandler {
                await tools.execute(callsJSON: callsJSON)
            } onCancel: {
                Task { await tools.cancelInFlight() }
            }
            let durationMs = Int64((ContinuousClock.now - started).seconds * 1000)
            observer.onToolCalls(ToolCallPairing.pair(callsJSON: callsJSON, resultsJSON: results, durationMs: durationMs))
            if Task.isCancelled { break }

            // Force thinking on for the answer when the model can think,
            // whatever the toggle: Gemma 4 and harmony-style models emit an
            // empty answer after tool calls with thinking off (ToolCallLoop).
            let responseThinking = supportsThinking || enableThinking
            state.reset(thinking: responseThinking)
            observer.onRoundStarted(responseThinking)
            result = try session.continueWithToolResults(results, thinking: responseThinking, onResponse: onResponse)
            total += state.totalTokens
        }
        if rounds == 0 { total = state.totalTokens }
        return Outcome(totalTokens: total, toolRounds: rounds, hitRoundLimit: hitLimit)
    }
}

/// Pairs each tool call with its result (port of :llamacpp ToolCallPairing):
/// matched on the call id, since nothing guarantees the results keep the
/// engine's order, with the round's duration split evenly across its calls.
enum ToolCallPairing {
    static func pair(callsJSON: String, resultsJSON: String, durationMs: Int64) -> [ToolCallInfo] {
        let calls = (try? JSONSerialization.jsonObject(with: Data(callsJSON.utf8)) as? [[String: Any]]) ?? []
        let results = (try? JSONSerialization.jsonObject(with: Data(resultsJSON.utf8)) as? [[String: Any]]) ?? []
        var byId: [String: String] = [:]
        for r in results {
            if let id = r["id"] as? String { byId[id] = r["content"] as? String ?? "" }
        }
        let perCall = durationMs / Int64(max(calls.count, 1))
        return calls.map { call in
            ToolCallInfo(name: call["name"] as? String ?? "",
                         arguments: call["arguments"] as? String ?? "",
                         result: (call["id"] as? String).flatMap { byId[$0] } ?? "",
                         durationMs: perCall)
        }
    }
}
