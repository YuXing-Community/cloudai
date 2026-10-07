"""
Agent 执行循环：模型 ↔ 工具，多轮迭代直到模型给出最终回复。
每一步通过 emit 推送事件（见 docs/agent-config.md「执行器事件」），由后端落库到 MySQL。
"""
from __future__ import annotations

import asyncio
import json
import mimetypes
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx

from . import config, knowledge, llm
from .hooks import HookRunner
from .http_tools import call_cloudlog, call_openapi
from .mcp import MCPClient
from .tools_builtin import SPECS as BUILTIN_SPECS, Sandbox, run_builtin

Emit = Callable[[dict], Awaitable[None]]
CST = timezone(timedelta(hours=8))


def _tool_name(*parts: str) -> str:
    n = "__".join(re.sub(r"[^a-zA-Z0-9_-]", "_", p or "x") for p in parts)
    return n[:64]


def _short(v: Any, n: int = 400) -> str:
    s = v if isinstance(v, str) else json.dumps(v, ensure_ascii=False)
    return s if len(s) <= n else s[:n] + "…"


class Run:
    """一次 Agent 执行（可嵌套用于 SubAgent）"""

    def __init__(self, payload: dict, emit: Emit, cancel: asyncio.Event, depth: int = 0, workspace: str | None = None):
        self.p = payload
        self.emit = emit
        self.cancel = cancel
        self.depth = depth
        self.workspace = workspace or payload.get("workspace_dir") or str(config.DATA_DIR / "workspaces" / "tmp")
        self.env = dict(payload.get("env") or {})
        skills = payload.get("skills") or []
        stores = payload.get("memory_stores") or []
        self.agent_mem_dir = payload.get("agent_memory_dir") if payload.get("memory_enabled") else None
        readable = [s["dir"] for s in skills] + [s["dir"] for s in stores]
        writable = [s["dir"] for s in stores if s.get("access", "rw") == "rw"]
        if self.agent_mem_dir:
            Path(self.agent_mem_dir).mkdir(parents=True, exist_ok=True)
            writable.append(self.agent_mem_dir)
        self.sb = Sandbox(self.workspace, self.env, readable=readable, writable=writable)
        self.hooks = HookRunner(payload.get("hooks") or [], str(self.sb.root), self.env, payload.get("session_id", ""))
        self.tools: dict[str, dict] = {}
        self.mcp_clients: dict[int, MCPClient] = {}
        self.final_text = ""
        self.turns = 0

    # ------------------------------------------------------------------ 工具注册

    def _register(self, name: str, description: str, parameters: dict, handler, kind: str, meta: dict | None = None):
        self.tools[name] = {"name": name, "description": description[:1024], "parameters": parameters or {"type": "object", "properties": {}},
                            "handler": handler, "kind": kind, "meta": meta or {}}

    def build_tools(self):
        p = self.p
        enabled = p.get("builtin_tools") or list(BUILTIN_SPECS)
        for name in enabled:
            spec = BUILTIN_SPECS.get(name)
            if spec:
                self._register(name, spec["description"], spec["parameters"], None, "builtin")

        for plug in p.get("plugins") or []:
            key = plug.get("key") or plug.get("name") or f"p{plug.get('id')}"
            for t in plug.get("tools") or []:
                prefix = "mcp" if plug.get("type") == "mcp" else plug.get("type")
                name = _tool_name(prefix, key, t["name"])
                n = 2
                while name in self.tools:
                    name = _tool_name(prefix, key, t["name"])[:60] + f"_{n}"
                    n += 1
                desc = f"[{plug.get('name')}] {t.get('description') or ''}"
                self._register(name, desc, t.get("input_schema") or {"type": "object", "properties": {}}, None,
                               plug.get("type"), {"plugin": plug, "tool": t})

        skills = p.get("skills") or []
        if skills:
            self._register("Skill", "加载一个 Skill 的完整使用说明（SKILL.md）和文件清单。在执行与该 Skill 相关的任务前先调用。",
                           {"type": "object", "properties": {"name": {"type": "string", "enum": [s["name"] for s in skills]}},
                            "required": ["name"]}, None, "skill")

        stores = p.get("memory_stores") or []
        if stores:
            ids = [s["id"] for s in stores]
            self._register("memory_search", "在关联的记忆库/知识库中语义检索相关内容（向量检索，失败时回退关键词检索）。",
                           {"type": "object", "properties": {"query": {"type": "string"},
                                                             "store_id": {"type": "string", "enum": ids, "description": "不填则检索全部"},
                                                             "top_k": {"type": "integer"}}, "required": ["query"]}, None, "memory")
            self._register("memory_list", "列出记忆库中的文档目录。",
                           {"type": "object", "properties": {"store_id": {"type": "string", "enum": ids},
                                                             "dir": {"type": "string", "description": "库内目录，默认 /"}},
                            "required": ["store_id"]}, None, "memory")
            self._register("memory_read", "读取记忆库中的文档。",
                           {"type": "object", "properties": {"store_id": {"type": "string", "enum": ids}, "path": {"type": "string"}},
                            "required": ["store_id", "path"]}, None, "memory")
            rw = [s["id"] for s in stores if s.get("access", "rw") == "rw"]
            if rw:
                self._register("memory_write", "向可写记忆库写入/覆盖一篇文档（Markdown），用于沉淀经验、案例、结论。",
                               {"type": "object", "properties": {"store_id": {"type": "string", "enum": rw},
                                                                 "path": {"type": "string", "description": "如 /cases/2026-10-01-xx.md"},
                                                                 "content": {"type": "string"}},
                                "required": ["store_id", "path", "content"]}, None, "memory")

        if self.agent_mem_dir:
            self._register("remember", "把值得长期记住的信息（用户偏好、重要结论、约定）追加到 Agent 的长期记忆。",
                           {"type": "object", "properties": {"note": {"type": "string"}}, "required": ["note"]}, None, "remember")

        subs = p.get("sub_agents") or []
        if subs:
            desc = "把子任务委派给 SubAgent 执行并返回其最终结果。可用：\n" + "\n".join(
                f"- {s['identifier']}: {s['name']}，{s.get('description') or ''}" for s in subs)
            self._register("delegate_to_agent", desc,
                           {"type": "object", "properties": {"agent": {"type": "string", "enum": [s["identifier"] for s in subs]},
                                                             "task": {"type": "string", "description": "完整、自包含的任务描述"}},
                            "required": ["agent", "task"]}, None, "subagent")

    # ------------------------------------------------------------------ 系统提示词

    def system_prompt(self) -> str:
        p = self.p
        now = datetime.now(CST).strftime("%Y-%m-%d %H:%M:%S")
        parts = [p.get("system_prompt") or "你是一个乐于助人的 AI 助手。"]
        env = [
            "\n\n---\n# 运行环境（平台注入）",
            f"- 你是 CloudAI 平台托管的 Agent「{p.get('agent', {}).get('name', '')}」。当前时间 {now}（Asia/Shanghai）。",
            f"- 沙箱工作目录：{self.sb.root}。Bash 与文件工具默认在此目录工作，不要访问目录之外的路径。",
        ]
        if "artifact_save" in self.tools:
            env.append("- 需要交付文件（报告、表格、图片）时，先写入工作目录，再调用 artifact_save 保存为会话产物。")
        if self.env:
            env.append("- 已注入环境变量（值不可见，用 $NAME 引用）：" + "、".join(sorted(self.env)))
        for cli in p.get("cli_plugins") or []:
            hint = {"lark": "lark-cli（飞书）：使用 LARK_APP_ID / LARK_APP_SECRET",
                    "gitlab": "GitLab CLI：使用 GITLAB_TOKEN / GITLAB_HOST",
                    "cloudai": "cloudai-cli：可访问当前平台能力"}.get(cli.get("type"), cli.get("type"))
            env.append(f"- CLI 插件 {hint}" + (f"（{cli['label']}）" if cli.get("label") else ""))
        parts.append("\n".join(env))
        skills = p.get("skills") or []
        if skills:
            parts.append("\n# 可用 Skills\n使用某个 Skill 前先调用 Skill 工具读取完整说明；Skill 文件目录可用 Read/Bash 访问。\n" +
                         "\n".join(f"- **{s['name']}**（v{s.get('version')}，目录 {s['dir']}）：{_short(s.get('description') or '', 300)}"
                                   for s in skills))
        stores = p.get("memory_stores") or []
        if stores:
            parts.append("\n# 关联记忆库\n" + "\n".join(
                f"- {s['name']}（id={s['id']}，{'读写' if s.get('access', 'rw') == 'rw' else '只读'}）：{s.get('description') or ''}"
                + (f"\n  使用说明：{s['instruction']}" if s.get("instruction") else "") for s in stores))
        if self.agent_mem_dir:
            mem = Path(self.agent_mem_dir) / "MEMORY.md"
            if mem.exists():
                parts.append("\n# 长期记忆（之前会话沉淀）\n" + mem.read_text("utf-8")[-6000:])
        return "\n".join(parts)

    # ------------------------------------------------------------------ 执行

    async def execute(self, history: list[dict], message: str) -> tuple[str, str | None]:
        """返回 (status, error)"""
        self.build_tools()
        messages: list[dict] = [{"role": "system", "content": self.system_prompt()}]
        for h in history or []:
            if h.get("content"):
                messages.append({"role": h["role"], "content": h["content"]})
        messages.append({"role": "user", "content": message})
        tool_defs = [{k: t[k] for k in ("name", "description", "parameters")} for t in self.tools.values()]
        models = self.p.get("models") or []
        if not models:
            return "failed", "未配置模型"
        ctx_window = int(models[0].get("context_window") or 128000)
        max_turns = int(self.p.get("max_turns") or 40)
        temperature = self.p.get("temperature")

        async def on_retry(spec, attempt, err, delay):
            await self.emit({"type": "error", "content": f"模型调用失败，{delay:.0f}s 后第 {attempt} 次重试（{spec.get('ref')}）：{_short(err, 300)}"})

        try:
            for turn in range(max_turns):
                if self.cancel.is_set():
                    return "stopped", "已手动停止"
                await self._maybe_compact(messages, ctx_window)
                res = await self._cancellable(llm.chat(models, messages, tool_defs, self.p.get("retry"), None, temperature, self.cancel, on_retry))
                self.turns += 1
                await self.emit({"type": "model", "name": res.model_ref, "input_tokens": res.input_tokens,
                                 "output_tokens": res.output_tokens, "cache_read": res.cache_read, "cache_write": res.cache_write,
                                 "duration_ms": res.duration_ms, "tool_calls": len(res.tool_calls), "stop_reason": res.stop_reason})
                if res.thinking and self.p.get("show_thinking"):
                    await self.emit({"type": "thinking", "content": res.thinking})
                if res.text:
                    await self.emit({"type": "agent", "content": res.text, "duration_ms": res.duration_ms})
                messages.append({"role": "assistant", "content": res.text, "tool_calls": res.tool_calls})
                if not res.tool_calls:
                    self.final_text = res.text
                    blocked, out = await self.hooks.fire("Stop", None, {"last_message": res.text})
                    if blocked and turn + 1 < max_turns:
                        # Stop Hook 阻止结束：把原因作为反馈继续
                        messages.append({"role": "user", "content": f"[Stop Hook 反馈] {out}"})
                        continue
                    return "success", None
                for call in res.tool_calls:
                    if self.cancel.is_set():
                        return "stopped", "已手动停止"
                    result, ok = await self._cancellable(self._call_tool(call), on_cancel=lambda c=call: self._emit_tool_stopped(c))
                    messages.append({"role": "tool", "tool_call_id": call["id"], "name": call["name"], "content": result,
                                     "is_error": not ok})
            return "failed", f"Agent 已达到最大迭代次数（{max_turns}），仍未完成任务"
        except asyncio.CancelledError:
            return "stopped", "已手动停止"
        except llm.LLMError as e:
            return "failed", str(e)
        finally:
            self.sb.kill_all()
            for c in self.mcp_clients.values():
                try:
                    await c.close()
                except Exception:
                    pass

    async def _cancellable(self, coro, on_cancel=None):
        """与取消信号赛跑：收到停止时立即中断（杀掉沙箱子进程），抛出 CancelledError"""
        task = asyncio.ensure_future(coro)
        waiter = asyncio.ensure_future(self.cancel.wait())
        try:
            done, _ = await asyncio.wait({task, waiter}, return_when=asyncio.FIRST_COMPLETED)
        except asyncio.CancelledError:
            task.cancel()
            raise
        finally:
            waiter.cancel()
        if task in done:
            return task.result()
        self.sb.kill_all()
        task.cancel()
        try:
            await task
        except BaseException:
            pass
        if on_cancel:
            await on_cancel()
        raise asyncio.CancelledError()

    async def _emit_tool_stopped(self, call: dict):
        await self.emit({"type": "tool_end", "call_id": call.get("id"), "name": call.get("name"), "result": "已手动停止",
                         "status": "error", "duration_ms": 0})

    async def _maybe_compact(self, messages: list[dict], ctx_window: int):
        est = sum(len(m.get("content") or "") + len(json.dumps(m.get("tool_calls") or [], ensure_ascii=False)) for m in messages) / 2.5
        if est < ctx_window * 0.75 or getattr(self, "_compacted_at", 0) >= len(messages):
            return
        self._compacted_at = len(messages)
        await self.hooks.fire("PreCompact", None, {"trigger": "auto", "estimated_tokens": int(est)})
        keep_tail = 8
        for m in messages[1:-keep_tail]:
            if m["role"] == "tool" and len(m.get("content") or "") > 600:
                m["content"] = m["content"][:300] + "\n…（上下文压缩：早期工具输出已截断）…\n" + m["content"][-200:]
            elif m["role"] in ("assistant", "user") and len(m.get("content") or "") > 4000:
                m["content"] = m["content"][:2000] + "\n…（上下文压缩）…"
        await self.emit({"type": "error", "name": "compact", "content": f"上下文接近上限（约 {int(est)} tokens），已自动压缩早期工具输出"})

    async def _call_tool(self, call: dict) -> tuple[str, bool]:
        call["id"] = call.get("id") or uuid.uuid4().hex
        name, args = call["name"], call.get("arguments") or {}
        cid = call["id"]
        await self.emit({"type": "tool_start", "call_id": cid, "name": name, "args": args})
        t0 = time.time()
        ok, result = False, ""
        tool = self.tools.get(name)
        try:
            if tool is None:
                ok, result = False, f"工具不存在：{name}"
            elif (deny := self.hooks.denied(name, args)):
                ok, result = False, deny
            else:
                blocked, reason = await self.hooks.fire("PreToolUse", name, {"tool_input": args})
                if blocked:
                    ok, result = False, f"PreToolUse Hook 阻止：{reason}"
                else:
                    ok, result = await self._dispatch(tool, args)
                    ev = "PostToolUse" if ok else "PostToolUseFailure"
                    _, extra = await self.hooks.fire(ev, name, {"tool_input": args, "tool_response": _short(result, 4000)})
                    if extra:
                        result += f"\n[{ev} Hook] {extra}"
        except asyncio.CancelledError:
            raise
        except Exception as e:
            ok, result = False, f"{type(e).__name__}: {e}"
        result = result if len(result) <= config.TOOL_OUTPUT_LIMIT else result[:config.TOOL_OUTPUT_LIMIT] + "\n…（已截断）"
        await self.emit({"type": "tool_end", "call_id": cid, "name": name, "result": result, "status": "ok" if ok else "error",
                         "duration_ms": int((time.time() - t0) * 1000)})
        return result, ok

    async def _dispatch(self, tool: dict, args: dict) -> tuple[bool, str]:
        kind, name = tool["kind"], tool["name"]
        if kind == "builtin":
            if name == "artifact_save":
                return await self._artifact(args)
            return await run_builtin(name, self.sb, args)
        if kind == "mcp":
            plug, t = tool["meta"]["plugin"], tool["meta"]["tool"]
            client = self.mcp_clients.get(plug["id"])
            if client is None:
                client = MCPClient(plug["endpoint"], plug.get("transport"), plug.get("headers"))
                self.mcp_clients[plug["id"]] = client  # 先登记，保证 finally 中一定会被关闭
                try:
                    await client.connect()
                except BaseException:
                    self.mcp_clients.pop(plug["id"], None)
                    await client.close()
                    raise
            return await client.call_tool(t["name"], args)
        if kind == "openapi":
            return await call_openapi(tool["meta"]["plugin"], tool["meta"]["tool"], args)
        if kind == "cloudlog":
            return await call_cloudlog(tool["meta"]["plugin"], tool["meta"]["tool"], args)
        if kind == "skill":
            return await self._skill(args)
        if kind == "memory":
            return await self._memory(name, args)
        if kind == "remember":
            mem = Path(self.agent_mem_dir) / "MEMORY.md"
            with mem.open("a", encoding="utf-8") as f:
                f.write(f"- [{datetime.now(CST).strftime('%Y-%m-%d %H:%M')}] {args.get('note', '').strip()}\n")
            return True, "已写入长期记忆"
        if kind == "subagent":
            return await self._delegate(args)
        return False, f"未知工具类型 {kind}"

    async def _artifact(self, args: dict) -> tuple[bool, str]:
        p = self.sb.resolve(args.get("file_path"))
        if not p.is_file():
            return False, f"文件不存在: {args.get('file_path')}"
        name = args.get("artifact_name") or p.name
        mime = args.get("mime_type") or mimetypes.guess_type(name)[0] or "application/octet-stream"
        await self.emit({"type": "artifact", "name": name, "path": str(p), "mime": mime})
        return True, f"产物已保存：{name}（{p.stat().st_size} 字节，{mime}）"

    async def _skill(self, args: dict) -> tuple[bool, str]:
        sk = next((s for s in self.p.get("skills") or [] if s["name"] == args.get("name")), None)
        if sk is None:
            return False, "Skill 不存在"
        d = Path(sk["dir"])
        md = d / "SKILL.md"
        content = md.read_text("utf-8") if md.exists() else "（缺少 SKILL.md）"
        files = [str(f.relative_to(d)) for f in sorted(d.rglob("*")) if f.is_file()][:200]
        await self.emit({"type": "skill_used", "name": sk["name"]})
        return True, f"# Skill: {sk['name']} v{sk.get('version')}\n目录：{d}\n文件：\n" + "\n".join(f"- {f}" for f in files) + \
            f"\n\n---\n{content}\n\n（脚本中出现的 ~/.claude/skills/{sk['name']}/ 等路径请替换为上面的目录）"

    async def _memory(self, name: str, args: dict) -> tuple[bool, str]:
        stores = {s["id"]: s for s in self.p.get("memory_stores") or []}
        if name == "memory_search":
            ids = [args["store_id"]] if args.get("store_id") else list(stores)
            top_k = int(args.get("top_k") or 6)
            try:
                hits = await asyncio.to_thread(knowledge.search, self.p.get("space_id", ""), ids, args.get("query", ""), top_k)
                mode = "向量检索"
            except Exception as e:
                hits = await asyncio.to_thread(knowledge.grep_search, [(i, stores[i]["dir"]) for i in ids], args.get("query", ""), top_k)
                mode = f"关键词检索（向量服务不可用：{_short(str(e), 120)}）"
            if not hits:
                return True, f"{mode}：没有找到相关内容"
            lines = [f"{mode}，命中 {len(hits)} 条："]
            for i, h in enumerate(hits, 1):
                sname = stores.get(h.get("store_id"), {}).get("name", h.get("store_id"))
                lines.append(f"\n## {i}. {sname} · {h.get('path')}（score {h.get('score')}）\n{h.get('text')}")
            return True, "\n".join(lines)
        st = stores.get(args.get("store_id"))
        if st is None:
            return False, "记忆库不存在或未关联"
        base = Path(st["dir"])
        base.mkdir(parents=True, exist_ok=True)
        rel = (args.get("path") or args.get("dir") or "/").lstrip("/")
        target = (base / rel).resolve()
        if not (target == base.resolve() or base.resolve() in target.parents):
            return False, "非法路径"
        if name == "memory_list":
            if not target.exists():
                return True, "（目录不存在）"
            items = [("/" + str(f.relative_to(base))) + ("/" if f.is_dir() else "") for f in sorted(target.rglob("*"))
                     if "_index" not in f.relative_to(base).parts][:500]
            return True, "\n".join(items) or "（空）"
        if name == "memory_read":
            if not target.is_file():
                return False, "文档不存在"
            return True, target.read_text("utf-8", errors="replace")[:config.TOOL_OUTPUT_LIMIT]
        if name == "memory_write":
            if st.get("access", "rw") != "rw":
                return False, "该记忆库为只读"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(args.get("content") or "", "utf-8")
            await _notify_memory_changed(self.p, st["id"], "/" + rel)
            return True, f"已写入 {st['name']}:/{rel}"
        return False, "未知记忆操作"

    async def _delegate(self, args: dict) -> tuple[bool, str]:
        sub = next((s for s in self.p.get("sub_agents") or [] if s["identifier"] == args.get("agent")), None)
        if sub is None:
            return False, "SubAgent 不存在"
        payload = dict(sub["payload"])
        payload["space_id"] = self.p.get("space_id")
        payload["session_id"] = self.p.get("session_id")
        payload["internal"] = self.p.get("internal")
        sub_ws = str(Path(self.workspace) / "subagents" / sub["identifier"])

        async def sub_emit(ev: dict):
            # SubAgent 的模型用量与产物计入本次执行；过程事件不直接展示
            if ev["type"] == "model":
                await self.emit({**ev, "name": f"{sub['identifier']} · {ev.get('name')}"})
            elif ev["type"] in ("artifact", "skill_used"):
                await self.emit(ev)

        child = Run(payload, sub_emit, self.cancel, self.depth + 1, workspace=sub_ws)
        status, err = await child.execute([], args.get("task") or "")
        if status != "success":
            return False, f"SubAgent {sub['name']} 执行{status}：{err}"
        return True, child.final_text or "（SubAgent 无输出）"


async def _notify_memory_changed(payload: dict, store_id: str, path: str):
    internal = payload.get("internal") or {}
    url = (internal.get("backend_url") or config.BACKEND_URL).rstrip("/") + f"/api/internal/memory/{store_id}/changed"
    try:
        async with httpx.AsyncClient(timeout=10) as c:
            await c.post(url, json={"path": path}, headers={"X-Internal-Token": internal.get("token") or config.INTERNAL_TOKEN,
                                                           "X-Space-Id": payload.get("space_id") or ""})
    except Exception:
        pass
