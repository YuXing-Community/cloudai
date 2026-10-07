import { http, authedUrl, Page } from './http'

export interface AgentRow {
  id: string
  name: string
  identifier: string
  description?: string
  avatar?: string
  groupName?: string
  status: string
  version: number
  model: { providerKey?: string; modelId?: string }
  capabilities: { skills: number; mcp: number; cli: number; memory: number; subAgents: number; envVars: number }
  owner?: { id: number; displayName: string; username: string; avatar?: string }
  favorite: boolean
  createdAt?: string
  updatedAt?: string
  config?: any
  versions?: { version: number; note?: string; createdAt: string; createdBy?: any }[]
}

export const agentApi = {
  list: (q: Record<string, any>) => http.get<{ page: Page<AgentRow>; counts: Record<string, number> }>('/agents', q),
  groups: () => http.get<string[]>('/agents/groups'),
  options: () => http.get<any[]>('/agents/options'),
  get: (id: string) => http.get<AgentRow>(`/agents/${id}`),
  create: (data: any) => http.post<{ id: string }>('/agents', data),
  aiDraft: (requirement: string) => http.post<{ name: string; identifier: string; description: string; prompt: string }>('/agents/ai-draft', { requirement }),
  save: (id: string, data: any) => http.put<{ id: string; version: number }>(`/agents/${id}`, data),
  copy: (id: string) => http.post<{ id: string }>(`/agents/${id}/copy`),
  exportUrl: (id: string) => authedUrl(`/agents/${id}/export`),
  import: (file: File) => { const fd = new FormData(); fd.append('file', file); return http.upload<{ id: string; name: string }>('/agents/import', fd) },
  owner: (id: string, userId: number) => http.put(`/agents/${id}/owner`, { userId }),
  favorite: (id: string) => http.post<{ favorite: boolean }>(`/agents/${id}/favorite`),
  archive: (id: string, archived: boolean) => http.put(`/agents/${id}/archive`, { archived }),
  remove: (id: string) => http.del(`/agents/${id}`),
  share: (id: string) => http.get<{ url: string }>(`/agents/${id}/share`),
  grants: (id: string) => http.get<any[]>(`/agents/${id}/grants`),
  grant: (id: string, userIds: number[]) => http.post(`/agents/${id}/grants`, { userIds }),
  revoke: (id: string, grantId: number) => http.del(`/agents/${id}/grants/${grantId}`),
  searchUsers: (q: string) => http.get<any[]>('/auth/users', { q }),
}

/** 编排配置里各类引用的候选数据（其他模块提供，失败时返回空数组） */
const safe = <T>(p: Promise<T>, d: T) => p.catch(() => d)
export const refApi = {
  models: () => safe(http.get<any[]>('/models/options', undefined, { silent: true }), []),
  envs: () => safe(http.get<any[]>('/runtime-envs/options', undefined, { silent: true }), []),
  hooks: () => safe(http.get<any[]>('/hooks/options', undefined, { silent: true }), []),
  plugins: () => safe(http.get<any[]>('/tool-plugins/options', undefined, { silent: true }), []),
  credentials: (type?: string) => safe(http.get<any[]>('/credentials/options', { type }, { silent: true }), []),
  createCredential: (data: any) => http.post<any>('/credentials', data),
  skills: () => safe(http.get<any[]>('/skills/options', undefined, { silent: true }), []),
  stores: () => safe(http.get<any[]>('/memory-stores/options', undefined, { silent: true }), []),
}

export const BUILTIN_TOOLS = [
  { key: 'Bash', label: 'Shell 命令' },
  { key: 'Read', label: '文件读取' },
  { key: 'Edit', label: '文件编辑' },
  { key: 'Write', label: '文件写入' },
  { key: 'LS', label: '目录列表' },
  { key: 'Glob', label: '文件匹配' },
  { key: 'Grep', label: '内容检索' },
  { key: 'artifact_save', label: '保存产物' },
]
