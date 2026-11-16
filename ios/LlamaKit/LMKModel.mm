#import "LMKModel.h"
#import "LMKInternal.h"

#include "LlamaCpp.h"

@implementation LMKModel {
    std::unique_ptr<LlamaModel> _model;
}

- (instancetype)initWithModel:(std::unique_ptr<LlamaModel>)model {
    if ((self = [super init])) {
        _model = std::move(model);
    }
    return self;
}

#if DEBUG
+ (instancetype)placeholder {
    return [[self alloc] initWithModel:nullptr];
}
#endif

- (void)dealloc {
    [self unload];
}

- (uint64_t)modelSize {
    return _model ? _model->getModelSize() : 0;
}

- (int32_t)contextTrainSize {
    return _model ? _model->getContextTrainSize() : 0;
}

- (NSString *)modelReport {
    if (!_model) return @"";
    return [NSString stringWithUTF8String:_model->getModelReport().c_str()] ?: @"";
}

- (BOOL)supportsThinking {
    return _model && _model->supportsThinking();
}

- (BOOL)supportsToolCalling {
    return _model && _model->supportsToolCalling();
}

- (BOOL)supportsVision {
    return _model && _model->supportsVision();
}

- (BOOL)attachProjectorAtPath:(NSString *)path error:(NSError **)error {
    if (_model) {
        _model->loadMmprojModel(LMKString(path));
    }
    if (!self.supportsVision) {
        if (error) *error = LMKMakeError(LMKErrorProjectorLoadFailed,
                                         [NSString stringWithFormat:@"Failed to load %@", path.lastPathComponent]);
        return NO;
    }
    return YES;
}

- (LMKGenerationSession *)createSessionWithParams:(LMKSamplerParams *)params error:(NSError **)error {
    LlamaGenerationSession *session = nullptr;
    if (_model) {
        SamplerParams native;
        native.n_ctx = params.contextSize;
        native.temperature = params.temperature;
        native.top_p = params.topP;
        native.repetition_penalty = params.repetitionPenalty;
        native.top_k = params.topK;
        native.min_p = params.minP;
        native.seed = params.seed < 0 ? LLAMA_DEFAULT_SEED : static_cast<uint32_t>(params.seed);
        native.thinking_budget = params.thinkingBudget;
        native.system_prompt = LMKString(params.systemPrompt);
        session = _model->createGenerationSession(native);
    }
    if (session == nullptr) {
        if (error) *error = LMKMakeError(LMKErrorSessionCreationFailed, @"Failed to create a session");
        return nil;
    }
    return [[LMKGenerationSession alloc] initWithSession:session];
}

- (LMKEmbeddingSession *)createEmbeddingSessionWithContextSize:(int32_t)contextSize error:(NSError **)error {
    LlamaEmbeddingSession *session = _model ? _model->createEmbeddingSession(contextSize) : nullptr;
    if (session == nullptr) {
        if (error) *error = LMKMakeError(LMKErrorSessionCreationFailed, @"Failed to create an embedding session");
        return nil;
    }
    return [[LMKEmbeddingSession alloc] initWithSession:session];
}

- (void)unload {
    if (_model) {
        _model->unloadModel();
        _model.reset();
    }
}

@end
