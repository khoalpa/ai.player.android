#include <jni.h>
#include <algorithm>
#include <atomic>
#include <memory>
#include <string>
#include <vector>
#include "llama.h"

namespace {
struct Engine {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::atomic<bool> cancelled{false};
};

jbyteArray failure(JNIEnv *env, const char *message) {
    env->ThrowNew(env->FindClass("java/io/IOException"), message);
    return nullptr;
}

bool aborted(void *engine) {
    return static_cast<Engine *>(engine)->cancelled.load();
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiplayer_android_ContextTranslationClient_openModel(JNIEnv *env, jclass, jstring path) {
    static const bool initialized = [] { llama_backend_init(); return true; }();
    (void) initialized;
    const char *file = env->GetStringUTFChars(path, nullptr);
    if (!file) return 0;
    auto engine = std::make_unique<Engine>();
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    engine->model.reset(llama_model_load_from_file(file, params));
    env->ReleaseStringUTFChars(path, file);
    if (!engine->model) return 0;
    return reinterpret_cast<jlong>(engine.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiplayer_android_ContextTranslationClient_cancelModel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiplayer_android_ContextTranslationClient_freeModel(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aiplayer_android_ContextTranslationClient_generate(JNIEnv *env, jclass, jlong handle,
                                                            jbyteArray prompt, jstring grammar, jint threads) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const auto *vocab = llama_model_get_vocab(engine->model.get());
    std::string input(env->GetArrayLength(prompt), '\0');
    env->GetByteArrayRegion(prompt, 0, static_cast<jsize>(input.size()),
                            reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return nullptr;
    constexpr int context_tokens = 8192;
    constexpr int output_tokens = 1600;
    constexpr int prompt_limit = context_tokens - output_tokens;
    std::vector<llama_token> tokens(prompt_limit);
    const int count = llama_tokenize(vocab, input.data(), static_cast<int>(input.size()),
                                    tokens.data(), prompt_limit, false, true);
    if (count < 0) return nullptr; // Java retries with a smaller group.
    if (count == 0) return failure(env, "Empty offline translation prompt.");
    auto params = llama_context_default_params();
    params.n_ctx = context_tokens;
    params.n_batch = 256;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.abort_callback = aborted;
    params.abort_callback_data = engine;
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(engine->model.get(), params), llama_free);
    if (!context) return failure(env, "Could not allocate offline translation memory.");
    for (int i = 0; i < count; i += 256) {
        if (engine->cancelled.load() ||
            llama_decode(context.get(), llama_batch_get_one(tokens.data() + i, std::min(256, count - i)))) {
            return failure(env, "Could not evaluate the offline translation prompt.");
        }
    }
    const char *rules = env->GetStringUTFChars(grammar, nullptr);
    if (!rules) return nullptr;
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    auto *constraint = llama_sampler_init_grammar(vocab, rules, "root");
    env->ReleaseStringUTFChars(grammar, rules);
    if (!constraint) return failure(env, "Could not initialize subtitle output constraints.");
    llama_sampler_chain_add(sampler.get(), constraint);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    std::string result;
    for (int i = 0; i < output_tokens && !engine->cancelled.load(); ++i) {
        auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        char piece[256];
        int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
        if (length < 0) return failure(env, "Invalid offline subtitle token.");
        result.append(piece, length);
        if (llama_decode(context.get(), llama_batch_get_one(&token, 1))) {
            return failure(env, "Offline subtitle generation was interrupted.");
        }
    }
    if (engine->cancelled.load()) return failure(env, "Offline subtitle generation was interrupted.");
    auto bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
                                     reinterpret_cast<const jbyte *>(result.data()));
    return bytes;
}
