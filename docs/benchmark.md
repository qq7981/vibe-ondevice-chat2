# 性能测试

测试方法：在 JNI 层用 `std::chrono::steady_clock` 打点（首 token 时刻在 `emitToken` 里记录），
内存用 `adb shell dumpsys meminfo` 采样。App 内置 `AUTO_TEST_PROMPTS` 开关可以在无人工
输入的情况下跑固定问题并打印完整 prompt / 回答，用于复现。

## 测试环境

| 项 | 值 |
|---|---|
| 设备 | 一加 Ace 2（PHK110 / OP5913L1） |
| SoC | 骁龙 8+ Gen 1，8 核 |
| 内存 | 15.6 GB（可用约 4.1 GB） |
| 系统 | Android 15（API 35） |
| ABI | arm64-v8a |
| 模型 | Qwen2.5-1.5B-Instruct INT4 |
| MNN 版本 | 3.6.1（源码编译） |
| 连接方式 | adb 无线调试（Wi-Fi） |
| 网络状态 | **飞行模式开启**（`airplane_mode_on=1`），全程无网络 |

## 结果

| 指标 | 数值 | 说明 |
|---|---|---|
| 模型文件大小 | 832 MB | 权重 868,491,506 B + 结构 1.1 MB + 分词器 3.2 MB |
| 模型加载耗时 | **1351–2346 ms** | 冷启动；多次测量区间 |
| 首 token 延迟 | **526–1078 ms** | 含 prefill，随输入长度与系统负载波动 |
| 解码速度 | **15–38 tok/s** | 纯解码阶段；短回复（4–7 token）偏低 |
| 峰值内存 | **约 1029 MB (PSS)** / 1062 MB (RSS) | 含权重、KV Cache 与运行时开销 |

解码速度区间较宽的原因：短回复里首 token 之后的样本数太少（例如只生成 4 个 token 时
用 3 个样本算均值，测量噪声很大）。较长的回复稳定在 **37 tok/s** 附近，
一次 75 token 的生成实测 2864 ms，换算中文约每秒 50 多字。

## 多轮对话验证

多轮记忆是该版本新增的能力，用两条连续问题验证（同为飞行模式下的实测日志）：

| 轮次 | 输入 | 输出 |
|---|---|---|
| 第 1 轮 | 我叫小明，请记住 | 好的，小明。 |
| 第 2 轮 | 我叫什么名字？ | **小明。** |

第 2 轮实际发出的 prompt 中，第 1 轮的问与答各自带着正确的 role 标记回填：

```
<|im_start|>system
你是一个运行在手机本地的 AI 助手，完全离线工作。…
<|im_end|>
<|im_start|>user
我叫小明，请记住
<|im_end|>
<|im_start|>assistant
好的，小明。
<|im_end|>
<|im_start|>user
我叫什么名字？
<|im_end|>
<|im_start|>assistant
```

模型能准确区分哪句是自己说过的，因此既保留了上下文，也没有出现角色混淆。

## 已知限制与观察

**Swap 的影响。** 内存采样期间观察到 Native Heap 在 841–867 MB 之间波动，同时出现
约 100–125 MB 的 Swap PSS。说明系统在内存压力下会把部分权重换出到 swap，
推理时再换入，这会抬高首 token 延迟。若关闭其他后台应用，延迟应能进一步下降。

**每轮重算 prefill。** 当前采用无状态调用：每轮把完整历史重新拼成 prompt 送入引擎，
并在发送前 `reset()` 清空 KV Cache。这样绝对不会串话，但代价是历史越长 prefill 越慢。
历史已限制在最近 12 条消息。后续可改为保留 KV Cache 的增量式对话以提升长对话性能。

**短回复的解码速度噪声。** 生成 token 数很少时，tok/s 的统计样本不足，
数值波动较大（见上表）。评价解码性能应看长回复。

## 优化记录

1. **问题**：MNN 的 C++ API 只提供 `std::ostream*` 输出，没有逐 token 回调，
   无法在 Java 侧做流式渲染。
   **做法**：实现 `TokenStream : std::ostream` 子类，重写 `overflow()`/`xsputn()`
   拦截字节流，按 UTF-8 边界切分成完整字符后经 JNI 回调到 Kotlin 的 `Flow`。
   **效果**：实现逐 token 流式输出；中文不会因多字节被切断而乱码。

2. **问题**：模型放在应用私有目录，但在 Android 11+ 分区存储下，
   用 adb 以 shell 身份 `mkdir` 出的目录属主是 `shell`，应用无权进入，
   会误判为「模型不存在」。
   **做法**：改由应用自己在 `getExternalFilesDir()` 下 `mkdirs()`，
   目录属主即为应用自身（`u0_a168`）。
   **效果**：模型目录可正常读写，加载成功率稳定。

3. **问题**：模型输出**无限重复**同一段话（实测连续循环 4–8 次）。
   **做法**：核对 MNN 源码发现 `llm_config.json` 未配置任何采样参数，
   `repetition_penalty` 默认为 `-1`（即关闭）。补上
   `repetition_penalty=1.05`、`presence_penalty=0.1`、`penalty_window=128`，
   并设置 `temperature=0.7` / `topP=0.8` / `topK=20`；同时在 Kotlin 侧
   给单轮生成加上 512 token 上限，避免退化时无限输出。
   **效果**：重复循环消失，回答能正常收尾。

4. **问题**：模型**自问自答、角色混淆**——输出里出现「我的问题，我需要帮助。请回答。」
   以及自己伪造的「用户：……」提问。
   **做法**：原实现把 system prompt 用纯文本拼在用户输入前
   （`"$systemPrompt\n\n用户：$input"`），而 MNN 的 `prompt_template` 会把整段
   当作用户发言，系统提示因此失去角色语义。改为按 Qwen2.5 的 ChatML 格式显式拼接
   `<|im_start|>system/user/assistant` 三段，并把 `prompt_template` 设为 `%s`
   （由 App 完全接管格式），同时每轮调用 `Llm::reset()` 隔离历史。
   **效果**：角色边界清晰，自问自答消失；system prompt 里的「简洁直接」等
   要求开始真正生效（问「请用一句话介绍你自己」只回一句话）。

5. **问题**：多轮对话无记忆——每轮独立，问完「我叫小明」再问「我叫什么」答不上来。
   **做法**：在 ViewModel 维护完整消息列表，每轮把历史逐条按 role 标记拼回 prompt，
   保留最近 12 条；仍保留每轮 `reset()` 以保证不串话。
   **效果**：上表已验证两轮记忆生效；长对话的增量式 KV Cache 复用列为后续优化。

6. **问题**：`docs/benchmark.md` 初版把 MNN 描述成「有 callback」，与事实不符。
   **做法**：核对 MNN 源码确认其只暴露 `std::ostream`，修正文档与 README 表述。
   **效果**：技术描述与实现一致，避免面试时被问穿。

## 复现命令

```bash
# 内存占用
adb shell dumpsys meminfo com.example.vibeondevicechat | grep -E "TOTAL PSS|Native Heap"

# 推理计时 + 完整 prompt/回答
adb logcat -s MnnLlmJni:* MnnLlmSession:* ChatViewModel:*

# 确认处于离线状态（1 = 飞行模式已开）
adb shell settings get global airplane_mode_on
```
