package com.example.vibeondevicechat.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.vibeondevicechat.data.PromptRepository
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

        /** MNN 的入口配置文件，同目录下需有 llm.mnn / llm.mnn.weight / tokenizer.txt。 */
        private const val MODEL_CONFIG = "llm_config.json"
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

        _uiState.update {
            it.copy(
                messages = it.messages + ChatMessage(trimmed, isUser = true),
                isGenerating = true,
            )
        }

        viewModelScope.launch {
            // 把系统提示词拼在用户输入前，作为单轮上下文。
            val prompt = if (systemPrompt.isBlank()) {
                trimmed
            } else {
                "$systemPrompt\n\n用户：$trimmed"
            }

            // 先把空的助手消息放进列表，后续每个 token 追加到这条上。
            _uiState.update { it.copy(messages = it.messages + ChatMessage("", isUser = false)) }

            try {
                session.generate(prompt).collect { token ->
                    _uiState.update { state ->
                        val updated = state.messages.toMutableList()
                        val last = updated.lastIndex
                        if (last >= 0) {
                            updated[last] = updated[last].copy(text = updated[last].text + token)
                        }
                        state.copy(messages = updated)
                    }
                }
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

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { session.release() }
    }
}
