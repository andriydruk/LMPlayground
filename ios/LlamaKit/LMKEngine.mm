#import "LMKEngine.h"
#import "LMKInternal.h"

#include "LlamaCpp.h"
#include "EngineRuntime.h"
#include "ModelProbe.h"

NSErrorDomain const LMKErrorDomain = @"com.druk.lmplayground.LlamaKit";

NSError *LMKMakeError(LMKErrorCode code, NSString *description) {
    return [NSError errorWithDomain:LMKErrorDomain
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: description}];
}

@implementation LMKSamplerParams

- (instancetype)init {
    if ((self = [super init])) {
        _contextSize = 4096;
        _temperature = 0.8f;
        _topP = 0.95f;
        _repetitionPenalty = 1.0f;
        _topK = 40;
        _minP = 0.05f;
        _seed = -1;
        _thinkingBudget = 1024;
        _systemPrompt = @"";
    }
    return self;
}

- (id)copyWithZone:(NSZone *)zone {
    LMKSamplerParams *copy = [[LMKSamplerParams allocWithZone:zone] init];
    copy.contextSize = self.contextSize;
    copy.temperature = self.temperature;
    copy.topP = self.topP;
    copy.repetitionPenalty = self.repetitionPenalty;
    copy.topK = self.topK;
    copy.minP = self.minP;
    copy.seed = self.seed;
    copy.thinkingBudget = self.thinkingBudget;
    copy.systemPrompt = self.systemPrompt;
    return copy;
}

@end

@implementation LMKModelMetadata

- (instancetype)initWithName:(NSString *)name
              hasChatTemplate:(BOOL)hasChatTemplate
                 architecture:(NSString *)architecture
                contextLength:(uint32_t)contextLength
              kvBytesPerToken:(uint64_t)kvBytesPerToken
                  isProjector:(BOOL)isProjector {
    if ((self = [super init])) {
        _name = [name copy];
        _hasChatTemplate = hasChatTemplate;
        _architecture = [architecture copy];
        _contextLength = contextLength;
        _kvBytesPerToken = kvBytesPerToken;
        _isProjector = isProjector;
    }
    return self;
}

@end

@implementation LMKEngine

+ (void)initializeWithStateDirectory:(NSString *)stateDirectory
                        visionDevice:(LMKVisionDevice)visionDevice {
    EngineConfig config;
    config.stateDir = LMKString(stateDirectory);
    // ggml's CPU, BLAS and Metal backends are linked in statically.
    config.loadBackends = false;
    config.visionPolicy = VisionPolicy::AnyGpu;
    if (visionDevice == LMKVisionDeviceCPU) {
        config.visionDeviceOverride = "CPU";
    }
    engineInit(config);
}

+ (NSString *)systemInfo {
    return [NSString stringWithUTF8String:llama_print_system_info()] ?: @"";
}

+ (LMKModelMetadata *)probeModelAtPath:(NSString *)path {
    GgufMetadata meta;
    if (!probeGgufMetadata(LMKString(path), meta)) {
        return nil;
    }
    NSString *name = [NSString stringWithUTF8String:meta.name.c_str()] ?: @"";
    NSString *arch = [NSString stringWithUTF8String:meta.architecture.c_str()] ?: @"";
    return [[LMKModelMetadata alloc] initWithName:name
                                  hasChatTemplate:meta.hasChatTemplate
                                     architecture:arch
                                    contextLength:meta.contextLength
                                  kvBytesPerToken:meta.kvBytesPerToken()
                                      isProjector:meta.isProjector()];
}

+ (LMKModel *)loadModelAtPath:(NSString *)path
                    gpuLayers:(int32_t)gpuLayers
                disableRepack:(BOOL)disableRepack
         chatTemplateOverride:(NSString *)chatTemplateOverride
                     progress:(void (NS_NOESCAPE ^)(float))progress
                        error:(NSError **)error {
    auto model = std::make_unique<LlamaModel>();

    // Passed as a raw pointer into the progress callback: safe only because
    // loadModel invokes it synchronously on this thread.
    struct ProgressContext {
        void (^block)(float);
    } context = {progress};

    model->loadModel(LMKString(path),
                     gpuLayers,
                     [](float value, void *user) -> bool {
                         auto *ctx = static_cast<ProgressContext *>(user);
                         if (ctx->block != nil) ctx->block(value);
                         return true;
                     },
                     &context,
                     disableRepack,
                     LMKString(chatTemplateOverride));

    if (!model->isLoaded()) {
        if (error) *error = LMKMakeError(LMKErrorModelLoadFailed,
                                         [NSString stringWithFormat:@"Failed to load %@", path.lastPathComponent]);
        return nil;
    }
    return [[LMKModel alloc] initWithModel:std::move(model)];
}

@end
