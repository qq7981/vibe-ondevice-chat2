// MNN 端侧 LLM 的 JNI 桥接层。
//
// 设计要点：MNN 的 C++ API 通过 std::ostream 输出生成的 token
// （见 MNN::Transformer::Llm::response），没有提供逐 token 的回调。
// 因此这里实现一个 TokenStream : std::ostream，把写入缓冲区的字节
// 实时转发到 Kotlin 的回调函数，从而在 Java 侧实现流式输出。

#include <jni.h>

#include <android/log.h>

#include <memory>
#include <mutex>
#include <ostream>
#include <sstream>
#include <string>
#include <vector>

#include "llm/llm.hpp"

#define LOG_TAG "MnnLlmJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 持有 Java 侧回调的全局引用。整个进程只服务一个推理会话，
// 因此用单例而非把 jobject 塞进每个流的 userdata。
JavaVM* g_vm = nullptr;
jobject g_callback = nullptr;
jmethodID g_onTokenMethod = nullptr;
std::mutex g_callbackMutex;

// 缓存方法 ID，避免每个 token 都查一遍。
void ensureCallbackMethod(JNIEnv* env, jobject callback) {
    if (g_onTokenMethod != nullptr) {
        return;
    }
    jclass cls = env->GetObjectClass(callback);
    g_onTokenMethod = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    env->DeleteLocalRef(cls);
    if (g_onTokenMethod == nullptr) {
        LOGE("回调类缺少 onToken(String) 方法");
    }
}

// 把 C++ 字符串投递到 Kotlin 的 onToken 回调。
// 推理发生在 native 线程，必须 AttachCurrentThread 后才能调 Java。
void emitToken(const std::string& token) {
    if (token.empty()) {
        return;
    }
    std::lock_guard<std::mutex> lock(g_callbackMutex);
    if (g_vm == nullptr || g_callback == nullptr || g_onTokenMethod == nullptr) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("AttachCurrentThread 失败，token 被丢弃");
            return;
        }
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }

    jstring jtoken = env->NewStringUTF(token.c_str());
    if (jtoken != nullptr) {
        env->CallVoidMethod(g_callback, g_onTokenMethod, jtoken);
        env->DeleteLocalRef(jtoken);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (attached) {
        g_vm->DetachCurrentThread();
    }
}

// 把 MNN 输出的字节流按 token 边界切分后转发给 Kotlin。
//
// MNN 以 UTF-8 字节流形式写出生成的文本，多字节字符可能被拆散在两次 write
// 之间，因此这里按 UTF-8 序列长度累积，凑齐一个完整字符再回调，
// 避免 Java 侧收到半个汉字。
class TokenStream : public std::ostream {
  public:
    TokenStream() : std::ostream(&buffer_), buffer_(*this) {}

    ~TokenStream() override {
        buffer_.flushPending();
    }

  private:
    class TokenBuffer : public std::streambuf {
      public:
        explicit TokenBuffer(TokenStream& owner) : owner_(owner) {}

        // 返回 1 表示"已消费"，让上层继续写出，不落盘。
        int_type overflow(int_type ch) override {
            if (ch != traits_type::eof()) {
                owner_.append(static_cast<char>(ch));
            }
            return ch;
        }

        std::streamsize xsputn(const char* s, std::streamsize n) override {
            for (std::streamsize i = 0; i < n; ++i) {
                overflow(traits_type::to_int_type(s[i]));
            }
            return n;
        }

        void flushPending() {
            // 收尾时把不完整字符原样吐出，避免丢内容。
            owner_.flushRemaining();
        }

      private:
        TokenStream& owner_;
    };

    // 按 UTF-8 编码长度判断当前 pending_ 是否构成一个完整字符。
    void append(char c) {
        pending_.push_back(c);
        const size_t expected = utf8Length(pending_[0]);
        if (expected != 0 && pending_.size() >= expected) {
            emitToken(pending_);
            pending_.clear();
        }
    }

    // 把缓冲区里残留的不完整字节吐出，供 streambuf 在收尾时调用。
    void flushRemaining() {
        if (!pending_.empty()) {
            emitToken(pending_);
            pending_.clear();
        }
    }

    // 返回该首字节对应的 UTF-8 字符总字节数；非法首字节返回 1（单独吐出）。
    static size_t utf8Length(unsigned char lead) {
        if ((lead & 0x80) == 0x00) return 1;  // ASCII
        if ((lead & 0xE0) == 0xC0) return 2;
        if ((lead & 0xF0) == 0xE0) return 3;
        if ((lead & 0xF8) == 0xF0) return 4;
        return 1;
    }

    TokenBuffer buffer_;
    std::string pending_;

    friend class TokenBuffer;
};

// 全局会话状态。MNN 的 Llm 非线程安全，用互斥量串行化请求。
std::unique_ptr<MNN::Transformer::Llm> g_llm;
std::mutex g_llmMutex;
TokenStream g_tokenStream;

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

// 创建并加载模型。configPath 指向模型目录下的 config.json。
JNIEXPORT jboolean JNICALL
Java_com_example_vibeondevicechat_llm_MnnLlmSession_nativeInit(
    JNIEnv* env, jobject /*thiz*/, jstring configPath, jobject callback) {
    std::lock_guard<std::mutex> lock(g_llmMutex);

    const char* pathChars = env->GetStringUTFChars(configPath, nullptr);
    if (pathChars == nullptr) {
        return JNI_FALSE;
    }
    std::string path(pathChars);
    env->ReleaseStringUTFChars(configPath, pathChars);

    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    if (callback != nullptr) {
        ensureCallbackMethod(env, callback);
        g_callback = env->NewGlobalRef(callback);
    }

    LOGI("加载模型: %s", path.c_str());
    g_llm.reset(MNN::Transformer::Llm::createLLM(path));
    if (g_llm == nullptr) {
        LOGE("createLLM 返回空，config 路径可能不对");
        return JNI_FALSE;
    }
    if (!g_llm->load()) {
        LOGE("模型加载失败");
        g_llm.reset();
        return JNI_FALSE;
    }
    LOGI("模型加载完成");
    return JNI_TRUE;
}

// 发起一次推理，token 通过 callback.onToken 流式返回。
JNIEXPORT void JNICALL
Java_com_example_vibeondevicechat_llm_MnnLlmSession_nativeGenerate(
    JNIEnv* env, jobject /*thiz*/, jstring prompt, jint maxNewTokens) {
    std::lock_guard<std::mutex> lock(g_llmMutex);
    if (g_llm == nullptr) {
        LOGE("nativeGenerate 在未初始化时被调用");
        return;
    }

    const char* promptChars = env->GetStringUTFChars(prompt, nullptr);
    if (promptChars == nullptr) {
        return;
    }
    const std::string userContent(promptChars);
    env->ReleaseStringUTFChars(prompt, promptChars);

    // 重置流状态，避免上一轮的残留字符污染本次输出。
    g_tokenStream.clear();
    g_llm->response(userContent, &g_tokenStream, nullptr, maxNewTokens);
    g_tokenStream.flush();
}

// 释放模型，回收显存与内存。Activity 退出时调用。
JNIEXPORT void JNICALL
Java_com_example_vibeondevicechat_llm_MnnLlmSession_nativeRelease(
    JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llmMutex);
    g_llm.reset();

    std::lock_guard<std::mutex> callbackLock(g_callbackMutex);
    if (g_callback != nullptr) {
        env->DeleteGlobalRef(g_callback);
        g_callback = nullptr;
    }
    g_onTokenMethod = nullptr;
    LOGI("模型已释放");
}

// 判断模型是否处于就绪状态，供 UI 显示。
JNIEXPORT jboolean JNICALL
Java_com_example_vibeondevicechat_llm_MnnLlmSession_nativeIsReady(
    JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llmMutex);
    return g_llm != nullptr ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
