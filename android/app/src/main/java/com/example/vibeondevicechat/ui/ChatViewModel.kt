package com.example.vibeondevicechat.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vibeondevicechat.data.PromptRepository
import com.example.vibeondevicechat.llm.ChatMessage as MnnChatMessage
import com.example.vibeondevicechat.llm.MnnLlmSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 单条对话消息。[isUser] 决定气泡靠左还是靠右。 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
)

/** 聊天界面的完整状态。 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isLoadingModel: Boolean = false,
    val isGenerating: Boolean = false,
    val modelReady: Boolean = false,
    val statusText: String = "正在加载模型…",
)

/**
 * 聊天界面状态管理。
 *
 * 负责模型生命周期、Prompt 拉取、以及流式生成的 token 累积。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"

        /** 模型目录（相对应用外部存储根目录）。 */
        private const val MODEL_DIR = "models/qwen2.5-1.5b-int4"

        /**
         * 调试开关：非空时，模型加载完成后按顺序自动发送这些问题，
         * 并把完整 prompt 与回答打进日志。留空即关闭。
         * 用两条问题可以验证多轮记忆是否生效。
         */
        private val AUTO_TEST_PROMPTS = emptyList<String>()

        /** MNN 的入口配置文件，同目录下需有 llm.mnn / llm.mnn.weight / tokenizer.txt。 */
        private const val MODEL_CONFIG = "llm_config.json"

        /**
         * 参与拼接的历史消息条数上限（不含本轮）。
         *
         * 用的是无状态调用：每轮把完整历史重新拼进 prompt。历史越长，
         * prefill 越慢、KV Cache 占用越大，所以需要截断。保留最近若干条
         * 足以维持多轮语义，同时避免长对话把延迟推高到不可接受。
         */
        private const val MAX_HISTORY_MESSAGES = 12
    }

    private val session = MnnLlmSession()
    private val promptRepository = PromptRepository()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var systemPrompt: String = ""

    init {
        bootstrap()
    }

    /**
     * 初始化流程：拉取 Prompt → 加载模型。
     *
     * 两步都异步，UI 通过 [ChatUiState.statusText] 显示进度。
     */
    private fun bootstrap() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingModel = true, statusText = "正在拉取 Prompt 配置…") }
            systemPrompt = promptRepository.fetchSystemPrompt()

            _uiState.update { it.copy(statusText = "正在加载端侧模型…") }
            // 模型放在应用外部存储目录，避免打进 APK 撑大包体。
            val configPath = resolveModelConfigPath()
            if (configPath == null) {
                _uiState.update {
                    it.copy(isLoadingModel = false, statusText = "未找到模型，请先推送模型文件")
                }
                return@launch
            }

            val ok = session.load(configPath)
            _uiState.update {
                it.copy(
                    isLoadingModel = false,
                    modelReady = ok,
                    statusText = if (ok) "模型就绪 · 完全离线" else "模型加载失败",
                )
            }

            // 调试用：加载完成后自动跑一次固定输入，把完整 prompt 与回答
            // 打进日志。用于在没有屏幕录制干扰的情况下核对输出质量。
            if (ok && AUTO_TEST_PROMPTS.isNotEmpty()) {
                for (q in AUTO_TEST_PROMPTS) {
                    Log.i(TAG, "[autotest] 输入: $q")
                    send(q)
                    // 等这一轮生成结束（send 是异步的），再做下一轮。
                    while (_uiState.value.isGenerating) {
                        kotlinx.coroutines.delay(200)
                    }
                    kotlinx.coroutines.delay(500)
                }
            }
        }
    }

    /**
     * 查找模型配置。约定模型放在应用外部存储的 models/<name>/ 下。
     *
     * MNN 的入口文件是 llm_config.json（不是 config.json）——
     * 同目录下还需有 llm.mnn、llm.mnn.weight、tokenizer.txt，
     * MNN 会以 config 所在目录为基准自动查找这些文件。
     *
     * 这里会主动 mkdirs：外部存储目录必须由应用自己创建，否则在
     * Android 11+ 的分区存储下，用 adb 以 shell 身份建出的目录属主是
     * shell，应用无权进入，会误判为"模型不存在"。
     */
    private fun resolveModelConfigPath(): String? {
        val dir = File(getApplication<Application>().getExternalFilesDir(null), MODEL_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.e(TAG, "模型目录创建失败: ${dir.absolutePath}")
        }
        Log.i(TAG, "模型目录: ${dir.absolutePath}")
        val entries = dir.list()?.joinToString() ?: "<无法列出>"
        Log.i(TAG, "目录内容: $entries")

        val config = File(dir, MODEL_CONFIG)
        if (!config.isFile) {
            Log.e(TAG, "未找到 $MODEL_CONFIG，实际路径: ${config.absolutePath}")
            return null
        }
        return config.absolutePath
    }

    /**
     * 发送用户输入并流式接收回复。
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isGenerating || !_uiState.value.modelReady) {
            return
        }

        // 本轮之前的历史（不含本轮提问），用于拼多轮上下文。
        val history = _uiState.value.messages

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(trimmed, isUser = true),
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            val messages = buildMessages(history, trimmed)

            // 先把空的助手消息放进列表，后续每个 token 追加到这条上。
            _uiState.update { it.copy(messages = it.messages + ChatMessage("", isUser = false)) }

            // 打印实际发给 native 的消息列表，便于核对角色与历史回填是否正确。
            Log.i(TAG, "[messages] >>>${messages.joinToString(" | ") { "${it.role}:${it.content}" }}<<<")

            val answer = StringBuilder()
            try {
                session.generateChat(messages).collect { token ->
                    answer.append(token)
                    _uiState.update { state ->
                        val updated = state.messages.toMutableList()
                        val last = updated.lastIndex
                        if (last >= 0) {
                            updated[last] = updated[last].copy(text = updated[last].text + token)
                        }
                        state.copy(messages = updated)
                    }
                }
                Log.i(TAG, "[answer] >>>${answer}<<<")
            } catch (e: Exception) {
                _uiState.update { state ->
                    val updated = state.messages.toMutableList()
                    val last = updated.lastIndex
                    if (last >= 0 && updated[last].text.isEmpty()) {
                        updated[last] = updated[last].copy(text = "生成失败：${e.message}")
                    }
                    state.copy(messages = updated)
                }
            } finally {
                _uiState.update { it.copy(isGenerating = false) }
            }
        }
    }

    /**
     * 构造发给模型的消息列表。
     *
     * 这里**不再自己拼 ChatML 文本**，而是把 role 与 content 分开交给 MNN：
     * 模型 config 里配了 `jinja.chat_template`，由 MNN 按 Qwen2.5 的模板渲染。
     * 这样做的收益是 MNN 能启用 prompt cache——它对本轮与上一轮的渲染结果做
     * 前缀比对，只 prefill 新增的 suffix，首 token 延迟不再随对话轮数线性增长。
     *
     * 如果改回自己拼文本、调 `response(string)`，MNN 拿不到角色信息，
     * 会退化成每轮全量重算 prefill。
     *
     * **assistant 回复必须去掉末尾换行。** MNN 的 `end_with` 默认为 `"\n"`，
     * 生成结束时会把换行一起写进输出流，所以客户端从流式 token 拼出的文本
     * 比 MNN 内部记录的回复多一个 `\n`。而缓存文本是用 `tokenizer_decode`
     * 重建的、不含这个换行——带着它回传，前缀比对会在倒数第二个字符处失配，
     * 缓存永远 MISS、每轮全量重算。这里统一裁掉，两边表示就对齐了。
     *
     * [history] 是此前所有消息（按时间顺序），[current] 是本轮提问。
     * 只保留最近 [MAX_HISTORY_MESSAGES] 条，并丢弃其中内容为空的消息
     * （流式输出失败时可能留下空助手气泡）。
     */
    private fun buildMessages(history: List<ChatMessage>, current: String): List<MnnChatMessage> {
        val messages = mutableListOf<MnnChatMessage>()
        if (systemPrompt.isNotBlank()) {
            messages += MnnChatMessage("system", systemPrompt)
        }
        history
            .filter { it.text.isNotBlank() }
            .takeLast(MAX_HISTORY_MESSAGES)
            .forEach { msg ->
                if (msg.isUser) {
                    messages += MnnChatMessage("user", msg.text)
                } else {
                    messages += MnnChatMessage("assistant", msg.text.trimEnd('\n', '\r'))
                }
            }
        messages += MnnChatMessage("user", current)
        return messages
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { session.release() }
    }
}
