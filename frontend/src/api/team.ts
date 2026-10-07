import { http } from './http'

export const teamApi = {
  list: () => http.get<any[]>('/teams'),
  create: (data: any) => http.post('/teams', data),
  update: (id: string, data: any) => http.put(`/teams/${id}`, data),
  remove: (id: string) => http.del(`/teams/${id}`),
  run: (id: string, goal: string, name?: string) => http.post<{ id: string }>(`/teams/${id}/runs`, { goal, name }),
  sessions: (q: Record<string, any>) => http.get<any[]>('/team-sessions', q),
  detail: (id: string) => http.get<any>(`/team-sessions/${id}`),
  answer: (id: string, msgId: number, answer: string) => http.post(`/team-sessions/${id}/inputs/${msgId}`, { answer }),
  terminate: (id: string) => http.post(`/team-sessions/${id}/terminate`),
  archive: (id: string) => http.put(`/team-sessions/${id}/archive`),
}

export const TS_STATUS: Record<string, { l: string; t: string }> = {
  running: { l: '执行中', t: 'tag-blue' },
  waiting_input: { l: '等待输入', t: 'tag-purple' },
  completed: { l: '已完成', t: 'tag-green' },
  failed: { l: '失败', t: 'tag-red' },
  terminated: { l: '已终止', t: '' },
}
export const TASK_COLUMNS = [
  { k: 'pending', l: '待处理', c: 'var(--text-4)' },
  { k: 'running', l: '执行中', c: 'var(--primary)' },
  { k: 'waiting_input', l: '等待输入', c: 'var(--purple)' },
  { k: 'waiting_approval', l: '等待审批', c: 'var(--orange)' },
  { k: 'done', l: '已完成', c: 'var(--green)' },
  { k: 'needs_revision', l: '需修订', c: 'var(--orange)' },
  { k: 'failed', l: '失败/取消', c: 'var(--red)' },
]
export const PATTERN: Record<string, string> = { expert_pool: '专家池', pipeline: '流水线' }
