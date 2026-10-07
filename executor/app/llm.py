"""
模型调用：统一三种 API 类型
- openai-completions  POST {base_url}/chat/completions
- openai-responses    POST {base_url}/responses
- anthropic           POST {base_url}/messages

内部统一消息格式（OpenAI chat 风格）：
  {"role": "system"|"user"|"assistant"|"tool", "content": str,
   "tool_calls": [{"id", "name", "arguments": dict}], "tool_call_id": str, "name": str}
工具定义：{"name", "description", "parameters": JSON Schema}
返回 LLMResult。
"""
from __future__ import annotations

import asyncio
import json
import random
import time
from dataclasses import dataclass, field
from typing import Any

import httpx


@dataclass
class LLMResult:
    text: str = ""
    thinking: str = ""
    tool_calls: list[dict] = field(default_factory=list)
    input_tokens: int = 0
    output_tokens: int = 0
    cache_read: int = 0
    cache_write: int = 0
    stop_reason: str = ""
    model_ref: str = ""
    duration_ms: int = 0


class LLMError(Exception):
    def __init__(self, message: str, retryable: bool = True):
        super().__init__(message)
        self.retryable = retryable


def _url(base: str, path: str) -> str:
    base = (base or "").rstrip("/")
    return base + path


def _parse_args(s: Any) -> dict:
    if isinstance(s, dict):
        return s
    if not s:
        return {}
    try:
        v = json.loads(s)
        return v if isinstance(v, dict) else {"value": v}
    except Exception:
        return {"_raw": s}


# ---------------------------------------------------------------- openai-completions

def _to_openai_messages(messages: list[dict]) -> list[dict]:
    out = []
    for m in messages:
        role = m["role"]
        if role == "assistant":
            msg: dict[str, Any] = {"role": "assistant", "content": m.get("content") or ""}
            if m.get("tool_calls"):
                msg["tool_calls"] = [
                    {"id": c["id"], "type": "function",
                     "function": {"name": c["name"], "arguments": json.dumps(c.get("arguments") or {}, ensure_ascii=False)}}
                    for c in m["tool_calls"]
                ]
                if not msg["content"]:
                    msg["content"] = None
            out.append(msg)
        elif role == "tool":
            out.append({"role": "tool", "tool_call_id": m["tool_call_id"], "content": m.get("content") or ""})
        else:
            out.append({"role": role, "content": m.get("content") or ""})
    return out


async def _openai_completions(client: httpx.AsyncClient, spec: dict, messages: list[dict], tools: list[dict],
                              max_tokens: int | None, temperature: float | None) -> LLMResult:
    body: dict[str, Any] = {"model": spec["model"], "messages": _to_openai_messages(messages)}
    if tools:
        body["tools"] = [{"type": "function", "function": {"name": t["name"], "description": t.get("description", ""),
                                                           "parameters": t.get("parameters") or {"type": "object", "properties": {}}}}
                         for t in tools]
    if max_tokens:
        body["max_tokens"] = max_tokens
    if temperature is not None:
        body["temperature"] = temperature
    headers = {"Authorization": f"Bearer {spec.get('api_key') or ''}", "Content-Type": "application/json"}
    r = await client.post(_url(spec["base_url"], "/chat/completions"), json=body, headers=headers)
    data = _check(r)
    choice = (data.get("choices") or [{}])[0]
    msg = choice.get("message") or {}
    res = LLMResult()
    res.text = msg.get("content") or ""
    res.thinking = msg.get("reasoning_content") or msg.get("reasoning") or ""
    for c in msg.get("tool_calls") or []:
        fn = c.get("function") or {}
        res.tool_calls.append({"id": c.get("id") or f"call_{random.randint(1, 1 << 30)}", "name": fn.get("name"),
                               "arguments": _parse_args(fn.get("arguments"))})
    u = data.get("usage") or {}
    res.input_tokens = int(u.get("prompt_tokens") or 0)
    res.output_tokens = int(u.get("completion_tokens") or 0)
    res.cache_read = int(((u.get("prompt_tokens_details") or {}).get("cached_tokens")) or u.get("prompt_cache_hit_tokens") or 0)
    res.stop_reason = choice.get("finish_reason") or ""
    return res


# ---------------------------------------------------------------- openai-responses

async def _openai_responses(client: httpx.AsyncClient, spec: dict, messages: list[dict], tools: list[dict],
                            max_tokens: int | None, temperature: float | None) -> LLMResult:
    instructions = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
    items: list[dict] = []
    for m in messages:
        if m["role"] == "system":
            continue
        if m["role"] == "user":
            items.append({"role": "user", "content": m.get("content") or ""})
        elif m["role"] == "assistant":
            if m.get("content"):
                items.append({"role": "assistant", "content": m["content"]})
            for c in m.get("tool_calls") or []:
                items.append({"type": "function_call", "call_id": c["id"], "name": c["name"],
                              "arguments": json.dumps(c.get("arguments") or {}, ensure_ascii=False)})
        elif m["role"] == "tool":
            items.append({"type": "function_call_output", "call_id": m["tool_call_id"], "output": m.get("content") or ""})
    body: dict[str, Any] = {"model": spec["model"], "input": items}
    if instructions:
        body["instructions"] = instructions
    if tools:
        body["tools"] = [{"type": "function", "name": t["name"], "description": t.get("description", ""), "strict": False,
                          "parameters": t.get("parameters") or {"type": "object", "properties": {}}} for t in tools]
    if max_tokens:
        body["max_output_tokens"] = max_tokens
    if temperature is not None:
        body["temperature"] = temperature
    headers = {"Authorization": f"Bearer {spec.get('api_key') or ''}", "Content-Type": "application/json"}
    r = await client.post(_url(spec["base_url"], "/responses"), json=body, headers=headers)
    data = _check(r)
    res = LLMResult()
    texts = []
    for it in data.get("output") or []:
        t = it.get("type")
        if t == "message":
            for c in it.get("content") or []:
                if c.get("type") in ("output_text", "text"):
                    texts.append(c.get("text") or "")
        elif t == "function_call":
            res.tool_calls.append({"id": it.get("call_id") or it.get("id"), "name": it.get("name"),
                                   "arguments": _parse_args(it.get("arguments"))})
        elif t == "reasoning":
            for s in it.get("summary") or []:
                res.thinking += (s.get("text") or "") + "\n"
    res.text = "".join(texts) or data.get("output_text") or ""
    u = data.get("usage") or {}
    res.input_tokens = int(u.get("input_tokens") or 0)
    res.output_tokens = int(u.get("output_tokens") or 0)
    res.cache_read = int(((u.get("input_tokens_details") or {}).get("cached_tokens")) or 0)
    res.stop_reason = data.get("status") or ""
    return res


# ---------------------------------------------------------------- anthropic

def _to_anthropic(messages: list[dict]) -> tuple[str, list[dict]]:
    system = "\n\n".join(m["content"] for m in messages if m["role"] == "system")
    out: list[dict] = []

    def push(role: str, block: dict):
        if out and out[-1]["role"] == role:
            out[-1]["content"].append(block)
        else:
            out.append({"role": role, "content": [block]})

    for m in messages:
        role = m["role"]
        if role == "system":
            continue
        if role == "user":
            if (m.get("content") or "").strip():
                push("user", {"type": "text", "text": m["content"]})
        elif role == "assistant":
            if (m.get("content") or "").strip():
                push("assistant", {"type": "text", "text": m["content"]})
            for c in m.get("tool_calls") or []:
                push("assistant", {"type": "tool_use", "id": c["id"], "name": c["name"], "input": c.get("arguments") or {}})
            # 空的 assistant 回合直接跳过（Anthropic 不接受纯空白文本块）
        elif role == "tool":
            push("user", {"type": "tool_result", "tool_use_id": m["tool_call_id"], "content": m.get("content") or "",
                          **({"is_error": True} if m.get("is_error") else {})})
    return system, out


async def _anthropic(client: httpx.AsyncClient, spec: dict, messages: list[dict], tools: list[dict],
                     max_tokens: int | None, temperature: float | None) -> LLMResult:
    system, msgs = _to_anthropic(messages)
    body: dict[str, Any] = {"model": spec["model"], "messages": msgs, "max_tokens": max_tokens or 8192}
    if system:
        # 系统提示词开启缓存（命中后 cache_read 计入用量）
        body["system"] = [{"type": "text", "text": system, "cache_control": {"type": "ephemeral"}}]
    if tools:
        body["tools"] = [{"name": t["name"], "description": t.get("description", ""),
                          "input_schema": t.get("parameters") or {"type": "object", "properties": {}}} for t in tools]
    if temperature is not None:
        body["temperature"] = temperature
    headers = {"x-api-key": spec.get("api_key") or "", "Authorization": f"Bearer {spec.get('api_key') or ''}",
               "anthropic-version": "2023-06-01", "Content-Type": "application/json"}
    r = await client.post(_url(spec["base_url"], "/messages"), json=body, headers=headers)
    data = _check(r)
    res = LLMResult()
    for b in data.get("content") or []:
        if b.get("type") == "text":
            res.text += b.get("text") or ""
        elif b.get("type") == "thinking":
            res.thinking += b.get("thinking") or ""
        elif b.get("type") == "tool_use":
            res.tool_calls.append({"id": b["id"], "name": b["name"], "arguments": b.get("input") or {}})
    u = data.get("usage") or {}
    res.cache_read = int(u.get("cache_read_input_tokens") or 0)
    res.cache_write = int(u.get("cache_creation_input_tokens") or 0)
    res.input_tokens = int(u.get("input_tokens") or 0) + res.cache_read + res.cache_write
    res.output_tokens = int(u.get("output_tokens") or 0)
    res.stop_reason = data.get("stop_reason") or ""
    return res


def _check(r: httpx.Response) -> dict:
    if r.status_code >= 400:
        text = r.text[:800]
        retryable = r.status_code in (408, 409, 425, 429) or r.status_code >= 500
        raise LLMError(f"模型接口返回 {r.status_code}: {text}", retryable=retryable)
    try:
        return r.json()
    except Exception:
        raise LLMError(f"模型接口返回非 JSON: {r.text[:300]}")


_IMPL = {
    "openai-completions": _openai_completions,
    "openai-responses": _openai_responses,
    "anthropic": _anthropic,
}


async def chat_once(spec: dict, messages: list[dict], tools: list[dict] | None = None,
                    max_tokens: int | None = None, temperature: float | None = None, timeout: float = 600) -> LLMResult:
    impl = _IMPL.get(spec.get("api_type") or "openai-completions")
    if impl is None:
        raise LLMError(f"不支持的 API 类型: {spec.get('api_type')}", retryable=False)
    t0 = time.time()
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(timeout, connect=15)) as client:
            res = await impl(client, spec, messages, tools or [], max_tokens, temperature)
    except httpx.TimeoutException:
        raise LLMError("模型接口超时")
    except httpx.HTTPError as e:
        raise LLMError(f"模型接口网络错误: {e}")
    res.model_ref = spec.get("ref") or f"{spec.get('provider', '')}/{spec.get('model')}"
    res.duration_ms = int((time.time() - t0) * 1000)
    return res


async def chat(models: list[dict], messages: list[dict], tools: list[dict] | None = None, retry: dict | None = None,
               max_tokens: int | None = None, temperature: float | None = None, cancel: asyncio.Event | None = None,
               on_retry=None) -> LLMResult:
    """主模型 + 备用模型容灾 + 指数退避重试"""
    retry = retry or {}
    times = int(retry.get("times", 2)) if retry.get("enabled", True) else 0
    base = float(retry.get("base_delay", 3))
    cap = float(retry.get("max_delay", 30))
    last: Exception | None = None
    for spec in models:
        for attempt in range(times + 1):
            if cancel and cancel.is_set():
                raise asyncio.CancelledError()
            try:
                return await chat_once(spec, messages, tools, max_tokens or spec.get("max_tokens"), temperature)
            except LLMError as e:
                last = e
                if not e.retryable or attempt >= times:
                    break
                delay = min(cap, base * (2 ** attempt)) * (0.8 + random.random() * 0.4)
                if on_retry:
                    await on_retry(spec, attempt + 1, str(e), delay)
                await _sleep(delay, cancel)
    raise LLMError(str(last) if last else "没有可用模型", retryable=False)


async def _sleep(sec: float, cancel: asyncio.Event | None):
    if cancel is None:
        await asyncio.sleep(sec)
        return
    try:
        await asyncio.wait_for(cancel.wait(), timeout=sec)
        raise asyncio.CancelledError()
    except asyncio.TimeoutError:
        return
