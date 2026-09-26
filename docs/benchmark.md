# 性能测试

测试方法：在 JNI 层用 `std::chrono::steady_clock` 打点（首 token 时刻在 `emitToken` 里记录），
内存用 `adb shell dumpsys meminfo` 采样。

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

## 结果

| 指标 | 数值 | 说明 |
|---|---|---|
| 模型文件大小 | 832 MB | 4 个文件：权重 868,491,506 B + 结构 1.1 MB + 分词器 3.2 MB |
| 模型加载耗时 | **2346 ms** | 冷启动，从外部存储读取并初始化 |
| 首 token 延迟 | **886 ms** | 含 prefill；输入为一句中文 + system prompt |
| 解码速度 | **37.41 tok/s** | 首 token 之后的纯解码阶段平均 |
| 峰值内存 | **约 1029 MB (PSS)** / 1062 MB (RSS) | 含模型权重、KV Cache 与运行时开销 |

实测样例：一次生成 75 个 token，总耗时 2864 ms。

37.4 tok/s 换算成中文约每秒 50 多个字，远快于人类阅读速度，交互上已无等待感。

## 已知限制与观察

**Swap 的影响。** 内存采样期间观察到 Native Heap 在 841–867 MB 之间波动，同时出现
约 100–125 MB 的 Swap PSS。说明系统在内存压力下会把部分权重换出到 swap，
推理时再换入，这会抬高首 token 延迟。若关闭其他后台应用，首 token 延迟应能进一步下降。

**上下文没有隔离。** 当前实现每轮把 `system prompt + 用户输入` 拼成单条 prompt 传给
`Llm::response`，但 MNN 的 `Llm` 实例内部保留对话历史，导致上一轮内容会残留到下一轮
（实测中问「你好」，回复却接着上一轮聊编程语言）。正确做法是每轮显式清空历史，
或改用 MNN 的 `reset()` 接口。这是待修的已知缺陷。

## 优化记录

1. **问题**：MNN 的 C++ API 只提供 `std::ostream*` 输出，没有逐 token 回调，
   无法在 Java 侧做流式渲染。
   **做法**：实现 `TokenStream : std::ostream` 子类，重写 `overflow()`/`xsputn()`
   拦截字节流，按 UTF-8 边界切分成完整字符后经 JNI 回调到 Kotlin 的 `Flow`。
   **效果**：实现了逐 token 流式输出；中文不会因多字节被切断而乱码。

2. **问题**：模型放在应用私有目录，但在 Android 11+ 分区存储下，
   用 adb 以 shell 身份 `mkdir` 出的目录属主是 `shell`，应用无权进入，
   会误判为「模型不存在」。
   **做法**：改由应用自己在 `getExternalFilesDir()` 下 `mkdirs()`，
   目录属主即为应用自身（`u0_a168`）。
   **效果**：模型目录可正常读写，加载成功率稳定。

3. **问题**：`docs/benchmark.md` 初版把 MNN 描述成「有 callback」，与事实不符。
   **做法**：核对 MNN 源码确认其只暴露 `std::ostream`，修正文档与 README 表述。
   **效果**：技术描述与实现一致，避免面试时被问穿。

## 复现命令

```bash
# 内存占用
adb shell dumpsys meminfo com.example.vibeondevicechat | grep -E "TOTAL PSS|Native Heap"

# 推理计时（JNI 层打点）
adb logcat -s MnnLlmJni:* MnnLlmSession:* ChatViewModel:*
```
