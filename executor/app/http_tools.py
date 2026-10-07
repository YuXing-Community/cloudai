"""OpenAPI 插件工具与云日志（阿里云 SLS）查询工具"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import time
from email.utils import formatdate
from urllib.parse import quote

import httpx


def _auth_headers(plugin: dict) -> dict:
    h = dict(plugin.get("headers") or {})
    cred = plugin.get("credential") or {}
    cfg = plugin.get("config") or {}
    if cred and cfg.get("credentialId") is not None and plugin.get("type") == "openapi":
        token = cred.get("token") or cred.get("access_token") or cred.get("bearer")
        api_key = cred.get("api_key") or cred.get("apikey") or cred.get("key")
        if token:
            h.setdefault("Authorization", f"Bearer {token}")
        elif api_key:
            h.setdefault(cfg.get("apiKeyHeader") or "X-API-Key", api_key)
    return h


async def call_openapi(plugin: dict, tool: dict, args: dict) -> tuple[bool, str]:
    hc = tool.get("http_config") or {}
    cfg = plugin.get("config") or {}
    method = (hc.get("method") or "GET").upper()
    path = hc.get("path") or "/"
    args = dict(args or {})
    # 路径参数 {id}
    for name in re.findall(r"{(\w+)}", path):
        if name in args:
            path = path.replace("{" + name + "}", quote(str(args.pop(name)), safe=""))
    base = (plugin.get("endpoint") or "").rstrip("/") + "/" + (cfg.get("basePath") or "").strip("/")
    url = base.rstrip("/") + "/" + path.lstrip("/")
    timeout = float(cfg.get("timeoutMs") or 30000) / 1000
    headers = _auth_headers(plugin)
    query_keys = set(hc.get("query") or [])
    params = {k: v for k, v in args.items() if method in ("GET", "DELETE") or k in query_keys}
    body = {k: v for k, v in args.items() if k not in params}
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as c:
        r = await c.request(method, url, params=params or None, json=body if body and method not in ("GET", "DELETE") else None,
                            headers=headers)
    text = r.text
    try:
        text = json.dumps(r.json(), ensure_ascii=False, indent=2)
    except Exception:
        pass
    return r.status_code < 400, f"HTTP {r.status_code}\n{text}"


# ------------------------------------------------------------------ 阿里云 SLS

def _sls_sign(ak_secret: str, method: str, content_md5: str, content_type: str, date: str, headers: dict, resource: str) -> str:
    canon_headers = "".join(f"{k}:{headers[k]}\n" for k in sorted(headers) if k.startswith("x-log-") or k.startswith("x-acs-"))
    s = f"{method}\n{content_md5}\n{content_type}\n{date}\n{canon_headers}{resource}"
    return base64.b64encode(hmac.new(ak_secret.encode(), s.encode(), hashlib.sha1).digest()).decode()


async def call_cloudlog(plugin: dict, tool: dict, args: dict) -> tuple[bool, str]:
    cfg = plugin.get("config") or {}
    vendor = cfg.get("vendor") or "aliyun_sls"
    if vendor != "aliyun_sls":
        return False, f"当前版本仅实现阿里云 SLS 查询，{vendor} 暂不支持"
    cred = plugin.get("credential") or {}
    ak = cred.get("access_key_id") or cred.get("AccessKeyId") or cred.get("ak")
    sk = cred.get("access_key_secret") or cred.get("AccessKeySecret") or cred.get("sk")
    if not ak or not sk:
        return False, "云日志凭证缺少 access_key_id / access_key_secret 字段"
    hc = tool.get("http_config") or {}
    project = args.get("project") or hc.get("project")
    logstore = args.get("logstore") or hc.get("logstore")
    if not project or not logstore:
        return False, "缺少 project 或 logstore"
    now = int(time.time())
    minutes = int(args.get("minutes") or hc.get("minutes") or 15)
    query = args.get("query") or hc.get("query") or "*"
    params = {
        "type": "log",
        "from": str(int(args.get("from") or now - minutes * 60)),
        "to": str(int(args.get("to") or now)),
        "query": query,
        "line": str(min(int(args.get("line") or hc.get("line") or 100), 1000)),
        "offset": str(int(args.get("offset") or 0)),
        "reverse": "true",
    }
    endpoint = (plugin.get("endpoint") or cfg.get("endpoint") or "cn-hangzhou.log.aliyuncs.com").replace("https://", "").replace("http://", "")
    host = f"{project}.{endpoint}"
    resource = f"/logstores/{logstore}?" + "&".join(f"{k}={params[k]}" for k in sorted(params))
    date = formatdate(usegmt=True)
    xh = {"x-log-apiversion": "0.6.0", "x-log-signaturemethod": "hmac-sha1", "x-log-bodyrawsize": "0"}
    sig = _sls_sign(sk, "GET", "", "", date, xh, resource)
    headers = {**xh, "Date": date, "Host": host, "Authorization": f"LOG {ak}:{sig}"}
    timeout = float(cfg.get("timeoutMs") or 30000) / 1000
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.get(f"https://{host}/logstores/{logstore}", params=params, headers=headers)
    if r.status_code >= 400:
        return False, f"SLS 返回 {r.status_code}: {r.text[:1000]}"
    try:
        rows = r.json()
    except Exception:
        return True, r.text
    return True, json.dumps({"count": len(rows), "logs": rows[:200]}, ensure_ascii=False, indent=2)
