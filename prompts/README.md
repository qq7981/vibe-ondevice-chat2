# Prompt 模板

本目录存放 App 使用的系统提示词，由 `server/main.py` 的 `/api/prompt/{name}` 接口下发，
客户端可在启动时拉取最新版本，实现**不发版更新 Prompt**。

| 文件 | 用途 |
|---|---|
| `chat_system.md` | 对话主流程的系统提示词 |

要新增模板，直接加 `<name>.md`，客户端请求 `/api/prompt/<name>` 即可。

## 注意事项

**服务端原样返回整个文件内容**（`Path.read_text()`），文件里写什么，模型就收到什么。
因此 `.md` 文件里**不要写注释或说明文字**，只放纯提示词正文——包括 HTML 注释
也会被当作提示词发给模型。

**必须与 App 内置版本保持同步**：离线时 App 走内置的 `FALLBACK_PROMPT`
（见 `android/app/src/main/java/com/example/vibeondevicechat/data/PromptRepository.kt`），
联网时走本目录。两者内容不一致会导致「同一个问题在线/离线表现不同」这类难排查的问题。
改任意一边时记得同步另一边。
