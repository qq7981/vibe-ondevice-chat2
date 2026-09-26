package com.example.vibeondevicechat.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * 端侧 LLM 推理会话，封装 MNN 的 JNI 接口。
 *
 * 推理本身是同步阻塞的（native 层持有模型锁），调用方应通过 [generate] 返回的
 * Flow 消费 token，不要在主线程直接调用。
 *
 * 用法：
 * ```
 * val session = MnnLlmSession()
 * if (session.load("/sdcard/.../config.json")) {
 *     session.generate("你好").collect { token -> ... }
 * }
 * session.release()
 * ```
 */
class MnnLlmSession {

    companion object {
        private const val TAG = "MnnLlmSession"

        /** 单轮生成上限。1.5B 模型过长容易退化重复，限制在 512 token。 */
        private const val DEFAULT_MAX_NEW_TOKENS = 512

        init {
            System.loadLibrary("MNN")
            System.loadLibrary("mnn_llm_jni")
        }
    }

    @Volatile
    private var ready = false

    /** 是否已加载模型。 */
    val isReady: Boolean
        get() = ready && nativeIsReady()

    /**
     * 加载模型。[configPath] 是模型目录下 config.json 的绝对路径。
     *
     * 加载耗时通常在数百毫秒到数秒，必须在 IO 线程执行。
     */
    suspend fun load(configPath: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "开始加载模型: $configPath")
        val ok = nativeInit(configPath, TokenCallback())
        ready = ok
        Log.i(TAG, if (ok) "模型加载成功" else "模型加载失败")
        ok
    }

    /**
     * 生成回复，逐 token 流式返回。
     *
     * [maxNewTokens] 默认 512：1.5B 小模型在长文本下容易退化重复，
     * 加上限可以避免无限生成。传 -1 表示不限制。
     *
     * 模型未加载时直接返回空流，调用方应先检查 [isReady]。
     */
    fun generate(prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): Flow<String> = callbackFlow {
        if (!isReady) {
            Log.w(TAG, "模型未就绪，忽略本次生成请求")
            close()
            return@callbackFlow
        }
        tokenChannel = this
        try {
            nativeGenerate(prompt, maxNewTokens)
        } finally {
            tokenChannel = null
            close()
        }
        awaitClose { tokenChannel = null }
    }.flowOn(Dispatchers.IO)

    /**
     * 生成回复，走 MNN 的 ChatMessages 路径，可复用跨轮的 KV Cache。
     *
     * 前提是模型 config 里配了 `jinja.chat_template`——没有模板时 MNN 会退化成
     * 把各条消息的内容裸拼在一起，role 丢失，模型会角色混淆。调用方需保证
     * 模板已配置。
     *
     * 与 [generate] 的区别在于历史由 MNN 自己维护：本轮只需传入**新增**的消息，
     * 它会与上一轮做前缀比对，只 prefill 增量部分。因此连续对话的首 token 延迟
     * 不随轮数增长。要重开一段对话请调 [resetCache]。
     */
    fun generateChat(
        messages: List<ChatMessage>,
        maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
    ): Flow<String> = callbackFlow {
        if (!isReady) {
            Log.w(TAG, "模型未就绪，忽略本次生成请求")
            close()
            return@callbackFlow
        }
        tokenChannel = this
        try {
            nativeGenerateChat(
                messages.map { it.role }.toTypedArray(),
                messages.map { it.content }.toTypedArray(),
                maxNewTokens,
            )
        } finally {
            tokenChannel = null
            close()
        }
        awaitClose { tokenChannel = null }
    }.flowOn(Dispatchers.IO)

    /**
     * 清空 MNN 侧缓存的对话历史。开新对话或切换会话时调用，
     * 否则上一段对话的 KV 会被当成前缀复用。
     */
    suspend fun resetCache() {
        withContext(Dispatchers.IO) { nativeResetCache() }
    }

    /** 释放模型占用的内存。 */
    suspend fun release() {
        withContext(Dispatchers.IO) {
            nativeRelease()
            ready = false
        }
    }

    // ---- 与 native 的桥接 ----

    /**
     * native 层用这个引用回调 token。JNI 的 emitToken 通过反射查找
     * 名为 onToken 的方法，因此方法名与签名不可更改。
     */
    @Suppress("unused")
    private inner class TokenCallback {
        // 由 JNI 反射调用，保持 public 可见性。
        fun onToken(token: String) {
            tokenChannel?.trySend(token)
        }
    }

    /**
     * 当前活跃的 token 通道。native 在推理线程上回调，通过它把 token
     * 推入 Flow。非线程安全场景由 callbackFlow 的单生产者语义保证。
     */
    @Volatile
    private var tokenChannel: kotlinx.coroutines.channels.ProducerScope<String>? = null

    private external fun nativeInit(configPath: String, callback: Any): Boolean
    private external fun nativeGenerate(prompt: String, maxNewTokens: Int)
    private external fun nativeGenerateChat(roles: Array<String>, contents: Array<String>, maxNewTokens: Int)
    private external fun nativeResetCache()
    private external fun nativeRelease()
    private external fun nativeIsReady(): Boolean
}

/**
 * 一条对话消息，对应 MNN 的 ChatMessages 元素。
 *
 * [role] 必须是 ChatML 认识的取值（system / user / assistant），
 * MNN 会把它填进 chat_template 的 role 位。
 */
data class ChatMessage(
    val role: String,
    val content: String,
)
