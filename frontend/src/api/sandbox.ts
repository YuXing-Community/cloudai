import { http, Page } from './http'

export const sandboxApi = {
  envs: (usage?: string) => http.get<{ page: Page<any>; customEnabled: boolean }>('/runtime-envs', { usage, size: 100 }),
  setDefault: (id: number) => http.put(`/runtime-envs/${id}/default`),
  createEnv: (data: any) => http.post('/runtime-envs', data),
  updateEnv: (id: number, data: any) => http.put(`/runtime-envs/${id}`, data),
  deleteEnv: (id: number) => http.del(`/runtime-envs/${id}`),
  sandboxes: (q: Record<string, any>) => http.get<Page<any>>('/sandboxes', q),
  setTimeout: (id: string, seconds: number) => http.put(`/sandboxes/${id}/timeout`, { seconds }),
  destroy: (id: string) => http.post(`/sandboxes/${id}/destroy`),
  workspaces: () => http.get<any[]>('/workspaces'),
  tree: (dir: string) => http.get<any[]>('/workspaces/tree', { dir }),
  file: (dir: string, path: string) => http.get<any>('/workspaces/file', { dir, path }),
  cleanWorkspace: (dir: string) => http.del('/workspaces', { dir }),
}
