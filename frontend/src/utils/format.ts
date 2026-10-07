/** 通用格式化工具 */

export function pad(n: number) { return n < 10 ? `0${n}` : `${n}` }

function toDate(v: any): Date | null {
  if (!v) return null
  if (v instanceof Date) return v
  if (typeof v === 'number') return new Date(v)
  // 后端格式 yyyy-MM-dd HH:mm:ss 或 ISO
  const s = String(v).replace(' ', 'T')
  const d = new Date(s)
  return isNaN(d.getTime()) ? null : d
}

/** 2026/9/22 18:54:01 */
export function fmtTime(v: any, withSec = true) {
  const d = toDate(v)
  if (!d) return '—'
  const base = `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
  return withSec ? `${base}:${pad(d.getSeconds())}` : base
}

/** 2026/9/22 */
export function fmtDate(v: any) {
  const d = toDate(v)
  if (!d) return '—'
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

/** 2026/07/14 */
export function fmtDatePad(v: any) {
  const d = toDate(v)
  if (!d) return '—'
  return `${d.getFullYear()}/${pad(d.getMonth() + 1)}/${pad(d.getDate())}`
}

export function fmtClock(v: any) {
  const d = toDate(v)
  if (!d) return '—'
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 相对时间：刚刚 / 5m / 3h / 8d */
export function ago(v: any, short = true) {
  const d = toDate(v)
  if (!d) return '—'
  const s = Math.max(0, (Date.now() - d.getTime()) / 1000)
  if (s < 60) return short ? '刚刚' : '刚刚'
  if (s < 3600) return short ? `${Math.floor(s / 60)}m` : `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return short ? `${Math.floor(s / 3600)}h` : `${Math.floor(s / 3600)} 小时前`
  if (s < 86400 * 365) return short ? `${Math.floor(s / 86400)}d` : `${Math.floor(s / 86400)} 天前`
  return short ? `${Math.floor(s / 86400 / 365)}y` : `${Math.floor(s / 86400 / 365)} 年前`
}

/** 剩余时间：5小时26分钟后销毁 */
export function remain(v: any) {
  const d = toDate(v)
  if (!d) return ''
  let s = Math.floor((d.getTime() - Date.now()) / 1000)
  if (s <= 0) return '已到期'
  const h = Math.floor(s / 3600); s -= h * 3600
  const m = Math.floor(s / 60)
  return h > 0 ? `${h}小时${m}分钟` : `${m}分钟`
}

export function bytes(n: number | null | undefined) {
  if (n === null || n === undefined) return '—'
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(1)} MB`
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 55.0K / 1.2M（token） */
export function kfmt(n: number | null | undefined) {
  if (n === null || n === undefined) return '0'
  if (n < 1000) return `${n}`
  if (n < 1e6) return `${(n / 1e3).toFixed(1)}K`
  if (n < 1e9) return `${(n / 1e6).toFixed(1)}M`
  return `${(n / 1e9).toFixed(1)}B`
}

/** 22.6亿 / 1274.7万（中文大数） */
export function cnNum(n: number | null | undefined) {
  if (n === null || n === undefined) return '0'
  if (Math.abs(n) >= 1e8) return `${(n / 1e8).toFixed(1)}亿`
  if (Math.abs(n) >= 1e4) return `${(n / 1e4).toFixed(1)}万`
  return n.toLocaleString()
}

export function thousands(n: number | null | undefined) {
  return (n ?? 0).toLocaleString()
}

export function duration(ms: number | null | undefined) {
  if (!ms) return '0 ms'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)} s`
  if (ms < 3600000) return `${(ms / 60000).toFixed(1)} min`
  return `${(ms / 3600000).toFixed(1)} h`
}

export function shortId(id: string | null | undefined, n = 8) {
  return id ? id.slice(0, n) : ''
}

export async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    const ta = document.createElement('textarea')
    ta.value = text
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
  }
}

/** 头像底色：按字符串稳定取色 */
const PALETTE = ['#10b981', '#f97316', '#3b82f6', '#8b5cf6', '#ec4899', '#06b6d4', '#eab308', '#ef4444', '#14b8a6', '#6366f1']
export function colorOf(s: string) {
  let h = 0
  for (let i = 0; i < (s || '').length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}

export function initials(s: string, n = 1) {
  if (!s) return '?'
  const t = s.trim()
  if (/^[a-zA-Z]/.test(t)) {
    const parts = t.split(/[\s_\-]+/).filter(Boolean)
    if (n >= 2 && parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase()
    return t.slice(0, n).toUpperCase()
  }
  return t.slice(0, n)
}

export function debounce<T extends (...a: any[]) => void>(fn: T, wait = 300) {
  let t: any
  return (...args: Parameters<T>) => {
    clearTimeout(t)
    t = setTimeout(() => fn(...args), wait)
  }
}
