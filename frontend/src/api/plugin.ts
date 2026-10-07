import { http } from './http'

export const pluginApi = {
  list: (q?: string, type?: string) => http.get<any[]>('/tool-plugins', { q, type }),
  get: (id: number) => http.get<any>(`/tool-plugins/${id}`),
  discover: (data: any) => http.post<{ tools: any[]; server?: any }>('/tool-plugins/discover', data),
  create: (data: any) => http.post<{ id: number }>('/tool-plugins', data),
  update: (id: number, data: any) => http.put(`/tool-plugins/${id}`, data),
  remove: (id: number) => http.del(`/tool-plugins/${id}`),
  rediscover: (id: number) => http.post<{ added: number; updated: number; removed: number }>(`/tool-plugins/${id}/rediscover`),
  addTool: (id: number, data: any) => http.post(`/tool-plugins/${id}/tools`, data),
  updateTool: (id: number, toolId: number, data: any) => http.put(`/tool-plugins/${id}/tools/${toolId}`, data),
  removeTool: (id: number, toolId: number) => http.del(`/tool-plugins/${id}/tools/${toolId}`),
  importOpenApi: (id: number, spec: string) => http.post<{ added: number; skipped: number }>(`/tool-plugins/${id}/openapi-import`, { spec }),
  invoke: (id: number, toolId: number, args: any) => http.post<{ ok: boolean; result: string; duration_ms: number }>(`/tool-plugins/${id}/tools/${toolId}/invoke`, { args }),
}

export const credentialApi = {
  list: (q?: string, type?: string) => http.get<any[]>('/credentials', { q, type }),
  options: (type?: string) => http.get<any[]>('/credentials/options', { type }),
  create: (data: any) => http.post<any>('/credentials', data),
  update: (id: number, data: any) => http.put(`/credentials/${id}`, data),
  remove: (id: number) => http.del(`/credentials/${id}`),
}

export const PLUGIN_TYPE: Record<string, string> = { mcp: 'MCP Server', openapi: 'OpenAPI', cloudlog: '云日志' }
export const RISK: Record<string, { label: string; tone: string }> = {
  low: { label: '低风险', tone: 'tag-green' }, medium: { label: '中风险', tone: 'tag-orange' }, high: { label: '高风险', tone: 'tag-red' },
}
export const CRED_TYPES = [
  { label: '通用', value: 'generic' }, { label: 'GitLab', value: 'gitlab' }, { label: '飞书', value: 'feishu' }, { label: '云日志 AK/SK', value: 'cloudlog' },
]
export const CLOUD_VENDORS = [
  { label: '阿里云 SLS', value: 'aliyun_sls', endpoint: 'cn-hangzhou.log.aliyuncs.com' },
  { label: '火山云 TLS', value: 'volc_tls', endpoint: 'tls-cn-beijing.volces.com' },
  { label: '华为云 LTS', value: 'huawei_lts', endpoint: 'lts.cn-north-4.myhuaweicloud.com' },
]
