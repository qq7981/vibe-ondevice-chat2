"""下载端侧模型（Qwen2.5-1.5B-Instruct，MNN INT4 量化版）。

HuggingFace 官方域名在国内多数网络下不可达（实测连不上），因此默认走
hf-mirror 镜像。若你在能直连 HF 的环境，把 HF_ENDPOINT 设为
https://huggingface.co 即可。

注意：hf-mirror 对 800 MB 以上的大文件支持不稳定——huggingface_hub 可能卡住
（.incomplete 文件停在 0 字节）。若遇到这种情况，用 curl 断点续传单独拉主权重，
它带重定向跟随、速度也更稳：

    curl -L -C - -o models/llm.mnn.weight \\
      https://hf-mirror.com/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/llm.mnn.weight

用法：
    pip install -r requirements.txt
    python download_model.py
"""

import os
from pathlib import Path

# 必须在 import huggingface_hub 之前设置，否则不生效。
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

from huggingface_hub import snapshot_download  # noqa: E402

MODEL_DIR = Path(__file__).parent / "models"

REPO_ID = "taobao-mnn/Qwen2.5-1.5B-Instruct-MNN"

# 只拉推理必需的文件，跳过 README / gitattributes 等。
# 注意入口配置叫 llm_config.json（不是 config.json）。
ALLOW_PATTERNS = [
    "llm_config.json",   # MNN 入口配置
    "llm.mnn",           # 模型结构
    "llm.mnn.weight",    # INT4 量化权重（868 MB）
    "tokenizer.txt",     # 分词器
]

# 主权重体积，用于校验下载是否完整。
EXPECTED_WEIGHT_SIZE = 868491506


def main() -> None:
    print(f"镜像端点: {os.environ['HF_ENDPOINT']}")
    print(f"下载 {REPO_ID} -> {MODEL_DIR}")
    MODEL_DIR.mkdir(parents=True, exist_ok=True)

    snapshot_download(
        repo_id=REPO_ID,
        local_dir=MODEL_DIR,
        allow_patterns=ALLOW_PATTERNS,
    )

    print("\n完成。文件清单：")
    total = 0
    for path in sorted(MODEL_DIR.rglob("*")):
        if path.is_file() and path.name not in (".gitattributes",):
            size = path.stat().st_size
            total += size
            print(f"  {path.relative_to(MODEL_DIR)}  {size / 1e6:.1f} MB")
    print(f"\n合计 {total / 1e6:.0f} MB")

    weight = MODEL_DIR / "llm.mnn.weight"
    if weight.is_file() and weight.stat().st_size != EXPECTED_WEIGHT_SIZE:
        print(f"\n警告：llm.mnn.weight 大小为 {weight.stat().st_size}，"
              f"与预期 {EXPECTED_WEIGHT_SIZE} 不符，下载可能不完整。")
        print("请用上文注释里的 curl 命令重新拉取主权重。")

    print(f"\n推送到设备可用：\n  adb push {MODEL_DIR} "
          f"/sdcard/Android/data/com.example.vibeondevicechat/files/models/qwen2.5-1.5b-int4/")


if __name__ == "__main__":
    main()
