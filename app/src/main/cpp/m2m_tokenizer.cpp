#include <jni.h>
#include <memory>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace {
std::string readBytes(JNIEnv *env, jbyteArray bytes) {
    std::string value(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<jbyte *>(value.data()));
    return value;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_aiplayer_android_M2mTranslation_openTokenizer(JNIEnv *env, jclass, jbyteArray path) {
    auto processor = std::make_unique<sentencepiece::SentencePieceProcessor>();
    if (!processor->Load(readBytes(env, path)).ok()) return 0;
    return reinterpret_cast<jlong>(processor.release());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_aiplayer_android_M2mTranslation_encode(JNIEnv *env, jclass, jlong handle, jbyteArray input) {
    auto *processor = reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
    std::vector<std::string> pieces;
    if (!processor->Encode(readBytes(env, input), &pieces).ok()) return nullptr;
    auto output = env->NewObjectArray(pieces.size(), env->FindClass("[B"), nullptr);
    if (!output) return nullptr;
    for (size_t i = 0; i < pieces.size(); ++i) {
        auto bytes = env->NewByteArray(pieces[i].size());
        if (!bytes) return nullptr;
        env->SetByteArrayRegion(bytes, 0, pieces[i].size(), reinterpret_cast<const jbyte *>(pieces[i].data()));
        env->SetObjectArrayElement(output, i, bytes);
        env->DeleteLocalRef(bytes);
    }
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_aiplayer_android_M2mTranslation_closeTokenizer(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<sentencepiece::SentencePieceProcessor *>(handle);
}
