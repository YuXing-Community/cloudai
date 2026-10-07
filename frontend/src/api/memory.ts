import { http } from './http'

export const memoryApi = {
  list: (q: Record<string, any>) => http.get<{ records: any[]; active: number; namespaces: string[] }>('/memory-stores', q),
  get: (id: string) => http.get<any>(`/memory-stores/${id}`),
  create: (data: any) => http.post<any>('/memory-stores', data),
  update: (id: string, data: any) => http.put(`/memory-stores/${id}`, data),
  archive: (id: string, archived: boolean) => http.put(`/memory-stores/${id}/archive`, { archived }),
  remove: (id: string) => http.del(`/memory-stores/${id}`),
  tree: (id: string, showHidden: boolean, q?: string) => http.get<any[]>(`/memory-stores/${id}/tree`, { showHidden, q }),
  doc: (id: string, path: string) => http.get<{ doc: any; content?: string; binary?: boolean }>(`/memory-stores/${id}/doc`, { path }),
  createDoc: (id: string, path: string, content: string) => http.post(`/memory-stores/${id}/doc`, { path, content }),
  saveDoc: (id: string, path: string, content: string) => http.put(`/memory-stores/${id}/doc`, { path, content }),
  hide: (id: string, path: string, hidden: boolean) => http.put(`/memory-stores/${id}/doc/hidden`, { path, hidden }),
  removeDoc: (id: string, path: string) => http.del(`/memory-stores/${id}/doc`, { path }),
  upload: (id: string, files: File[], dir?: string) => {
    const fd = new FormData()
    files.forEach((f) => fd.append('files', f, (f as any).webkitRelativePath || f.name))
    if (dir) fd.append('dir', dir)
    return http.upload<any[]>(`/memory-stores/${id}/upload`, fd)
  },
  reindex: (id: string) => http.post(`/memory-stores/${id}/reindex`),
  keywords: (id: string) => http.get<{ content: string }>(`/memory-stores/${id}/keywords`),
  search: (query: string, storeIds: string[], topK: number) => http.post<{ results: any[]; model: string; dim: number; tookMs: number }>('/vector/search', { query, storeIds, topK }),
  status: () => http.get<any>('/vector/status', undefined, { silent: true }),
}
