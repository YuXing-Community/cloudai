import { http } from './http'

export const settingsApi = {
  space: () => http.get<any>('/space'),
  updateSpace: (data: { name: string; description?: string }) => http.put('/space', data),
  toggleCustomEnv: (enabled: boolean) => http.put('/space/custom-env', { enabled }),
  deleteSpace: (confirmName: string) => http.del('/space', { confirmName }),

  members: (q?: string, role?: string) => http.get<{ records: any[]; counts: Record<string, number> }>('/members', { q, role }),
  addMembers: (userIds: number[], roleCode: string) => http.post('/members', { userIds, roleCode }),
  changeRole: (id: number, roleCode: string) => http.put(`/members/${id}`, { roleCode }),
  removeMember: (id: number) => http.del(`/members/${id}`),
  leave: () => http.post('/members/leave'),
  searchUsers: (q: string) => http.get<any[]>('/auth/users', { q }),

  roles: () => http.get<{ roles: any[]; permissions: { code: string; name: string }[] }>('/roles'),
  createRole: (data: any) => http.post('/roles', data),
  updateRole: (id: number, data: any) => http.put(`/roles/${id}`, data),
  deleteRole: (id: number) => http.del(`/roles/${id}`),

  providers: () => http.get<{ providers: any[]; stats: any }>('/models/providers'),
  createProvider: (data: any) => http.post('/models/providers', data),
  updateProvider: (id: number, data: any) => http.put(`/models/providers/${id}`, data),
  deleteProvider: (id: number) => http.del(`/models/providers/${id}`),
  setDefaultModel: (ref: string) => http.put('/models/default', { ref }),
  testProvider: (data: any) => http.post<{ ok: boolean; latencyMs: number; text?: string; error?: string; model: string }>('/models/test', data),
}

export const ROLE_TONE: Record<string, string> = { owner: 'tag-orange', admin: 'tag-blue', member: 'tag-green', guest: '' }
export const API_TYPES = [
  { label: 'openai-completions（/v1/chat/completions）', value: 'openai-completions' },
  { label: 'openai-responses（/v1/responses）', value: 'openai-responses' },
  { label: 'anthropic（/v1/messages）', value: 'anthropic' },
]
export const MODEL_LEVELS = [
  { label: 'low', value: 'low' },
  { label: 'medium', value: 'medium' },
  { label: 'high', value: 'high' },
  { label: 'extra high', value: 'xhigh' },
  { label: 'max', value: 'max' },
]
export const LEVEL_TONE: Record<string, string> = { low: 'tag-cyan', medium: '', high: 'tag-purple', xhigh: 'tag-orange', max: 'tag-red' }
export const levelLabel = (v: string) => MODEL_LEVELS.find((l) => l.value === v)?.label || v
