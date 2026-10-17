//
// Process-wide entry point to the shared C++ engine (core/llamacore).
//
// Unlike Android, where the engine lives in a separate `:llama` process behind
// AIDL, it runs in-process here. All methods are synchronous and must be called
// from one serial queue (the Swift layer's InferenceActor); only the abort
// methods are safe from any thread.
//
#import <Foundation/Foundation.h>
#import <LlamaKit/LMKTypes.h>

@class LMKModel;

NS_ASSUME_NONNULL_BEGIN

@interface LMKEngine : NSObject

- (instancetype)init NS_UNAVAILABLE;

/// One-time setup: log routing, vision device choice, backend init.
/// `stateDirectory` holds crash-sentinel markers.
+ (void)initializeWithStateDirectory:(NSString *)stateDirectory
                        visionDevice:(LMKVisionDevice)visionDevice;

/// Backend and CPU feature description, for diagnostics.
@property (class, nonatomic, readonly, copy) NSString *systemInfo;

/// Read a GGUF header without loading tensors. Nil if the file is not GGUF.
+ (nullable LMKModelMetadata *)probeModelAtPath:(NSString *)path;

/// Load a model. `gpuLayers` -1 offloads every layer (Metal), 0 keeps the
/// model on the CPU. `chatTemplateOverride` replaces the GGUF's own template
/// when non-nil. `progress` is called synchronously, on the calling thread.
+ (nullable LMKModel *)loadModelAtPath:(NSString *)path
                             gpuLayers:(int32_t)gpuLayers
                         disableRepack:(BOOL)disableRepack
                  chatTemplateOverride:(nullable NSString *)chatTemplateOverride
                              progress:(nullable void (NS_NOESCAPE ^)(float progress))progress
                                 error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
