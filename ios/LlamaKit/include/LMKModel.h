//
// A loaded model. Created by +[LMKEngine loadModelAtPath:...].
//
#import <Foundation/Foundation.h>
#import <LlamaKit/LMKTypes.h>

@class LMKGenerationSession;
@class LMKEmbeddingSession;

NS_ASSUME_NONNULL_BEGIN

@interface LMKModel : NSObject

- (instancetype)init NS_UNAVAILABLE;

#if DEBUG
/// A model with no weights, for store screenshots: properties read as empty
/// and session creation fails.
+ (instancetype)placeholder;
#endif

/// Weight bytes.
@property (nonatomic, readonly) uint64_t modelSize;
/// Context length the model was trained with.
@property (nonatomic, readonly) int32_t contextTrainSize;
/// Architecture, quantization and size, for diagnostics.
@property (nonatomic, readonly, copy) NSString *modelReport;

/// Detected from the chat template at load time.
@property (nonatomic, readonly) BOOL supportsThinking;
@property (nonatomic, readonly) BOOL supportsToolCalling;
/// True once a compatible projector (mmproj) is attached.
@property (nonatomic, readonly) BOOL supportsVision;

/// Attach the multimodal projector. Re-attaching the same file is a no-op.
- (BOOL)attachProjectorAtPath:(NSString *)path error:(NSError **)error;

- (nullable LMKGenerationSession *)createSessionWithParams:(LMKSamplerParams *)params
                                                     error:(NSError **)error;

- (nullable LMKEmbeddingSession *)createEmbeddingSessionWithContextSize:(int32_t)contextSize
                                                                  error:(NSError **)error;

/// Free the weights. Destroy every session created from this model first.
/// Also runs on dealloc.
- (void)unload;

@end

NS_ASSUME_NONNULL_END
