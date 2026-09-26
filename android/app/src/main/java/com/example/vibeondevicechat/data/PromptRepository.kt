package com.example.vibeondevicechat.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 从服务端拉取 Prompt 模板。
 *
 * 把系统提示词放在服务端可让提示词迭代不必发版——这是端侧 AI 应用里
 * 常见的做法，也是本项目「前后端同仓」的一部分。
 *
 * 服务端不可达时回退到内置默认提示词，保证离线也能用。
 */
class PromptRepository(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    companion object {
        // 10.0.2.2 是 Android 模拟器访问宿主机 localhost 的地址。
        // 真机调试时改成电脑在局域网中的 IP。
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8000"

        private const val PROMPT_NAME = "chat_system"

        private const val FALLBACK_PROMPT = """你是一个运行在手机本地的 AI 助手，完全离线工作。

回答要求：
- 用简体中文回答。
- 简洁直接，避免冗长铺垫。
- 不确定的信息明确说"不确定"，不要编造。"""
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 获取系统提示词。网络异常时返回内置默认值，不抛异常。
     */
    suspend fun fetchSystemPrompt(): String = withContext(Dispatchers.IO) {
        val url = "$baseUrl/api/prompt/$PROMPT_NAME"
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext FALLBACK_PROMPT
                }
                val body = response.body?.string().orEmpty()
                val content = JSONObject(body).optString("content")
                content.ifBlank { FALLBACK_PROMPT }
            }
        } catch (e: Exception) {
            // 服务端未启动是正常情况，静默回退即可。
            FALLBACK_PROMPT
        }
    }
}
