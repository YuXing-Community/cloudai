import { http, authedUrl, Page } from './http'

export const skillApi = {
  list: (q: Record<string, any>) => http.get<Page<any>>('/skills', q),
  tags: () => http.get<string[]>('/skills/tags'),
  get: (id: string) => http.get<any>(`/skills/${id}`),
  tree: (id: string, version?: string) => http.get<any[]>(`/skills/${id}/tree`, { version }),
  file: (id: string, path: string, version?: string) => http.get<any>(`/skills/${id}/file`, { path, version }),
  downloadUrl: (id: string, version?: string) => authedUrl(`/skills/${id}/download`, { version }),
  parse: (fd: FormData) => http.upload<any>('/skills/parse', fd),
  upload: (fd: FormData) => http.upload<{ id: string; name: string; version: string }>('/skills/upload', fd),
  update: (id: string, data: any) => http.put(`/skills/${id}`, data),
  remove: (id: string) => http.del(`/skills/${id}`),
  shareTargets: () => http.get<any[]>('/skills/share-targets'),
  shares: (id: string) => http.get<any[]>(`/skills/${id}/shares`),
  share: (id: string, spaceId: string) => http.post(`/skills/${id}/shares`, { spaceId }),
  unshare: (id: string, spaceId: string) => http.del(`/skills/${id}/shares/${spaceId}`),
}

export const hookApi = {
  list: () => http.get<any[]>('/hooks'),
  get: (id: number) => http.get<any>(`/hooks/${id}`),
  create: (data: any) => http.post<any>('/hooks', data),
  update: (id: number, data: any) => http.put<any>(`/hooks/${id}`, data),
  remove: (id: number) => http.del(`/hooks/${id}`),
}

export const SKILL_CATEGORIES = ['数据分析', '自动化', '运维诊断', '研发工具', '知识问答', '其他']
export const SKILL_MARKET_URL = 'https://skill.ydata.vip/'
