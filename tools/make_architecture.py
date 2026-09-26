"""Generate docs/architecture.png -- the on-device inference data flow.

Run:  python tools/make_architecture.py
Output: docs/architecture.png  (referenced by README.md)

Layout note: the canvas is laid out on a 100x100 grid and saved with
bbox_inches="tight", so all coordinates are chosen with explicit gutters
reserved for edge labels -- card text must never sit under a label.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.patches as mpatches
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties, findfont

# --- CJK font: pick the first one actually installed, else fall back to English ---
CJK_CANDIDATES = ["Microsoft YaHei", "SimHei", "Noto Sans CJK SC", "Source Han Sans SC"]
CJK = None
for _name in CJK_CANDIDATES:
    try:
        _path = findfont(FontProperties(family=_name), fallback_to_default=False)
        CJK = FontProperties(fname=_path)
        break
    except Exception:
        continue

EN = FontProperties(family="DejaVu Sans")


def t(zh: str, en: str) -> str:
    """Return Chinese if a CJK font resolved, otherwise the English fallback."""
    return zh if CJK is not None else en


def font(bold: bool = False) -> FontProperties:
    fp = (CJK if CJK is not None else EN).copy()
    if bold and CJK is None:
        fp.set_weight("bold")
    return fp


# --- palette ---
INK = "#1f2933"
MUTED = "#52606d"

C_UI, C_UI_E = "#dbeafe", "#2563eb"
C_KT, C_KT_E = "#ede9fe", "#7c3aed"
C_JNI, C_JNI_E = "#fef3c7", "#d97706"
C_MNN, C_MNN_E = "#dcfce7", "#16a34a"
C_SRV, C_SRV_E = "#f1f5f9", "#64748b"

fig, ax = plt.subplots(figsize=(15.0, 8.6), dpi=150)
ax.set_xlim(0, 100)
ax.set_ylim(0, 100)
ax.axis("off")
fig.patch.set_facecolor("white")

# ============================ geometry ============================
# Row A (top)    y 63.0 .. 88.0
# Row B (bottom) y 36.0 .. 57.0
# Gutter between rows: y 57.0 .. 63.0  -> horizontal edge labels
# Gutter below row B:  y 26.0 .. 36.0  -> caption labels
AX_L, AX_R = 4.0, 96.0
L_X, L_W = 6.4, 25.4          # left column cards
C_X, C_W = 36.8, 26.4         # centre card
R_X, R_W = 69.2, 26.4         # right column cards
GUT_A = (30.6, 36.4)          # x label 1 (left -> centre)
GUT_B = (63.6, 68.8)          # x label 2 (centre -> right)


def box(x, y, w, h, fc, ec, title, lines, title_size=11.4, body_size=9.1):
    ax.add_patch(
        mpatches.FancyBboxPatch(
            (x, y), w, h,
            boxstyle="round,pad=0.0,rounding_size=1.4",
            facecolor=fc, edgecolor=ec, linewidth=1.8, zorder=2,
        )
    )
    ax.text(x + w / 2, y + h - 1.9, title, ha="center", va="top",
            fontsize=title_size, color=INK, fontproperties=font(True), zorder=3)
    if lines:
        ax.text(x + w / 2, y + h - 5.4, "\n".join(lines), ha="center", va="top",
                fontsize=body_size, color=MUTED, linespacing=1.75,
                fontproperties=font(), zorder=3)


def arrow(x1, y1, x2, y2, color, lw=1.8, style="-|>", ls="-", rad=0.0):
    ax.annotate(
        "", xy=(x2, y2), xytext=(x1, y1),
        arrowprops=dict(arrowstyle=style, color=color, linewidth=lw, linestyle=ls,
                        shrinkA=2, shrinkB=2, connectionstyle=f"arc3,rad={rad}"),
        zorder=5,
    )


def label(x, y, text, color, ha="center", va="center", size=8.7):
    ax.text(x, y, text, ha=ha, va=va, fontsize=size, color=color, linespacing=1.65,
            fontproperties=font(), zorder=8,
            bbox=dict(boxstyle="round,pad=0.32", fc="white", ec="none", alpha=0.96))


# ============================ title ============================
ax.text(50, 99.0, t("端侧 LLM 对话 App — 推理数据流",
                    "On-device LLM Chat — Inference Data Flow"),
        ha="center", va="top", fontsize=16.5, color=INK, fontproperties=font(True))
ax.text(50, 94.6,
        t("全离线运行：模型不出设备，逐 token 流式渲染到界面",
          "Fully offline: the model never leaves the device, tokens stream to the UI"),
        ha="center", va="top", fontsize=10, color=MUTED, fontproperties=font())

# device band
ax.add_patch(mpatches.FancyBboxPatch((AX_L - 1.0, 28.5), (AX_R - AX_L) + 2.0, 62.0,
                                     boxstyle="round,pad=0.0,rounding_size=2.0",
                                     facecolor="#fafbfc", edgecolor="#e4e7eb",
                                     linewidth=1.4, zorder=1))
ax.text(6.4, 88.6, t("设备端 / On device", "On device"), ha="left", va="center",
        fontsize=10.5, color=C_UI_E, fontproperties=font(True))

# ============================ row A ============================
box(L_X, 63.0, L_W, 22.0, C_UI, C_UI_E,
    t("① 表现层 · Compose UI", "1. Presentation · Compose"),
    [t("ChatScreen", "ChatScreen"),
     t("消息气泡 / 自动滚动", "bubbles / auto-scroll"),
     "",
     t("ChatViewModel · modelReady", "ChatViewModel · modelReady")])

box(C_X, 63.0, C_W, 22.0, C_KT, C_KT_E,
    t("③ Kotlin 桥接 · MnnLlmSession", "3. Kotlin bridge · MnnLlmSession"),
    [t("load(configPath): Boolean", "load(configPath): Boolean"),
     t("generate(prompt, n): Flow<String>", "generate(prompt, n): Flow<String>"),
     "",
     "",
     t("callbackFlow 里把每个 token", "trySend each token from a"),
     t("trySend 出去，awaitClose 中", "callbackFlow; awaitClose calls"),
     t("nativeRelease() 释放引擎", "nativeRelease() to free it")])

box(R_X, 64.5, R_W, 20.5, C_JNI, C_JNI_E,
    t("⑤ JNI 桥接层 · C++", "5. JNI bridge · C++"),
    [t("llm_jni.cpp", "llm_jni.cpp"),
     "",
     t("nativeInit · nativeGenerate", "nativeInit · nativeGenerate"),
     t("nativeRelease · nativeIsReady", "nativeRelease · nativeIsReady"),
     "",
     t("TokenStream : std::ostream", "TokenStream : std::ostream"),
     t("重写 overflow() 拦截 token", "override overflow() to catch tokens"),
     t("按 UTF-8 边界切分再回传", "split on UTF-8 boundaries")])

# ============================ row B ============================
box(L_X, 36.0, L_W, 21.0, C_UI, C_UI_E,
    t("② 数据层 · PromptRepository", "2. Data · PromptRepository"),
    [t("OkHttp 拉取 system prompt", "OkHttp fetch of system prompt"),
     t("失败静默降级到内置默认", "silently falls back to built-in"),
     t("→ 断网也能正常对话", "→ works with no network")])

box(R_X, 34.5, R_W, 19.5, C_MNN, C_MNN_E,
    t("⑥ MNN 推理引擎 3.6.1", "6. MNN engine 3.6.1"),
    [t("MNN::Transformer::Llm", "MNN::Transformer::Llm"),
     "",
     t("Qwen2.5-1.5B-Instruct · INT4", "Qwen2.5-1.5B-Instruct · INT4"),
     t("权重约 832 MB", "weights ~832 MB"),
     "",
     t("CPU 多线程 · 可选 OpenCL", "CPU threads · optional OpenCL")])

# ============================ edges ============================
# 1) UI -> Kotlin
arrow(31.8, 76.0, C_X, 76.0, C_UI_E)
label(33.6, 81.0, t("提问 + 上下文", "turn + context"), C_UI_E, size=8.5)

# 2) Kotlin -> JNI
arrow(63.2, 78.5, R_X, 78.5, C_KT_E)
label(66.2, 84.0, t("JNI 调用", "JNI call"), C_KT_E, size=8.5)

# 3) JNI -> MNN (down the right gutter) and 4) tokens streamed back up.
#    Both run in the 52.5 .. 64.5 gap so neither label touches a card.
arrow(90.6, 64.5, 90.6, 54.1, C_JNI_E)
label(94.0, 59.3, t("response()", "response()"), C_JNI_E, ha="left", size=8.5)

arrow(79.0, 54.0, 79.0, 64.3, C_JNI_E, style="-|>", ls=(0, (4, 2)))
label(75.6, 59.3, t("std::ostream 写入", "std::ostream write"),
      C_JNI_E, ha="right", size=8.5)

# 5) JNI -> Kotlin (return path through the row gutter)
arrow(C_X + C_W, 52.5, 63.2, 52.5, C_JNI_E)
arrow(63.2, 52.5, 63.2, 59.0, C_JNI_E, style="-")
arrow(63.2, 59.0, C_X + C_W, 59.0, C_JNI_E)

# 6) prompt fetch: repo -> centre card, rising through the left gutter
arrow(24.0, 57.2, 24.0, 62.8, C_UI_E)
label(20.4, 60.1, t("拉 prompt", "fetch prompt"), C_UI_E, ha="right", size=8.5)

# 7) tokens -> UI (up the left-hand channel, then into card 1)
arrow(18.0, 57.2, 18.0, 62.8, C_KT_E, style="-|>", ls=(0, (4, 2)))
arrow(18.0, 62.8, 18.0, 63.0, C_KT_E, style="-|>")

# ============================ row gutter caption ============================
# Sits in the empty band between the device box (bottom 28.5) and the server
# card (top 20.0); it is left-aligned so it never runs under card (6).
ax.text(6.0, 24.2,
        t("④ 逐 token 回传：MNN 写 ostream → C++ 按 UTF-8 边界切分 → Kotlin Flow 收集 → Compose 打字机式增量刷新",
          "4. Per-token return: MNN writes the ostream → C++ splits on UTF-8 boundaries → collected as a Kotlin Flow → rendered incrementally"),
        ha="left", va="center", fontsize=9.0, color=INK, fontproperties=font(),
        bbox=dict(boxstyle="round,pad=0.45", fc="#f5f3ff", ec=C_KT_E, lw=1.2), zorder=8)

# ============================ server band ============================
box(6.0, 5.0, 88.0, 14.0, C_SRV, C_SRV_E,
    t("⑦ 服务端（可选，仅下发配置，不参与推理）",
      "7. Server (optional: ships config only, never runs inference)"),
    [t("FastAPI · /api/prompt/chat_system · /api/model/info · /api/model/download",
       "FastAPI · /api/prompt/chat_system · /api/model/info · /api/model/download"),
     "",
     t("作用：调 Prompt 不用重新打包 APK；App 离线时全部走内置默认值",
       "Purpose: tune prompts without rebuilding the APK; offline the app uses defaults")])

arrow(50.0, 19.0, 50.0, 17.7, C_SRV_E, ls=(0, (5, 3)))
label(50.0, 21.3, t("HTTP GET（可离线跳过）", "HTTP GET (skippable offline)"),
      C_SRV_E, size=8.5)

# ============================ footnote ============================
ax.text(50, 1.8,
        t("关键设计：MNN 不提供逐 token 回调，只暴露 std::ostream* —— 因此需要自定义 ostream 子类做拦截。",
          "Key design: MNN exposes no per-token callback, only a std::ostream* — hence the custom ostream subclass."),
        ha="center", va="center", fontsize=9.2, color=INK, fontproperties=font(),
        bbox=dict(boxstyle="round,pad=0.45", fc="#fffbeb", ec=C_JNI_E, lw=1.2))

out = Path(__file__).resolve().parent.parent / "docs" / "architecture.png"
out.parent.mkdir(parents=True, exist_ok=True)
fig.savefig(out, dpi=150, bbox_inches=None, facecolor="white", pad_inches=0.0)
plt.close(fig)
print(f"CJK font: {CJK.get_name() if CJK else 'NONE (English fallback)'}")
print(f"wrote {out} ({out.stat().st_size} bytes)")
