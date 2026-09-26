"""模型下发与 Prompt 配置服务。

端侧 App 启动时拉取 Prompt 模板与模型元信息；模型文件本身走静态资源下载，
避免打进 APK 导致包体过大。
"""

from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel

APP_VERSION = "0.1.0"

MODEL_DIR = Path(__file__).parent / "models"
PROMPT_DIR = Path(__file__).parent.parent / "prompts"

# MNN 模型由多个文件组成，权重单独一个文件且体积最大。
# 客户端关心的是下载体积，所以以 .weight 为准，它缺失时再退回整个目录。
WEIGHT_SUFFIX = ".weight"
MODEL_NAME = "qwen2.5-1.5b-instruct"

app = FastAPI(title="Vibe On-Device Chat Server", version=APP_VERSION)


class ModelInfo(BaseModel):
    name: str
    version: str
    size_bytes: int
    quant: str
    download_url: str


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "version": APP_VERSION}


@app.get("/api/model/info", response_model=ModelInfo)
def model_info() -> ModelInfo:
    """返回当前推荐模型元信息，客户端据此决定是否更新本地模型。"""
    weight_path = _find_weight()
    if weight_path is None:
        raise HTTPException(status_code=404, detail="model not found on server")
    return ModelInfo(
        name=MODEL_NAME,
        version="1.0.0",
        size_bytes=weight_path.stat().st_size,
        quant="int4",
        download_url="/api/model/download",
    )


@app.get("/api/model/download")
def model_download() -> FileResponse:
    weight_path = _find_weight()
    if weight_path is None:
        raise HTTPException(status_code=404, detail="model not found on server")
    return FileResponse(weight_path, media_type="application/octet-stream")


@app.get("/api/prompt/{name}")
def get_prompt(name: str) -> dict:
    """下发 Prompt 模板，客户端可热更新而无需发版。"""
    prompt_path = PROMPT_DIR / f"{name}.md"
    if not prompt_path.is_file():
        raise HTTPException(status_code=404, detail=f"prompt '{name}' not found")
    return {"name": name, "content": prompt_path.read_text(encoding="utf-8")}


def _find_weight() -> Path | None:
    """找到模型权重文件（体积最大的那个）。"""
    if not MODEL_DIR.is_dir():
        return None
    weights = sorted(MODEL_DIR.glob(f"*{WEIGHT_SUFFIX}"))
    if weights:
        return weights[0]
    # 兼容只有单个 .mnn 文件的情况。
    for path in sorted(MODEL_DIR.glob("*.mnn")):
        return path
    return None
