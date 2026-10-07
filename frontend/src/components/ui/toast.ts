import { reactive } from 'vue'

export type ToastKind = 'success' | 'error' | 'info' | 'warning'
export interface ToastItem { id: number; kind: ToastKind; text: string }

export const toastState = reactive<{ items: ToastItem[] }>({ items: [] })
let seq = 0

function push(kind: ToastKind, text: string, ms = 2600) {
  const id = ++seq
  toastState.items.push({ id, kind, text })
  setTimeout(() => {
    const i = toastState.items.findIndex((t) => t.id === id)
    if (i >= 0) toastState.items.splice(i, 1)
  }, kind === 'error' ? Math.max(ms, 4000) : ms)
}

export const toast = {
  success: (t: string) => push('success', t),
  error: (t: string) => push('error', t),
  info: (t: string) => push('info', t),
  warning: (t: string) => push('warning', t),
}

/* ---------------- 确认弹窗 ---------------- */
export interface ConfirmOpts {
  title: string
  message?: string
  okText?: string
  cancelText?: string
  danger?: boolean
  /** 需要输入该文本才能确认 */
  requireText?: string
}

export const confirmState = reactive<{ open: boolean; opts: ConfirmOpts | null; resolve: ((v: boolean) => void) | null }>({
  open: false, opts: null, resolve: null,
})

export function confirm(opts: ConfirmOpts | string): Promise<boolean> {
  const o = typeof opts === 'string' ? { title: opts } : opts
  return new Promise((resolve) => {
    confirmState.opts = o
    confirmState.resolve = resolve
    confirmState.open = true
  })
}
