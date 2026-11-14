//
// Embeddings-enabled context over a loaded model (mean pooling,
// L2-normalized output), independent of generation sessions.
//
#import <Foundation/Foundation.h>
#import <LlamaKit/LMKTypes.h>

NS_ASSUME_NONNULL_BEGIN

@interface LMKEmbeddingSession : NSObject

- (instancetype)init NS_UNAVAILABLE;

/// Floats per embedding.
@property (nonatomic, readonly) int32_t dimension;

/// Embed one text: `dimension` float32 values, L2-normalized. Inputs longer
/// than the context are truncated.
- (nullable NSData *)embedText:(NSString *)text error:(NSError **)error;

- (void)destroy;

@end

NS_ASSUME_NONNULL_END
