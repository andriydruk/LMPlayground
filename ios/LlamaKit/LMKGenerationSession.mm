#import "LMKGenerationSession.h"
#import "LMKInternal.h"

#include "LlamaCpp.h"
#include "Utf8.h"

#include <atomic>
#include <utility>
#include <vector>

@implementation LMKGenerationSession {
    // Atomic so -requestAbort (any thread) never races -destroy.
    std::atomic<LlamaGenerationSession *> _session;
}

- (instancetype)initWithSession:(LlamaGenerationSession *)session {
    if ((self = [super init])) {
        _session.store(session);
    }
    return self;
}

- (void)dealloc {
    [self destroy];
}

- (void)setImageData:(NSData *)imageData {
    if (auto *s = _session.load()) {
        s->setImageData(static_cast<const unsigned char *>(imageData.bytes), imageData.length);
    }
}

- (BOOL)addMessage:(NSString *)message enableThinking:(BOOL)enableThinking {
    auto *s = _session.load();
    return s != nullptr && s->addMessage(message.UTF8String, enableThinking) == 0;
}

- (LMKStepResult)generateStep:(void (NS_NOESCAPE ^)(NSString *))onResponse {
    auto *s = _session.load();
    if (s == nullptr) return LMKStepResultDone;
    int rc = s->generate([onResponse](const std::string &full) {
        std::string safe = full;
        utf8TrimIncompleteTail(safe);
        NSString *text = [[NSString alloc] initWithBytes:safe.data()
                                                  length:safe.size()
                                                encoding:NSUTF8StringEncoding];
        if (text != nil) onResponse(text);
    });
    switch (rc) {
        case 0:  return LMKStepResultMore;
        case 2:  return LMKStepResultToolCalls;
        default: return LMKStepResultDone;
    }
}

- (void)requestAbort {
    if (auto *s = _session.load()) {
        s->requestAbort();
    }
}

- (void)replayHistoryWithUserMessages:(NSArray<NSString *> *)userMessages
                    assistantMessages:(NSArray<NSString *> *)assistantMessages {
    auto *s = _session.load();
    if (s == nullptr) return;
    std::vector<std::pair<std::string, std::string>> history;
    NSUInteger count = MIN(userMessages.count, assistantMessages.count);
    history.reserve(count);
    for (NSUInteger i = 0; i < count; i++) {
        history.emplace_back(LMKString(userMessages[i]), LMKString(assistantMessages[i]));
    }
    s->replayHistory(history);
}

- (void)setToolsJSON:(NSString *)toolsJSON {
    if (auto *s = _session.load()) {
        s->setTools(toolsJSON.UTF8String);
    }
}

- (NSString *)toolCallsJSON {
    auto *s = _session.load();
    if (s == nullptr) return @"[]";
    return [NSString stringWithUTF8String:s->getToolCallsJson().c_str()] ?: @"[]";
}

- (BOOL)submitToolResults:(NSString *)resultsJSON enableThinking:(BOOL)enableThinking {
    auto *s = _session.load();
    return s != nullptr && s->submitToolResults(resultsJSON.UTF8String, enableThinking) == 0;
}

- (BOOL)continueResponseWithThinking:(BOOL)enableThinking {
    auto *s = _session.load();
    return s != nullptr && s->continueResponse(enableThinking) == 0;
}

- (int32_t)rewindToTurn:(int32_t)turnIndex expectedText:(NSString *)expectedText {
    auto *s = _session.load();
    return s == nullptr ? -1 : s->rewindToTurn(turnIndex, expectedText.UTF8String);
}

- (NSString *)renderPreambleWithThinking:(BOOL)enableThinking {
    auto *s = _session.load();
    if (s == nullptr) return @"";
    return [NSString stringWithUTF8String:s->renderPreambleString(enableThinking).c_str()] ?: @"";
}

- (void)setPreambleCachePath:(NSString *)path fingerprint:(NSString *)fingerprint {
    if (auto *s = _session.load()) {
        s->setPreambleCachePath(path.UTF8String, fingerprint.UTF8String);
    }
}

- (NSString *)report {
    auto *s = _session.load();
    if (s == nullptr) return @"";
    return [NSString stringWithUTF8String:s->getReport().c_str()] ?: @"";
}

- (void)destroy {
    delete _session.exchange(nullptr);
}

@end
