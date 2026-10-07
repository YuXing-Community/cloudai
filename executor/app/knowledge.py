"""
知识库向量化：本地 BGE 模型 embedding → CloudAI 独立 Milvus 实例；同时维护关键词索引 _index/keywords.md
"""
from __future__ import annotations

import json
import os
import re
import threading
from pathlib import Path

from . import config

_model = None
_model_lock = threading.Lock()
_milvus = None
_milvus_lock = threading.Lock()

_store_locks: dict[str, threading.Lock] = {}
_store_locks_guard = threading.Lock()


def store_lock(store_id: str) -> threading.Lock:
    """同一记忆库的索引/删除串行执行，避免向量重复与关键词索引文件互相覆盖"""
    with _store_locks_guard:
        return _store_locks.setdefault(store_id, threading.Lock())


QUERY_INSTRUCTION = "为这个句子生成表示以用于检索相关文章："


def get_model():
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                from sentence_transformers import SentenceTransformer
                _model = SentenceTransformer(config.EMBEDDING_MODEL_PATH)
    return _model


def embed(texts: list[str], is_query: bool = False) -> list[list[float]]:
    model = get_model()
    if is_query:
        texts = [QUERY_INSTRUCTION + t for t in texts]
    vecs = model.encode(texts, normalize_embeddings=True, batch_size=32, show_progress_bar=False)
    return [v.tolist() for v in vecs]


def get_milvus():
    global _milvus
    if _milvus is None:
        with _milvus_lock:
            if _milvus is None:
                from pymilvus import MilvusClient, DataType
                client = MilvusClient(uri=config.MILVUS_URI, db_name=config.MILVUS_DB)
                if not client.has_collection(config.MILVUS_COLLECTION):
                    schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
                    schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=128)
                    schema.add_field("space_id", DataType.VARCHAR, max_length=64)
                    schema.add_field("store_id", DataType.VARCHAR, max_length=64)
                    schema.add_field("doc_id", DataType.INT64)
                    schema.add_field("path", DataType.VARCHAR, max_length=1024)
                    schema.add_field("chunk_index", DataType.INT64)
                    schema.add_field("text", DataType.VARCHAR, max_length=8192)
                    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=config.EMBEDDING_DIM)
                    idx = client.prepare_index_params()
                    idx.add_index(field_name="vector", index_type="HNSW", metric_type="COSINE", params={"M": 16, "efConstruction": 200})
                    client.create_collection(config.MILVUS_COLLECTION, schema=schema, index_params=idx)
                client.load_collection(config.MILVUS_COLLECTION)
                _milvus = client
    return _milvus


def milvus_status() -> dict:
    try:
        c = get_milvus()
        stats = c.get_collection_stats(config.MILVUS_COLLECTION)
        return {"ok": True, "uri": config.MILVUS_URI, "collection": config.MILVUS_COLLECTION, "rows": stats.get("row_count")}
    except Exception as e:
        return {"ok": False, "uri": config.MILVUS_URI, "error": str(e)[:300]}


# ------------------------------------------------------------------ 切块

def chunk_text(text: str, path: str, size: int = 600, overlap: int = 80) -> list[str]:
    """按 Markdown 标题/段落切块，每块带上文档路径和最近标题作为上下文"""
    text = (text or "").replace("\r\n", "\n")
    blocks: list[tuple[str, str]] = []  # (heading, paragraph)
    heading = ""
    for para in re.split(r"\n\s*\n", text):
        p = para.strip()
        if not p:
            continue
        m = re.match(r"^(#{1,6})\s+(.*)", p)
        if m and "\n" not in p:
            heading = m.group(2).strip()
            blocks.append((heading, p))
            continue
        blocks.append((heading, p))
    chunks: list[str] = []
    cur, cur_head = "", ""
    for head, p in blocks:
        while len(p) > size:
            piece, p = p[:size], p[size - overlap:]
            chunks.append(_with_ctx(path, head, piece))
        if len(cur) + len(p) + 1 > size and cur:
            chunks.append(_with_ctx(path, cur_head, cur))
            cur = cur[-overlap:] if overlap else ""
        if not cur:
            cur_head = head
        cur = (cur + "\n" + p).strip()
    if cur.strip():
        chunks.append(_with_ctx(path, cur_head, cur))
    return chunks or ([_with_ctx(path, "", text[:size])] if text.strip() else [])


def _with_ctx(path: str, head: str, body: str) -> str:
    ctx = f"[{path}]" + (f" {head}" if head else "")
    return (ctx + "\n" + body)[:8000]


# ------------------------------------------------------------------ 关键词索引

def extract_keywords(text: str, top_k: int = 15) -> list[str]:
    def ok(w: str) -> bool:
        w = w.strip()
        # 至少含一个字母/汉字，排除 Markdown 符号与纯数字
        return len(w) > 1 and re.search(r"[A-Za-z一-龥]", w) is not None
    try:
        import jieba.analyse
        return [w for w in jieba.analyse.extract_tags(text or "", topK=top_k * 2) if ok(w)][:top_k]
    except Exception:
        words = re.findall(r"[A-Za-z_][A-Za-z0-9_]{2,}|[一-龥]{2,6}", text or "")
        freq: dict[str, int] = {}
        for w in words:
            freq[w] = freq.get(w, 0) + 1
        return [w for w, _ in sorted(freq.items(), key=lambda x: -x[1])[:top_k]]


def update_keyword_index(store_dir: str, path: str, keywords: list[str] | None):
    """维护 <store_dir>/_index/keywords.json 并重新生成 keywords.md（文档→关键词 + 关键词→文档 倒排）"""
    d = Path(store_dir) / "_index"
    d.mkdir(parents=True, exist_ok=True)
    jf = d / "keywords.json"
    data: dict[str, list[str]] = {}
    if jf.exists():
        try:
            data = json.loads(jf.read_text("utf-8"))
        except Exception:
            data = {}
    if keywords is None:
        for k in list(data):
            if k == path or k.startswith(path.rstrip("/") + "/"):
                data.pop(k, None)
    else:
        data[path] = keywords
    _atomic_write(jf, json.dumps(data, ensure_ascii=False, indent=1))
    inverted: dict[str, list[str]] = {}
    for p, kws in data.items():
        for k in kws:
            inverted.setdefault(k, []).append(p)
    lines = ["# 关键词索引", "", f"> 自动生成，共 {len(data)} 篇文档、{len(inverted)} 个关键词。上传或修改文档后自动更新。", "",
             "## 文档关键词", "", "| 文档 | 关键词 |", "|---|---|"]
    for p in sorted(data):
        lines.append(f"| `{p}` | {'、'.join(data[p])} |")
    lines += ["", "## 关键词倒排", "", "| 关键词 | 文档 |", "|---|---|"]
    for k in sorted(inverted, key=lambda x: (-len(inverted[x]), x)):
        lines.append(f"| {k} | {'<br>'.join('`' + p + '`' for p in sorted(inverted[k]))} |")
    _atomic_write(d / "keywords.md", "\n".join(lines) + "\n")


def _atomic_write(p: Path, text: str):
    tmp = p.with_suffix(p.suffix + f".{threading.get_ident()}.tmp")
    tmp.write_text(text, "utf-8")
    os.replace(tmp, p)


# ------------------------------------------------------------------ 索引 / 删除 / 检索

def index_doc(space_id: str, store_id: str, doc_id: int, path: str, text: str, store_dir: str | None) -> dict:
    with store_lock(store_id):
        return _index_doc(space_id, store_id, doc_id, path, text, store_dir)


def _index_doc(space_id: str, store_id: str, doc_id: int, path: str, text: str, store_dir: str | None) -> dict:
    chunks = chunk_text(text, path)
    keywords = extract_keywords(text)
    client = get_milvus()
    client.delete(config.MILVUS_COLLECTION, filter=f'store_id == "{_esc(store_id)}" and doc_id == {int(doc_id)}')
    if chunks:
        vecs = embed(chunks)
        rows = [{"id": f"{store_id[:24]}:{doc_id}:{i}", "space_id": space_id, "store_id": store_id, "doc_id": int(doc_id),
                 "path": path[:1000], "chunk_index": i, "text": c, "vector": v} for i, (c, v) in enumerate(zip(chunks, vecs))]
        client.insert(config.MILVUS_COLLECTION, rows)
    if store_dir:
        update_keyword_index(store_dir, path, keywords)
    return {"chunks": len(chunks), "keywords": keywords}


def delete_docs(store_id: str, doc_id: int | None = None, path: str | None = None, store_dir: str | None = None) -> dict:
    with store_lock(store_id):
        return _delete_docs(store_id, doc_id, path, store_dir)


def _delete_docs(store_id: str, doc_id: int | None = None, path: str | None = None, store_dir: str | None = None) -> dict:
    client = get_milvus()
    flt = f'store_id == "{_esc(store_id)}"'
    if doc_id is not None:
        flt += f" and doc_id == {int(doc_id)}"
    res = client.delete(config.MILVUS_COLLECTION, filter=flt)
    if store_dir and path:
        update_keyword_index(store_dir, path, None)
    return {"deleted": (res or {}).get("delete_count", 0) if isinstance(res, dict) else 0}


def search(space_id: str, store_ids: list[str], query: str, top_k: int = 8) -> list[dict]:
    client = get_milvus()
    vec = embed([query], is_query=True)[0]
    flt = f'space_id == "{_esc(space_id)}"'
    if store_ids:
        flt += " and store_id in [" + ",".join(f'"{_esc(s)}"' for s in store_ids) + "]"
    res = client.search(config.MILVUS_COLLECTION, data=[vec], filter=flt, limit=max(1, min(int(top_k), 50)),
                        output_fields=["store_id", "doc_id", "path", "chunk_index", "text"],
                        search_params={"metric_type": "COSINE", "params": {"ef": 128}})
    out = []
    for hit in (res[0] if res else []):
        ent = hit.get("entity") or {}
        out.append({"store_id": ent.get("store_id"), "doc_id": ent.get("doc_id"), "path": ent.get("path"),
                    "chunk_index": ent.get("chunk_index"), "text": _strip_ctx(ent.get("text") or ""),
                    "score": round(float(hit.get("distance") or 0), 4)})
    return out


def _strip_ctx(t: str) -> str:
    return t.split("\n", 1)[1] if t.startswith("[") and "\n" in t else t


def _esc(s: str) -> str:
    return str(s).replace("\\", "\\\\").replace('"', '\\"')


def grep_search(dirs: list[tuple[str, str]], query: str, top_k: int = 8) -> list[dict]:
    """向量服务不可用时的关键词兜底：在记忆库目录中按词频检索"""
    terms = [t for t in re.split(r"\s+", query.strip()) if t] or [query]
    hits = []
    for store_id, d in dirs:
        base = Path(d)
        if not base.exists():
            continue
        for f in base.rglob("*"):
            if not f.is_file() or "_index" in f.parts or f.suffix.lower() not in (".md", ".txt", ".csv", ".json", ".yaml", ".yml"):
                continue
            try:
                text = f.read_text("utf-8", errors="ignore")
            except Exception:
                continue
            score = sum(text.count(t) for t in terms)
            if score:
                pos = max(0, min((text.find(t) for t in terms if t in text), default=0) - 120)
                hits.append({"store_id": store_id, "path": "/" + str(f.relative_to(base)), "text": text[pos:pos + 600],
                             "score": score})
    hits.sort(key=lambda x: -x["score"])
    return hits[:top_k]
