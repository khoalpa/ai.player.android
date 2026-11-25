#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
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

void initializeBackend() {
    static const bool initialized = [] { llama_backend_init(); return true; }();
    (void) initialized;
}

ggml_backend_dev_t vulkanDevice() {
    initializeBackend();
    auto *registry = ggml_backend_reg_by_name("Vulkan");
    if (!registry) return nullptr;
    for (size_t i = 0; i < ggml_backend_reg_dev_count(registry); ++i) {
        auto *device = ggml_backend_reg_dev_get(registry, i);
        const auto type = ggml_backend_dev_type(device);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) return device;
    }
    return nullptr;
}

bool aborted(void *engine) {
    return static_cast<Engine *>(engine)->cancelled.load();
}

constexpr int context_tokens = 8192;

llama_context_params contextParams(Engine *engine, int threads) {
    auto params = llama_context_default_params();
    params.n_ctx = context_tokens;
    params.n_batch = 256;
    params.n_threads = threads;
    params.n_threads_batch = threads;
    params.abort_callback = aborted;
    params.abort_callback_data = engine;
    return params;
}

std::string readPrompt(JNIEnv *env, jbyteArray prompt) {
    std::string input(env->GetArrayLength(prompt), '\0');
    env->GetByteArrayRegion(prompt, 0, static_cast<jsize>(input.size()),
                            reinterpret_cast<jbyte *>(input.data()));
    return input;
}

llama_token sampleConstrainedGreedy(llama_context *context, llama_sampler *sampler,
                                    llama_sampler *grammar, int vocab_size) {
    const auto *logits = llama_get_logits_ith(context, -1);
    if (!logits) return LLAMA_TOKEN_NULL;
    const auto *best = std::max_element(logits, logits + vocab_size);
    llama_token_data candidate{static_cast<llama_token>(best - logits), *best, 0.0f};
    llama_token_data_array candidates{&candidate, 1, -1, false};

    // Only scan the whole vocabulary when the highest-scoring token is invalid.
    llama_sampler_apply(grammar, &candidates);
    if (!std::isfinite(candidate.logit)) return llama_sampler_sample(sampler, context, -1);
    llama_sampler_accept(sampler, candidate.id);
    return candidate.id;
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_aiplayer_android_ContextTranslationClient_hasVulkanDevice(JNIEnv *, jclass) {
    try {
        return vulkanDevice() != nullptr;
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_WARN, "ContextTranslation", "Vulkan probe: %s", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiplayer_android_ContextTranslationClient_openModel(JNIEnv *env, jclass, jstring path, jboolean gpu) {
    const char *file = env->GetStringUTFChars(path, nullptr);
    if (!file) return 0;
    std::string model_path(file);
    env->ReleaseStringUTFChars(path, file);
    try {
        initializeBackend();
        auto *device = gpu ? vulkanDevice() : nullptr;
        if (gpu && !device) return 0;
        ggml_backend_dev_t devices[] = {device, nullptr};
        auto engine = std::make_unique<Engine>();
        auto params = llama_model_default_params();
        params.devices = devices;
        params.n_gpu_layers = gpu ? 999 : 0;
        engine->model.reset(llama_model_load_from_file(model_path.c_str(), params));
        if (!engine->model) return 0;
        if (gpu) {
            // Check that the full context fits before reporting GPU or translating any cues.
            std::unique_ptr<llama_context, decltype(&llama_free)> probe(
                llama_init_from_model(engine->model.get(), contextParams(engine.get(), 1)), llama_free);
            if (!probe) return 0;
        }
        __android_log_print(ANDROID_LOG_INFO, "ContextTranslation", "backend=%s", gpu ? "Vulkan" : "CPU");
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception &error) {
        __android_log_print(ANDROID_LOG_WARN, "ContextTranslation", "Qwen initialization: %s", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiplayer_android_ContextTranslationClient_cancelModel(JNIEnv *, jclass, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiplayer_android_ContextTranslationClient_freeModel(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_aiplayer_android_ContextTranslationClient_countTokens(JNIEnv *env, jclass, jlong handle,
                                                               jbyteArray prompt) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const auto *vocab = llama_model_get_vocab(engine->model.get());
    const auto input = readPrompt(env, prompt);
    if (env->ExceptionCheck()) return 0;
    // With zero capacity llama_tokenize returns the negative required token count.
    return -llama_tokenize(vocab, input.data(), static_cast<int>(input.size()), nullptr, 0, false, true);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_aiplayer_android_ContextTranslationClient_generateBounded(JNIEnv *env, jclass, jlong handle,
                                                            jbyteArray prompt, jstring grammar, jint threads, jint output_tokens) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    const auto *vocab = llama_model_get_vocab(engine->model.get());
    const auto input = readPrompt(env, prompt);
    if (env->ExceptionCheck()) return nullptr;
    const int prompt_limit = context_tokens - output_tokens;
    std::vector<llama_token> tokens(prompt_limit);
    const int count = llama_tokenize(vocab, input.data(), static_cast<int>(input.size()),
                                    tokens.data(), prompt_limit, false, true);
    if (count < 0) return nullptr; // Java retries with a smaller group.
    if (count == 0) return failure(env, "Empty offline translation prompt.");
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(engine->model.get(), contextParams(engine, threads)), llama_free);
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
    const int vocab_size = llama_vocab_n_tokens(vocab);
    std::string result;
    int generated = 0;
    for (; generated < output_tokens && !engine->cancelled.load(); ++generated) {
        auto token = sampleConstrainedGreedy(context.get(), sampler.get(), constraint, vocab_size);
        if (token == LLAMA_TOKEN_NULL) return failure(env, "Missing offline subtitle token.");
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
    __android_log_print(ANDROID_LOG_INFO, "ContextTranslation",
                       "inference prompt_tokens=%d output_tokens=%d output_limit=%d",
                       count, generated, output_tokens);
    auto bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
                                     reinterpret_cast<const jbyte *>(result.data()));
    return bytes;
}
