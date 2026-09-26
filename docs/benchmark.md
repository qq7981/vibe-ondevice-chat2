# 性能测试

测试方法：`adb shell` 读取内存，App 内置计时打点统计延迟。每项重复 5 次取中位数。

## 测试环境

| 项 | 值 |
|---|---|
| 设备 | `<机型>` |
| SoC | `<芯片>` |
| 内存 | `<RAM>` |
| App 版本 | `<commit>` |
| MNN 版本 | `<version>` |
| 线程数 | `<n>` |

## 结果

| 指标 | 数值 | 说明 |
|---|---|---|
| 模型文件大小 | `X GB` | INT4 量化后 |
| 模型加载耗时 | `X ms` | 冷启动 |
| 首 token 延迟 | `X ms` | 输入 32 token |
| 解码速度 | `X tok/s` | 生成 128 token 平均 |
| 峰值内存 | `X MB` | 含模型 + KV Cache |

## 优化记录

<!-- 每条写清：问题 → 做了什么 → 效果。这部分面试时最有用 -->

1. **问题**：官方 demo 模型太大导致 OOM
   **做法**：换 INT4 量化模型
   **效果**：内存从 `X` 降到 `Y`

2. **问题**：原生 demo 不支持流式输出
   **做法**：接 MNN callback 实现逐 token 回调
   **效果**：首 token 从 `X s` 降到 `Y ms`

## 复现命令

```bash
adb shell dumpsys meminfo <package> | grep TOTAL
```
