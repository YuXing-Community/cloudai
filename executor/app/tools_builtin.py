"""
内置工具：Bash / Read / Write / Edit / LS / Glob / Grep / artifact_save
文件类工具限制在会话工作区内（另外允许只读访问 Skill 目录与只读记忆库目录）。
"""
from __future__ import annotations

import asyncio
import fnmatch
import os
import re
import signal
from pathlib import Path

from . import config

SPECS = {
    "Bash": {
        "description": "在会话沙箱工作目录中执行 shell 命令（bash）。适合运行脚本、安装依赖、处理数据。返回 stdout/stderr 与退出码。",
        "parameters": {"type": "object", "properties": {
            "command": {"type": "string", "description": "要执行的命令"},
            "timeout": {"type": "integer", "description": "超时秒数，默认 120，最大 600"},
            "description": {"type": "string", "description": "命令用途（5-10 字）"}},
            "required": ["command"]},
    },
    "Read": {
        "description": "读取文本文件，返回带行号的内容。路径可以是工作目录相对路径或允许访问的绝对路径。",
        "parameters": {"type": "object", "properties": {
            "file_path": {"type": "string"}, "offset": {"type": "integer", "description": "起始行（1 开始）"},
            "limit": {"type": "integer", "description": "读取行数，默认 2000"}}, "required": ["file_path"]},
    },
    "Write": {
        "description": "写入（覆盖）文件，自动创建父目录。",
        "parameters": {"type": "object", "properties": {"file_path": {"type": "string"}, "content": {"type": "string"}},
                       "required": ["file_path", "content"]},
    },
    "Edit": {
        "description": "精确替换文件中的字符串。old_string 必须在文件中唯一（或设置 replace_all）。",
        "parameters": {"type": "object", "properties": {
            "file_path": {"type": "string"}, "old_string": {"type": "string"}, "new_string": {"type": "string"},
            "replace_all": {"type": "boolean"}}, "required": ["file_path", "old_string", "new_string"]},
    },
    "LS": {
        "description": "列出目录内容。",
        "parameters": {"type": "object", "properties": {"path": {"type": "string", "description": "默认为工作目录"}}},
    },
    "Glob": {
        "description": "按 glob 模式查找文件，如 **/*.py。",
        "parameters": {"type": "object", "properties": {"pattern": {"type": "string"}, "path": {"type": "string"}},
                       "required": ["pattern"]},
    },
    "Grep": {
        "description": "用正则在文件中搜索内容，返回 文件:行号:内容。",
        "parameters": {"type": "object", "properties": {
            "pattern": {"type": "string"}, "path": {"type": "string"}, "glob": {"type": "string", "description": "文件过滤，如 *.md"},
            "ignore_case": {"type": "boolean"}}, "required": ["pattern"]},
    },
    "artifact_save": {
        "description": "把工作目录中的文件保存为本次会话的产物（报告、表格、图片等），用户可在「产物」中查看和下载。",
        "parameters": {"type": "object", "properties": {
            "file_path": {"type": "string", "description": "工作目录中的文件路径"},
            "artifact_name": {"type": "string", "description": "产物显示名，默认文件名"},
            "mime_type": {"type": "string"}}, "required": ["file_path"]},
    },
}


class Sandbox:
    """会话沙箱：工作目录 + 可读目录白名单 + 环境变量 + 运行中的子进程"""

    def __init__(self, workspace: str, env: dict, readable: list[str] | None = None, writable: list[str] | None = None):
        self.root = Path(workspace).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.env = {k: str(v) for k, v in (env or {}).items()}
        self.readable = [Path(p).resolve() for p in (readable or [])]
        self.writable = [Path(p).resolve() for p in (writable or [])]
        self.procs: set[asyncio.subprocess.Process] = set()

    def resolve(self, p: str | None, write: bool = False) -> Path:
        if not p:
            return self.root
        path = Path(p)
        path = (self.root / path).resolve() if not path.is_absolute() else path.resolve()
        allowed = [self.root] + self.writable + ([] if write else self.readable)
        if not any(path == a or a in path.parents for a in allowed):
            raise PermissionError(f"路径不在允许范围内: {p}（工作目录 {self.root}）")
        return path

    def kill_all(self):
        for p in list(self.procs):
            try:
                os.killpg(p.pid, signal.SIGKILL)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass


def _limit(s: str, n: int = config.TOOL_OUTPUT_LIMIT) -> str:
    if len(s) <= n:
        return s
    half = n // 2
    return s[:half] + f"\n\n…（输出过长，省略 {len(s) - n} 字符）…\n\n" + s[-half:]


async def run_bash(sb: Sandbox, args: dict) -> tuple[bool, str]:
    cmd = args.get("command") or ""
    if not cmd.strip():
        return False, "command 不能为空"
    timeout = min(int(args.get("timeout") or 120), config.BASH_TIMEOUT)
    env = safe_env(sb.env, sb.root)
    # 输出写临时文件而不是管道：命令里带后台进程（cmd &、nohup）时不会因管道未关闭而卡到超时
    out_path = sb.root / f".bash-{os.getpid()}-{id(args)}.out"
    out_f = open(out_path, "wb")
    proc = await asyncio.create_subprocess_exec(
        "/bin/bash", "-c", cmd, cwd=str(sb.root), env=env,
        stdin=asyncio.subprocess.DEVNULL, stdout=out_f, stderr=asyncio.subprocess.STDOUT, start_new_session=True,
    )
    sb.procs.add(proc)
    try:
        await asyncio.wait_for(proc.wait(), timeout=timeout)
        code = proc.returncode
        return code == 0, _limit(_read_tail(out_path)) + (f"\n[exit code {code}]" if code else "")
    except asyncio.TimeoutError:
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except Exception:
            proc.kill()
        return False, _limit(_read_tail(out_path)) + f"\n命令执行超时（{timeout}s），已终止"
    finally:
        sb.procs.discard(proc)
        out_f.close()
        try:
            out_path.unlink()
        except OSError:
            pass


def _read_tail(p: Path, max_bytes: int = 4_000_000) -> str:
    try:
        size = p.stat().st_size
        with open(p, "rb") as f:
            if size > max_bytes:
                f.seek(size - max_bytes)
            return f.read().decode("utf-8", "replace")
    except OSError:
        return ""


# 沙箱子进程只继承这些基础变量；执行器自身的配置（内部 token、Milvus 地址、各种 key）不透传
_ENV_ALLOW = ("PATH", "LANG", "LC_ALL", "LC_CTYPE", "TZ", "TERM", "SHELL", "USER", "LOGNAME", "TMPDIR")


def safe_env(extra: dict, root: Path) -> dict:
    env = {k: os.environ[k] for k in _ENV_ALLOW if k in os.environ}
    env.update({k: str(v) for k, v in (extra or {}).items()})
    env.update({"HOME": str(root), "PWD": str(root), "CLOUDAI_WORKSPACE": str(root), "PYTHONUNBUFFERED": "1"})
    return env


def read_file(sb: Sandbox, args: dict) -> tuple[bool, str]:
    p = sb.resolve(args.get("file_path"))
    if not p.exists():
        return False, f"文件不存在: {args.get('file_path')}"
    if p.is_dir():
        return False, "这是一个目录，请使用 LS"
    size = p.stat().st_size
    with open(p, "rb") as f:
        head = f.read(4096)
        if b"\x00" in head:
            return False, f"二进制文件（{size} 字节），无法以文本读取"
        raw = head + f.read(20_000_000)  # 最多读 20MB，避免超大文件撑爆内存
    lines = raw.decode("utf-8", "replace").splitlines()
    off = max(1, int(args.get("offset") or 1))
    lim = int(args.get("limit") or 2000)
    sel = lines[off - 1: off - 1 + lim]
    body = "\n".join(f"{i:>6}\t{l[:2000]}" for i, l in enumerate(sel, start=off))
    more = f"\n…（共 {len(lines)} 行，已显示 {off}-{off + len(sel) - 1}）" if off - 1 + lim < len(lines) else ""
    return True, _limit(body + more)


def write_file(sb: Sandbox, args: dict) -> tuple[bool, str]:
    p = sb.resolve(args.get("file_path"), write=True)
    p.parent.mkdir(parents=True, exist_ok=True)
    content = args.get("content") or ""
    p.write_text(content, "utf-8")
    return True, f"已写入 {p.relative_to(sb.root) if sb.root in p.parents else p}（{len(content)} 字符）"


def edit_file(sb: Sandbox, args: dict) -> tuple[bool, str]:
    p = sb.resolve(args.get("file_path"), write=True)
    if not p.exists():
        return False, "文件不存在"
    text = p.read_text("utf-8")
    old, new = args.get("old_string") or "", args.get("new_string") or ""
    if not old:
        return False, "old_string 不能为空"
    n = text.count(old)
    if n == 0:
        return False, "未找到 old_string"
    if n > 1 and not args.get("replace_all"):
        return False, f"old_string 出现了 {n} 次，请提供更多上下文或设置 replace_all"
    text = text.replace(old, new) if args.get("replace_all") else text.replace(old, new, 1)
    p.write_text(text, "utf-8")
    return True, f"已替换 {n if args.get('replace_all') else 1} 处"


def list_dir(sb: Sandbox, args: dict) -> tuple[bool, str]:
    p = sb.resolve(args.get("path"))
    if not p.is_dir():
        return False, "目录不存在"
    rows = []
    for c in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name))[:500]:
        try:
            rows.append(f"{c.name}/" if c.is_dir() else f"{c.name}  ({c.stat().st_size} B)")
        except OSError:
            rows.append(f"{c.name}  (无法访问)")
    return True, "\n".join(rows) or "（空目录）"


def glob_files(sb: Sandbox, args: dict) -> tuple[bool, str]:
    base = sb.resolve(args.get("path"))
    pat = args.get("pattern") or "*"
    if ".." in Path(pat).parts or Path(pat).is_absolute():
        return False, "pattern 不能包含 .. 或绝对路径"
    res = []
    for x in base.glob(pat):
        try:
            if x.is_file() and (x.resolve() == base or base in x.resolve().parents):
                res.append(str(x.relative_to(base)))
        except OSError:
            continue
        if len(res) >= 500:
            break
    return True, "\n".join(sorted(res)) or "没有匹配的文件"


def grep(sb: Sandbox, args: dict) -> tuple[bool, str]:
    base = sb.resolve(args.get("path"))
    try:
        rx = re.compile(args.get("pattern") or "", re.I if args.get("ignore_case") else 0)
    except re.error as e:
        return False, f"正则错误: {e}"
    flt = args.get("glob")
    files = [base] if base.is_file() else [f for f in base.rglob("*") if f.is_file()]
    out = []
    for f in files:
        if flt and not fnmatch.fnmatch(f.name, flt):
            continue
        try:
            if f.stat().st_size > 5_000_000:
                continue
            for i, line in enumerate(f.read_text("utf-8", errors="ignore").splitlines(), 1):
                if rx.search(line):
                    out.append(f"{f.relative_to(base) if f != base else f.name}:{i}:{line[:300]}")
                    if len(out) >= 300:
                        break
        except Exception:
            continue
        if len(out) >= 300:
            break
    return True, "\n".join(out) or "没有匹配"


async def run_builtin(name: str, sb: Sandbox, args: dict) -> tuple[bool, str]:
    try:
        if name == "Bash":
            return await run_bash(sb, args)
        fn = {"Read": read_file, "Write": write_file, "Edit": edit_file, "LS": list_dir, "Glob": glob_files, "Grep": grep}.get(name)
        if fn is None:
            return False, f"未知工具 {name}"
        return await asyncio.to_thread(fn, sb, args)
    except PermissionError as e:
        return False, str(e)
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"
