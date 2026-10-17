import Foundation

public enum LlamaError: Error, Equatable {
    /// The chat template could not render the turn; it was rolled back.
    case templateRenderFailed
    /// Tool results could not be fed back into the turn.
    case toolResultsRejected
}

/// How a generation round ended.
public enum RoundResult: Sendable, Equatable {
    /// The model finished (or was stopped, or hit `maxTokens`).
    case finished
    /// The model asked for tools: run them, then `continueWithToolResults`.
    case toolCalls(json: String)
}

/// One conversation: the shared C++ LlamaGenerationSession.
///
/// Rounds run synchronously on the inference actor; `onResponse` receives the
/// full processed response so far (thinking inside `<think>…</think>`) after
/// every token, on the inference thread. Call `abort()` from anywhere to stop
/// a round, including during a long prompt evaluation. Cancelling the task
/// that runs a round aborts it too.
@InferenceActor
public final class GenerationSession {
    // Only -requestAbort is touched off the actor; the bridge makes that safe.
    nonisolated(unsafe) private let native: LMKGenerationSession

    init(native: LMKGenerationSession) {
        self.native = native
    }

    public nonisolated func abort() {
        native.requestAbort()
    }

    /// Add a user turn (with an optional encoded image for vision models) and
    /// generate the reply.
    public func respond(to message: String,
                        image: Data? = nil,
                        thinking: Bool,
                        maxTokens: Int = .max,
                        onResponse: (String) -> Void) throws -> RoundResult {
        if let image {
            native.setImageData(image)
        }
        guard native.addMessage(message, enableThinking: thinking) else {
            throw LlamaError.templateRenderFailed
        }
        return run(maxTokens: maxTokens, onResponse: onResponse)
    }

    /// Feed tool results (OpenAI-format JSON array) back into the turn and
    /// keep generating.
    public func continueWithToolResults(_ resultsJSON: String,
                                        thinking: Bool,
                                        maxTokens: Int = .max,
                                        onResponse: (String) -> Void) throws -> RoundResult {
        guard native.submitToolResults(resultsJSON, enableThinking: thinking) else {
            throw LlamaError.toolResultsRejected
        }
        return run(maxTokens: maxTokens, onResponse: onResponse)
    }

    private func run(maxTokens: Int, onResponse: (String) -> Void) -> RoundResult {
        var tokens = 0
        while true {
            let step = native.generateStep(onResponse)
            switch step {
            case .toolCalls:
                return .toolCalls(json: native.toolCallsJSON)
            case .more:
                tokens += 1
                if tokens >= maxTokens || Task.isCancelled {
                    native.requestAbort()
                    return .finished
                }
            case .done:
                return .finished
            @unknown default:
                return .finished
            }
        }
    }

    /// Rebuild the conversation from persisted turns.
    public func replay(_ turns: [(user: String, assistant: String)]) {
        native.replayHistory(withUserMessages: turns.map(\.user),
                             assistantMessages: turns.map(\.assistant))
    }

    /// OpenAI-format tool array; "[]" turns tool calling off.
    public func setTools(_ json: String) {
        native.setToolsJSON(json)
    }

    public func renderPreamble(thinking: Bool) -> String {
        native.renderPreamble(withThinking: thinking)
    }

    /// Persistent preamble KV cache at `<path>.bin` / `<path>.json`.
    public func setPreambleCache(path: String, fingerprint: String) {
        native.setPreambleCachePath(path, fingerprint: fingerprint)
    }

    /// Timing and throughput of the last round.
    public var report: String { native.report }

    public func destroy() {
        native.destroy()
    }
}
