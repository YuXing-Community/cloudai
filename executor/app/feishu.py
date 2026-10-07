"""
飞书消息通道
- 每个通道一个独立子进程，用 lark-oapi 的 WebSocket 长连接接收消息（SDK 的 ws 客户端占用进程级事件循环，多通道必须进程隔离）
- 收到消息后回调后端 /api/internal/channels/{id}/message 执行 Agent，再把回复发回飞书
- 发送/回复/表情使用 OpenAPI（httpx），不依赖 SDK
"""
from __future__ import annotations

import json
import multiprocessing as mp
import re
import threading
import time
from typing import Any

import httpx

from . import config

FEISHU = "https://open.feishu.cn/open-apis"
_token_cache: dict[str, tuple[str, float]] = {}


# ------------------------------------------------------------------ OpenAPI（同步，子进程与主进程共用）

def tenant_token(app_id: str, app_secret: str) -> str:
    cached = _token_cache.get(app_id)
    if cached and cached[1] > time.time() + 60:
        return cached[0]
    r = httpx.post(f"{FEISHU}/auth/v3/tenant_access_token/internal", json={"app_id": app_id, "app_secret": app_secret}, timeout=15)
    j = r.json()
    if j.get("code") != 0:
        raise RuntimeError(f"获取飞书 tenant_access_token 失败：{j.get('msg')}")
    _token_cache[app_id] = (j["tenant_access_token"], time.time() + int(j.get("expire", 7200)))
    return j["tenant_access_token"]


def _api(method: str, path: str, app_id: str, app_secret: str, **kw) -> dict:
    h = {"Authorization": f"Bearer {tenant_token(app_id, app_secret)}", "Content-Type": "application/json; charset=utf-8"}
    r = httpx.request(method, FEISHU + path, headers=h, timeout=30, **kw)
    try:
        j = r.json()
    except Exception:
        raise RuntimeError(f"飞书接口返回 {r.status_code}: {r.text[:200]}")
    if j.get("code") not in (0, None):
        raise RuntimeError(f"飞书接口错误 {j.get('code')}: {j.get('msg')}")
    return j.get("data") or j


def bot_info(app_id: str, app_secret: str) -> dict:
    data = _api("GET", "/bot/v3/info", app_id, app_secret)
    bot = data.get("bot") or data
    return {"bot_name": bot.get("app_name") or bot.get("bot_name") or "", "open_id": bot.get("open_id")}


def card(text: str, title: str | None = None, footer: str | None = None) -> str:
    elements: list[dict[str, Any]] = [{"tag": "markdown", "content": text[:28000] or "（空）"}]
    if footer:
        elements.append({"tag": "note", "elements": [{"tag": "plain_text", "content": footer}]})
    c: dict[str, Any] = {"config": {"wide_screen_mode": True}, "elements": elements}
    if title:
        c["header"] = {"title": {"tag": "plain_text", "content": title[:100]}, "template": "blue"}
    return json.dumps(c, ensure_ascii=False)


def send_to_chat(app_id: str, app_secret: str, chat_id: str, text: str, title: str | None = None) -> dict:
    return _api("POST", "/im/v1/messages?receive_id_type=chat_id", app_id, app_secret,
                json={"receive_id": chat_id, "msg_type": "interactive", "content": card(text, title)})


def reply(app_id: str, app_secret: str, message_id: str, text: str, footer: str | None, in_thread: bool) -> dict:
    return _api("POST", f"/im/v1/messages/{message_id}/reply", app_id, app_secret,
                json={"msg_type": "interactive", "content": card(text, None, footer), "reply_in_thread": in_thread})


def add_reaction(app_id: str, app_secret: str, message_id: str, emoji: str = "Typing") -> str | None:
    try:
        d = _api("POST", f"/im/v1/messages/{message_id}/reactions", app_id, app_secret, json={"reaction_type": {"emoji_type": emoji}})
        return d.get("reaction_id")
    except Exception:
        return None


def remove_reaction(app_id: str, app_secret: str, message_id: str, reaction_id: str | None):
    if not reaction_id:
        return
    try:
        _api("DELETE", f"/im/v1/messages/{message_id}/reactions/{reaction_id}", app_id, app_secret)
    except Exception:
        pass


# ------------------------------------------------------------------ 子进程：WebSocket 接收

def _worker(channel: dict, status_q: mp.Queue):
    import lark_oapi as lark
    from lark_oapi.api.im.v1 import P2ImMessageReceiveV1

    app_id, app_secret = channel["app_id"], channel["app_secret"]
    settings = channel.get("settings") or {}
    seen: dict[str, float] = {}
    try:
        me = bot_info(app_id, app_secret)
    except Exception as e:
        status_q.put((channel["channel_id"], "error", str(e)))
        return
    bot_open_id = me.get("open_id")

    def handle(msg: dict):
        mid = msg["message_id"]
        reaction = add_reaction(app_id, app_secret, mid) if settings.get("showTyping", True) else None
        try:
            r = httpx.post(f"{config.BACKEND_URL}/api/internal/channels/{channel['channel_id']}/message", json=msg,
                           headers={"X-Internal-Token": config.INTERNAL_TOKEN, "X-Space-Id": channel.get("space_id", "")},
                           timeout=httpx.Timeout(2400, connect=10))
            body = r.json()
            data = body.get("data") if isinstance(body, dict) and "data" in body else body
            if isinstance(body, dict) and body.get("code") not in (0, None):
                text, footer = f"处理失败：{body.get('message')}", None
            else:
                text, footer = (data or {}).get("reply") or "（无回复）", (data or {}).get("footer")
        except Exception as e:
            text, footer = f"处理失败：{e}", None
        try:
            reply(app_id, app_secret, mid, text, footer, bool(settings.get("replyInThread")))
        except Exception as e:
            status_q.put((channel["channel_id"], "running", f"回复失败：{e}"))
        remove_reaction(app_id, app_secret, mid, reaction)

    def on_message(data: P2ImMessageReceiveV1):
        try:
            ev = data.event
            m = ev.message
            if m.message_id in seen:
                return
            seen[m.message_id] = time.time()
            for k in [k for k, t in seen.items() if t < time.time() - 3600]:
                seen.pop(k, None)
            if m.message_type not in ("text", "post"):
                return
            content = json.loads(m.content or "{}")
            if m.message_type == "text":
                text = content.get("text") or ""
            else:
                text = "\n".join("".join(seg.get("text", "") for seg in line) for line in (content.get("content") or []))
            mentions = m.mentions or []
            mentioned = any(getattr(getattr(x, "id", None), "open_id", None) == bot_open_id for x in mentions)
            is_group = m.chat_type != "p2p"
            if is_group and settings.get("requireMention", True) and not mentioned:
                return
            if is_group and settings.get("filterAtAll") and "@_all" in text and not mentioned:
                return
            text = re.sub(r"@_user_\d+|@_all", "", text).strip()
            if not text:
                return
            sender = ev.sender.sender_id.open_id if ev.sender and ev.sender.sender_id else ""
            msg = {"chat_id": m.chat_id, "chat_type": m.chat_type, "message_id": m.message_id,
                   "thread_id": getattr(m, "thread_id", None) or getattr(m, "root_id", None), "text": text,
                   "sender_id": sender, "sender_name": ""}
            threading.Thread(target=handle, args=(msg,), daemon=True).start()
        except Exception as e:
            status_q.put((channel["channel_id"], "running", f"消息处理异常：{e}"))

    handler = lark.EventDispatcherHandler.builder("", "").register_p2_im_message_receive_v1(on_message).build()
    client = lark.ws.Client(app_id, app_secret, event_handler=handler, log_level=lark.LogLevel.WARNING)
    status_q.put((channel["channel_id"], "running", ""))
    try:
        client.start()
    except Exception as e:
        status_q.put((channel["channel_id"], "error", f"长连接断开：{e}"))


class ChannelManager:
    def __init__(self):
        self.ctx = mp.get_context("spawn")
        self.procs: dict[str, mp.Process] = {}
        self.state: dict[str, dict] = {}
        self.q: mp.Queue = self.ctx.Queue()
        self._t = threading.Thread(target=self._drain, daemon=True)
        self._t.start()

    def _drain(self):
        while True:
            try:
                cid, state, err = self.q.get()
                self.state[cid] = {"state": state, "error": err, "at": time.time()}
            except Exception:
                time.sleep(1)

    def start(self, channel: dict):
        cid = channel["channel_id"]
        self.stop(cid)
        try:
            import lark_oapi  # noqa: F401
        except ImportError:
            self.state[cid] = {"state": "error", "error": "执行器未安装 lark-oapi", "at": time.time()}
            raise RuntimeError("执行器未安装 lark-oapi，请 pip install lark-oapi")
        p = self.ctx.Process(target=_worker, args=(channel, self.q), daemon=True, name=f"feishu-{cid[:8]}")
        p.start()
        self.procs[cid] = p
        self.state[cid] = {"state": "starting", "error": "", "at": time.time()}

    def stop(self, cid: str):
        p = self.procs.pop(cid, None)
        if p and p.is_alive():
            p.terminate()
            p.join(timeout=5)
        self.state[cid] = {"state": "stopped", "error": "", "at": time.time()}

    def status(self) -> dict:
        out = {}
        for cid, st in self.state.items():
            p = self.procs.get(cid)
            s = dict(st)
            if p is not None and not p.is_alive() and s["state"] in ("running", "starting"):
                s = {"state": "error", "error": s.get("error") or f"进程已退出（code {p.exitcode}）", "at": time.time()}
            out[cid] = s
        return out

    def stop_all(self):
        for cid in list(self.procs):
            self.stop(cid)
