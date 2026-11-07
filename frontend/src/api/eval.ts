import { http, authedUrl, Page } from './http'

export const evalApi = {
  list: (q?: string) => http.get<any[]>('/evals', { q }),
  get: (id: number) => http.get<any>(`/evals/${id}`),
  create: (data: any) => http.post('/evals', data),
  update: (id: number, data: any) => http.put(`/evals/${id}`, data),
  remove: (id: number) => http.del(`/evals/${id}`),
  enable: (id: number, enabled: boolean) => http.put(`/evals/${id}/enabled`, { enabled }),
  run: (id: number) => http.post<any>(`/evals/${id}/run`),
  runs: (id: number, page = 1) => http.get<Page<any>>(`/evals/${id}/runs`, { page, size: 20 }),
  stopRun: (runId: number) => http.post(`/evals/runs/${runId}/stop`),
  results: (q: Record<string, any>) => http.get<Page<any>>('/evals/results', q),
  result: (id: number) => http.get<any>(`/evals/results/${id}`),
  rerun: (id: number) => http.post(`/evals/results/${id}/rerun`),
  candidates: (data: { agentIds: string[]; filters: any; taskId?: number; page?: number; size?: number }) =>
    http.post<Page<any>>('/evals/candidates', data),
}

export const annotationApi = {
  queue: (q: Record<string, any>) => http.get<Page<any>>('/annotations/queue', q),
  target: (mode: string, evalResultId?: number, sessionId?: string) => http.get<{ mine: any; all: any[] }>('/annotations/target', { mode, evalResultId, sessionId }),
  save: (data: any) => http.post<any>('/annotations', data),
  remove: (id: number) => http.del(`/annotations/${id}`),
  list: (q: Record<string, any>) => http.get<Page<any>>('/annotations', q),
  stats: (mode?: string) => http.get<any>('/annotations/stats', { mode }),
  exportUrl: (q: Record<string, any>) => authedUrl('/annotations/export', q),
}

export const TRIGGER_MODES = [
  { label: '手动执行', value: 'manual', desc: '在列表或详情页点击「立即执行」时评测' },
  { label: '定时执行', value: 'schedule', desc: '按 cron 周期评测筛选出的执行记录' },
  { label: '实时执行', value: 'realtime', desc: '开启后，新会话执行完成即自动评测' },
]
export const TRIGGER_LABEL: Record<string, string> = { manual: '手动', schedule: '定时', realtime: '实时' }

export const RUN_STATUS: Record<string, { label: string; tone: string }> = {
  running: { label: '评测中', tone: 'tag-blue' },
  success: { label: '已完成', tone: 'tag-green' },
  partial: { label: '部分失败', tone: 'tag-orange' },
  failed: { label: '失败', tone: 'tag-red' },
  stopped: { label: '已停止', tone: '' },
}
export const RESULT_STATUS: Record<string, { label: string; tone: string }> = {
  pending: { label: '排队中', tone: '' },
  running: { label: '评测中', tone: 'tag-blue' },
  success: { label: '已评测', tone: 'tag-green' },
  failed: { label: '失败', tone: 'tag-red' },
}
export const SESSION_SOURCES = [
  { label: 'Web 会话', value: 'web' }, { label: '飞书', value: 'feishu' }, { label: 'Webhook', value: 'webhook' },
  { label: '定时任务', value: 'schedule' }, { label: 'Agent 小队', value: 'team' }, { label: '分享对话', value: 'share' },
]
export const SESSION_STATUSES = [
  { label: '已完成', value: 'completed' }, { label: '失败', value: 'failed' }, { label: '已停止', value: 'stopped' },
]

export const LABELS = [
  { value: 'good', label: '优秀', tone: 'tag-green' },
  { value: 'acceptable', label: '合格', tone: 'tag-blue' },
  { value: 'bad', label: '不合格', tone: 'tag-red' },
]
export const LABEL_MAP: Record<string, { label: string; tone: string }> = Object.fromEntries(LABELS.map((l) => [l.value, l]))
export const ISSUE_TAGS = ['答非所问', '事实错误/幻觉', '工具使用不当', '未遵循指令', '回复不完整', '格式问题', '冗长啰嗦', '安全合规风险', '拒答不当']
export const SEVERITY: Record<string, { label: string; tone: string }> = {
  high: { label: '严重', tone: 'tag-red' }, medium: { label: '一般', tone: 'tag-orange' }, low: { label: '轻微', tone: '' },
}

/** 分数颜色：≥85 绿、≥60 蓝、其余红 */
export function scoreTone(s: number | null | undefined) {
  if (s == null) return ''
  return s >= 85 ? 'good' : s >= 60 ? 'ok' : 'bad'
}
export function fmtScore(s: number | string | null | undefined) {
  if (s == null || s === '') return '—'
  const n = Number(s)
  return Number.isInteger(n) ? String(n) : n.toFixed(1)
}
