//
// Private: C++ constructors for the bridge classes. Never imported by public
// headers, so Swift never sees C++.
//
#import "LMKTypes.h"
#import "LMKModel.h"
#import "LMKGenerationSession.h"
#import "LMKEmbeddingSession.h"

#include <memory>
#include <string>

class LlamaModel;
class LlamaGenerationSession;
class LlamaEmbeddingSession;

NS_ASSUME_NONNULL_BEGIN

NSError *LMKMakeError(LMKErrorCode code, NSString *description);

inline std::string LMKString(NSString *_Nullable s) {
    return s != nil ? std::string(s.UTF8String) : std::string();
}

@interface LMKModelMetadata ()
- (instancetype)initWithName:(NSString *)name hasChatTemplate:(BOOL)hasChatTemplate;
@end

@interface LMKModel ()
- (instancetype)initWithModel:(std::unique_ptr<LlamaModel>)model;
@end

@interface LMKGenerationSession ()
- (instancetype)initWithSession:(LlamaGenerationSession *)session;
@end

@interface LMKEmbeddingSession ()
- (instancetype)initWithSession:(LlamaEmbeddingSession *)session;
@end

NS_ASSUME_NONNULL_END
