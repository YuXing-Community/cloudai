"""执行器配置，全部可用环境变量覆盖。"""
import os
from pathlib import Path

HOST = os.getenv("CLOUDAI_EXECUTOR_HOST", "127.0.0.1")
PORT = int(os.getenv("CLOUDAI_EXECUTOR_PORT", "8590"))

# 后端内部接口（执行器回调用）
BACKEND_URL = os.getenv("CLOUDAI_BACKEND_URL", "http://127.0.0.1:8580")
INTERNAL_TOKEN = os.getenv("CLOUDAI_INTERNAL_TOKEN", "cloudai-internal-dev-token")

# CloudAI 专用 Milvus（独立实例，和 datademand 完全分开）
MILVUS_URI = os.getenv("CLOUDAI_MILVUS_URI", "http://127.0.0.1:19540")
MILVUS_DB = os.getenv("CLOUDAI_MILVUS_DB", "default")
MILVUS_COLLECTION = os.getenv("CLOUDAI_MILVUS_COLLECTION", "cloudai_knowledge")

# 本地 BGE 向量模型
EMBEDDING_MODEL = os.getenv("CLOUDAI_EMBEDDING_MODEL", "BAAI/bge-small-zh-v1.5")
EMBEDDING_DIM = int(os.getenv("CLOUDAI_EMBEDDING_DIM", "512"))
# 国内网络默认走 HuggingFace 镜像下载模型
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")

DATA_DIR = Path(os.getenv("CLOUDAI_DATA_DIR", str(Path(__file__).resolve().parents[2] / "data"))).resolve()

# 本地已下载的模型目录优先（data/models/bge-small-zh-v1.5，可用 ModelScope 下载），否则按名称从 HuggingFace 拉取
_local_model = DATA_DIR / "models" / EMBEDDING_MODEL.split("/")[-1]
EMBEDDING_MODEL_PATH = os.getenv("CLOUDAI_EMBEDDING_MODEL_PATH", str(_local_model) if _local_model.is_dir() else EMBEDDING_MODEL)

# 工具输出截断，避免撑爆上下文
TOOL_OUTPUT_LIMIT = int(os.getenv("CLOUDAI_TOOL_OUTPUT_LIMIT", "30000"))
BASH_TIMEOUT = int(os.getenv("CLOUDAI_BASH_TIMEOUT", "600"))
