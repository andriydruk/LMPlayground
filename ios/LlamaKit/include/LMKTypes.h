//
// Value types shared by the LlamaKit bridge.
//
#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

extern NSErrorDomain const LMKErrorDomain;

typedef NS_ERROR_ENUM(LMKErrorDomain, LMKErrorCode) {
    LMKErrorModelLoadFailed = 1,
    LMKErrorProjectorLoadFailed = 2,
    LMKErrorSessionCreationFailed = 3,
    LMKErrorTemplateRenderFailed = 4,
    LMKErrorEmbeddingFailed = 5,
    LMKErrorAsrFailed = 6,
};

/// Result of one `-[LMKGenerationSession generateStep:]` call. Mirrors the
/// native contract: a step samples (at most) one token.
typedef NS_ENUM(NSInteger, LMKStepResult) {
    /// A token was produced; call again.
    LMKStepResultMore = 0,
    /// The turn ended (end of generation, a stop, an abort or an error).
    LMKStepResultDone = 1,
    /// The model emitted tool calls; read `toolCallsJSON`, then
    /// `submitToolResults:` and keep stepping.
    LMKStepResultToolCalls = 2,
};

/// Which device runs the vision encoder.
typedef NS_ENUM(NSInteger, LMKVisionDevice) {
    /// The GPU (Metal) when present, otherwise the CPU.
    LMKVisionDeviceAutomatic = 0,
    /// Always the CPU (the simulator, or a debugging override).
    LMKVisionDeviceCPU = 1,
};

/// Sampling and context settings for a generation session.
@interface LMKSamplerParams : NSObject <NSCopying>
@property (nonatomic) int32_t contextSize;
@property (nonatomic) float temperature;
@property (nonatomic) float topP;
@property (nonatomic) float repetitionPenalty;
@property (nonatomic) int32_t topK;
@property (nonatomic) float minP;
/// Negative means a random seed.
@property (nonatomic) int64_t seed;
/// Thinking token budget; -1 means unlimited.
@property (nonatomic) int32_t thinkingBudget;
@property (nonatomic, copy) NSString *systemPrompt;
@end

/// What a GGUF header says about a file, read without loading tensors.
@interface LMKModelMetadata : NSObject
@property (nonatomic, readonly, copy) NSString *name;
@property (nonatomic, readonly) BOOL hasChatTemplate;
@end

NS_ASSUME_NONNULL_END
