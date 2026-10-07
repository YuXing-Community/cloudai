"""
最小 MCP 客户端，支持两种传输：
- streamable-http：每条 JSON-RPC 都 POST 到 endpoint，响应为 JSON 或 SSE
- sse（旧版）：GET endpoint 建立 SSE，收到 endpoint 事件后向该地址 POST，响应从 SSE 流返回
"""
from __future__ import annotations

import asyncio
import itertools
import json
from typing import Any
from urllib.parse import urljoin

import httpx

PROTOCOL_VERSION = "2025-03-26"
CLIENT_INFO = {"name": "cloudai-executor", "version": "1.0.0"}


class MCPError(Exception):
    pass


def _iter_sse(text: str):
    """解析一段完整 SSE 文本，产出 data JSON"""
    data_lines: list[str] = []
    for line in text.splitlines():
        if line.startswith("data:"):
            data_lines.append(line[5:].strip())
        elif line.strip() == "" and data_lines:
            yield "\n".join(data_lines)
            data_lines = []
    if data_lines:
        yield "\n".join(data_lines)


class MCPClient:
    def __init__(self, endpoint: str, transport: str = "streamable-http", headers: dict | None = None, timeout: float = 120):
        self.endpoint = endpoint
        self.transport = (transport or "streamable-http").lower()
        self.headers = dict(headers or {})
        self.timeout = timeout
        self._ids = itertools.count(1)
        self._client: httpx.AsyncClient | None = None
        self._session_id: str | None = None
        # sse 传输
        self._post_url: str | None = None
        self._pending: dict[int, asyncio.Future] = {}
        self._reader: asyncio.Task | None = None
        self._ready: asyncio.Event | None = None
        self.server_info: dict = {}
        self._closed_reason: str | None = None

    async def __aenter__(self):
        try:
            await self.connect()
        except BaseException:
            await self.close()
            raise
        return self

    async def __aexit__(self, *exc):
        await self.close()

    async def connect(self):
        self._client = httpx.AsyncClient(timeout=httpx.Timeout(self.timeout, connect=15), follow_redirects=True)
        if self.transport == "sse":
            self._ready = asyncio.Event()
            self._reader = asyncio.create_task(self._sse_reader())
            try:
                await asyncio.wait_for(self._ready.wait(), timeout=20)
            except asyncio.TimeoutError:
                raise MCPError("SSE 连接超时，未收到 endpoint 事件")
            if not self._post_url:
                raise MCPError(f"SSE 连接失败：{self._closed_reason or '未收到 endpoint 事件'}")
        init = await self.request("initialize", {
            "protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": CLIENT_INFO,
        })
        self.server_info = init.get("serverInfo") or {}
        await self.notify("notifications/initialized", {})

    async def close(self):
        if self._reader:
            self._reader.cancel()
        for f in self._pending.values():
            if not f.done():
                f.set_exception(MCPError("连接已关闭"))
        if self._client:
            try:
                if self._session_id and self.transport != "sse":
                    await self._client.delete(self.endpoint, headers=self._h())
            except Exception:
                pass
            await self._client.aclose()
            self._client = None

    def _h(self) -> dict:
        h = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json",
             "MCP-Protocol-Version": PROTOCOL_VERSION, **self.headers}
        if self._session_id:
            h["Mcp-Session-Id"] = self._session_id
        return h

    async def notify(self, method: str, params: dict):
        msg = {"jsonrpc": "2.0", "method": method, "params": params}
        url = self._post_url if self.transport == "sse" else self.endpoint
        try:
            await self._client.post(url, json=msg, headers=self._h())
        except Exception:
            pass

    async def request(self, method: str, params: dict | None = None) -> dict:
        rid = next(self._ids)
        msg = {"jsonrpc": "2.0", "id": rid, "method": method, "params": params or {}}
        if self.transport == "sse":
            if self._closed_reason:
                raise MCPError(f"MCP SSE 连接已断开：{self._closed_reason}")
            fut = asyncio.get_running_loop().create_future()
            self._pending[rid] = fut
            r = await self._client.post(self._post_url, json=msg, headers=self._h())
            if r.status_code >= 400:
                self._pending.pop(rid, None)
                raise MCPError(f"MCP 请求失败 {r.status_code}: {r.text[:300]}")
            resp = await asyncio.wait_for(fut, timeout=self.timeout)
        else:
            resp = await self._post_streamable(msg, rid)
        if "error" in resp and resp["error"]:
            err = resp["error"]
            raise MCPError(f"MCP 错误 {err.get('code')}: {err.get('message')}")
        return resp.get("result") or {}

    async def _post_streamable(self, msg: dict, rid: int) -> dict:
        async with self._client.stream("POST", self.endpoint, json=msg, headers=self._h()) as r:
            sid = r.headers.get("mcp-session-id")
            if sid:
                self._session_id = sid
            if r.status_code >= 400:
                body = (await r.aread()).decode("utf-8", "replace")
                raise MCPError(f"MCP 请求失败 {r.status_code}: {body[:300]}")
            ctype = r.headers.get("content-type", "")
            if "text/event-stream" in ctype:
                buf: list[str] = []
                async for line in r.aiter_lines():
                    if line.startswith("data:"):
                        buf.append(line[5:].strip())
                    elif line.strip() == "" and buf:
                        data = "\n".join(buf)
                        buf = []
                        try:
                            obj = json.loads(data)
                        except Exception:
                            continue
                        if isinstance(obj, dict) and obj.get("id") == rid:
                            return obj
                if buf:
                    obj = json.loads("\n".join(buf))
                    if obj.get("id") == rid:
                        return obj
                raise MCPError("MCP SSE 响应中没有找到结果")
            body = await r.aread()
            obj = json.loads(body or b"{}")
            if isinstance(obj, list):
                for o in obj:
                    if o.get("id") == rid:
                        return o
                raise MCPError("MCP 批量响应中没有找到结果")
            return obj

    async def _sse_reader(self):
        h = {"Accept": "text/event-stream", **self.headers}
        try:
            async with self._client.stream("GET", self.endpoint, headers=h, timeout=None) as r:
                if r.status_code >= 400:
                    raise MCPError(f"SSE 连接失败 {r.status_code}")
                event = "message"
                buf: list[str] = []
                async for line in r.aiter_lines():
                    if line.startswith("event:"):
                        event = line[6:].strip()
                    elif line.startswith("data:"):
                        buf.append(line[5:].strip())
                    elif line.strip() == "":
                        if buf:
                            data = "\n".join(buf)
                            buf = []
                            if event == "endpoint":
                                self._post_url = urljoin(self.endpoint, data)
                                self._ready.set()
                            else:
                                try:
                                    obj = json.loads(data)
                                except Exception:
                                    obj = None
                                if isinstance(obj, dict) and "id" in obj and obj["id"] in self._pending:
                                    fut = self._pending.pop(obj["id"])
                                    if not fut.done():
                                        fut.set_result(obj)
                        event = "message"
            self._fail_pending("SSE 流已结束")
        except asyncio.CancelledError:
            pass
        except Exception as e:
            self._fail_pending(f"SSE 断开: {e}")

    def _fail_pending(self, reason: str):
        self._closed_reason = reason
        for f in self._pending.values():
            if not f.done():
                f.set_exception(MCPError(reason))
        self._pending.clear()
        if self._ready and not self._ready.is_set():
            self._ready.set()

    async def list_tools(self) -> list[dict]:
        tools: list[dict] = []
        cursor = None
        for _ in range(20):
            res = await self.request("tools/list", {"cursor": cursor} if cursor else {})
            tools.extend(res.get("tools") or [])
            cursor = res.get("nextCursor")
            if not cursor:
                break
        return [{"name": t.get("name"), "description": t.get("description") or "",
                 "input_schema": t.get("inputSchema") or {"type": "object", "properties": {}}} for t in tools]

    async def call_tool(self, name: str, args: dict) -> tuple[bool, str]:
        res = await self.request("tools/call", {"name": name, "arguments": args or {}})
        parts = []
        for c in res.get("content") or []:
            t = c.get("type")
            if t == "text":
                parts.append(c.get("text") or "")
            elif t == "resource":
                r = c.get("resource") or {}
                parts.append(r.get("text") or json.dumps(r, ensure_ascii=False))
            elif t == "image":
                parts.append(f"[image {c.get('mimeType', '')}]")
            else:
                parts.append(json.dumps(c, ensure_ascii=False))
        if not parts and res.get("structuredContent") is not None:
            parts.append(json.dumps(res["structuredContent"], ensure_ascii=False))
        return (not res.get("isError", False)), "\n".join(parts)


async def discover(endpoint: str, transport: str, headers: dict) -> dict[str, Any]:
    async with MCPClient(endpoint, transport, headers, timeout=60) as c:
        tools = await c.list_tools()
        return {"server": c.server_info, "tools": tools}
