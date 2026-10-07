"""
CloudAI Agent 执行器（FastAPI，默认 :8590）
后端通过 X-Internal-Token 调用；接口约定见 docs/dev-contracts.md
"""
from __future__ import annotations

import asyncio
import json
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import StreamingResponse

from . import config, convert, feishu, knowledge, llm
from .agent import Run
from .http_tools import call_cloudlog, call_openapi
from .mcp import MCPClient, discover

channels: feishu.ChannelManager | None = None
cancels: dict[int, asyncio.Event] = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    global channels
    channels = feishu.ChannelManager()
    yield
    channels.stop_all()


app = FastAPI(title="CloudAI Executor", lifespan=lifespan)


def auth(x_internal_token: str | None = Header(default=None)):
    if x_internal_token != config.INTERNAL_TOKEN:
        raise HTTPException(status_code=403, detail="invalid internal token")


@app.get("/health")
async def health():
    model_loaded = knowledge._model is not None
    return {"ok": True, "embedding_model": config.EMBEDDING_MODEL, "dim": config.EMBEDDING_DIM,
            "embedding_loaded": model_loaded, "milvus": config.MILVUS_URI, "running": len(cancels)}


@app.get("/health/deep", dependencies=[Depends(auth)])
async def health_deep():
    st = await asyncio.to_thread(knowledge.milvus_status)
    return {"ok": True, "milvus": st, "embedding_model": config.EMBEDDING_MODEL}


# ------------------------------------------------------------------ Agent 执行

@app.post("/run", dependencies=[Depends(auth)])
async def run(request: Request):
    payload: dict[str, Any] = await request.json()
    run_id = int(payload.get("run_id") or 0)
    cancel = asyncio.Event()
    cancels[run_id] = cancel
    queue: asyncio.Queue = asyncio.Queue()

    async def emit(ev: dict):
        await queue.put(ev)

    async def worker():
        r = None
        status, err = "failed", None
        limit = float(payload.get("timeout_seconds") or 1800)
        timed_out = False

        async def watchdog():
            nonlocal timed_out
            await asyncio.sleep(limit)
            timed_out = True
            cancel.set()  # 与手动停止走同一条路径：Agent 循环与 SubAgent 都会收到取消

        dog = asyncio.create_task(watchdog())
        try:
            r = Run(payload, emit, cancel)
            status, err = await r.execute(payload.get("history") or [], payload.get("message") or "")
        except Exception as e:  # 兜底，保证 done 事件一定发出
            status, err = "failed", f"{type(e).__name__}: {e}"
        finally:
            dog.cancel()
        if timed_out:
            status, err = "failed", f"执行超时（{int(limit)} 秒）"
        await queue.put({"type": "done", "status": status, "final_text": r.final_text if r else "",
                         "turns": r.turns if r else 0, "error": err})
        await queue.put(None)

    task = asyncio.create_task(worker())

    async def stream():
        try:
            while True:
                try:
                    ev = await asyncio.wait_for(queue.get(), timeout=15)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
                    continue
                if ev is None:
                    break
                yield "data: " + json.dumps(ev, ensure_ascii=False) + "\n\n"
        finally:
            cancels.pop(run_id, None)
            if not task.done():
                # 后端断开连接：停止执行
                cancel.set()

    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


@app.post("/runs/{run_id}/cancel", dependencies=[Depends(auth)])
async def cancel_run(run_id: int):
    ev = cancels.get(run_id)
    if ev:
        ev.set()
    return {"ok": ev is not None}


# ------------------------------------------------------------------ LLM 直接调用

@app.post("/llm/complete", dependencies=[Depends(auth)])
async def llm_complete(body: dict):
    spec = body.get("model") or {}
    spec.setdefault("ref", f"{spec.get('provider', '')}/{spec.get('model', '')}")
    msgs = []
    if body.get("system"):
        msgs.append({"role": "system", "content": body["system"]})
    msgs += body.get("messages") or [{"role": "user", "content": body.get("prompt") or ""}]
    try:
        r = await llm.chat_once(spec, msgs, None, body.get("max_tokens") or 2048, body.get("temperature"), timeout=120)
    except llm.LLMError as e:
        raise HTTPException(status_code=502, detail=str(e))
    return {"text": r.text, "usage": {"input_tokens": r.input_tokens, "output_tokens": r.output_tokens}, "duration_ms": r.duration_ms}


# ------------------------------------------------------------------ 插件

@app.post("/mcp/discover", dependencies=[Depends(auth)])
async def mcp_discover(body: dict):
    try:
        return await discover(body.get("endpoint"), body.get("transport") or "streamable-http", body.get("headers") or {})
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"连接 MCP Server 失败：{e}")


@app.post("/tools/invoke", dependencies=[Depends(auth)])
async def tools_invoke(body: dict):
    plugin, tool_name, args = body.get("plugin") or {}, body.get("tool"), body.get("args") or {}
    tool = next((t for t in plugin.get("tools") or [] if t.get("name") == tool_name), {"name": tool_name})
    t0 = time.time()
    try:
        if plugin.get("type") == "mcp":
            async with MCPClient(plugin["endpoint"], plugin.get("transport"), plugin.get("headers")) as c:
                ok, result = await c.call_tool(tool_name, args)
        elif plugin.get("type") == "openapi":
            ok, result = await call_openapi(plugin, tool, args)
        elif plugin.get("type") == "cloudlog":
            ok, result = await call_cloudlog(plugin, tool, args)
        else:
            ok, result = False, f"未知插件类型 {plugin.get('type')}"
    except Exception as e:
        ok, result = False, f"{type(e).__name__}: {e}"
    return {"ok": ok, "result": result, "duration_ms": int((time.time() - t0) * 1000)}


# ------------------------------------------------------------------ 知识库 / 向量

@app.post("/embed/index", dependencies=[Depends(auth)])
async def embed_index(body: dict):
    text = body.get("text")
    if text is None and body.get("file_path"):
        try:
            text = open(body["file_path"], encoding="utf-8", errors="replace").read()
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"读取文件失败：{e}")
    store_dir = str(config.DATA_DIR / "memory" / body["space_id"] / body["store_id"])
    try:
        return await asyncio.to_thread(knowledge.index_doc, body["space_id"], body["store_id"], int(body["doc_id"]),
                                       body.get("path") or "", text or "", store_dir)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"向量化失败：{e}")


@app.post("/embed/delete", dependencies=[Depends(auth)])
async def embed_delete(body: dict):
    store_dir = None
    if body.get("space_id"):
        store_dir = str(config.DATA_DIR / "memory" / body["space_id"] / body["store_id"])
    try:
        return await asyncio.to_thread(knowledge.delete_docs, body["store_id"], body.get("doc_id"), body.get("path"), store_dir)
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"删除向量失败：{e}")


@app.post("/embed/search", dependencies=[Depends(auth)])
async def embed_search(body: dict):
    try:
        res = await asyncio.to_thread(knowledge.search, body.get("space_id") or "", body.get("store_ids") or [],
                                      body.get("query") or "", int(body.get("top_k") or 8))
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"向量检索失败：{e}")
    return {"model": config.EMBEDDING_MODEL, "dim": config.EMBEDDING_DIM, "results": res}


@app.post("/docs/convert", dependencies=[Depends(auth)])
async def docs_convert(body: dict):
    try:
        return {"text": await asyncio.to_thread(convert.to_markdown, body["file_path"])}
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"转换失败：{e}")


# ------------------------------------------------------------------ 飞书

@app.post("/channels/feishu/start", dependencies=[Depends(auth)])
async def feishu_start(body: dict):
    try:
        await asyncio.to_thread(channels.start, body)
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))
    return {"ok": True}


@app.post("/channels/feishu/stop", dependencies=[Depends(auth)])
async def feishu_stop(body: dict):
    await asyncio.to_thread(channels.stop, body.get("channel_id"))
    return {"ok": True}


@app.get("/channels/status", dependencies=[Depends(auth)])
async def channels_status():
    return {"channels": channels.status()}


@app.post("/feishu/send", dependencies=[Depends(auth)])
async def feishu_send(body: dict):
    try:
        return await asyncio.to_thread(feishu.send_to_chat, body["app_id"], body["app_secret"], body["chat_id"],
                                       body.get("text") or "", body.get("title"))
    except Exception as e:
        raise HTTPException(status_code=502, detail=str(e))


@app.get("/feishu/bot-info", dependencies=[Depends(auth)])
async def feishu_bot_info(app_id: str, app_secret: str):
    try:
        return await asyncio.to_thread(feishu.bot_info, app_id, app_secret)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"飞书凭据校验失败：{e}")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("app.main:app", host=config.HOST, port=config.PORT, reload=False)
