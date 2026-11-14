#import "LMKAsrModel.h"
#import "LMKInternal.h"

#include "AsrEngine.h"
#include "parakeet_capi.h"

static NSString *LMKTakeString(char *text) {
    if (text == nullptr) return nil;
    NSString *result = [NSString stringWithUTF8String:text];
    parakeet_capi_free_string(text);
    return result;
}

@interface LMKAsrModel ()
- (instancetype)initPrivate;
@end

@interface LMKAsrStream ()
- (instancetype)initWithStream:(parakeet_stream *)stream;
@end

@implementation LMKAsrModel {
    parakeet_ctx *_ctx;
}

+ (instancetype)loadModelAtPath:(NSString *)path threads:(int32_t)threads error:(NSError **)error {
    asrConfigureBackend(threads);
    parakeet_ctx *ctx = asrLoadModel(path.UTF8String);
    if (ctx == nullptr) {
        if (error) *error = LMKMakeError(LMKErrorAsrFailed,
                                         [NSString stringWithFormat:@"Failed to load %@", path.lastPathComponent]);
        return nil;
    }
    LMKAsrModel *model = [[LMKAsrModel alloc] initPrivate];
    model->_ctx = ctx;
    return model;
}

- (instancetype)initPrivate {
    return [super init];
}

- (void)dealloc {
    [self unload];
}

- (NSString *)transcribeSamples:(const float *)samples
                          count:(NSInteger)count
                       language:(NSString *)language
                          error:(NSError **)error {
    if (_ctx == nullptr || samples == nullptr || count <= 0) {
        if (error) *error = LMKMakeError(LMKErrorAsrFailed, @"Nothing to transcribe");
        return nil;
    }
    // decoder 0 = the model's own head (TDT for the tdt-* checkpoints).
    char *text = parakeet_capi_transcribe_pcm_lang(_ctx, samples, static_cast<int>(count), 16000, 0,
                                                   language != nil ? language.UTF8String : nullptr);
    NSString *result = LMKTakeString(text);
    if (result == nil && error) {
        *error = LMKMakeError(LMKErrorAsrFailed,
                              [NSString stringWithUTF8String:parakeet_capi_last_error(_ctx)] ?: @"Transcription failed");
    }
    return result;
}

- (LMKAsrStream *)beginStreamWithLanguage:(NSString *)language error:(NSError **)error {
    if (_ctx == nullptr) return nil;
    parakeet_stream *stream = parakeet_capi_stream_begin_lang(_ctx, language != nil ? language.UTF8String : nullptr);
    if (stream == nullptr) {
        if (error) *error = LMKMakeError(LMKErrorAsrFailed,
                                         [NSString stringWithUTF8String:parakeet_capi_last_error(_ctx)] ?: @"Not a streaming model");
        return nil;
    }
    return [[LMKAsrStream alloc] initWithStream:stream];
}

- (void)unload {
    if (_ctx != nullptr) {
        asrFreeModel(_ctx);
        _ctx = nullptr;
    }
}

@end

@implementation LMKAsrStream {
    parakeet_stream *_stream;
}

- (instancetype)initWithStream:(parakeet_stream *)stream {
    if ((self = [super init])) {
        _stream = stream;
    }
    return self;
}

- (void)dealloc {
    [self close];
}

- (NSString *)feedSamples:(const float *)samples count:(NSInteger)count {
    if (_stream == nullptr || samples == nullptr || count <= 0) return @"";
    // Events are ignored: end-of-utterance is a voice-agent turn-taking
    // signal, and dictation ends when the user stops it.
    char *text = parakeet_capi_stream_feed(_stream, samples, static_cast<int>(count), nullptr);
    if (text == nullptr) return @"";
    std::string cleaned = asrStripLocaleTags(text);
    parakeet_capi_free_string(text);
    return [NSString stringWithUTF8String:cleaned.c_str()] ?: @"";
}

- (NSString *)finish {
    if (_stream == nullptr) return @"";
    char *text = parakeet_capi_stream_finalize(_stream);
    if (text == nullptr) return @"";
    std::string cleaned = asrStripLocaleTags(text);
    parakeet_capi_free_string(text);
    return [NSString stringWithUTF8String:cleaned.c_str()] ?: @"";
}

- (void)close {
    if (_stream != nullptr) {
        parakeet_capi_stream_free(_stream);
        _stream = nullptr;
    }
}

@end
