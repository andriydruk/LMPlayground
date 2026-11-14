//
// Voice dictation: a Parakeet ASR model (parakeet.cpp, vendored in the
// llama.cpp fork) and its streaming recognizer. Input is 16 kHz mono float32
// PCM. Calls on one model must be serialized.
//
#import <Foundation/Foundation.h>
#import <LlamaKit/LMKTypes.h>

@class LMKAsrStream;

NS_ASSUME_NONNULL_BEGIN

@interface LMKAsrModel : NSObject

- (instancetype)init NS_UNAVAILABLE;

/// `threads` > 0 overrides the default thread count (first load only).
+ (nullable instancetype)loadModelAtPath:(NSString *)path
                                 threads:(int32_t)threads
                                   error:(NSError **)error NS_SWIFT_NAME(load(atPath:threads:));

/// Transcribe a whole utterance. `language` (e.g. "en") is a hint; nil lets
/// the model detect it.
- (nullable NSString *)transcribeSamples:(const float *)samples
                                   count:(NSInteger)count
                                language:(nullable NSString *)language
                                   error:(NSError **)error;

/// Begin a streaming session. Nil for models without streaming support.
- (nullable LMKAsrStream *)beginStreamWithLanguage:(nullable NSString *)language
                                             error:(NSError **)error;

- (void)unload;

@end

@interface LMKAsrStream : NSObject

- (instancetype)init NS_UNAVAILABLE;

/// Feed recorded samples; returns the text finalized since the last feed
/// (append it), with locale tags removed.
- (NSString *)feedSamples:(const float *)samples count:(NSInteger)count;

/// Flush the remaining audio and return the final text delta.
- (NSString *)finish;

- (void)close;

@end

NS_ASSUME_NONNULL_END
