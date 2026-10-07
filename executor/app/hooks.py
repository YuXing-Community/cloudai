"""
Hooks 与权限规则（兼容 Claude Code Hook 语义）
- deny 规则：Tool(pattern) 形式，如 Bash(rm:*)、Read(/etc/**)，命中则拒绝工具调用
- 事件：PreToolUse / PostToolUse / PostToolUseFailure / Stop / PreCompact
- 处理器：command（会话工作区内执行，事件 JSON 通过 stdin 传入；退出码 2 表示阻止）或 http（POST 事件 JSON）
"""
from __future__ import annotations

import asyncio
import fnmatch
import json
import os
import re

import httpx

from pathlib import Path

from .tools_builtin import safe_env

# 工具调用的“主参数”，用于规则匹配
PRIMARY_ARG = {"Bash": "command", "Read": "file_path", "Write": "file_path", "Edit": "file_path",
               "LS": "path", "Glob": "pattern", "Grep": "pattern"}

_BG: set = set()  # 异步处理器任务的强引用，防止被 GC

RULE_RE = re.compile(r"^\s*([\w\-*]+)\s*(?:\((.*)\))?\s*$")


def _match_rule(rule: str, tool: str, args: dict) -> bool:
    m = RULE_RE.match(rule or "")
    if not m:
        return False
    name, pattern = m.group(1), m.group(2)
    if not fnmatch.fnmatchcase(tool, name):
        return False
    if pattern is None or pattern in ("", "*", "**"):
        return True
    value = str(args.get(PRIMARY_ARG.get(tool, ""), "") or json.dumps(args, ensure_ascii=False))
    if tool == "Bash" and pattern.endswith(":*"):
        return value.strip().startswith(pattern[:-2])
    pat = pattern
    if pat.startswith("./"):
        pat = pat[2:]
        value = value.lstrip("./")
    return fnmatch.fnmatch(value, pat) or fnmatch.fnmatch(value, pat.replace("**", "*"))


class HookRunner:
    def __init__(self, hooks: list[dict], workspace: str, env: dict, session_id: str):
        self.workspace = workspace
        self.env = env
        self.session_id = session_id
        self.deny: list[str] = []
        self.allow: list[str] = []
        self.events: dict[str, list[dict]] = {}
        for h in hooks or []:
            cfg = h.get("config") or {}
            self.deny += [r for r in cfg.get("deny") or [] if r]
            self.allow += [r for r in cfg.get("allow") or [] if r]
            for ev, groups in (cfg.get("hooks") or {}).items():
                self.events.setdefault(ev, []).extend(groups or [])

    def denied(self, tool: str, args: dict) -> str | None:
        for r in self.deny:
            if _match_rule(r, tool, args):
                return f"工具调用被 Deny 规则拒绝：{r}"
        return None

    async def fire(self, event: str, tool: str | None = None, payload: dict | None = None) -> tuple[bool, str]:
        """执行事件处理器；返回 (是否阻止, 原因/输出)"""
        groups = self.events.get(event) or []
        if not groups:
            return False, ""
        data = {"hook_event_name": event, "session_id": self.session_id, "cwd": self.workspace, **(payload or {})}
        if tool:
            data["tool_name"] = tool
        outputs = []
        for g in groups:
            matcher = (g.get("matcher") or "").strip()
            if tool and matcher and matcher != "*":
                try:
                    if not re.fullmatch(matcher, tool):
                        continue
                except re.error:
                    if matcher != tool:
                        continue
            for hd in g.get("handlers") or []:
                coro = self._run_handler(hd, data)
                if hd.get("async"):
                    t = asyncio.create_task(coro)
                    _BG.add(t)
                    t.add_done_callback(_BG.discard)
                    continue
                blocked, out = await coro
                if out:
                    outputs.append(out)
                if blocked:
                    return True, out or f"{event} Hook 阻止了本次操作"
        return False, "\n".join(outputs)

    async def _run_handler(self, hd: dict, data: dict) -> tuple[bool, str]:
        kind = hd.get("type") or "command"
        timeout = float(hd.get("timeout") or 0) or (5 if kind == "http" else 60)
        try:
            if kind == "http":
                headers = {h.get("name"): h.get("value") for h in hd.get("headers") or [] if h.get("name")}
                async with httpx.AsyncClient(timeout=timeout) as c:
                    r = await c.request(hd.get("method") or "POST", hd.get("url"), json=data, headers=headers)
                body = r.text[:2000]
                try:
                    j = r.json()
                    if isinstance(j, dict) and j.get("decision") == "block":
                        return True, j.get("reason") or "HTTP Hook 阻止"
                except Exception:
                    pass
                return False, body
            cmd = hd.get("command") or ""
            args = [str(a) for a in hd.get("args") or []]
            if args:
                cmd = cmd + " " + " ".join(_shq(a) for a in args)
            shell = hd.get("shell") or "default"
            exe = None if shell == "default" else f"/bin/{shell}"
            proc = await asyncio.create_subprocess_shell(
                cmd, cwd=self.workspace, executable=exe, stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, env=safe_env(self.env, Path(self.workspace)),
            )
            try:
                out, err = await asyncio.wait_for(proc.communicate(json.dumps(data, ensure_ascii=False).encode()), timeout)
            except asyncio.TimeoutError:
                proc.kill()
                return False, f"Hook 命令超时（{timeout}s）"
            so, se = out.decode("utf-8", "replace").strip(), err.decode("utf-8", "replace").strip()
            if proc.returncode == 2:
                return True, se or so or "Hook 阻止"
            try:
                j = json.loads(so) if so.startswith("{") else None
                if isinstance(j, dict) and j.get("decision") == "block":
                    return True, j.get("reason") or "Hook 阻止"
            except Exception:
                pass
            return False, so[:2000]
        except Exception as e:
            return False, f"Hook 执行失败: {e}"


def _shq(s: str) -> str:
    return "'" + s.replace("'", "'\"'\"'") + "'"
