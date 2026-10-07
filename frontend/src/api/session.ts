import { http, openStream, Page } from './http'

export interface SessionRow {
  id: string
  title: string
  source: string
  sourceRef?: string
  status: 'idle' | 'running' | 'completed' | 'failed' | 'stopped'
  agentId: string
  agentVersion?: number
  modelRef?: string
  runtimeKind?: string
  runtimeEnv?: string
  creator?: { id: number; displayName: string; username: string }
  createdAt?: string
  updatedAt?: string
  lastEventAt?: string
  running?: boolean
  agent?: { id: string; name: string; identifier: string; avatar?: string; version: number }
}

export interface SessionEvent {
  id: number
  sessionId: string
  runId?: number
  seq: number
  type: 'input' | 'agent' | 'tool' | 'model' | 'thinking' | 'error' | 'status'
  name?: string
  content?: string
  result?: string
  status: 'ok' | 'error' | 'running'
  durationMs: number
  inputTokens: number
  outputTokens: number
  startedAt?: string
  createdAt: string
}

export interface ModelOption { providerKey: string; providerName: string; models: { modelId: string; ref: string; displayName?: string; level?: string; isDefault?: boolean; contextWindow?: number }[] }
export interface AgentOption { id: string; name: string; identifier: string; avatar?: string; version: number; description?: string }
export interface EnvOption { name: string; description?: string; isDefault?: boolean; usageType?: string; isSystem?: boolean }

export const sessionApi = {
  list: (q: Record<string, any>) => http.get<Page<SessionRow>>('/sessions', q),
  get: (id: string) => http.get<SessionRow>(`/sessions/${id}`),
  create: (data: { agentId: string; title?: string; modelRef?: string; runtimeEnv?: string; source?: string }) => http.post<SessionRow>('/sessions', data),
  rename: (id: string, title: string) => http.put(`/sessions/${id}`, { title }),
  remove: (id: string) => http.del(`/sessions/${id}`),
  send: (id: string, content: string, fileIds: string[] = [], modelRef?: string) => http.post(`/sessions/${id}/messages`, { content, fileIds, modelRef }),
  stop: (id: string) => http.post(`/sessions/${id}/stop`),
  events: (id: string, after = 0) => http.get<SessionEvent[]>(`/sessions/${id}/events`, { after }),
  artifacts: (id: string) => http.get<any[]>(`/sessions/${id}/artifacts`),
  info: (id: string) => http.get<any>(`/sessions/${id}/info`),
  stream: (id: string, after: number, handlers: Record<string, (d: any) => void>) => openStream(`/sessions/${id}/stream`, { after }, handlers),
}

export const optionApi = {
  agents: () => http.get<AgentOption[]>('/agents/options', undefined, { silent: true }).catch(() => [] as AgentOption[]),
  models: () => http.get<ModelOption[]>('/models/options', undefined, { silent: true }).catch(() => [] as ModelOption[]),
  envs: () => http.get<EnvOption[]>('/runtime-envs/options', undefined, { silent: true }).catch(() => [] as EnvOption[]),
}

export const shareApi = {
  agent: (id: string) => http.get<any>(`/share/agents/${id}`),
  create: (id: string) => http.post<SessionRow>(`/share/agents/${id}/sessions`),
  send: (sid: string, content: string) => http.post(`/share/sessions/${sid}/messages`, { content }),
  stop: (sid: string) => http.post(`/share/sessions/${sid}/stop`),
  stream: (sid: string, after: number, handlers: Record<string, (d: any) => void>) => openStream(`/share/sessions/${sid}/stream`, { after }, handlers),
}

export const SOURCE_LABEL: Record<string, string> = {
  web: '网页', debug: '调试', feishu: '飞书', webhook: 'Webhook', schedule: '定时任务', team: 'Agent 小队', share: '分享',
}
export const STATUS_LABEL: Record<string, string> = {
  idle: '空闲', running: '执行中', completed: '已完成', failed: '失败', stopped: '已停止',
}
