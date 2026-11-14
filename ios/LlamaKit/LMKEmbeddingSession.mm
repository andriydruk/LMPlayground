#import "LMKEmbeddingSession.h"
#import "LMKInternal.h"

#include "LlamaCpp.h"

@implementation LMKEmbeddingSession {
    LlamaEmbeddingSession *_session;
}

- (instancetype)initWithSession:(LlamaEmbeddingSession *)session {
    if ((self = [super init])) {
        _session = session;
    }
    return self;
}

- (void)dealloc {
    [self destroy];
}

- (int32_t)dimension {
    return _session ? _session->embeddingDim() : 0;
}

- (NSData *)embedText:(NSString *)text error:(NSError **)error {
    int dim = self.dimension;
    if (dim <= 0) {
        if (error) *error = LMKMakeError(LMKErrorEmbeddingFailed, @"Embedding session is closed");
        return nil;
    }
    NSMutableData *out = [NSMutableData dataWithLength:sizeof(float) * dim];
    if (_session->embed(LMKString(text), static_cast<float *>(out.mutableBytes)) != 0) {
        if (error) *error = LMKMakeError(LMKErrorEmbeddingFailed, @"Embedding failed");
        return nil;
    }
    return out;
}

- (void)destroy {
    delete _session;
    _session = nullptr;
}

@end
