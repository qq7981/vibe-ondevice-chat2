# Android 客户端

Kotlin + Jetpack Compose，通过 MNN 的 JNI 接口在端侧运行量化 LLM。

## 这里放什么

不要从零写这个目录。**基于 MNN 官方 demo 改造**是唯一可行的短期路径：

```bash
git clone https://github.com/alibaba/MNN.git
# 参考 MNN/apps/Android/MnnLlmChat —— 这是官方已经写好的端侧 LLM 聊天 App
```

`MnnLlmChat` 已经包含：模型加载、流式输出、对话 UI、模型下载。
你的工作是把它的代码**迁进这个 `android/` 目录**，然后做三件事：

1. 换掉默认模型，改用 Qwen2.5-1.5B INT4（更小更快，手机跑得动）
2. 把系统提示词改成从 `server/api/prompt/chat_system` 拉取
3. 加上性能打点，产出 `docs/benchmark.md` 里的数据

## 需要改的关键文件

| 文件 | 改动 |
|---|---|
| `app/build.gradle.kts` | 加 MNN AAR 依赖、NDK 配置 |
| `jni/` | MNN 的 native 库，直接拷官方 |
| `ui/ChatScreen.kt` | 聊天 UI，可保留官方实现 |
| `llm/LlmSession.kt` | 推理会话封装，接流式回调 |
| `llm/PromptRepository.kt` | 新增：从 server 拉 Prompt |

## 构建前置

- Android Studio（含 SDK 34、Build-Tools、Platform-Tools）
- JDK 17
- 一台 Android 真机（arm64，模拟器跑 LLM 太慢，不建议）

## 构建

```bash
# 用 Android Studio 打开本目录，或命令行：
./gradlew :app:assembleDebug

# 产物
app/build/outputs/apk/debug/app-debug.apk

# 装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 已配置的国内镜像

首次同步慢是常态，工程里已经配好两个镜像，无需再手动改：

| 用途 | 位置 | 镜像 |
|---|---|---|
| Maven 依赖 | `settings.gradle.kts` | `maven.aliyun.com` |
| Gradle 发行版 | `gradle/wrapper/gradle-wrapper.properties` | `mirrors.cloud.tencent.com` |

## MNN 接入方式

**MNN 不发布到公共 Maven 仓库**（`com.alibaba.mnn:mnn` 这个坐标不存在，试过会报
`Could not find com.alibaba.mnn:mnn`）。正确做法是把官方编译好的 native 库直接放进工程：

1. 从 MNN 官方 Release 或自行编译取得 `libMNN.so`、`libMNN_CL.so`。
2. 放到 `app/src/main/jniLibs/arm64-v8a/`。
3. Kotlin 侧加载：

```kotlin
init {
    System.loadLibrary("MNN")
    System.loadLibrary("MNN_CL")
}
```

或直接使用 MNN 官方 Android demo `apps/Android/MnnLlmChat` 的 JNI 封装层，连 Java/Kotlin
桥接代码一起拷过来，比自己写省事得多。

## 需要改的关键文件
