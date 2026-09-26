# Prompt 模板

本目录存放 App 使用的系统提示词，由 `server/main.py` 的 `/api/prompt/{name}` 接口下发，
客户端可在启动时拉取最新版本，实现**不发版更新 Prompt**。

| 文件 | 用途 |
|---|---|
| `chat_system.md` | 对话主流程的系统提示词 |

要新增模板，直接加 `<name>.md`，客户端请求 `/api/prompt/<name>` 即可。
