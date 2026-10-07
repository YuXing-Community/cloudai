import { toast } from '@/components/ui/toast'

const TOKEN_KEY = 'cloudai.token'
const SPACE_KEY = 'cloudai.space'

export const session = {
  get token() { return localStorage.getItem(TOKEN_KEY) || '' },
  set token(v: string) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY) },
  get spaceId() { return localStorage.getItem(SPACE_KEY) || '' },
  set spaceId(v: string) { v ? localStorage.setItem(SPACE_KEY, v) : localStorage.removeItem(SPACE_KEY) },
}

export class ApiError extends Error {
  constructor(public code: number, message: string) { super(message) }
}

type Query = Record<string, any> | undefined

function qs(q: Query) {
  if (!q) return ''
  const p = new URLSearchParams()
  Object.entries(q).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    if (Array.isArray(v)) v.forEach((x) => p.append(k, String(x)))
    else p.append(k, String(v))
  })
  const s = p.toString()
  return s ? `?${s}` : ''
}

function headers(json = true): Record<string, string> {
  const h: Record<string, string> = {}
  if (json) h['Content-Type'] = 'application/json'
  if (session.token) h.Authorization = `Bearer ${session.token}`
  if (session.spaceId) h['X-Space-Id'] = session.spaceId
  return h
}

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void) { onUnauthorized = fn }

async function handle<T>(resp: Response, silent: boolean): Promise<T> {
  let body: any = null
  const text = await resp.text()
  try { body = text ? JSON.parse(text) : null } catch { body = null }
  if (resp.status === 401 || body?.code === 401) {
    onUnauthorized()
    throw new ApiError(401, body?.message || '登录已失效')
  }
  if (!resp.ok && !body) {
    const msg = resp.status === 502 || resp.status === 504 ? '后端服务不可用，请确认 backend 已启动' : `请求失败 (${resp.status})`
    if (!silent) toast.error(msg)
    throw new ApiError(resp.status, msg)
  }
  if (body && typeof body.code === 'number' && body.code !== 0) {
    if (!silent) toast.error(body.message || '请求失败')
    throw new ApiError(body.code, body.message)
  }
  return (body && 'data' in body ? body.data : body) as T
}

export interface ReqOpts { silent?: boolean; query?: Query }

async function request<T>(method: string, url: string, data?: any, opts: ReqOpts = {}): Promise<T> {
  let resp: Response
  try {
    resp = await fetch(`/api${url}${qs(opts.query)}`, {
      method,
      headers: headers(!(data instanceof FormData)),
      body: data === undefined ? undefined : data instanceof FormData ? data : JSON.stringify(data),
    })
  } catch (e: any) {
    const msg = '网络错误，无法连接后端服务'
    if (!opts.silent) toast.error(msg)
    throw new ApiError(-1, msg)
  }
  return handle<T>(resp, !!opts.silent)
}

export const http = {
  get: <T = any>(url: string, query?: Query, opts: ReqOpts = {}) => request<T>('GET', url, undefined, { ...opts, query }),
  post: <T = any>(url: string, data?: any, opts: ReqOpts = {}) => request<T>('POST', url, data ?? {}, opts),
  put: <T = any>(url: string, data?: any, opts: ReqOpts = {}) => request<T>('PUT', url, data ?? {}, opts),
  del: <T = any>(url: string, query?: Query, opts: ReqOpts = {}) => request<T>('DELETE', url, undefined, { ...opts, query }),
  upload: <T = any>(url: string, form: FormData, opts: ReqOpts = {}) => request<T>('POST', url, form, opts),
}

/** 浏览器直接打开/下载的 URL（带 token 与空间参数） */
export function authedUrl(path: string, extra?: Record<string, any>) {
  return `/api${path}${qs({ ...(extra || {}), token: session.token, spaceId: session.spaceId })}`
}

/** 订阅 SSE（EventSource 不能带 header，token 走 query） */
export function openStream(path: string, extra: Record<string, any>, handlers: Record<string, (data: any) => void>) {
  const es = new EventSource(authedUrl(path, extra))
  Object.entries(handlers).forEach(([name, fn]) => {
    es.addEventListener(name, (e: MessageEvent) => {
      try { fn(JSON.parse(e.data)) } catch { /* ignore */ }
    })
  })
  return es
}

export interface Page<T> { records: T[]; total: number; page: number; size: number }
