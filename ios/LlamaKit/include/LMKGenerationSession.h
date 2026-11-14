//
// One conversation against a loaded model: the shared C++
// LlamaGenerationSession (prompt rendering, KV-cache reuse, context
// compaction, thinking budget, tool-call grammar, vision turns).
//
#import <Foundation/Foundation.h>
#import <LlamaKit/LMKTypes.h>

NS_ASSUME_NONNULL_BEGIN

@interface LMKGenerationSession : NSObject

- (instancetype)init NS_UNAVAILABLE;

/// Stage image bytes (an encoded, already downscaled JPEG/PNG) for the next
/// `-addMessage:`. Needs an attached projector.
- (void)setImageData:(NSData *)imageData;

/// Append a user turn and prepare its prompt. Returns NO if the chat template
/// could not be rendered (the turn is rolled back).
- (BOOL)addMessage:(NSString *)message enableThinking:(BOOL)enableThinking;

/// Sample one token. `onResponse` receives the full processed response so
/// far (thinking in `<think>…</think>`), trimmed of an incomplete trailing
/// UTF-8 sequence.
- (LMKStepResult)generateStep:(void (NS_NOESCAPE ^)(NSString *response))onResponse;

/// Interrupt the current decode, including a long prompt evaluation. Safe
/// from any thread. Cleared by the next `-addMessage:` or `-submitToolResults:`.
- (void)requestAbort;

/// Rebuild state from persisted turns (equal-length arrays).
- (void)replayHistoryWithUserMessages:(NSArray<NSString *> *)userMessages
                    assistantMessages:(NSArray<NSString *> *)assistantMessages;

/// OpenAI-format tool array; "[]" disables tool calling.
- (void)setToolsJSON:(NSString *)toolsJSON;
/// Valid after a step returned `LMKStepResultToolCalls`.
@property (nonatomic, readonly, copy) NSString *toolCallsJSON;
/// Feed tool results back into the same turn. Returns NO on failure.
- (BOOL)submitToolResults:(NSString *)resultsJSON enableThinking:(BOOL)enableThinking;

/// Reopen the last assistant reply (stopped, interrupted, or the last turn of
/// a replayed history) so `-generateStep:` keeps writing it. Reuses the cache
/// when it still holds that turn. NO when there's nothing to continue, the
/// context is full, or an image turn is no longer cached.
- (BOOL)continueResponseWithThinking:(BOOL)enableThinking;

/// Drop user turn `turnIndex` and everything after it, keeping the cache up to
/// there. `expectedText`, when not empty, must occur in that turn. Returns the
/// tokens kept, or -1 when the session is untouched and needs a replay.
- (int32_t)rewindToTurn:(int32_t)turnIndex expectedText:(NSString *)expectedText;

/// The rendered static preamble (system prompt + tool descriptions), or empty.
- (NSString *)renderPreambleWithThinking:(BOOL)enableThinking;
/// Persistent preamble KV cache: `<path>.bin` / `<path>.json`. Empty path
/// disables it. Consulted on the first `-addMessage:`.
- (void)setPreambleCachePath:(NSString *)path fingerprint:(NSString *)fingerprint;

/// Timing and throughput of the last turn.
@property (nonatomic, readonly, copy) NSString *report;

/// Release native resources. Safe to call twice; also runs on dealloc.
- (void)destroy;

@end

NS_ASSUME_NONNULL_END
